package com.sdv.platform.persistence.business;

import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 6: group names come from settings already split at commas, so a comma here is internal. */
class StoredNameCheckTest {
    @Test
    void aNameWithACommaIsInternal() {
        assertThrows(InternalCheckFailed.class, () -> JdbcRoleGovernanceRepository.join(List.of("a,b")));
        assertThrows(InternalCheckFailed.class, () -> JdbcRoleGovernanceRepository.join(Arrays.asList("a", null)));
        assertEquals("a,b", JdbcRoleGovernanceRepository.join(List.of("a", "b")));
    }
}
