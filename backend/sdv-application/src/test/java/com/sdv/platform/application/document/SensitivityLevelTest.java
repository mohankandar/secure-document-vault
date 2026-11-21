package com.sdv.platform.application.document;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.sdv.platform.domain.document.SensitivityLevel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 0024: the domain rules for sensitivity levels (the enum lives in sdv-domain, which has no test setup). */
class SensitivityLevelTest {
    @Test
    void missingMeansStandardAndEachExactNameIsAccepted() {
        assertEquals(STANDARD, SensitivityLevel.fromRequest(null, "f"));
        for (SensitivityLevel level : values()) assertEquals(level, SensitivityLevel.fromRequest(level.name(), "f"));
    }

    @Test
    void anythingElseIsRefusedNeverGuessed() {
        for (String bad : List.of("standard", "Confidential", "HIGHLY-CONFIDENTIAL", "SECRET", "", " ", "STANDARD ")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SensitivityLevel.fromRequest(bad, "sensitivityLevel"), bad);
            assertTrue(ex.getMessage().startsWith("sensitivityLevel must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL"));
        }
    }

    @Test
    void theOrderIsLowestToHighest() {
        assertEquals(List.of(STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL), List.of(values()));
    }

    @Test
    void theEffectiveLevelIsTheHigherOfTheDocumentsAndTheRecords() {
        for (SensitivityLevel doc : values()) {
            assertEquals(doc, SensitivityLevel.effective(doc, null), "no record level");
            for (SensitivityLevel record : values()) {
                SensitivityLevel expected = doc.ordinal() >= record.ordinal() ? doc : record;
                assertEquals(expected, SensitivityLevel.effective(doc, record), doc + " / " + record);
            }
        }
        assertEquals(HIGHLY_CONFIDENTIAL, SensitivityLevel.effective(STANDARD, HIGHLY_CONFIDENTIAL));
        assertEquals(HIGHLY_CONFIDENTIAL, SensitivityLevel.effective(HIGHLY_CONFIDENTIAL, CONFIDENTIAL));
        assertThrows(InternalCheckFailed.class, () -> SensitivityLevel.effective(null, STANDARD), "task 0037: internal, SDV-SYS-016");
    }
}
