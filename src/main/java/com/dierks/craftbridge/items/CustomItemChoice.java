package com.dierks.craftbridge.items;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Turns a custom item into the {@link RecipeChoice} a recipe should match it with.
 *
 * <h2>Why this is not simply {@code new ExactChoice(stack)}</h2>
 * {@code ExactChoice} matches on the <em>entire</em> component patch
 * ({@code ItemStack.isSameItemSameComponents}). That is stricter than "carries our stamp":
 * if the stamped item ever gains or loses any component in normal use — an anvil rename, a
 * repair-cost bump, damage, an enchantment — it silently stops satisfying its own recipe,
 * with no error anywhere. That is the single most likely way a custom-item recipe breaks in
 * the field, and it breaks for Java and Bedrock players alike.
 *
 * <p>Paper grew {@code RecipeChoice.predicateChoice(Predicate, ItemStack)} during 26.2 for
 * exactly this. It keeps {@code isExact()} true — so the recipe book still shows the example
 * stack and Geyser still sees an {@code ItemStackSlotDisplay} — while running our predicate
 * for the actual match. Matching on the PDC id alone survives every kind of component drift.
 *
 * <p>It is resolved reflectively because the pinned dev bundle
 * ({@code 26.2.build.107-stable}) predates some of these factories, and
 * {@link com.dierks.craftbridge.recipes.Ingredient} already documents that the static
 * factories "may not" exist there. Compiling against the method directly would tie the
 * plugin to a build newer than the one it pins. When it is missing we fall back to
 * {@code ExactChoice}, which is correct but brittle in the way described above; the fallback
 * is logged once so the drift is diagnosable rather than mysterious.
 *
 * <p><b>Measured, not assumed:</b> on {@code 26.2.build.107-stable} the lookup finds nothing
 * and the {@code ExactChoice} fallback is what runs. The reflection is therefore load-bearing
 * on the current pin, not a precaution — a direct call would fail to link and take the whole
 * plugin down with it. Bump the dev bundle to a build carrying {@code predicateChoice} to get
 * the drift-proof matching.
 *
 * <h2>Items made before 0.15</h2>
 * Since 0.15 every freshly built custom item carries a model tag in its custom model data
 * ({@link ModelTags}). That is a new component, and the exact fallback compares components,
 * so an {@code ExactChoice} of the new item alone would stop accepting every custom item made
 * on 0.14 the moment 0.15 loads. The fallback therefore lists two stacks: the current, tagged
 * item first (the recipe book and JEI cycle through the list, starting there) and the item
 * exactly as 0.14 built it second. {@link CustomItemRefresher} tags old items as players come across them, but a
 * stack in a hopper line, a crafter or a chest nobody has opened yet is still the 0.14 shape,
 * so the second entry is not optional. The predicate path needs none of this: it only reads
 * the {@code cb_item} id, which both shapes carry.
 */
public final class CustomItemChoice {

    private static final MethodHandle PREDICATE_CHOICE = findPredicateChoice();

    private CustomItemChoice() {
    }

