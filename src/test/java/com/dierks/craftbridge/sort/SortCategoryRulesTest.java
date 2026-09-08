package com.dierks.craftbridge.sort;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SortCategoryRulesTest {

    @Test
    void defaultOrderPutsToolsFirstBlocksBeforeItems() {
        SortCategoryRules rules = SortCategoryRules.defaults();
        int tools = rules.categoryOf("DIAMOND_PICKAXE", false, false);
        int weapons = rules.categoryOf("IRON_SWORD", false, false);
        int armor = rules.categoryOf("LEATHER_BOOTS", false, false);
        int food = rules.categoryOf("COOKED_BEEF", false, true);
        int blocks = rules.categoryOf("STONE", true, false);
        int items = rules.categoryOf("STRING", false, false);
        assertEquals("tools", rules.nameOf(tools));
        assertEquals("weapons", rules.nameOf(weapons));
        assertEquals("armor", rules.nameOf(armor));
        assertEquals("food", rules.nameOf(food));
        assertEquals("blocks", rules.nameOf(blocks));
        assertEquals("items", rules.nameOf(items));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), List.of(tools, weapons, armor, food, blocks, items));
    }

    @Test
    void patternWinsOverBlockFlag() {
        SortCategoryRules rules = SortCategoryRules.defaults();
        // An edible block (cake) is food because @edible is checked before @block.
        assertEquals("food", rules.nameOf(rules.categoryOf("CAKE", true, true)));
    }

    @Test
    void badPatternIsIgnoredAndDefaultIsUsed() {
        SortCategoryRules rules = new SortCategoryRules(List.of(
                new SortCategoryRules.Category("broken", List.of("[unclosed")),
                new SortCategoryRules.Category("rest", List.of("@default"))));
        assertEquals("rest", rules.nameOf(rules.categoryOf("STONE", true, false)));
    }
}
