package com.dierks.craftbridge.jei;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crafting-table layout: grid 1-9, inventory 10-45. Keys are strings; "sword" is unstackable. */
class TransferEngineTest {

    private static final Set<Integer> GRID = GridLayout.WORKBENCH.gridSlots();
    private static final Set<Integer> INV = GridLayout.WORKBENCH.inventorySlots();
    private static final List<Integer> GRID_LIST = List.copyOf(GRID);
    private static final List<Integer> INV_LIST = List.copyOf(INV);

    private final TransferEngine<String> engine = new TransferEngine<>(new TransferEngine.Model<>() {
        @Override
        public int maxStack(String key) {
            return key.equals("sword") ? 1 : key.equals("pearl") ? 16 : 64;
        }

        @Override
        public boolean same(String a, String b) {
            return a.equals(b);
        }
    });

    private static TransferEngine.Stack<String> s(String key, int count) {
        return new TransferEngine.Stack<>(key, count);
    }

    private static TransferPacket packet(List<TransferPacket.Op> ops, boolean max, boolean complete) {
        return new TransferPacket(ops, GRID_LIST, INV_LIST, max, complete, 1);
    }

    private static Map<Integer, TransferEngine.Stack<String>> slots(Object... kv) {
        Map<Integer, TransferEngine.Stack<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 3) {
            m.put((Integer) kv[i], s((String) kv[i + 1], (Integer) kv[i + 2]));
        }
        return m;
    }

    @Test
    void movesOneSetIntoTheGrid() {
        var before = slots(10, "plank", 3);
        var r = engine.apply(before, packet(List.of(new TransferPacket.Op(10, 1, 1)), false, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("plank", 1), r.slots().get(1));
        assertEquals(s("plank", 2), r.slots().get(10));
        assertTrue(r.overflow().isEmpty());
    }

    @Test
    void shiftClickFillsAsManySetsAsPossible() {
        var before = slots(10, "plank", 10, 11, "stick", 4);
        var ops = List.of(new TransferPacket.Op(10, 1, 1), new TransferPacket.Op(11, 4, 1));
        var r = engine.apply(before, packet(ops, true, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("plank", 4), r.slots().get(1));
        assertEquals(s("stick", 4), r.slots().get(4));
        assertEquals(s("plank", 6), r.slots().get(10));
        assertNull(r.slots().get(11));
    }

    @Test
    void completeSetsRollBackAPartialSet() {
        var before = slots(10, "plank", 3, 11, "stick", 1);
        var ops = List.of(new TransferPacket.Op(10, 1, 1), new TransferPacket.Op(11, 2, 1));
        var r = engine.apply(before, packet(ops, true, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("plank", 1), r.slots().get(1));
        assertEquals(s("stick", 1), r.slots().get(2));
        assertEquals(s("plank", 2), r.slots().get(10), "second set's plank must be rolled back");
    }

    @Test
    void withoutCompleteSetsShiftClickFloodsWhatItCan() {
        var before = slots(10, "plank", 3, 11, "stick", 1);
        var ops = List.of(new TransferPacket.Op(10, 1, 1), new TransferPacket.Op(11, 2, 1));
        var r = engine.apply(before, packet(ops, true, false), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("plank", 3), r.slots().get(1));
        assertEquals(s("stick", 1), r.slots().get(2));
        assertNull(r.slots().get(10));
    }

    @Test
    void oneSourceStackFeedsSeveralGridSlots() {
        var before = slots(10, "plank", 2);
        var ops = List.of(new TransferPacket.Op(10, 1, 1), new TransferPacket.Op(10, 2, 1));
        var r = engine.apply(before, packet(ops, false, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("plank", 1), r.slots().get(1));
        assertEquals(s("plank", 1), r.slots().get(2));
        assertNull(r.slots().get(10));
    }

    @Test
    void clearsUnrelatedGridItemsBackIntoTheInventory() {
        var before = slots(1, "cobble", 5, 10, "plank", 1, 12, "cobble", 60);
        var r = engine.apply(before, packet(List.of(new TransferPacket.Op(10, 2, 1)), false, true), GRID, INV);
        assertTrue(r.success());
        assertNull(r.slots().get(1));
        assertEquals(s("plank", 1), r.slots().get(2));
        assertEquals(s("cobble", 64), r.slots().get(12), "topped up the existing cobble stack first");
        assertEquals(s("cobble", 1), r.slots().get(10), "then the first empty inventory slot");
        assertTrue(r.overflow().isEmpty());
    }

    @Test
    void itemsAlreadyInTheGridCanBeTheSource() {
        var before = slots(5, "plank", 1);
        var r = engine.apply(before, packet(List.of(new TransferPacket.Op(5, 1, 1)), false, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("plank", 1), r.slots().get(1));
        assertNull(r.slots().get(5));
    }

    @Test
    void unstackableItemsNeverExceedOnePerSlot() {
        var before = slots(10, "sword", 1, 11, "sword", 1);
        var r = engine.apply(before, packet(List.of(new TransferPacket.Op(10, 1, 1)), true, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("sword", 1), r.slots().get(1));
        assertEquals(s("sword", 1), r.slots().get(11));
    }

    @Test
    void respectsSmallerStackLimitsWhenFillingSets() {
        var before = slots(10, "pearl", 16, 11, "pearl", 16, 12, "plank", 64);
        var ops = List.of(new TransferPacket.Op(10, 1, 1), new TransferPacket.Op(12, 2, 1));
        var r = engine.apply(before, packet(ops, true, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(s("pearl", 16), r.slots().get(1));
        assertEquals(s("plank", 16), r.slots().get(2));
        // JEI keeps pulling planks after the pearl slot is full, then stows the 48 that
        // exceed the complete-set limit into the first empty inventory slot (10).
        assertEquals(s("plank", 48), r.slots().get(10));
        assertEquals(s("pearl", 16), r.slots().get(11));
        assertNull(r.slots().get(12));
    }

    @Test
    void failsCleanlyWhenTheSourceIsEmptyOrSlotsAreOutOfRange() {
        var before = slots(10, "plank", 1);
        var empty = engine.apply(before, packet(List.of(new TransferPacket.Op(11, 1, 1)), false, true), GRID, INV);
        assertFalse(empty.success());
        assertEquals(before, empty.slots());

        var resultSlot = new TransferPacket(List.of(new TransferPacket.Op(10, 0, 1)), List.of(0), INV_LIST, false, true, 1);
        assertFalse(engine.apply(before, resultSlot, GRID, INV).success());

        var armour = new TransferPacket(List.of(new TransferPacket.Op(5, 1, 1)), GRID_LIST, List.of(5), false, true, 1);
        assertFalse(engine.apply(slots(5, "plank", 1), armour, GridLayout.PLAYER.gridSlots(), GridLayout.PLAYER.inventorySlots()).success());
    }

    @Test
    void neverCreatesOrDestroysItems() {
        var before = slots(1, "cobble", 64, 2, "cobble", 64, 10, "plank", 9, 11, "stick", 9);
        for (int i = 12; i <= 45; i++) {
            before.put(i, s("dirt", 64));
        }
        var ops = List.of(new TransferPacket.Op(10, 1, 1), new TransferPacket.Op(10, 2, 1), new TransferPacket.Op(11, 5, 1));
        var r = engine.apply(before, packet(ops, true, true), GRID, INV);
        assertTrue(r.success());
        assertEquals(total(before), total(r.slots()) + r.overflow().stream().mapToInt(TransferEngine.Stack::count).sum());
        assertFalse(r.overflow().isEmpty(), "the cleared cobble has nowhere to go and must be reported as overflow");
    }

    private static int total(Map<Integer, TransferEngine.Stack<String>> m) {
        return m.values().stream().mapToInt(TransferEngine.Stack::count).sum();
    }
}
