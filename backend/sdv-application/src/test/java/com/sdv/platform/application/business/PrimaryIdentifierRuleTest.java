package com.sdv.platform.application.business;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0056: the one primary-identifier rule: display order first, unsaved fields after, then field key. */
class PrimaryIdentifierRuleTest {

    private static List<String> sorted(List<String> displayOrder, String... keys) {
        List<String> list = new ArrayList<>(List.of(keys));
        list.sort(PrimaryIdentifierRule.fieldOrder(displayOrder));
        return list;
    }

    @Test
    void displayOrderWinsOverFieldKey() {
        // A non-identifier field is first in display order; of the identifiers, policyNumber is shown before accountRef.
        assertEquals(List.of("policyNumber", "accountRef"),
                sorted(List.of("branch", "policyNumber", "accountRef"), "accountRef", "policyNumber"));
    }

    @Test
    void fieldsWithoutASavedPositionComeAfterByFieldKey() {
        assertEquals(List.of("zone", "alpha", "beta"), sorted(List.of("zone"), "beta", "alpha", "zone"));
        assertEquals(List.of("alpha", "beta"), sorted(List.of(), "beta", "alpha"), "no saved order: by field key");
        assertEquals(List.of("alpha", "beta"), sorted(null, "beta", "alpha"));
    }
}
