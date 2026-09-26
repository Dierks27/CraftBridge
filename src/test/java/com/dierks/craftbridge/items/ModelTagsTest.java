package com.dierks.craftbridge.items;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model tag is how a resource pack tells custom items apart, and the refresh that adds it
 * to items made before 0.15 runs over every inventory a player opens. Both are worth pinning:
 * a tag that collided with a block's string would draw a custom item as a Linked Workbench,
 * and a refresh that touched more than index 0 would quietly rewrite other plugins' data.
 */
class ModelTagsTest {

    /**
     * {@code BlockKind#modelData()} for the two blocks. Hardcoded: loading BlockKind pulls in
     * Material, which needs a running server.
     */
    private static final List<String> BLOCK_STRINGS = List.of("craftbridge:linked_workbench", "craftbridge:combo_chest");

    @Test
    void theTagPrefixCannotCollideWithTheBlockStrings() {
        List<String> ids = List.of("linked_workbench", "combo_chest", "a", "a".repeat(64), "x-y_z");
        for (String id : ids) {
            assertTrue(CustomItemIds.isValid(id), id + " should be a legal item id");
            String tag = ModelTags.of(id);
            assertEquals("craftbridge:item/" + id, tag);
            for (String block : BLOCK_STRINGS) {
                assertNotEquals(block, tag, id);
            }
            String afterNamespace = tag.substring(tag.indexOf(':') + 1);
            assertTrue(afterNamespace.contains("/"), tag + " should have a path separator after the namespace");
        }
        for (String block : BLOCK_STRINGS) {
            assertFalse(block.contains("/"), block + " must stay a flat name for the prefix to keep them apart");
        }
    }

    @Test
    void refreshAddsAMissingTag() {
        assertEquals(List.of("craftbridge:item/flesh"), ModelTags.withTag(List.of(), "craftbridge:item/flesh"));
    }

    @Test
    void refreshReplacesOnlyAWrongIndexZero() {
        List<String> before = List.of("craftbridge:item/old_name", "other:plugin", "third");
        List<String> after = ModelTags.withTag(before, "craftbridge:item/flesh");
        assertEquals(List.of("craftbridge:item/flesh", "other:plugin", "third"), after);
    }

    @Test
    void refreshLeavesACorrectTagAlone() {
        assertNull(ModelTags.withTag(List.of("craftbridge:item/flesh"), "craftbridge:item/flesh"));
        assertNull(ModelTags.withTag(List.of("craftbridge:item/flesh", "extra"), "craftbridge:item/flesh"));
    }

    @Test
    void refreshNeverMutatesItsInput() {
        List<String> empty = new ArrayList<>();
        ModelTags.withTag(empty, "craftbridge:item/flesh");
        assertTrue(empty.isEmpty());

        List<String> wrong = new ArrayList<>(List.of("wrong", "kept"));
        ModelTags.withTag(wrong, "craftbridge:item/flesh");
        assertEquals(List.of("wrong", "kept"), wrong);

        // Paper hands out unmodifiable lists; mutating one would throw here.
        assertEquals(List.of("craftbridge:item/flesh", "kept"),
                ModelTags.withTag(List.of("wrong", "kept"), "craftbridge:item/flesh"));
    }

    @Test
    void refreshKeepsTheRestOfTheComponent() {
        ModelTags.Refreshed<Float, Boolean, String> next = ModelTags.refreshed(List.of(1.5f, 2f), List.of(true),
                List.of("wrong", "kept"), List.of("#ff0000"), "craftbridge:item/flesh");

        assertEquals(List.of(1.5f, 2f), next.floats());
        assertEquals(List.of(true), next.flags());
        assertEquals(List.of("#ff0000"), next.colors());
        assertEquals(List.of("craftbridge:item/flesh", "kept"), next.strings());
        assertNull(ModelTags.refreshed(List.of(1f), List.of(), List.of("craftbridge:item/flesh"), List.of(),
                "craftbridge:item/flesh"), "already tagged: nothing to write");
        assertEquals(List.of("craftbridge:item/flesh"),
                ModelTags.refreshed(List.of(), List.of(), List.of(), List.of(), "craftbridge:item/flesh").strings());
    }

    /**
     * The policy only: which stacks the exact fallback lists, in which order. Paper's ExactChoice
     * itself needs a running server to build stacks, so that old items really match is in-game
     * acceptance test 4.
     */
    @Test
    void theExactFallbackListsTheTaggedStackThenTheOldOne() {
        assertEquals(List.of("tagged", "before-0.15"), CustomItemChoice.exactMatchStacks("tagged", "before-0.15"),
                "the tagged stack first, so the recipe book shows it first");
        assertEquals(List.of("tagged"), CustomItemChoice.exactMatchStacks("tagged", null));
    }
}
