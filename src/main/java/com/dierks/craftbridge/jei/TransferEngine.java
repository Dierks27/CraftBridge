package com.dierks.craftbridge.jei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server side of a JEI recipe transfer — a faithful port of JEI's
 * {@code BasicRecipeTransferHandlerServer.setItemsWithResult} (26.2), written against an
 * abstract slot map so it needs no Bukkit and is unit-tested with plain keys.
 *
 * <p>Slots are keyed by their raw container index. A slot holds a {@link Stack} (an
 * item key + count) or nothing. The engine never invents or destroys items: every item
 * ends up in the grid, back in an inventory slot, or in {@link Result#overflow()} for the
 * caller to hand to the player.
 *
 * @param <K> the item key type (in production an ItemStack with amount 1)
 */
public final class TransferEngine<K> {

    public record Stack<K>(K key, int count) {
        public Stack<K> withCount(int newCount) {
            return new Stack<>(key, newCount);
        }
    }

    /** What the engine needs to know about item keys. */
    public interface Model<K> {
        /** Largest stack of this item a slot may hold (item max stack capped at 64). */
        int maxStack(K key);

        /** "Same item" for stacking purposes (ItemStack#isSimilar). */
        boolean same(K a, K b);
    }

    /**
     * @param success  whether anything was moved; on failure {@code slots} equals the input
     * @param slots    the full slot map after the transfer (absent = empty)
     * @param overflow items that fit nowhere in the listed inventory slots
     * @param failure  why it failed (null on success), for DEBUG logs
     */
    public record Result<K>(boolean success, Map<Integer, Stack<K>> slots, List<Stack<K>> overflow, String failure) {
    }

    private record Required<K>(int recipeSlot, int hint, Stack<K> stack) {
    }

    private final Model<K> model;

    public TransferEngine(Model<K> model) {
        this.model = model;
    }

    /**
     * @param initial        current contents by raw slot (only non-empty slots need entries)
     * @param packet         the decoded request
     * @param gridSlots      raw slots that are crafting-grid slots in the open menu
     * @param inventorySlots raw slots that are player-inventory slots in the open menu
     */
    public Result<K> apply(Map<Integer, Stack<K>> initial, TransferPacket packet, Set<Integer> gridSlots, Set<Integer> inventorySlots) {
        Map<Integer, Stack<K>> slots = new HashMap<>();
        initial.forEach((slot, stack) -> {
            if (stack != null && stack.count() > 0) {
                slots.put(slot, stack);
            }
        });
        List<Integer> craftingSlots = new ArrayList<>(new LinkedHashSet<>(packet.craftingSlots()));
        List<Integer> invSlots = new ArrayList<>(new LinkedHashSet<>(packet.inventorySlots()));
        String invalid = validate(packet, craftingSlots, invSlots, gridSlots, inventorySlots);
        if (invalid != null) {
            return fail(initial, invalid);
        }

        // calculateRequiredTransfers
        List<Required<K>> required = new ArrayList<>(packet.ops().size());
        Map<Integer, K> targetKeys = new HashMap<>();
        for (TransferPacket.Op op : packet.ops()) {
            Stack<K> source = slots.get(op.inventorySlot());
            if (source == null) {
                return fail(initial, "source slot " + op.inventorySlot() + " is empty");
            }
            K existing = targetKeys.putIfAbsent(op.craftingSlot(), source.key());
            if (existing != null && !model.same(existing, source.key())) {
                return fail(initial, "different items requested for crafting slot " + op.craftingSlot());
            }
            required.add(new Required<>(op.craftingSlot(), op.inventorySlot(), new Stack<>(source.key(), op.count())));
        }

        // Transfer as many items as possible only if the implementation asked for it and the player shift-clicked.
        boolean transferAsCompleteSets = packet.requireCompleteSets() || !packet.maxTransfer();
        Map<Integer, Stack<K>> taken = packet.maxTransfer()
                ? takeMax(slots, required, craftingSlots, invSlots, transferAsCompleteSets)
                : takeOneSet(slots, required, craftingSlots, invSlots, transferAsCompleteSets);
        if (taken.isEmpty()) {
            return fail(initial, "unable to remove any items from the inventory");
        }

        // clear the crafting grid
        List<Stack<K>> cleared = new ArrayList<>();
        for (int slot : craftingSlots) {
            Stack<K> s = slots.remove(slot);
            if (s != null) {
                cleared.add(s);
            }
        }

        // put items into the crafting grid
        int slotStackLimit = Integer.MAX_VALUE;
        if (packet.requireCompleteSets()) {
            for (Stack<K> s : taken.values()) {
                slotStackLimit = Math.min(slotStackLimit, model.maxStack(s.key()));
            }
        }
        List<Stack<K>> remainders = new ArrayList<>();
        for (Map.Entry<Integer, Stack<K>> e : taken.entrySet()) {
            Stack<K> s = e.getValue();
            int put = Math.min(Math.min(s.count(), slotStackLimit), model.maxStack(s.key()));
            slots.put(e.getKey(), s.withCount(put));
            if (s.count() > put) {
                remainders.add(s.withCount(s.count() - put));
            }
        }

        // put leftovers back into the inventory
        List<Stack<K>> overflow = new ArrayList<>();
        for (Stack<K> s : cleared) {
            stow(slots, invSlots, s, overflow);
        }
        for (Stack<K> s : remainders) {
            stow(slots, invSlots, s, overflow);
        }
        return new Result<>(true, slots, overflow, null);
    }

    private static <K> Result<K> fail(Map<Integer, Stack<K>> initial, String why) {
        return new Result<>(false, new HashMap<>(initial), List.of(), why);
    }

    /** RecipeTransferUtil.validateSlots, plus "crafting slots must be grid slots and inventory slots must be inventory". */
    private static String validate(TransferPacket packet, List<Integer> craftingSlots, List<Integer> invSlots,
                                   Set<Integer> gridSlots, Set<Integer> inventorySlots) {
        if (craftingSlots.isEmpty()) {
            return "no crafting slots";
        }
        for (int s : craftingSlots) {
            if (!gridSlots.contains(s)) {
                return "crafting slot " + s + " is not a grid slot of the open menu";
            }
        }
        for (int s : invSlots) {
            if (!inventorySlots.contains(s)) {
                return "inventory slot " + s + " is not a player-inventory slot of the open menu";
            }
            if (craftingSlots.contains(s)) {
                return "slot " + s + " listed as both crafting and inventory";
            }
        }
        for (TransferPacket.Op op : packet.ops()) {
            if (!craftingSlots.contains(op.craftingSlot())) {
                return "operation targets slot " + op.craftingSlot() + " which is not in the crafting slots";
            }
            if (!invSlots.contains(op.inventorySlot()) && !craftingSlots.contains(op.inventorySlot())) {
                return "operation sources from slot " + op.inventorySlot() + " which is neither inventory nor crafting";
            }
        }
        return null;
    }

    private Map<Integer, Stack<K>> takeMax(Map<Integer, Stack<K>> slots, List<Required<K>> required,
                                           List<Integer> craftingSlots, List<Integer> invSlots, boolean completeSets) {
        List<Required<K>> remaining = new ArrayList<>(required);
        Map<Integer, Stack<K>> result = new LinkedHashMap<>();
        while (true) {
            removeFullRecipeSlots(remaining, result);
            if (remaining.isEmpty()) {
                break;
            }
            Map<Integer, Stack<K>> found = takeOneSet(slots, remaining, craftingSlots, invSlots, completeSets);
            if (found.isEmpty()) {
                break;
            }
            found.forEach((slot, stack) -> merge(result, slot, stack));
        }
        return result;
    }

    private void removeFullRecipeSlots(List<Required<K>> required, Map<Integer, Stack<K>> result) {
        Set<Integer> full = new HashSet<>();
        for (Required<K> r : required) {
            Stack<K> have = result.get(r.recipeSlot());
            if (have == null) {
                continue;
            }
            int requiredCount = 0;
            for (Required<K> other : required) {
                if (other.recipeSlot() == r.recipeSlot()) {
                    requiredCount += other.stack().count();
                }
            }
            if (have.count() + requiredCount > model.maxStack(have.key())) {
                full.add(r.recipeSlot());
            }
        }
        required.removeIf(r -> full.contains(r.recipeSlot()));
    }

    private Map<Integer, Stack<K>> takeOneSet(Map<Integer, Stack<K>> slots, List<Required<K>> required,
                                              List<Integer> craftingSlots, List<Integer> invSlots, boolean completeSets) {
        Map<Integer, Stack<K>> original = completeSets ? new HashMap<>() : null;
        Map<Integer, Stack<K>> found = new LinkedHashMap<>();
        for (Required<K> r : required) {
            Integer source = findSource(slots, r, craftingSlots, invSlots);
            if (source != null) {
                Stack<K> s = slots.get(source);
                if (original != null && !original.containsKey(source)) {
                    original.put(source, s);
                }
                int left = s.count() - r.stack().count();
                if (left <= 0) {
                    slots.remove(source);
                } else {
                    slots.put(source, s.withCount(left));
                }
                merge(found, r.recipeSlot(), new Stack<>(s.key(), r.stack().count()));
            } else if (completeSets) {
                // Incomplete set: roll back everything taken during this set.
                original.forEach(slots::put);
                return Map.of();
            }
        }
        return found;
    }

    private Integer findSource(Map<Integer, Stack<K>> slots, Required<K> r, List<Integer> craftingSlots, List<Integer> invSlots) {
        if (matches(slots.get(r.hint()), r.stack())) {
            return r.hint();
        }
        for (int slot : craftingSlots) {
            if (matches(slots.get(slot), r.stack())) {
                return slot;
            }
        }
        for (int slot : invSlots) {
            if (matches(slots.get(slot), r.stack())) {
                return slot;
            }
        }
        return null;
    }

    private boolean matches(Stack<K> have, Stack<K> want) {
        return have != null && model.same(have.key(), want.key()) && have.count() >= want.count();
    }

    private void merge(Map<Integer, Stack<K>> into, int slot, Stack<K> stack) {
        Stack<K> existing = into.get(slot);
        into.put(slot, existing == null ? stack : existing.withCount(existing.count() + stack.count()));
    }

    /** BasicRecipeTransferHandlerServer.stowItem: top up same-item stacks first, then empty slots. */
    private void stow(Map<Integer, Stack<K>> slots, List<Integer> invSlots, Stack<K> stack, List<Stack<K>> overflow) {
        int remaining = stack.count();
        int max = model.maxStack(stack.key());
        for (int slot : invSlots) {
            Stack<K> existing = slots.get(slot);
            if (existing != null && model.same(existing.key(), stack.key()) && existing.count() < max) {
                int move = Math.min(remaining, max - existing.count());
                slots.put(slot, existing.withCount(existing.count() + move));
                remaining -= move;
                if (remaining == 0) {
                    return;
                }
            }
        }
        for (int slot : invSlots) {
            if (!slots.containsKey(slot)) {
                int move = Math.min(remaining, max);
                slots.put(slot, stack.withCount(move));
                remaining -= move;
                if (remaining == 0) {
                    return;
                }
            }
        }
        overflow.add(stack.withCount(remaining));
    }
}
