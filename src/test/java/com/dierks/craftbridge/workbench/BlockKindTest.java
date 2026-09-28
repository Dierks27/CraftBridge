package com.dierks.craftbridge.workbench;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which block stands under a placed block. The sweep forgets a record whose block is anything
 * else, so both the barrier and the vanilla block must count, whichever display.invisible-block
 * asks for now: that is what lets a switch convert placed blocks instead of forgetting them.
 */
class BlockKindTest {

    @Test
    void theInvisibleBlockIsABarrierAndTheOtherIsTheVanillaBlock() {
        assertEquals(Material.BARRIER, BlockKind.WORKBENCH.worldBlock(true));
        assertEquals(Material.CRAFTING_TABLE, BlockKind.WORKBENCH.worldBlock(false));
        assertEquals(Material.BARRIER, BlockKind.COMBO_CHEST.worldBlock(true));
        assertEquals(Material.BARREL, BlockKind.COMBO_CHEST.worldBlock(false));
    }

    @Test
    void aPlacedBlockStandsOnEitherButNothingElse() {
        assertTrue(BlockKind.WORKBENCH.standsOn(Material.BARRIER));
        assertTrue(BlockKind.WORKBENCH.standsOn(Material.CRAFTING_TABLE));
        assertFalse(BlockKind.WORKBENCH.standsOn(Material.BARREL), "a workbench is never a barrel");
        assertFalse(BlockKind.WORKBENCH.standsOn(Material.AIR), "broken or wiped by a regen");
        assertTrue(BlockKind.COMBO_CHEST.standsOn(Material.BARREL));
        assertFalse(BlockKind.COMBO_CHEST.standsOn(Material.CRAFTING_TABLE));
    }
}
