package com.sdv.platform.application.business;

import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0024, part 2: one canonical text value per record, the same for intake and for record detail. */
class RecordKeyTest {

    @Test
    void identifierKeysAreSortedSoTheOrderSentDoesNotMatter() {
        String a = RecordKey.ofIdentifiers(List.of(
                new RecordKey.Part("memberId", "M-2"), new RecordKey.Part("contractNumber", "123456")));
        String b = RecordKey.ofIdentifiers(List.of(
                new RecordKey.Part("contractNumber", "123456"), new RecordKey.Part("memberId", "M-2")));
        assertEquals(a, b);
        assertEquals("identifiers:[[\"contractNumber\",\"123456\"],[\"memberId\",\"M-2\"]]", a);
    }

    @Test
    void valuesAreWrittenAsJsonSoNoValueCanImitateAnotherKey() {
        String tricky = RecordKey.ofIdentifiers(List.of(new RecordKey.Part("a", "1\"],[\"b\",\"2")));
        String two = RecordKey.ofIdentifiers(List.of(new RecordKey.Part("a", "1"), new RecordKey.Part("b", "2")));
        assertNotEquals(tricky, two);
    }

    @Test
    void aSubmissionWithoutIdentifiersIsItsOwnRecord() {
        UUID one = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("submission:00000000-0000-0000-0000-000000000001", RecordKey.ofSubmission(one));
        assertNotEquals(RecordKey.ofSubmission(one), RecordKey.ofSubmission(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> RecordKey.ofIdentifiers(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RecordKey.Part("contractNumber", " "));
    }

    @Test
    void theShaIsOverTheUtf8BytesAsTheDatabaseCheckComputesIt() {
        // The same value is checked in Postgres by Test-SDV-SchemaUpgrade.ps1 (024).
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RecordKey.sha256("abc"));
        assertEquals(64, RecordKey.sha256("identifiers:[[\"name\",\"Zoë\"]]").length());
    }

    @Test
    void aRecordLevelIsOptionalButNeverGuessed() {
        assertNull(SensitivityLevel.optionalFromRequest(null, "recordSensitivityLevel"));
        for (SensitivityLevel level : SensitivityLevel.values()) {
            assertEquals(level, SensitivityLevel.optionalFromRequest(level.name(), "recordSensitivityLevel"));
        }
        for (String bad : List.of("confidential", "Confidential", "SECRET", "", " CONFIDENTIAL", "null", "1")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> SensitivityLevel.optionalFromRequest(bad, "recordSensitivityLevel"), bad);
            assertEquals("recordSensitivityLevel must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL "
                    + "(exactly, in capitals); leave it out for no record level.", ex.getMessage());
        }
    }
}
