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
}
