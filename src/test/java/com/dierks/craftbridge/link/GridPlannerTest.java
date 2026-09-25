package com.dierks.craftbridge.link;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dierks.craftbridge.link.GridPlanner.Fill;
import static com.dierks.craftbridge.link.GridPlanner.Plan;
import static com.dierks.craftbridge.link.GridPlanner.Slot;
import static com.dierks.craftbridge.link.GridPlanner.Supply;
import static com.dierks.craftbridge.link.GridPlanner.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's side of a CraftBridge transfer. The client asks for a recipe and nothing more,
 * so everything asserted here is worked out from the server's own reading of the player's
 * inventory and the containers in range.
 */
class GridPlannerTest {

    private static int[] stacks(int... limits) {
        return limits;
    }

    /** Eight slots of type 0 around one of type 1: a chest, near enough. */
    private static List<Slot> chestShape() {
        return List.of(
                new Slot(0, new int[]{0}), new Slot(1, new int[]{0}), new Slot(2, new int[]{0}),
                new Slot(3, new int[]{0}), new Slot(5, new int[]{0}),
                new Slot(6, new int[]{0}), new Slot(7, new int[]{0}), new Slot(8, new int[]{0}));
    }

    @Test
    void oneSetTakesOneItemPerSlot() {
        Plan p = plan(chestShape(), List.of(new Supply(8, 0)), stacks(64), false);
        assertTrue(p.ok(), p.failure());
        assertEquals(1, p.sets());
        assertEquals(8, p.fills().size());
        for (Fill fill : p.fills()) {
            assertEquals(1, fill.count(), "slot " + fill.gridIndex());
        }
    }

    @Test
    void theScarcestIngredientDecidesHowManySetsAreMade() {
        // 8 planks per set; 100 planks in range makes 12 sets with 4 left over.
        Plan p = plan(chestShape(), List.of(new Supply(0, 100)), stacks(64), true);
        assertTrue(p.ok(), p.failure());
        assertEquals(12, p.sets());
        assertEquals(96, p.fills().stream().mapToInt(Fill::count).sum());
    }

    @Test
    void aCraftCountFillsTheGridForExactlyThatMany() {
        // Sticks: two planks stacked, 100 planks in range. Sixteen asked, sixteen per slot.
        List<Slot> sticks = List.of(new Slot(1, new int[]{0}), new Slot(4, new int[]{0}));
        Plan p = plan(sticks, List.of(new Supply(0, 100)), stacks(64), false, 16);
        assertTrue(p.ok(), p.failure());
        assertEquals(16, p.sets());
        for (Fill fill : p.fills()) {
            assertEquals(16, fill.count(), "slot " + fill.gridIndex());
        }
    }

    @Test
    void aCraftCountOverridesMaxTransferInBothDirections() {
        List<Slot> sticks = List.of(new Slot(1, new int[]{0}), new Slot(4, new int[]{0}));
        assertEquals(3, plan(sticks, List.of(new Supply(0, 100)), stacks(64), true, 3).sets());
        assertEquals(50, plan(sticks, List.of(new Supply(0, 100)), stacks(64), true, 0).sets());
        assertEquals(1, plan(sticks, List.of(new Supply(0, 100)), stacks(64), false, 0).sets());
    }

    @Test
    void aCraftCountIsBoundedByWhatIsToHandAndByStackSizes() {
        // Coal for 10 torches only.
        List<Slot> torch = List.of(new Slot(1, new int[]{0}), new Slot(4, new int[]{1}));
        Plan p = plan(torch, List.of(new Supply(3, 7), new Supply(0, 500)), stacks(64, 64), false, 64);
        assertTrue(p.ok(), p.failure());
        assertEquals(10, p.sets());
        // Ender pearls stack to 16: a request for 64 eyes stops at a full slot.
        List<Slot> eye = List.of(new Slot(0, new int[]{0}), new Slot(1, new int[]{1}));
        assertEquals(16, plan(eye, List.of(new Supply(0, 500), new Supply(0, 500)), stacks(16, 64), false, 64).sets());
    }

    @Test
    void aCraftCountStillSpendsThePlayersOwnItemsFirst() {
        List<Slot> one = List.of(new Slot(4, new int[]{0}));
        Plan p = plan(one, List.of(new Supply(5, 100)), stacks(64), false, 12);
        assertEquals(12, p.sets());
        assertEquals(5, p.fills().get(0).fromPlayer());
        assertEquals(7, p.fills().get(0).fromStorage());
    }

    @Test
    void aSlotNeverHoldsMoreThanItsStackSize() {
        // A shulker box stacks to 1: however many are in range, one per slot is the limit.
        Plan p = plan(List.of(new Slot(0, new int[]{0}), new Slot(1, new int[]{0})),
                List.of(new Supply(0, 40)), stacks(1), true);
        assertTrue(p.ok(), p.failure());
        assertEquals(1, p.sets());
    }

    @Test
    void theInventoryIsSpentBeforeStorageIs() {
        // What stays in a chest stays findable, and only what came out of one has to go back.
        Plan p = plan(List.of(new Slot(0, new int[]{0})), List.of(new Supply(3, 100)), stacks(64), true);
        assertTrue(p.ok(), p.failure());
        Fill fill = p.fills().get(0);
        assertEquals(3, fill.fromPlayer());
        assertEquals(fill.count() - 3, fill.fromStorage());
    }

