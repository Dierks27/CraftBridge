package com.dierks.craftbridge.sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sorting rule, independent of Bukkit so it can be unit-tested:
 * <ol>
 *   <li>Merge every stack that is "similar" (same {@code key}) into one total.</li>
 *   <li>Order the merged groups by category, then material name, then display name.</li>
 *   <li>Re-split each group into full stacks with one partial stack at the end.</li>
 * </ol>
 * Nothing is ever added or removed: the total amount per key is preserved exactly.
 *
 * @param <K> the similarity key (an ItemStack with amount 1 in production)
 */
public final class SortAlgorithm<K> {

    /** One input stack. */
    public record Entry<K>(K key, int amount, int maxStack, int category, String materialName, String displayName) {
    }

    /** One output stack, in final slot order. */
    public record Stack<K>(K key, int amount) {
    }

    private record Group<K>(K key, int maxStack, int category, String materialName, String displayName, int firstSeen) {
    }

    public List<Stack<K>> sort(List<Entry<K>> entries) {
        Map<K, Group<K>> groups = new LinkedHashMap<>();
        Map<K, Integer> totals = new LinkedHashMap<>();
        int order = 0;
        for (Entry<K> e : entries) {
            if (e == null || e.amount() <= 0) {
                continue;
            }
            if (!groups.containsKey(e.key())) {
                groups.put(e.key(), new Group<>(e.key(), Math.max(1, e.maxStack()), e.category(),
                        e.materialName() == null ? "" : e.materialName(),
                        e.displayName() == null ? "" : e.displayName(), order++));
            }
            totals.merge(e.key(), e.amount(), Integer::sum);
        }

        List<Group<K>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.<Group<K>>comparingInt(Group::category)
                .thenComparing(Group::materialName)
                .thenComparing(Group::displayName)
                .thenComparingInt(Group::firstSeen));

        List<Stack<K>> out = new ArrayList<>();
        for (Group<K> g : ordered) {
            int remaining = totals.get(g.key());
            while (remaining > 0) {
                int take = Math.min(remaining, g.maxStack());
                out.add(new Stack<>(g.key(), take));
                remaining -= take;
            }
        }
        return out;
    }
}
