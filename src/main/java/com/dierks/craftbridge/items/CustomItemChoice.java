package com.dierks.craftbridge.items;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
                : "custom-item recipes match on the exact item (RecipeChoice.ExactChoice) — this Paper build has no "
                        + "predicateChoice, so a renamed or damaged custom item will stop matching its own recipe";
    }

    /**
     * A choice that accepts exactly the given custom item.
     *
     * @param id      the custom-item id that must be stamped on the stack
     * @param example the built item, used for the recipe-book display (and as the whole match
     *                when this build has no {@code predicateChoice})
     */
    public static RecipeChoice forCustomItem(String id, ItemStack example) {
        return forCustomItemPredicate(stack -> CustomItemRegistry.is(stack, id), example);
    }

    /**
     * The general form: match on an arbitrary predicate where Paper supports it, else fall
     * back to exact-matching {@code example}. Used by {@link #forCustomItem} and by the
     * throwaway Bedrock spike, so the spike exercises the production path.
     */
    public static RecipeChoice forCustomItemPredicate(Predicate<ItemStack> test, ItemStack example) {
        if (PREDICATE_CHOICE != null) {
            try {
                return (RecipeChoice) PREDICATE_CHOICE.invoke(test, example.clone());
            } catch (Throwable ignored) {
                // Fall through to the exact choice below rather than failing the registration.
            }
        }
        return exact(example);
    }

    /**
     * An exact-item choice. The {@code ExactChoice} constructors are deprecated-for-removal
     * as of 26.2 in favour of {@code RecipeChoice.exactChoice(...)}, which the pinned dev
     * bundle may not have yet, so the factory is preferred reflectively and the constructor
     * is the fallback.
     */
    @SuppressWarnings("removal")
    public static RecipeChoice exact(ItemStack stack) {
        ItemStack one = stack.clone();
        one.setAmount(1);
        if (EXACT_CHOICE != null) {
            try {
                return (RecipeChoice) EXACT_CHOICE.invoke(one, new ItemStack[0]);
            } catch (Throwable ignored) {
                // Fall through to the constructor.
            }
        }
        return new RecipeChoice.ExactChoice(one);
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
