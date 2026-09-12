package com.dierks.craftbridge.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomItemIdsTest {

    @Test
    void ordinaryIdsAreAccepted() {
        assertNull(CustomItemIds.problem("burned_zombie_flesh"));
        assertNull(CustomItemIds.problem("a"));
        assertNull(CustomItemIds.problem("item-2"));
        assertNull(CustomItemIds.problem("x9"));
    }

    @Test
    void emptyAndReservedIdsAreRejected() {
        assertNotNull(CustomItemIds.problem(null));
        assertNotNull(CustomItemIds.problem(""));
        assertNotNull(CustomItemIds.problem("   "));
        assertNotNull(CustomItemIds.problem("air"));
        assertNotNull(CustomItemIds.problem("none"));
        assertNotNull(CustomItemIds.problem("null"));
    }

    @Test
    void punctuationAndSpacesAreRejected() {
        assertNotNull(CustomItemIds.problem("Burned Flesh"));
        assertNotNull(CustomItemIds.problem("burned/flesh"));
        assertNotNull(CustomItemIds.problem("burned.flesh"));
        assertNotNull(CustomItemIds.problem("burned:flesh"));
        assertNotNull(CustomItemIds.problem("Uppercase"));
    }

    @Test
    void overlongIdsAreRejected() {
        assertNotNull(CustomItemIds.problem("a".repeat(65)));
        assertNull(CustomItemIds.problem("a".repeat(64)));
    }

    @Test
    void sanitiseProducesAValidIdFromAdminInput() {
        assertEquals("burned_zombie_flesh", CustomItemIds.sanitise("Burned Zombie Flesh"));
        assertEquals("founders_skull", CustomItemIds.sanitise("Founder's Skull"));
        assertEquals("weird_name", CustomItemIds.sanitise("  weird!!  name  "));
    }

    @Test
    void sanitiseOutputIsAlwaysValidOrEmpty() {
        String[] inputs = {"Burned Zombie Flesh", "!!!", "___", "-x-", "a".repeat(200), "Ünïcödé", "42"};
        for (String input : inputs) {
            String id = CustomItemIds.sanitise(input);
            if (!id.isEmpty()) {
                assertTrue(CustomItemIds.isValid(id), "sanitise(" + input + ") = '" + id + "' should be valid");
            }
        }
    }

    @Test
    void sanitiseStripsLeadingAndTrailingSeparators() {
        assertEquals("x", CustomItemIds.sanitise("___x___"));
        assertEquals("x", CustomItemIds.sanitise("---x---"));
        assertEquals("", CustomItemIds.sanitise("___"));
        assertEquals("", CustomItemIds.sanitise("!!!"));
    }

    @Test
    void sanitiseCollapsesRunsLeftByStrippedPunctuation() {
        // "a & b" would otherwise become "a___b".
        assertEquals("a_b", CustomItemIds.sanitise("a & b"));
    }

    @Test
    void isValidAgreesWithProblem() {
        String[] samples = {"ok", "", "Bad Id", "air", "a-b_c", "a".repeat(65)};
        for (String s : samples) {
            assertEquals(CustomItemIds.problem(s) == null, CustomItemIds.isValid(s), s);
        }
        assertFalse(CustomItemIds.isValid(null));
    }
}
