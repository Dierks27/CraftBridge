package com.dierks.craftbridge.sort;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SortAlgorithmTest {

    private static SortAlgorithm.Entry<String> e(String key, int amount, int max, int cat, String mat, String name) {
        return new SortAlgorithm.Entry<>(key, amount, max, cat, mat, name);
    }

    @Test
    void mergesPartialStacksAndKeepsTotals() {
        SortAlgorithm<String> algo = new SortAlgorithm<>();
        List<SortAlgorithm.Stack<String>> out = algo.sort(List.of(
                e("cobble", 30, 64, 4, "COBBLESTONE", ""),
                e("dirt", 5, 64, 4, "DIRT", ""),
                e("cobble", 50, 64, 4, "COBBLESTONE", ""),
                e("cobble", 10, 64, 4, "COBBLESTONE", "")));
        assertEquals(3, out.size());
        assertEquals(new SortAlgorithm.Stack<>("cobble", 64), out.get(0));
        assertEquals(new SortAlgorithm.Stack<>("cobble", 26), out.get(1));
        assertEquals(new SortAlgorithm.Stack<>("dirt", 5), out.get(2));
    }

    @Test
    void ordersByCategoryThenMaterialThenName() {
        SortAlgorithm<String> algo = new SortAlgorithm<>();
        List<SortAlgorithm.Stack<String>> out = algo.sort(List.of(
                e("stone", 1, 64, 4, "STONE", ""),
                e("apple", 1, 64, 3, "APPLE", ""),
                e("pick-named", 1, 1, 0, "DIAMOND_PICKAXE", "Zed"),
                e("pick", 1, 1, 0, "DIAMOND_PICKAXE", ""),
                e("sword", 1, 1, 1, "DIAMOND_SWORD", "")));
        assertEquals(List.of("pick", "pick-named", "sword", "apple", "stone"),
                out.stream().map(SortAlgorithm.Stack::key).toList());
    }

    @Test
    void respectsMaxStackOfUnstackables() {
        SortAlgorithm<String> algo = new SortAlgorithm<>();
        List<SortAlgorithm.Stack<String>> out = algo.sort(List.of(
                e("pick", 1, 1, 0, "DIAMOND_PICKAXE", ""),
                e("pick", 1, 1, 0, "DIAMOND_PICKAXE", "")));
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(s -> s.amount() == 1));
    }

    @Test
    void ignoresEmptyEntries() {
        SortAlgorithm<String> algo = new SortAlgorithm<>();
        assertTrue(algo.sort(List.of(e("x", 0, 64, 0, "X", ""))).isEmpty());
    }
}
