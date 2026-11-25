package com.sdv.platform.application.metadata;

import com.sdv.platform.application.access.ActiveTenant;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.CaseFieldsPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task 0077: the entity's field list: what is sensitive, what is locked, export and import. Every change and export is audited. */
@Service
public class CaseFieldService {
    /** platform-fields' own limit is about this; more is no field list. */
    static final int MAX_FILE = 2_000_000;
    private final TenantRepository tenants;
    private final CaseFieldsPort fields;
    private final AuditEventPort audit;

    public CaseFieldService(TenantRepository tenants, CaseFieldsPort fields, AuditEventPort audit) {
        this.tenants = tenants;
        this.fields = fields;
        this.audit = audit;
    }

    public List<CaseFieldsPort.FieldState> list(UUID entity) {
        ActiveTenant.require(tenants, entity);
        return fields.fields(entity);
    }

    @Transactional
    public CaseFieldsPort.FieldState setSensitive(UUID entity, String key, boolean sensitive, String actorId, String correlationId) {
        ActiveTenant.require(tenants, entity);
        CaseFieldsPort.FieldState state = fields.setSensitive(entity, key, sensitive, actorId);
        audit(entity, actorId, "FIELD_SENSITIVITY_CHANGED", key, correlationId, "{\"sensitive\":" + sensitive + "}");
        return state;
    }

    @Transactional
    public String export(UUID entity, String actorId, String correlationId) {
        ActiveTenant.require(tenants, entity);
        String file = fields.exportFile(entity);
        audit(entity, actorId, "FIELD_LIST_EXPORTED", "list", correlationId, "{}");
        return file;
    }

    public List<CaseFieldsPort.ImportLine> preview(UUID entity, String file) {
        ActiveTenant.require(tenants, entity);
        return fields.previewImport(entity, checked(file));
    }

    @Transactional
    public List<CaseFieldsPort.ImportLine> importFile(UUID entity, String file, String actorId, String correlationId) {
        ActiveTenant.require(tenants, entity);
        List<CaseFieldsPort.ImportLine> lines = fields.importFile(entity, checked(file), actorId);
        long created = lines.stream().filter(l -> l.outcome().equals("CREATE")).count(), conflicts = lines.stream().filter(l -> l.outcome().equals("CONFLICT")).count();
        audit(entity, actorId, "FIELD_LIST_IMPORTED", "list", correlationId, "{\"created\":" + created + ",\"conflicts\":" + conflicts + "}");
        return lines;
    }

    private static String checked(String file) {
        if (file == null || file.isBlank() || file.length() > MAX_FILE) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.FLD_004, "The field list can't be read: it is empty or too large.", "length");
        }
        return file;
    }

    private void audit(UUID entity, String actorId, String action, String target, String correlationId, String details) {
        audit.append(new AuditEvent(UUID.randomUUID(), entity, "USER", actorId, action, "CASE_FIELD", target, Instant.now(), correlationId, AuditOutcome.SUCCESS, details));
    }
}
