package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.usage.UsageAssessment;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.RoleCapabilities;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Task 0075: the licence's state and this entity's use of it (Business Admin, IT Admin); the usage file (IT Admin). Never a price. */
@RestController
@RequestMapping("/api/v1/admin/licence")
public class LicenceController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final LicenceService licence;
    private final PlatformIdentityReads identity;
    private final UsageExports exports;

    public LicenceController(SecurityContextFacade security, AuthorizationService authorization, LicenceService licence,
                             PlatformIdentityReads identity, UsageExports exports) {
        this.security = security;
        this.authorization = authorization;
        this.licence = licence;
        this.identity = identity;
        this.exports = exports;
    }

    /** limit: 0 when the licence names none for this kind. level: WITHIN, NEAR_LIMIT, AT_LIMIT, OVER_LIMIT. */
    public record Use(String type, String unit, long used, long limit, String level) { }

    public record View(String state, String message, boolean intakeAllowed, boolean productLicensed, boolean testEnvironment, String licenceId, long version,
                       String customer, String kind, LocalDate startsOn, LocalDate expiresOn, LocalDate updatesUntil, LocalDate graceEndsOn,
                       LocalDate renewalStartsOn, boolean clockBehind, String clockMessage, int entities, String entitySlot, String month, List<Use> usage) { }

    @GetMapping
    public ResponseEntity<View> licence() {
        SdvPrincipal person = security.requirePrincipal();
        if (!RoleCapabilities.has(person.roles(), Capability.CONFIGURE_TENANT_AUTH)) authorization.require(person, Capability.VIEW_RESTRICTIONS);
        LicenceStatus status = licence.status();
        Licence l = status.licence();
        YearMonth month = licence.usage().currentMonth();
        String slot = identity.slotOf(person.tenantId()).map(PlatformIdentityReads.EntitySlot::slotId).orElse(null);
        List<Use> usage = slot == null || l.slot(slot).isEmpty() ? List.of() : java.util.stream.Stream.of(AllowanceType.STORAGE, AllowanceType.CONCURRENT_SEATS, AllowanceType.NAMED_SEATS)
                .map(type -> use(licence.usage().assess(slot, licence.productCode().orElseThrow(), type, month))).toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new View(status.state().name(), status.message(), licence.intakeAllowed(),
                licence.productLicensed(status), l.testEnvironment(), l.licenceId(), l.version(), l.customer(), l.kind().name(), l.startsOn(),
                l.expiry().orElse(null), l.updatesUntilDate().orElse(null), status.graceEndsOn().orElse(null),
                status.renewalWaiting().map(Licence::startsOn).orElse(null), status.clockBehind(), status.clock().message().orElse(null),
                l.entitySlots().size(), slot, month.toString(), usage));
    }

    private static Use use(UsageAssessment a) {
        return new Use(a.type().name(), a.type().unit(), a.used(), a.hasAllowance() ? a.limit() : 0, a.hasAllowance() ? a.level().name() : "WITHIN");
    }

    @PostMapping("/usage-export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "false") boolean startAgain) {
        SdvPrincipal person = security.requirePrincipal();
        authorization.require(person, Capability.CONFIGURE_TENANT_AUTH);
        UsageExports.Exported file = exports.export(person, MDC.get("correlationId"), startAgain);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(file.text().getBytes(StandardCharsets.UTF_8));
    }
}
