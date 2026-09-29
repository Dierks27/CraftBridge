package com.dierks.craftbridge.pack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockVariantsTest {

    private static final String TABLE = "assets/minecraft/items/crafting_table.json";
    private static final byte[] DEFINITION = ("{\"model\": {\"type\": \"minecraft:select\", \"property\":"
            + " \"minecraft:custom_model_data\", \"index\": 0, \"cases\": [{\"when\": \"craftbridge:linked_workbench\","
            + " \"model\": {\"type\": \"minecraft:model\", \"model\": \"craftbridge:block/linked_workbench\"}}],"
            + " \"fallback\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:block/crafting_table\"}}}")
            .getBytes(StandardCharsets.UTF_8);
    private static final Map<String, String> BLOCKS = Map.of("linked_workbench", "crafting_table");

    @Test
    void withoutVariantsTheDefinitionIsUntouched() {
        SortedMap<String, byte[]> out = BlockVariants.withVariantCases(Map.of(TABLE, DEFINITION),
                Set.of(TABLE, BlockVariants.modelPath("linked_workbench")), BLOCKS);
        assertSame(DEFINITION, out.get(TABLE), "same bytes, so the pack's SHA-1 does not change");
    }

    @Test
    void eachVariantModelGetsItsCaseAfterTheBlocksOwn() {
        SortedMap<String, byte[]> out = BlockVariants.withVariantCases(Map.of(TABLE, DEFINITION),
                Set.of(BlockVariants.modelPath("linked_workbench_active"), BlockVariants.modelPath("linked_workbench_lid")),
                BLOCKS);
        String text = new String(out.get(TABLE), StandardCharsets.UTF_8);
        int own = text.indexOf("\"craftbridge:linked_workbench\"");
        int active = text.indexOf("\"craftbridge:linked_workbench_active\"");
        int lid = text.indexOf("\"craftbridge:linked_workbench_lid\"");
        assertTrue(own >= 0 && own < active && active < lid, text);
        assertTrue(text.contains("\"craftbridge:block/linked_workbench_lid\""), text);
        assertTrue(text.contains("\"minecraft:block/crafting_table\""), "the vanilla fallback stays");
        SortedMap<String, byte[]> again = BlockVariants.withVariantCases(out,
                Set.of(BlockVariants.modelPath("linked_workbench_lid")), BLOCKS);
        assertSame(out.get(TABLE), again.get(TABLE), "never added twice");
    }

    @Test
    void theHingeIsTheMiddleOfTheLidsBackBottomEdge() {
        byte[] lid = "{\"elements\": [{\"from\": [1, 10, 1], \"to\": [15, 14, 15]}, {\"from\": [7, 11, 0], \"to\": [9, 13, 1]}]}"
                .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(new double[] {8, 10, 15}, BlockVariants.hinge(lid));
        assertNull(BlockVariants.hinge("{}".getBytes(StandardCharsets.UTF_8)));
    }

    /** The lid turns about its hinge, and its front edge goes up, whatever the display's scale. */
    @Test
    void anOpenLidTurnsAboutItsHingeWithTheFrontEdgeUp() {
        double[] hinge = {8, 10, 15};
        for (double scale : new double[] {1.0, 1.002, 1.5}) {
            double[] base = {0, 0.25, 0};
            double[] t = BlockVariants.lidTransform(hinge, BlockVariants.LID_ANGLE, scale, base);
            assertArrayEquals(model(hinge, scale, base), rendered(hinge, t, scale), 1e-9, "the hinge stays put");
            double[] front = {8, 10, 1}; // the front bottom edge of the lid, closed
            double[] open = rendered(front, t, scale);
            double[] closed = model(front, scale, base);
            assertEquals(closed[1] + scale * 14 / 16.0, open[1], 1e-9, "a 90 degree turn lifts the front 14 px");
            double[] hingeAt = model(hinge, scale, base);
            assertEquals(hingeAt[2], open[2], 1e-9, "straight up above the hinge");
        }
    }

    /** Where a model point is drawn with the lid closed: flipped half a turn, centred, scaled, moved. */
    private static double[] model(double[] m, double scale, double[] base) {
        return new double[] {base[0] - scale * (m[0] / 16 - 0.5), base[1] + scale * (m[1] / 16 - 0.5),
                base[2] - scale * (m[2] / 16 - 0.5)};
    }

    /** Where it is drawn with the transformation {@code t}: T + scale * R(flip(m)). */
    private static double[] rendered(double[] m, double[] t, double scale) {
        double x = -(m[0] / 16 - 0.5), y = m[1] / 16 - 0.5, z = -(m[2] / 16 - 0.5);
        double a = t[3];
        double ry = y * Math.cos(a) - z * Math.sin(a);
        double rz = y * Math.sin(a) + z * Math.cos(a);
        return new double[] {t[0] + scale * x, t[1] + scale * ry, t[2] + scale * rz};
    }
}
