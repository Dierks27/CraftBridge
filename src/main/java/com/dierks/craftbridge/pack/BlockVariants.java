package com.dierks.craftbridge.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Optional extra models for a block (the Linked Workbench, the Combo Chest), found by name next
 * to its model in the pack:
 *
 * <ul>
 *   <li>{@code <id>_active.json}: the whole block while someone is using it (a lit screen, a
 *       tool off its peg). The display swaps to it on open and back on close.</li>
 *   <li>{@code <id>_lid.json}: a lid, drawn by a second display on top of the block's own model,
 *       which swings open on its back edge like a chest lid while someone is using the block.
 *       The block's own model is then everything but the lid.</li>
 * </ul>
 *
 * Bukkit-free: which item-definition cases the pack needs, where a lid's hinge is, and the
 * display transformation that swings it, all unit tested.
 */
public final class BlockVariants {

    public static final String ACTIVE = "_active";
    public static final String LID = "_lid";
    /** How far a lid opens, like a chest's. */
    public static final double LID_ANGLE = 90.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BlockVariants() {
    }

    /** {@code linked_workbench_lid} -> {@code assets/craftbridge/models/block/linked_workbench_lid.json}. */
    public static String modelPath(String name) {
        return "assets/craftbridge/models/block/" + name + ".json";
    }

    /**
     * {@code own} with a case added to each block's item definition for every variant model in
     * {@code present} (paths of the files the pack will have): {@code craftbridge:<id>_active}
     * draws {@code craftbridge:block/<id>_active}, and the same for the lid. A definition that
     * needs no case is left byte for byte as it is, so a pack without variants does not change.
     *
     * @param blocks the vanilla item each block's definition belongs to, by block id
     *               ({@code linked_workbench} -> {@code crafting_table})
     */
    public static SortedMap<String, byte[]> withVariantCases(Map<String, byte[]> own, Collection<String> present,
                                                            Map<String, String> blocks) {
        SortedMap<String, byte[]> out = new TreeMap<>(own);
        for (Map.Entry<String, String> block : blocks.entrySet()) {
            String path = "assets/minecraft/items/" + block.getValue() + ".json";
            byte[] bytes = own.get(path);
            if (bytes == null) {
                continue;
            }
            JsonObject definition = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray cases = definition.getAsJsonObject("model").getAsJsonArray("cases");
            boolean changed = false;
            String lidName = block.getKey() + LID;
            if (present.contains(modelPath(lidName))) {
                changed |= wholeInItems(cases, "craftbridge:" + block.getKey(), "craftbridge:block/" + lidName);
            }
            for (String suffix : new String[] {ACTIVE, LID}) {
                String name = block.getKey() + suffix;
                if (!present.contains(modelPath(name)) || hasCase(cases, "craftbridge:" + name)) {
                    continue;
                }
                JsonObject model = new JsonObject();
                model.addProperty("type", "minecraft:model");
                model.addProperty("model", "craftbridge:block/" + name);
                JsonObject entry = new JsonObject();
                entry.addProperty("when", "craftbridge:" + name);
                entry.add("model", model);
                cases.add(entry);
                changed = true;
            }
            if (changed) {
                out.put(path, (GSON.toJson(definition) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /**
     * A block with a lid is drawn in two pieces on the block (the lid is a display of its own),
     * but an item in a hand or an inventory is one model: the block's case draws only the body
     * where the display draws it (display context {@code none}) and body plus lid everywhere
     * else, so a place-item does not look lidless. False when already done.
     */
    private static boolean wholeInItems(JsonArray cases, String when, String lidModel) {
        for (JsonElement c : cases) {
            if (!c.isJsonObject() || !c.getAsJsonObject().has("when")
                    || !when.equals(c.getAsJsonObject().get("when").getAsString())) {
                continue;
            }
            JsonObject entry = c.getAsJsonObject();
            JsonObject body = entry.getAsJsonObject("model");
            if ("minecraft:select".equals(body.has("type") ? body.get("type").getAsString() : null)) {
                return false;
            }
            JsonObject lid = new JsonObject();
            lid.addProperty("type", "minecraft:model");
            lid.addProperty("model", lidModel);
            JsonArray parts = new JsonArray();
            parts.add(body.deepCopy());
            parts.add(lid);
            JsonObject whole = new JsonObject();
            whole.addProperty("type", "minecraft:composite");
            whole.add("models", parts);
            JsonObject onBlock = new JsonObject();
            onBlock.addProperty("when", "none");
            onBlock.add("model", body.deepCopy());
            JsonArray contexts = new JsonArray();
            contexts.add(onBlock);
            JsonObject select = new JsonObject();
            select.addProperty("type", "minecraft:select");
            select.addProperty("property", "minecraft:display_context");
            select.add("cases", contexts);
            select.add("fallback", whole);
            entry.add("model", select);
            return true;
        }
        return false;
    }

    private static boolean hasCase(JsonArray cases, String when) {
        for (JsonElement c : cases) {
            if (c.isJsonObject() && c.getAsJsonObject().has("when")
                    && when.equals(c.getAsJsonObject().get("when").getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where a lid swings from, in model pixels: the middle of its back bottom edge, like a chest
     * lid's. The front is north (small z), so the back is its largest z and the bottom its
     * smallest y. Null when the model has no elements to measure.
     */
    public static double[] hinge(byte[] lidModel) {
        JsonObject model = JsonParser.parseString(new String(lidModel, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray elements = model.has("elements") && model.get("elements").isJsonArray()
                ? model.getAsJsonArray("elements") : new JsonArray();
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (JsonElement e : elements) {
            JsonObject element = e.getAsJsonObject();
            for (String corner : new String[] {"from", "to"}) {
                JsonArray p = element.getAsJsonArray(corner);
                minX = Math.min(minX, p.get(0).getAsDouble());
                maxX = Math.max(maxX, p.get(0).getAsDouble());
                minY = Math.min(minY, p.get(1).getAsDouble());
                maxZ = Math.max(maxZ, p.get(2).getAsDouble());
            }
        }
        return elements.isEmpty() ? null : new double[] {(minX + maxX) / 2, minY, maxZ};
    }

    /**
     * The display transformation of a lid opened by {@code angle} degrees: a rotation about the
     * display's x axis, and the translation that keeps the hinge where it is.
     *
     * <p>An item display draws its model turned half a turn about y (model north along the
     * display's facing), centred on the display. So a model point {@code m} (pixels) sits at
     * {@code (-(mx/16 - 0.5), my/16 - 0.5, -(mz/16 - 0.5))} in the transformation's space, and
     * the model's "front edge up" rotation about x becomes the opposite rotation there. With the
     * display's uniform {@code scale} and its base {@code translation}, a point {@code v} ends up
     * at {@code T + scale * R v}; T = base + scale * (H - R H) turns it about the hinge H.
     *
     * @return {tx, ty, tz, angle in radians about x} for the transformation's left rotation
     */
    public static double[] lidTransform(double[] hingePixels, double angle, double scale, double[] base) {
        double hy = hingePixels[1] / 16 - 0.5;
        double hz = -(hingePixels[2] / 16 - 0.5);
        double a = -Math.toRadians(angle);
        // R = rotation about x by a: (x, y cos a - z sin a, y sin a + z cos a)
        double rhy = hy * Math.cos(a) - hz * Math.sin(a);
        double rhz = hy * Math.sin(a) + hz * Math.cos(a);
        return new double[] {base[0], base[1] + scale * (hy - rhy), base[2] + scale * (hz - rhz), a};
    }
}