    private static MethodHandle findPredicateChoice() {
        try {
            return MethodHandles.publicLookup().findStatic(RecipeChoice.class, "predicateChoice",
                    MethodType.methodType(Class.forName("org.bukkit.inventory.RecipeChoice$PredicateChoice"),
                            Predicate.class, ItemStack.class));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** True when this server's Paper build offers component-drift-proof predicate matching. */
    public static boolean predicateChoiceAvailable() {
        return PREDICATE_CHOICE != null;
    }

    /** One line for the startup log so which matching mode is live is never a guess. */
    public static String describeMode() {
        return predicateChoiceAvailable()
                ? "custom-item recipes match on the cb_item tag (RecipeChoice.predicateChoice)"
                : "custom-item recipes match on the exact item (RecipeChoice.ExactChoice; items made before 0.15 "
                        + "still match) — this Paper build has no predicateChoice, so a renamed or damaged custom "
                        + "item will stop matching its own recipe";
    }

    /**
     * A choice that accepts exactly the given custom item.
     *
     * @param id        the custom-item id that must be stamped on the stack
     * @param example   the built item, used for the recipe-book display (and as the first
     *                  accepted stack when this build has no {@code predicateChoice})
     * @param before015 the same item as CraftBridge 0.14 built it, without the model tag;
     *                  only the exact fallback uses it (see "Items made before 0.15" above)
     */
    public static RecipeChoice forCustomItem(String id, ItemStack example, ItemStack before015) {
        RecipeChoice byId = predicate(stack -> CustomItemRegistry.is(stack, id), example);
        return byId != null ? byId : exact(exactMatchStacks(example, before015));
    }

    /**
     * The general form: match on an arbitrary predicate where Paper supports it, else fall
     * back to exact-matching {@code example}.
     */
    public static RecipeChoice forCustomItemPredicate(Predicate<ItemStack> test, ItemStack example) {
        RecipeChoice byPredicate = predicate(test, example);
        return byPredicate != null ? byPredicate : exact(example);
    }

    /** A {@code predicateChoice}, or null when this build has none (or building it failed). */
    private static RecipeChoice predicate(Predicate<ItemStack> test, ItemStack example) {
        if (PREDICATE_CHOICE == null) {
            return null;
        }
        try {
            return (RecipeChoice) PREDICATE_CHOICE.invoke(test, example.clone());
        } catch (Throwable ignored) {
            // The caller falls back to an exact choice rather than failing the registration.
            return null;
        }
    }

    /**
     * What the exact fallback accepts for a custom item, in order: the current (tagged) item
     * first, so it is the one the recipe book and JEI show first, then the item as 0.14 built
     * it, so items made before 0.15 keep matching. A null {@code before015} is left out.
     *
     * <p>Generic so the policy can be pinned by a test without a server to build stacks.
     */
    static <T> List<T> exactMatchStacks(T current, T before015) {
        List<T> out = new ArrayList<>(2);
        out.add(current);
        if (before015 != null) {
            out.add(before015);
        }
        return out;
    }

    /**
     * An exact-item choice. The {@code ExactChoice} constructors are deprecated-for-removal
     * as of 26.2 in favour of {@code RecipeChoice.exactChoice(...)}, which the pinned dev
     * bundle may not have yet, so the factory is preferred reflectively and the constructor
     * is the fallback.
     */
    public static RecipeChoice exact(ItemStack stack) {
        return exact(List.of(stack));
    }

    /**
     * An exact choice accepting any of {@code stacks} (at least one), each compared at amount
     * 1. The first is what the recipe book displays first. See {@link #exact(ItemStack)} for
     * why the factory is looked up reflectively.
     */
    @SuppressWarnings("removal")
    public static RecipeChoice exact(List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            throw new IllegalArgumentException("an exact choice needs at least one stack");
        }
        ItemStack[] ones = new ItemStack[stacks.size()];
        for (int i = 0; i < ones.length; i++) {
            ItemStack one = stacks.get(i).clone();
            one.setAmount(1);
            ones[i] = one;
        }
        if (EXACT_CHOICE != null) {
            try {
                ItemStack[] others = new ItemStack[ones.length - 1];
                System.arraycopy(ones, 1, others, 0, others.length);
                return (RecipeChoice) EXACT_CHOICE.invoke(ones[0], others);
            } catch (Throwable ignored) {
                // Fall through to the constructor.
            }
        }
        return new RecipeChoice.ExactChoice(ones);
    }

    private static final MethodHandle EXACT_CHOICE = findExactChoice();

    private static MethodHandle findExactChoice() {
        try {
            return MethodHandles.publicLookup().findStatic(RecipeChoice.class, "exactChoice",
                    MethodType.methodType(RecipeChoice.ExactChoice.class, ItemStack.class, ItemStack[].class));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }
}
