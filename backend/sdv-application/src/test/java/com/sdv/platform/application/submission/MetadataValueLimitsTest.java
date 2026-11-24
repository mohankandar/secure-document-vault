package com.sdv.platform.application.submission;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SetupProblem;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;
import static com.sdv.platform.application.submission.MetadataValueLimitsTestFields.text;

/** Task 0063: a value has a longest length, and a pattern check a longest time. */
class MetadataValueLimitsTest {
    @Test
    void aValueHasALongestLength() {
        MetadataFieldDefinition field = text("{}");
        assertEquals(4000, MetadataValueValidator.validate(field, "x".repeat(4000)).storedValue().length());
        var refused = assertThrows(CodedRefusal.BadRequest.class, () -> MetadataValueValidator.validate(field, "x".repeat(4001)));
        assertEquals("SDV-INT-009", refused.errorCode().code());
        assertFalse(refused.getMessage().contains("xxx"), "never the value");
    }

    @Test
    @Timeout(10)
    void aPatternThatTakesTooLongIsTheFieldsSetupNotTheSenders() {
        var problem = assertThrows(SetupProblem.class, () -> MetadataValueValidator.validate(text("{\"pattern\":\"(a|aa)+\\\\1?b\"}"), "a".repeat(60) + "!"));
        assertEquals("SDV-INT-010", problem.errorCode().code());
        assertTrue(problem.logDetail().contains("takes too long"), problem.logDetail());
    }

    @Test
    void anOrdinaryPatternStillWorks() {
        assertEquals("300000000001", MetadataValueValidator.validate(text("{\"pattern\":\"\\\\d{12}\"}"), "300000000001").storedValue());
        assertThrows(CodedRefusal.BadRequest.class, () -> MetadataValueValidator.validate(text("{\"pattern\":\"\\\\d{12}\"}"), "30000000000a"));
    }
}
