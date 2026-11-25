package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.fields.CaseFieldsEraseHandler;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentRedactionRepository;
import com.sdv.platform.application.port.EraseRepository;
import com.sdv.platform.application.port.HeldRedactionRepository;
import com.sdv.platform.application.redaction.RedactionOperation;
import com.sdv.platform.application.redaction.SealedOriginalService;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.persistence.fields.PlatformRecordValues;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0080: how each kind of erase log entry is checked, erased and verified. {@code check} runs before an entry is written, never in a
 * replay: what the log says was erased is erased again, whatever the restored database says about it.
 */
@Configuration
public class EraseHandlersConfiguration {
    /** The folder is checked before platform-core makes its files there ({@link EraseLogFolder}). */
    @Bean
    static BeanFactoryPostProcessor eraseLogFolderFirst() {
        return factory -> {
            if (factory.containsBeanDefinition("eraseLogStore")) factory.getBeanDefinition("eraseLogStore").setDependsOn("eraseLogFolder");
        };
    }

    /** Vero's own indicator ({@link EraseLogHealth}) is in readiness only: the overall health must not be DOWN for the database or a share. */
    @Bean
    static BeanDefinitionRegistryPostProcessor eraseLogNotInTheOverallHealth() {
        return registry -> {
            if (registry.containsBeanDefinition("eraseLogHealthIndicator")) registry.removeBeanDefinition("eraseLogHealthIndicator");
        };
    }

    @Bean
    EraseHandler documentErase(DocumentContent content, DocumentIntakeRepository documents, EraseRepository erased) {
        return new Handler(EraseIds.DOCUMENT) {
            @Override
            public void check(EraseRequest r) {
                UUID id = EraseIds.document(r);
                boolean claimed = documents.findDocumentByIdForTenant(r.entity(), id).map(d -> d.status() == DocumentStatus.RETENTION_DELETING).orElse(false);
                if (!claimed || erased.kept(r.entity(), id)) throw DocumentContent.refused();
            }

            @Override
            Applied apply(EraseRequest r) {
                return done(content.erase(r.entity(), EraseIds.document(r), r.erasedAt(), UUID.fromString(r.auditId())));
            }

            @Override
            boolean readable(EraseRequest r) {
                return content.readable(r.entity(), EraseIds.document(r));
            }
        };
    }

    @Bean
    EraseHandler sealedOriginalErase(DocumentContent content, HeldRedactionRepository held, EraseRepository erased) {
        return new Handler(EraseIds.SEALED_ORIGINAL) {
            @Override
            public void check(EraseRequest r) {
                if (erased.kept(r.entity(), EraseIds.document(r)) || !held.isDestroying(r.entity(), EraseIds.sealed(r))) throw DocumentContent.refused();
            }

            @Override
            Applied apply(EraseRequest r) {
                UUID tenant = r.entity(), id = EraseIds.sealed(r);
                if (!held.knowsSealed(tenant, id)) {
                    // A database from before the redaction: the document itself is still the original.
                    return done(content.erase(tenant, EraseIds.document(r), r.erasedAt(), null));
                }
                HeldRedactionRepository.Sealed sealed = held.findUndestroyed(tenant, id).orElse(null);
                if (sealed == null) return Applied.ALREADY_GONE;
                content.deleteFile(tenant, sealed.storage().providerId(), sealed.storage().storageVolumeId(), sealed.storage().objectKey());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("sealedOriginalId", id.toString());
                if (sealed.requestId() != null) details.put("accessRequestId", sealed.requestId().toString());
                details.put("contentSha256", sealed.contentSha256());
                details.put("sealedAt", sealed.sealedAt().toString());
                return done(content.audited(UUID.fromString(r.auditId()), () -> held.markDestroyed(tenant, id, r.erasedAt()),
                        audit -> content.event(audit, tenant, "sdv-legal-hold", SealedOriginalService.DESTROYED_ACTION, "DOCUMENT",
                                EraseIds.document(r), r.erasedAt(), "sealed-original-" + id, details)));
            }

            @Override
            boolean readable(EraseRequest r) {
                return held.knowsSealed(r.entity(), EraseIds.sealed(r))
                        ? held.findUndestroyed(r.entity(), EraseIds.sealed(r)).isPresent()
                        : content.readable(r.entity(), EraseIds.document(r));
            }
        };
    }

