package com.sdv.platform.application.source;

import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.util.UUID;

/**
 * Task 0039: the intake's checks of the sending system and of the submission it names, with their codes.
 * <ul>
 *   <li>SDV-INT-005 (403): the token's sending system isn't one of the tenant's, or Tenant IT paused it. One answer for
 *       both; the WARN line says which, with the source ID. It used to be 404 or 409.</li>
 *   <li>SDV-INT-004 (404): no submission of this sending system has this ID. It used to be 404 NOT_FOUND.</li>
 * </ul>
 */
public final class ActiveSource {
    public static final String PAUSED_OR_UNKNOWN = "This sending system is paused or not set up in Vero. Nothing was stored.";
    public static final String NO_SUCH_SUBMISSION =
            "No submission of this sending system has this ID. Create the submission first, or check the ID.";

    private ActiveSource() { }

    public static IngestionSource require(IngestionSourceRepository sources, UUID tenantId, UUID sourceId) {
        IngestionSource source = sources.findById(tenantId, sourceId).orElse(null);
        if (source == null) throw refused(sourceId, "not a sending system of tenant=" + tenantId);
        if (source.status() != IngestionSourceStatus.ACTIVE) throw refused(sourceId, "status=" + source.status());
        return source;
    }

    public static CodedRefusal.NotFound noSuchSubmission(UUID submissionId) {
        return new CodedRefusal.NotFound(SdvErrorCode.INT_004, NO_SUCH_SUBMISSION,
                submissionId == null ? "no submission ID" : "submissionId=" + submissionId);
    }

    private static CodedRefusal.Forbidden refused(UUID sourceId, String why) {
        return new CodedRefusal.Forbidden(SdvErrorCode.INT_005, PAUSED_OR_UNKNOWN, "sourceId=" + sourceId + " " + why);
    }
}
