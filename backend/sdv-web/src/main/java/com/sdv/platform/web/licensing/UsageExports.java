package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.usage.ExportAnchor;
import com.documentplatform.licensing.usage.UsageExport;
import com.documentplatform.licensing.usage.UsageRecord;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.application.json.VeroJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0075: the usage file for the vendor: numbers only. Each file goes on from the one before; its anchor is Vero's
 * ({@code usage_export_anchor}, rows are only added). The audit (which can't be changed) holds the anchor of every export,
 * so a missing or replaced anchor is seen. It is then refused and said; only an explicit "start again" goes on, audited.
 */
@Service
public class UsageExports {
    static final String ACTION = "USAGE_EXPORTED";
    private final LicenceService licence;
    private final JdbcTemplate db;
    private final AuditEventPort audit;
    private final Clock clock = Clock.systemUTC();

    public UsageExports(LicenceService licence, JdbcTemplate db, AuditEventPort audit) {
        this.licence = licence;
        this.db = db;
        this.audit = audit;
    }

    public record Exported(String fileName, String text) { }

    @Transactional
    public Exported export(SdvPrincipal person, String correlationId, boolean startAgain) {
        // One export at a time, on every Vero of this database.
        db.execute("lock table usage_export_anchor in exclusive mode");
        Optional<ExportAnchor> kept = kept();
        Optional<String> last = lastAudited();
        boolean matches = last.equals(kept.map(UsageExports::mark));
        if (!matches && !startAgain) {
            throw new CodedRefusal.Conflict(SdvErrorCode.LIC_003, kept.isEmpty()
                    ? "The anchor of the last usage export is missing. Nothing was exported. Tell support before you start a new series of files."
                    : "The anchor kept for usage exports is not the last export's. Nothing was exported. Tell support before you start a new series of files.");
        }
        ExportAnchor after = matches && kept.isPresent() ? kept.get() : ExportAnchor.first();
        Instant now = clock.instant();
        Licence current = licence.status().licence();
        YearMonth to = YearMonth.from(now.atZone(ZoneOffset.UTC));
        YearMonth from = after.previousPeriodTo().orElseGet(() -> licence.ledger().records().stream().map(UsageRecord::period).min(YearMonth::compareTo).orElse(to));
        if (from.isAfter(to)) throw new CodedRefusal.BadRequest(SdvErrorCode.LIC_004, "This server's date is before the last usage export's month. Correct the clock.");
        UsageExport file = UsageExport.create(current, licence.ledger(), from, to, after, now);
        ExportAnchor next = file.nextAnchor();
        if (!matches) db.update("delete from usage_export_anchor");
        db.update("insert into usage_export_anchor (sequence, final_chain_value, period_to, created_at) values (?, ?, ?, ?)",
                next.sequence(), next.finalChainValue(), String.valueOf(next.periodTo()), java.sql.Timestamp.from(now));
        audit.append(new AuditEvent(UUID.randomUUID(), person.tenantId(), "USER", person.subject(), ACTION, "INSTALLATION", "vero", now, correlationId,
                AuditOutcome.SUCCESS, VeroJson.mapper().writeValueAsString(java.util.Map.of("anchor", mark(next), "startedAgain", !matches)), person.displayName()));
        return new Exported("vero-usage-" + from + "-to-" + to + ".json", file.text());
    }

    Optional<ExportAnchor> kept() {
        List<ExportAnchor> rows = db.query("select sequence, final_chain_value, period_to from usage_export_anchor order by sequence desc limit 1",
                (rs, n) -> new ExportAnchor(rs.getLong(1), rs.getString(2), YearMonth.parse(rs.getString(3))));
        return rows.stream().findFirst();
    }

    private Optional<String> lastAudited() {
        List<String> details = db.queryForList("select safe_details::text from audit_events where action = ? order by occurred_at desc limit 1", String.class, ACTION);
        return details.stream().findFirst().map(d -> VeroJson.mapper().readTree(d).path("anchor").asString(""));
    }

    static String mark(ExportAnchor a) {
        return a.sequence() + ":" + a.finalChainValue() + ":" + a.periodTo();
    }
}
