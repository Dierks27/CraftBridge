package com.dierks.craftbridge.recipes;

import com.dierks.craftbridge.CraftBridgePlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code plugins/CraftBridge/recipes.yml}: one entry per recipe under {@code recipes:}.
 *
 * <pre>
 * recipes:
 *   ender_pearl:
 *     type: shaped              # or shapeless
 *     enabled: true
 *     group: ''                 # optional recipe-book group
 *     result: {material: ENDER_PEARL, amount: 2}          # or item: &lt;base64&gt; for full items
 *     shape: ['AGA', 'GDG', 'AGA']
 *     ingredients:
 *       A: {material: AMETHYST_SHARD}
 *       G: {material: GLOWSTONE_DUST}
 *       D: {item: &lt;base64&gt;, exact: true}   # exact-item match
 *       W: {tag: 'minecraft:wool'}          # any item in a tag (hand-edited only)
 *   string:
 *     type: shapeless
 *     result: {material: STRING, amount: 4}
 *     ingredients: [{tag: 'minecraft:wool'}, {material: BAMBOO}]
 * </pre>
 *
 * Results and exact ingredients written by the GUI carry the full item as base64 (Paper's
 * version-upgradeable {@code serializeAsBytes}) plus a human-readable {@code material}
 * mirror; hand-written entries can use just {@code material} (+ {@code amount}).
 */
public final class RecipeStore {

    private final CraftBridgePlugin plugin;
    private final File file;
    private final Map<String, CustomRecipe> recipes = new LinkedHashMap<>();

