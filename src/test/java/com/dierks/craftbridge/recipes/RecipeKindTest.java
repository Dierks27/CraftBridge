package com.dierks.craftbridge.recipes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeKindTest {

    @Test
    void cookingDefaultsMatchVanilla() {
        assertEquals(200, RecipeKind.FURNACE.defaultCookingTime());
        assertEquals(100, RecipeKind.SMOKER.defaultCookingTime());
        assertEquals(100, RecipeKind.BLAST_FURNACE.defaultCookingTime());
        assertEquals(600, RecipeKind.CAMPFIRE.defaultCookingTime());
    }

    @Test
    void craftingKindsHaveNoCookTime() {
        assertEquals(0, RecipeKind.SHAPED.defaultCookingTime());
        assertEquals(0, RecipeKind.SHAPELESS.defaultCookingTime());
        assertFalse(RecipeKind.SHAPED.isCooking());
        assertFalse(RecipeKind.SHAPELESS.isCooking());
    }

    @Test
    void cookingKindsUseOneInputSlotAndCraftingUsesNine() {
        assertEquals(9, RecipeKind.SHAPED.inputSlots());
        assertEquals(9, RecipeKind.SHAPELESS.inputSlots());
        for (RecipeKind kind : RecipeKind.values()) {
            if (kind.isCooking()) {
                assertEquals(1, kind.inputSlots(), kind + " should take a single input");
            }
        }
    }

    @Test
    void tokensRoundTrip() {
        for (RecipeKind kind : RecipeKind.values()) {
            assertSame(kind, RecipeKind.fromToken(kind.token()), "round trip for " + kind);
        }
    }

    @Test
    void unknownTypeFallsBackToShapedSoOldFilesKeepWorking() {
        // The old store wrote only "shaped"/"shapeless" and treated anything else as shaped.
        assertSame(RecipeKind.SHAPED, RecipeKind.fromToken(null));
        assertSame(RecipeKind.SHAPED, RecipeKind.fromToken(""));
        assertSame(RecipeKind.SHAPED, RecipeKind.fromToken("nonsense"));
        assertSame(RecipeKind.SHAPELESS, RecipeKind.fromToken("shapeless"));
    }

    @Test
    void tokenParsingToleratesSpellingAdminsReachFor() {
        assertSame(RecipeKind.FURNACE, RecipeKind.fromToken("smelting"));
        assertSame(RecipeKind.FURNACE, RecipeKind.fromToken("SMELT"));
        assertSame(RecipeKind.BLAST_FURNACE, RecipeKind.fromToken("blasting"));
        assertSame(RecipeKind.BLAST_FURNACE, RecipeKind.fromToken("blast-furnace"));
        assertSame(RecipeKind.BLAST_FURNACE, RecipeKind.fromToken("blast furnace"));
        assertSame(RecipeKind.SMOKER, RecipeKind.fromToken("smoking"));
        assertSame(RecipeKind.CAMPFIRE, RecipeKind.fromToken("campfire_cooking"));
    }

    @Test
    void nextCyclesThroughEveryKindAndReturns() {
        RecipeKind kind = RecipeKind.SHAPED;
        for (int i = 0; i < RecipeKind.values().length; i++) {
            kind = kind.next();
        }
        assertSame(RecipeKind.SHAPED, kind, "cycling all kinds should come back round");
    }

    @Test
    void cookTimeIsClampedAwayFromZeroSoACookerCannotStall() {
        assertEquals(1, RecipeKind.clampCookingTime(0));
        assertEquals(1, RecipeKind.clampCookingTime(-40));
        assertEquals(200, RecipeKind.clampCookingTime(200));
        assertEquals(72_000, RecipeKind.clampCookingTime(999_999));
    }

    @Test
    void experienceIsClampedToASaneRange() {
        assertEquals(0f, RecipeKind.clampExperience(-1f));
        assertEquals(0f, RecipeKind.clampExperience(Float.NaN));
        assertEquals(0.1f, RecipeKind.clampExperience(0.1f));
        assertEquals(100f, RecipeKind.clampExperience(5000f));
    }

    @Test
    void everyKindHasALabel() {
        for (RecipeKind kind : RecipeKind.values()) {
            assertNotNull(kind.label());
            assertFalse(kind.label().isBlank(), kind + " needs a label");
        }
    }

    @Test
    void craftingAndCookingPartitionTheKinds() {
        for (RecipeKind kind : RecipeKind.values()) {
            assertTrue(kind.isCooking() ^ kind.isCrafting(), kind + " must be exactly one of the two");
        }
    }
}
