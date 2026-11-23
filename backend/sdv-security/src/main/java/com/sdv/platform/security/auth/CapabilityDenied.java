package com.sdv.platform.security.auth;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import org.springframework.security.access.AccessDeniedException;

/**
 * Task 0038 (SDV-ROL-006): the person's role doesn't include this action. It stays an {@link AccessDeniedException},
 * so the areas with their own code (SEN-010, HLD-001, APR-001/005/015, INT-001, RPT-001) still catch it and send theirs.
 */
public final class CapabilityDenied extends AccessDeniedException implements CodedRefusal {
    private final Capability capability;

    public CapabilityDenied(Capability capability) {
        super("Your role doesn't include " + inPlainWords(capability) + ". Ask someone who has it.");
        this.capability = capability;
    }

    public Capability capability() {
        return capability;
    }

    @Override
    public SdvErrorCode errorCode() {
        return SdvErrorCode.ROL_006;
    }

    @Override
    public String logDetail() {
        return "capability=" + capability.name();
    }

    /** The action in the words a person sees on the screen. Every capability has its own (the switch is exhaustive). */
    public static String inPlainWords(Capability capability) {
        return switch (capability) {
            case SEARCH_RECORDS -> "searching records";
            case VIEW_PDF -> "viewing documents";
            case PERMANENT_REDACTION -> "correcting documents (permanent redaction)";
            case DOWNLOAD_CURRENT_PDF -> "downloading documents";
            case PRINT_DOCUMENT -> "printing documents";
            case ADD_DOCUMENT_TAG -> "tagging documents";
            case ADD_DOCUMENT_COMMENT -> "commenting on documents";
            case CONFIGURE_METADATA_SEARCH -> "setting up record fields and search";
            case CONFIGURE_RETENTION -> "setting the retention policy";
            case CONFIGURE_BUSINESS_LIMITS -> "setting intake limits";
            case CONFIGURE_ROLE_PERMISSIONS -> "changing roles and permissions";
            case CONFIGURE_INGESTION_SOURCES -> "setting up sending systems";
            case CONFIGURE_TENANT_AUTH -> "changing the sign-in settings";
            case CONFIGURE_STORAGE_KEY_PROVIDER -> "setting up storage";
            case VIEW_TECHNICAL_FAILURES -> "seeing Operations and technical problems";
            case VIEW_CONFIDENTIAL -> "opening Confidential documents";
            case VIEW_HIGHLY_CONFIDENTIAL -> "opening Highly Confidential documents";
            case CHANGE_SENSITIVITY_LEVEL -> "changing a document's sensitivity level";
            case REQUEST_SENSITIVE_ACCESS -> "asking to print or download Highly Confidential documents";
            case APPROVE_SENSITIVE_ACCESS -> "deciding print and download requests";
            case MANAGE_LEGAL_HOLD -> "managing legal holds";
            case VIEW_INTAKE_REPORTS -> "seeing reports";
            case QUARANTINE_DOCUMENTS -> "quarantining and reviewing documents";
            case QUARANTINE_BY_FILTER -> "quarantining documents by filter";
            case GENERATE_AUDIT_REPORT -> "generating and verifying the sealed audit report";
            case VIEW_RESTRICTIONS -> "seeing the Restrictions page";
        };
    }
}