    public RecipeStore(CraftBridgePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "recipes.yml");
    }

    public Map<String, CustomRecipe> all() {
        return recipes;
    }

    public CustomRecipe get(String id) {
        return recipes.get(id);
    }

    public boolean contains(String id) {
        return recipes.containsKey(id);
    }

    public void put(CustomRecipe recipe) {
        recipes.put(recipe.id(), recipe);
        save();
    }

    public CustomRecipe remove(String id) {
        CustomRecipe removed = recipes.remove(id);
        if (removed != null) {
            save();
        }
        return removed;
    }

    /** {@code ender_pearl}, then {@code ender_pearl_2}, ... — never prompts the admin for an id. */
    public String freeId(Material material) {
        String base = material.name().toLowerCase(Locale.ROOT);
        if (!recipes.containsKey(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "_" + i;
            if (!recipes.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    // ---- load / save --------------------------------------------------------

    public void load() {
        recipes.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        recipes.putAll(parse(yaml, "recipes.yml"));
    }

    /** Parse the entries of a recipes YAML (file or bundled resource). Bad entries are logged and skipped. */
    public Map<String, CustomRecipe> parse(YamlConfiguration yaml, String sourceName) {
        Map<String, CustomRecipe> out = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("recipes");
        if (root == null) {
            return out;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                CustomRecipe recipe = parseOne(id.toLowerCase(Locale.ROOT), s);
                out.put(recipe.id(), recipe);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning(sourceName + ": recipe '" + id + "' skipped: " + ex.getMessage());
            }
        }
        return out;
    }

    private CustomRecipe parseOne(String id, ConfigurationSection s) {
        if (!id.matches("[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("id must be lowercase letters, digits, _ - . /");
        }
        boolean shaped = !"shapeless".equalsIgnoreCase(s.getString("type", "shaped"));
        boolean enabled = s.getBoolean("enabled", true);
        String group = s.getString("group", "");
        ItemStack result = readItem(s.getConfigurationSection("result"), true);
        if (result == null) {
            throw new IllegalArgumentException("missing result");
        }
        if (shaped) {
            List<String> shape = s.getStringList("shape");
            if (shape.isEmpty() || shape.size() > 3) {
                throw new IllegalArgumentException("shape must have 1-3 rows");
            }
            Map<Character, Ingredient> legend = new LinkedHashMap<>();
            ConfigurationSection ing = s.getConfigurationSection("ingredients");
            if (ing != null) {
                for (String letter : ing.getKeys(false)) {
                    if (letter.length() != 1) {
                        throw new IllegalArgumentException("ingredient keys must be single letters");
                    }
                    legend.put(letter.charAt(0), readIngredient(ing.getConfigurationSection(letter)));
                }
            }
            for (String row : shape) {
                for (char ch : row.toCharArray()) {
                    if (ch != ' ' && !legend.containsKey(ch)) {
                        throw new IllegalArgumentException("shape uses '" + ch + "' with no ingredient");
                    }
                }
            }
            return new CustomRecipe(id, true, enabled, group, result, shape, legend, List.of());
        }
        List<Ingredient> ingredients = new ArrayList<>();
        List<Map<?, ?>> list = s.getMapList("ingredients");
        for (Map<?, ?> m : list) {
            ingredients.add(readIngredient(m));
        }
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            throw new IllegalArgumentException("shapeless recipes need 1-9 ingredients");
        }
        return new CustomRecipe(id, false, enabled, group, result, List.of(), Map.of(), ingredients);
    }

    private Ingredient readIngredient(ConfigurationSection s) {
        if (s == null) {
            throw new IllegalArgumentException("empty ingredient");
        }
        return readIngredient(s.getValues(false));
    }

    private Ingredient readIngredient(Map<?, ?> m) {
        Object tag = m.get("tag");
        if (tag != null) {
            NamespacedKey key = NamespacedKey.fromString(tag.toString().toLowerCase(Locale.ROOT));
            if (key == null) {
                throw new IllegalArgumentException("bad tag '" + tag + "'");
            }
            return Ingredient.ofTag(key);
        }
        Object exactFlag = m.get("exact");
        boolean exact = exactFlag != null && Boolean.parseBoolean(exactFlag.toString());
        Object item = m.get("item");
        if (item != null) {
            ItemStack stack = decode(item.toString());
            return exact ? Ingredient.ofExact(stack) : Ingredient.ofMaterial(stack.getType());
        }
        Object material = m.get("material");
        if (material == null) {
            throw new IllegalArgumentException("ingredient needs material, item or tag");
        }
        Material mat = Material.matchMaterial(material.toString());
        if (mat == null || !mat.isItem()) {
            throw new IllegalArgumentException("unknown material '" + material + "'");
        }
        return Ingredient.ofMaterial(mat);
    }

    private ItemStack readItem(ConfigurationSection s, boolean withAmount) {
        if (s == null) {
            return null;
        }
        String encoded = s.getString("item");
        ItemStack stack;
        if (encoded != null && !encoded.isBlank()) {
            stack = decode(encoded);
        } else {
            String material = s.getString("material");
            Material mat = material == null ? null : Material.matchMaterial(material);
            if (mat == null || !mat.isItem()) {
                throw new IllegalArgumentException("unknown material '" + material + "'");
            }
            stack = new ItemStack(mat);
            if (withAmount) {
                stack.setAmount(Math.max(1, Math.min(99, s.getInt("amount", 1))));
            }
        }
        return stack;
    }

    private static ItemStack decode(String base64) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
    }

    private static String encode(ItemStack stack) {
        return Base64.getEncoder().encodeToString(stack.serializeAsBytes());
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "CraftBridge custom recipes. Edited by /recipe in-game; hand edits are fine too",
                "(see README for the format). Reload with /recipe reload or /craftbridge reload."));
        for (CustomRecipe r : recipes.values()) {
            String base = "recipes." + r.id();
            yaml.set(base + ".type", r.shaped() ? "shaped" : "shapeless");
            yaml.set(base + ".enabled", r.enabled());
            if (!r.group().isEmpty()) {
                yaml.set(base + ".group", r.group());
            }
            writeItem(yaml, base + ".result", r.result(), true);
            if (r.shaped()) {
                yaml.set(base + ".shape", r.shape());
                for (Map.Entry<Character, Ingredient> e : r.legend().entrySet()) {
                    writeIngredient(yaml, base + ".ingredients." + e.getKey(), e.getValue());
                }
            } else {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Ingredient ing : r.ingredients()) {
                    list.add(ingredientMap(ing));
                }
                yaml.set(base + ".ingredients", list);
            }
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save recipes.yml: " + ex.getMessage());
        }
    }

    private void writeItem(YamlConfiguration yaml, String path, ItemStack stack, boolean withAmount) {
        yaml.set(path + ".material", stack.getType().name());
        if (withAmount) {
            yaml.set(path + ".amount", stack.getAmount());
        }
        if (stack.hasItemMeta()) {
            yaml.set(path + ".item", encode(stack));
        }
    }

    private void writeIngredient(YamlConfiguration yaml, String path, Ingredient ing) {
        for (Map.Entry<String, Object> e : ingredientMap(ing).entrySet()) {
            yaml.set(path + "." + e.getKey(), e.getValue());
        }
    }

    private Map<String, Object> ingredientMap(Ingredient ing) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (ing.isTag()) {
            m.put("tag", ing.tag().asString());
        } else if (ing.isExact()) {
            m.put("material", ing.material().name());
            m.put("item", encode(ing.exact()));
            m.put("exact", true);
        } else {
            m.put("material", ing.material().name());
        }
        return m;
    }

    // ---- starter pack -------------------------------------------------------

    /**
     * Load the bundled peaceful-mode starter recipes (also copied to the plugin folder
     * as {@code recipes-peaceful-starter.yml} so they can be tuned). Only ids that do
     * not exist yet are added. Returns the ids that were imported.
     */
    public List<String> importStarter() {
        File starter = new File(plugin.getDataFolder(), "recipes-peaceful-starter.yml");
        if (!starter.exists()) {
            plugin.saveResource("recipes-peaceful-starter.yml", false);
        }
        YamlConfiguration yaml;
        if (starter.exists()) {
            yaml = YamlConfiguration.loadConfiguration(starter);
        } else {
            try (InputStream in = plugin.getResource("recipes-peaceful-starter.yml")) {
                if (in == null) {
                    return List.of();
                }
                yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                plugin.getLogger().warning("Could not read starter pack: " + ex.getMessage());
                return List.of();
            }
        }
        List<String> added = new ArrayList<>();
        for (CustomRecipe r : parse(yaml, "recipes-peaceful-starter.yml").values()) {
            if (!recipes.containsKey(r.id())) {
                recipes.put(r.id(), r);
                added.add(r.id());
            }
        }
        if (!added.isEmpty()) {
            save();
        }
        return added;
    }
}
