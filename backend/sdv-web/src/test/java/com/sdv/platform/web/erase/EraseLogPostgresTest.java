package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlerException;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasedBy;
import com.sdv.platform.application.port.ErasePort;
import com.sdv.platform.application.port.EraseRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.retention.RetentionDeletionService;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.persistence.fields.CaseRows;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.retention.JdbcRetentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0080 against PostgreSQL and a real erase log: what is erased, what is refused, and what a restored database keeps of it. */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class EraseLogPostgresTest {
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final JdbcTemplate db = new JdbcTemplate(dataSource);
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(db);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final Set<String> files = new HashSet<>();
    private boolean folderAway;
    private final StorageProvider storage = new StorageProvider() {
        @Override public String providerId() { return "memory"; }
        @Override public StoredObject store(UUID tenantId, StorageVolume volume, ContentWriter writer) { throw new UnsupportedOperationException(); }
        @Override public InputStream open(UUID tenantId, StorageVolume volume, String objectKey) { return new ByteArrayInputStream(new byte[0]); }
        @Override public void delete(UUID tenantId, StorageVolume volume, String objectKey) throws IOException {
            if (folderAway) throw new IOException("not mounted");
            files.remove(objectKey);
        }
        @Override public boolean exists(UUID tenantId, StorageVolume volume, String objectKey) { return files.contains(objectKey); }
    };
    private final EraseKit erase = new EraseKit(dataSource, tx, List.of(storage));
    private final JdbcSecureIngestionRepository ingestion = new JdbcSecureIngestionRepository(jdbc);
    private final RetentionDeletionService retention = new RetentionDeletionService(new JdbcRetentionRepository(jdbc), ingestion, erase.port, erase.erased,
            new JdbcFoundationRepository(jdbc), tx);

    private final UUID tenant = UUID.randomUUID(), source = UUID.randomUUID(), volume = UUID.randomUUID();

    @BeforeEach
    void entity() {
        // Nothing of another test class is due here.
        db.update("update documents set expires_at = now() + interval '1 year' where expires_at <= now() and status <> 'EXPIRED_DELETED'");
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Erase', 'ACTIVE')", tenant, "erase-" + tenant);
        db.update("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values (?, ?, 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')", source, tenant);
        db.update("insert into storage_volumes(volume_id, tenant_id, provider_id, display_name, root_path, state, priority, warning_percent, stop_write_percent) values (?, ?, 'memory', 'M', ?, 'ACTIVE', 1, 80, 90)",
                volume, tenant, "erase-" + volume);
        CaseRows.field(db, tenant, source, "contractNumber", "Contract number", true, "DIGITS_ONLY", 1);
    }

    private UUID submission(String contract) {
        UUID id = UUID.randomUUID();
        db.update("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values (?, ?, ?, ?, 1, 'COMPLETE')", id, tenant, source, id.toString());
        CaseRows.value(db, tenant, source, id, "contractNumber", contract);
        return id;
    }

    private UUID document(UUID submission, boolean due) {
        UUID id = UUID.randomUUID();
        db.update("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, content_sha256, content_size_bytes, status, available_at, page_count, retention_days_snapshot, expires_at)"
                + " values (?, ?, ?, ?, ?, 'x.pdf', 'application/pdf', repeat('a', 64), 10, 'AVAILABLE', now(), 1, 1, now() + cast(? as interval))", id, tenant, submission, source, id.toString(), due ? "-1 day" : "30 days");
        content(id, 1);
        return id;
    }

    /** As the stored document has it; also what a restored backup brings back. */
    private void content(UUID document, int version) {
        files.add("file-" + document);
        db.update("insert into document_storage_refs(document_id, tenant_id, provider_id, storage_volume_id, object_key, encrypted_size_bytes, integrity_value) values (?, ?, 'memory', ?, ?, 10, repeat('b', 64))",
                document, tenant, volume, "file-" + document);
        db.update("insert into document_crypto_metadata(document_id, tenant_id, crypto_profile, crypto_version, key_provider_id, protected_data_key_reference, key_provider_version, object_version) values (?, ?, 'COPY', 1, 'test', ?, '1', ?)",
                document, tenant, "key-" + document, version);
    }

    private String status(UUID document) {
        return db.queryForObject("select status from documents where document_id = ?", String.class, document);
    }

    private int events(String action, UUID target) {
        return db.queryForObject("select count(*) from audit_events where action = ? and target_id = ?", Integer.class, action, target.toString());
    }

    private EraseEntry entry(String kind, String id) {
        return EraseEntry.link(erase.log.logId(), erase.log.head(), Instant.now(), new EraseRequest(tenant, "SDV", kind, id, kind,
                Instant.now().truncatedTo(ChronoUnit.MICROS), ErasedBy.rule("test"), UUID.randomUUID().toString()));
    }

    @Test
    void theCleanupErasesThroughTheLogAndARestoredDocumentIsErasedAgain() throws Exception {
        UUID first = submission("8001"), due = document(first, true), second = submission("8001"), later = document(second, false);
        UUID record = db.queryForObject("select record_id from submissions where submission_id = ?", UUID.class, first);
        long head = erase.log.head().seq();

        // Not claimed by the cleanup, on hold, quarantined: refused, and nothing is logged.
        ErasePort.EraseFailed refused = assertThrows(ErasePort.EraseFailed.class, () -> erase.port.document(tenant, later, UUID.randomUUID()));
        assertTrue(refused.refused());
        db.update("update documents set status = 'RETENTION_DELETING' where document_id = ?", later);
        db.update("insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_at) values (gen_random_uuid(), ?, ?, 'x', 'ba', now())", tenant, later);
        assertTrue(assertThrows(ErasePort.EraseFailed.class, () -> erase.port.document(tenant, later, UUID.randomUUID())).refused());
        db.update("update documents set status = 'AVAILABLE' where document_id = ?", later);
        assertEquals(head, erase.log.head().seq());

        RetentionDeletionService.SweepResult swept = retention.sweep(100);
        assertEquals(1, swept.completed());
        assertEquals(0, swept.failed());
        assertEquals("EXPIRED_DELETED", status(due));
        assertFalse(files.contains("file-" + due));
        assertEquals(0, db.queryForObject("select count(*) from document_crypto_metadata where document_id = ?", Integer.class, due));
        assertEquals(1, events("DOCUMENT_RETENTION_DELETED", due));
        assertTrue(files.contains("file-" + later));
        // The record still has a document, so its values stay.
        assertEquals(1, db.queryForObject("select count(*) from fld_record where record_id = ?", Integer.class, record.toString()));
        // The log holds IDs only.
        EraseEntry logged = erase.log.after(head).getFirst();
        assertEquals(due.toString(), logged.request().recordId());
        String line = Files.readString(Path.of(erase.log.location(), "erase-log.jsonl"));
        assertFalse(line.contains("x.pdf") || line.contains("8001") || line.contains("key-" + due), line);

        // A restored database brings it back: readable, erased again, the audit event not twice; its hold and status there don't matter.
        EraseHandler handler = erase.handlers.get("document");
        assertEquals(EraseHandler.Verified.GONE, handler.verify(logged));
        content(due, 1);
        db.update("update documents set status = 'AVAILABLE', deleted_at = null, deletion_reason = null where document_id = ?", due);
        assertEquals(EraseHandler.Verified.READABLE, handler.verify(logged));
        assertEquals(EraseHandler.Applied.ERASED, handler.apply(logged, null));
        assertEquals(EraseHandler.Verified.GONE, handler.verify(logged));
        assertEquals(EraseHandler.Applied.ALREADY_GONE, handler.apply(logged, null), "twice is safe");
        assertEquals("EXPIRED_DELETED", status(due));
        assertEquals(1, events("DOCUMENT_RETENTION_DELETED", due));

        // The last document goes: the record's values go with it, its row stays; a held one keeps them until then.
        db.update("update legal_holds set released_at = now() where document_id = ?", later);
        db.update("update documents set expires_at = now() - interval '1 day' where document_id = ?", later);
        db.update("update submissions set status = 'RECEIVING' where submission_id = ?", second);
        assertEquals(0, retention.sweep(100).failed());
        assertEquals("EXPIRED_DELETED", status(later));
        assertEquals(1, db.queryForObject("select count(*) from fld_record where record_id = ?", Integer.class, record.toString()), "a delivery is still open");
        db.update("update submissions set status = 'COMPLETE' where submission_id = ?", second);
        assertEquals(0, retention.sweep(100).failed());
        assertEquals(0, db.queryForObject("select count(*) from fld_record where record_id = ?", Integer.class, record.toString()));
        assertEquals(0, db.queryForObject("select count(*) from fld_value where entity_uuid = ?", Integer.class, tenant));
        assertEquals(1, db.queryForObject("select count(*) from records where record_id = ?", Integer.class, record));
        assertEquals(1, events("RECORD_VALUES_ERASED", record));
        assertFalse(erase.erased.lockRecordToErase(new EraseRepository.RecordKey(tenant, source, record)));

        // The whole log against this database: in step, and nothing readable.
        assertTrue(erase.replay.run().passed(), () -> erase.replay.run().describe());
        assertTrue(erase.guard.mayTakeWork());
    }

    @Test
    void aFailingEntryStopsTheLaterOnesUntilItIsDone() {
        UUID a = document(submission("8101"), true), b = document(submission("8102"), true);
        folderAway = true;
        RetentionDeletionService.SweepResult swept = retention.sweep(100);
        assertEquals(1, swept.failed(), "the first one stops the run");
        assertEquals(0, swept.completed());
        assertEquals("SDV-ERS-003", erase.guard.state().lastFailure().handlerCode());
        assertEquals(0, events("DOCUMENT_RETENTION_DELETED", a) + events("DOCUMENT_RETENTION_DELETED", b));
        assertTrue(files.contains("file-" + a) && files.contains("file-" + b));
        com.documentplatform.monitoring.Verdict shown = com.sdv.platform.web.monitoring.EraseLogVerdict.of(erase.guard.state());
        assertNotNull(shown);

        folderAway = false;
        assertEquals(0, retention.sweep(100).failed());
        assertEquals("EXPIRED_DELETED", status(a));
        assertEquals("EXPIRED_DELETED", status(b));
        assertNull(erase.guard.state().lastFailure());
        assertEquals(1, events("DOCUMENT_RETENTION_DELETED", a));
        assertEquals(1, events("DOCUMENT_RETENTION_DELETED", b));
    }

    @Test
    void aRestoredOriginalOfARedactedDocumentIsErasedAndTheRedactedOneIsNot() {
        EraseHandler replaced = erase.handlers.get("replaced-version");
        UUID restored = document(submission("8201"), false);
        EraseEntry entry = entry("replaced-version", EraseIds.version(restored, 1));
        assertThrows(EraseHandlerException.class, () -> replaced.check(entry.request()), "no redaction is under way");
        assertEquals(EraseHandler.Verified.READABLE, replaced.verify(entry));
        assertEquals(EraseHandler.Applied.ERASED, replaced.apply(entry, null));
        assertEquals(EraseHandler.Verified.GONE, replaced.verify(entry));
        assertEquals("EXPIRED_DELETED", status(restored));
        assertFalse(files.contains("file-" + restored));
        assertEquals(1, events("DOCUMENT_ERASED_AFTER_RESTORE", restored));
        assertEquals(EraseHandler.Applied.ALREADY_GONE, replaced.apply(entry, null));
        assertEquals(1, events("DOCUMENT_ERASED_AFTER_RESTORE", restored));

        // A database that has the redacted version.
        UUID kept = document(submission("8202"), false);
        db.update("update document_crypto_metadata set object_version = 2 where document_id = ?", kept);
        EraseEntry old = entry("replaced-version", EraseIds.version(kept, 1));
        assertEquals(EraseHandler.Verified.GONE, replaced.verify(old));
        assertEquals(EraseHandler.Applied.ALREADY_GONE, replaced.apply(old, null));
        assertEquals("AVAILABLE", status(kept));

        // A sealed original that was destroyed, in a database from before it was sealed: the document still is the original.
        EraseHandler sealed = erase.handlers.get("sealed-original");
        UUID before = document(submission("8203"), false);
        EraseEntry destroyed = entry("sealed-original", EraseIds.sealed(before, UUID.randomUUID()));
        assertThrows(EraseHandlerException.class, () -> sealed.check(destroyed.request()));
        assertEquals(EraseHandler.Verified.READABLE, sealed.verify(destroyed));
        assertEquals(EraseHandler.Applied.ERASED, sealed.apply(destroyed, null));
        assertEquals(EraseHandler.Verified.GONE, sealed.verify(destroyed));
        assertEquals("EXPIRED_DELETED", status(before));

        // A database older than the document: nothing to do.
        EraseEntry unknown = entry("document", UUID.randomUUID().toString());
        assertEquals(EraseHandler.Applied.ALREADY_GONE, erase.handlers.get("document").apply(unknown, null));
        assertEquals(EraseHandler.Verified.GONE, erase.handlers.get("document").verify(unknown));
    }

    @Test
    void theDatabaseRefusesToMoveThePositionBackOrDeleteIt() {
        erase.port.inTurn(() -> null);
        UUID due = document(submission("8301"), true);
        assertEquals(0, retention.sweep(100).failed());
        assertEquals("EXPIRED_DELETED", status(due));
        assertThrows(RuntimeException.class, () -> db.update("update erl_position set applied_seq = applied_seq - 1"));
        assertThrows(RuntimeException.class, () -> db.update("delete from erl_position"));
    }
}