    @Test
    void slotsThatShareAnIngredientShareItsSupply() {
        // Two slots of the same type and five items: two sets, not five.
        Plan p = plan(List.of(new Slot(0, new int[]{0}), new Slot(1, new int[]{0})),
                List.of(new Supply(0, 5)), stacks(64), true);
        assertTrue(p.ok(), p.failure());
        assertEquals(2, p.sets());
        assertEquals(4, p.fills().stream().mapToInt(Fill::count).sum());
    }

    @Test
    void aSlotThatAcceptsSeveralItemsTakesOneThatIsActuallyThere() {
        // Any plank will do, and only the third kind is in range.
        Plan p = plan(List.of(new Slot(4, new int[]{0, 1, 2})),
                List.of(new Supply(0, 0), new Supply(0, 0), new Supply(0, 7)), stacks(64, 64, 64), false);
        assertTrue(p.ok(), p.failure());
        assertEquals(2, p.fills().get(0).type());
    }

    @Test
    void twoSlotsAcceptingTheSameThingDoNotBothClaimTheLastOne() {
        // One oak plank and one birch: each slot takes a different one rather than both
        // choosing oak and the plan promising an item that is not there.
        Plan p = plan(List.of(new Slot(0, new int[]{0, 1}), new Slot(1, new int[]{0, 1})),
                List.of(new Supply(1, 0), new Supply(1, 0)), stacks(64, 64), false);
        assertTrue(p.ok(), p.failure());
        assertEquals(0, p.fills().get(0).type());
        assertEquals(1, p.fills().get(1).type());
        assertEquals(1, p.fills().get(0).count());
        assertEquals(1, p.fills().get(1).count());
    }

    @Test
    void anIngredientNobodyHasNamesTheSlotItIsMissingFrom() {
        Plan p = plan(List.of(new Slot(0, new int[]{0}), new Slot(4, new int[]{1})),
                List.of(new Supply(5, 5), new Supply(0, 0)), stacks(64, 64), false);
        assertFalse(p.ok());
        assertEquals(List.of(4), p.missing());
        assertTrue(p.fills().isEmpty(), "nothing is moved when the recipe cannot be made");
    }

    @Test
    void everyMissingSlotIsNamed() {
        Plan p = plan(List.of(new Slot(0, new int[]{1}), new Slot(3, new int[]{1})),
                List.of(new Supply(9, 9), new Supply(0, 0)), stacks(64, 64), false);
        assertFalse(p.ok());
        assertEquals(List.of(0, 3), p.missing());
    }

    @Test
    void aRequestWithNoIngredientsIsRefusedRatherThanFilledWithNothing() {
        Plan p = plan(List.of(), List.of(), stacks(), true);
        assertFalse(p.ok());
        assertEquals(0, p.sets());
    }

    @Test
    void anOptionOutsideTheSupplyTableIsIgnoredNotTrusted() {
        // The request is client-written: an index pointing at nothing must not throw or
        // conjure an ingredient, it just does not satisfy the slot.
        Plan p = plan(List.of(new Slot(0, new int[]{7, 0})), List.of(new Supply(0, 3)), stacks(64), false);
        assertTrue(p.ok(), p.failure());
        assertEquals(0, p.fills().get(0).type());

        Plan none = plan(List.of(new Slot(0, new int[]{7})), List.of(new Supply(0, 3)), stacks(64), false);
        assertFalse(none.ok());
    }

    @Test
    void oneSetIsExactlyOneSetEvenWhenPlentyIsAvailable() {
        Plan p = plan(chestShape(), List.of(new Supply(64, 640)), stacks(64), false);
        assertTrue(p.ok(), p.failure());
        assertEquals(1, p.sets());
        assertEquals(8, p.fills().stream().mapToInt(Fill::count).sum());
    }
    @Test
    void aGridIndexOutsideTheGridIsRefusedBeforeAnythingIsPlanned() {
        // Client-written indexes: a slot 9 or -1 would have its items taken and never placed.
        for (int bad : new int[]{-1, 9, 42, Integer.MIN_VALUE}) {
            List<Slot> slots = List.of(new Slot(0, new int[]{0}), new Slot(bad, new int[]{0}));
            assertNotNull(GridPlanner.gridProblem(slots), "index " + bad);
            Plan p = plan(slots, List.of(new Supply(64, 64)), stacks(64), true);
            assertFalse(p.ok(), "index " + bad);
            assertTrue(p.fills().isEmpty(), "nothing is taken for a request that cannot be placed");
        }
    }

    @Test
    void aGridSlotNamedTwiceIsRefused() {
        // Two fills for one slot: the second would overwrite the first, deleting its items.
        List<Slot> slots = List.of(new Slot(4, new int[]{0}), new Slot(4, new int[]{0}));
        assertNotNull(GridPlanner.gridProblem(slots));
        Plan p = plan(slots, List.of(new Supply(64, 64)), stacks(64), true);
        assertFalse(p.ok());
        assertTrue(p.fills().isEmpty());
    }

    @Test
    void everyRealGridSlotIsAccepted() {
        List<Slot> all = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            all.add(new Slot(i, new int[]{0}));
        }
        assertNull(GridPlanner.gridProblem(all));
        assertNull(GridPlanner.gridProblem(chestShape()));
        assertTrue(plan(all, List.of(new Supply(9, 0)), stacks(64), false).ok());
    }
}
