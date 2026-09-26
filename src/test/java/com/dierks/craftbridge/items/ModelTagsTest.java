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
    void exactChoiceAcceptsTaggedAndUntaggedStacks() {
        List<String> stacks = CustomItemChoice.exactMatchStacks("tagged", "before-0.15");
        assertEquals(List.of("tagged", "before-0.15"), stacks, "the tagged stack is listed first, for display");
        assertTrue(stacks.contains("tagged"));
        assertTrue(stacks.contains("before-0.15"));
    }
}
