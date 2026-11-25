package com.sdv.platform.application.port;

import java.util.List;
import java.util.UUID;

/** Task 0077: the entity's case field definitions (platform-fields), beside what each sending system sends. */
public interface CaseFieldsPort {
    /** locked: the field has values, so its type, its identifier flag and its sensitivity don't change any more. */
    record FieldState(String fieldKey, String label, String type, boolean primary, boolean sensitive, boolean locked, boolean active) { }

    /** One line of an import. outcome: CREATE, UNCHANGED, CONFLICT; conflict: TYPE_DIFFERS, CURRENCY_DIFFERS, KEY_CASE_DIFFERS or null. */
    record ImportLine(String fieldKey, String label, String outcome, String conflict, String message) { }

    List<FieldState> fields(UUID entity);

    FieldState setSensitive(UUID entity, String fieldKey, boolean sensitive, String actor);

    String exportFile(UUID entity);

    /** Changes nothing. */
    List<ImportLine> previewImport(UUID entity, String file);

    List<ImportLine> importFile(UUID entity, String file, String actor);
}