    @Bean
    EraseHandler replacedVersionErase(DocumentContent content, DocumentIntakeRepository documents, DocumentRedactionRepository redactions,
                                      EraseRepository erased) {
        return new Handler(EraseIds.REPLACED_VERSION) {
            @Override
            public void check(EraseRequest r) {
                UUID id = EraseIds.document(r);
                boolean redacting = redactions.findPending(r.entity(), id).filter(op -> op.accessRequestId() == null).isPresent();
                if (!redacting || version(r) != EraseIds.version(r) || erased.kept(r.entity(), id)) throw DocumentContent.refused();
            }

            /** The document's version now; 0: none. Versions only rise, so a higher one means the old key is overwritten. */
            private int version(EraseRequest r) {
                return documents.findCryptoMetadata(r.entity(), EraseIds.document(r)).map(DocumentCryptoMetadata::objectVersion).orElse(0);
            }

            @Override
            Applied apply(EraseRequest r) {
                int now = version(r);
                if (now == 0 || now > EraseIds.version(r)) return Applied.ALREADY_GONE;
                RedactionOperation pending = redactions.findPending(r.entity(), EraseIds.document(r)).orElse(null);
                if (pending != null) {
                    // The redaction is under way (its own transaction waits for this one): it overwrites the key.
                    content.deleteFile(r.entity(), pending.oldProviderId(), pending.oldStorageVolumeId(), pending.oldObjectKey());
                    return Applied.ERASED;
                }
                return done(content.erase(r.entity(), EraseIds.document(r), r.erasedAt(), null));
            }

            @Override
            boolean readable(EraseRequest r) {
                int now = version(r);
                return now != 0 && now <= EraseIds.version(r) && content.readable(r.entity(), EraseIds.document(r));
            }
        };
    }

    /** platform-core's own handler, with the audit event. */
    @Bean
    EraseHandler caseFieldsErase(PlatformRecordValues values, DocumentContent content) {
        CaseFieldsEraseHandler fields = new CaseFieldsEraseHandler(values.values(), values.store(), values.holds());
        return new EraseHandler() {
            @Override
            public String kind() {
                return fields.kind();
            }

            @Override
            public void check(EraseRequest r) {
                fields.check(r);
            }

            @Override
            public Applied apply(EraseEntry entry, Context context) {
                Applied applied = fields.apply(entry, context);
                EraseRequest r = entry.request();
                Instant at = r.erasedAt();
                content.audited(UUID.fromString(r.auditId()), () -> true, audit -> content.event(audit, r.entity(), "retention-scheduler",
                        "RECORD_VALUES_ERASED", "RECORD", UUID.fromString(r.recordId()), at, null, Map.of("reason", "LAST_DOCUMENT_ERASED")));
                return applied;
            }

            @Override
            public Verified verify(EraseEntry entry) {
                return fields.verify(entry);
            }
        };
    }

    private abstract static class Handler implements EraseHandler {
        private final String kind;

        Handler(String kind) {
            this.kind = kind;
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public Applied apply(EraseEntry entry, Context context) {
            return apply(entry.request());
        }

        @Override
        public Verified verify(EraseEntry entry) {
            return readable(entry.request()) ? Verified.READABLE : Verified.GONE;
        }

        abstract Applied apply(EraseRequest request);

        abstract boolean readable(EraseRequest request);

        static Applied done(boolean erased) {
            return erased ? Applied.ERASED : Applied.ALREADY_GONE;
        }
    }
}
