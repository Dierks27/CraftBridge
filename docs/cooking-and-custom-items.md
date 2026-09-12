# Cooking recipes and custom items

Two additions to Feature 4 (`/recipe`): the four cooking recipe types, and a custom-item
registry that recipes reference by id.

Driving use case: rotten flesh → (furnace) → **Burned Zombie Flesh** → (+ water bucket,
crafting) → leather.

---

## Cooking recipes

`RecipeKind` covers `shaped`, `shapeless`, `furnace`, `smoker`, `blast_furnace` and
`campfire`. The type selector in the recipe editor cycles all six (right-click goes back).

**Each cooking kind is its own recipe.** There is no "works in any cooker" entry: vanilla
datapacks model smelting, smoking, blasting and campfire cooking as four separate recipes,
and a combined entry would hide which one the admin meant. Registering the same input for
several cookers means creating several recipes.

Selecting a cooking kind collapses the 3×3 grid to a single input slot. The other eight
cells are locked shut, and anything already in them is handed back (real items) or dropped
(picked ghosts) before the switch completes — the same contract the editor honours on
close, just for part of the grid.

### Cook time and experience

| Kind | Default cook time | Vanilla equivalent |
|---|---|---|
| `furnace` | 200 ticks (10 s) | ordinary smelting |
| `smoker` | 100 ticks (5 s) | food, twice as fast |
| `blast_furnace` | 100 ticks (5 s) | ores, twice as fast |
| `campfire` | 600 ticks (30 s) | campfire cooking |

Experience defaults to `0.1`. Both are editable in the editor: cook time in ±20 / ±100 tick
steps (the lore shows seconds alongside ticks), experience in ±0.1 / ±0.5 steps. Cook time
is clamped to at least 1 tick — a zero would leave the cooker running forever.

### Storage

```yaml
recipes:
  burned_zombie_flesh:
    type: furnace
    enabled: true
    cook-time: 200
    experience: 0.1
    result: {custom: burned_zombie_flesh, amount: 1}
    ingredients: [{material: ROTTEN_FLESH}]
```

A cooking recipe takes exactly one ingredient — the first entry of `ingredients`. The
singular `ingredient:` spelling is accepted for hand-edited files.

An unrecognised `type` still falls back to `shaped`, exactly as before this change, so a
`recipes.yml` written by an older build loads untouched.

### JEI

Cooking recipes needed **no wire-format change**. `PaperRecipeSyncEncoder` groups by recipe
serializer and encodes each recipe with `serializer.streamCodec()`, so vanilla's cooking
codec already carries cook time and experience, and all four cooking types are already in
the default `jei.recipe-sync.types`. JEI derives the category client-side from the decoded
recipe, so they land in `minecraft:smelting` / `smoking` / `blasting` / `campfire_cooking`
on their own.

There is no category field on the wire at all, which cuts both ways: CraftBridge cannot
place a recipe in the wrong category even deliberately, and it cannot override where one
goes. `/craftbridge jei dump <key>` now reports a cooking recipe's input choice, cook time
and experience.

JEI's `[+]` transfer applies to crafting recipes only — its transfer handler is registered
for `RecipeTypes.CRAFTING`. There is nothing to transfer into a furnace beyond a single
slot, and JEI does not offer the button there.

### The vanilla-shadow warning

Furnaces, smokers and blast furnaces do **not** resolve their recipe through the lookup
CraftBukkit patches to give plugin recipes priority (SPIGOT-4638). They go through
`RecipeManager.CachedCheck`, which returns the block entity's remembered last recipe as
soon as it still matches. Vanilla cooking ingredients match on item type, so:

> A furnace that last smelted the plain base item will keep using the **vanilla** recipe
> when a stamped custom item is put in — vanilla output, vanilla experience, vanilla cook
> time, and your recipe's choice never consulted.

The cache is per block entity and in memory only, so it clears on chunk unload and restart.
That makes the failure intermittent and order-dependent, which is exactly the shape a clean
test server never reproduces.

Campfires have a matching split: `placeFood` uses the patched lookup (so your recipe does
gate what may be placed) but `cookTick` resolves the output through the cache.

Nothing in the Bukkit API fixes this, so the editor warns instead. Saving a cooking recipe
whose input material already has a vanilla recipe for that cooker asks for a second click,
the same way an overlapping crafting layout does.

**This does not affect the driving use case.** `ROTTEN_FLESH` has no vanilla furnace recipe,
so there is nothing to shadow it.

---

## Custom items

A definition is `{ id, base material, display name, lore, optional head texture }`, stored
in `custom-items.yml` beside `recipes.yml` and in the same shape.

```yaml
items:
  burned_zombie_flesh:
    material: DRIED_KELP
    name: '<dark_gray>Burned Zombie Flesh'
    lore:
      - '<dark_gray><italic>Charred past recognition.'
```

Names and lore are MiniMessage, like every other admin-facing string in the plugin.

### Identity is the tag, never the name

The id is stamped into the item as `craftbridge:cb_item` (a PDC string). Recipes match that
tag.

Matching on the display name would be forgeable: any player with an anvil can rename a plain
dried kelp to "Burned Zombie Flesh" and craft leather with it. A PDC value cannot be produced
without `/give` with raw NBT, which is operator-level.

Custom items do not stack with their plain vanilla counterparts. That is intended — it is
what keeps the two tellable apart in a chest.

### Recipes reference the id, not a copy

`{custom: burned_zombie_flesh}` as an ingredient, and `result: {custom: ...}` as a result.
The id resolves to a freshly built stack when the recipe is registered, so editing a
definition — a new lore line, a corrected colour — updates every recipe that uses it instead
of leaving them matching a stale copy.

Deleting a definition that recipes still reference leaves them in place and logs
`references custom item '<id>', which is not defined`. They will not register until the item
comes back. Silently deleting them would be worse: the recipe would quietly start matching
nothing.

### Matching: `predicateChoice` preferred, `ExactChoice` as fallback

`ExactChoice` compares the **entire** component patch (`ItemStack.isSameItemSameComponents`),
which is stricter than "carries our stamp". If a stamped item ever gains or loses any
component in normal use — an anvil rename, a repair-cost bump, damage, an enchantment — it
silently stops satisfying its own recipe, with no error anywhere. That is the most likely way
a custom-item recipe breaks in the field, and it breaks for Java and Bedrock players alike.

Paper grew `RecipeChoice.predicateChoice(Predicate, ItemStack)` during 26.2 for exactly this.
It keeps `isExact()` true — so the recipe book still shows the example stack and Geyser still
sees an `ItemStackSlotDisplay` — while running the predicate for the actual match. Matching on
the PDC id alone survives every kind of component drift.

It is resolved **reflectively**: the pinned dev bundle (`26.2.build.107-stable`) may predate
the 26.2 static factories, and compiling against the method directly would tie the plugin to a
newer build than it pins. When absent, matching falls back to `ExactChoice` and startup logs
which mode is live.

> **On build 107 the fallback is what runs.** Tested: `predicateChoice` is *not* present on
> that build, so custom items are matched with `ExactChoice`. The reflection is what keeps the
> plugin loading at all there — a direct call would fail to link. Bumping the
> `paperweightDevBundle` pin to a build that has `predicateChoice` is the way to get the
> drift-proof matching; until then, treat a custom item that has been renamed, damaged or
> repaired as no longer matching its own recipe.

### Player heads

A head-based custom item cannot be placed as a block or worn in the helmet slot. Both lose the
item's identity, and neither is what an admin means by "custom item".

HomeCraftManagement has head handling of its own (`MiniHeadListener`), but it is a separate
plugin and a separate jar, and it does the opposite — it *records* placed Minis rather than
preventing placement. There was nothing to reuse. The two never see each other's items: HCM
keys off `homecraftmanagement:mini_id`, CraftBridge off `craftbridge:cb_item`.

### Text input

Display name and lore are typed in chat, not in an anvil field. The plugin is deliberately
chest-GUI-only so Geyser/Bedrock players get identical screens, and an anvil rename field is
one of the places Bedrock behaves differently. Every Bedrock client can type in chat. The
message is always cancelled, so a half-typed item name never reaches public chat.

---

## `[+]` transfer and stamped items

Tracing the transfer path turned up a live item-loss bug, fixed here.

A vanilla or datapack recipe hands back a material-only `RecipeChoice`, which accepts any item
sharing its base material — including a CraftBridge custom item. Pressing `[+]` on
"8 dried kelp → dried kelp block" would pull Burned Zombie Flesh out of a nearby chest and
consume it. The item is gone and nothing says why.

A stamped item is now only offered to a slot whose choice actually *discriminates*: one that
rejects the plain, unstamped version of the same material. An exact or predicate choice built
for that custom item passes; a plain material choice does not. Unstamped items are unaffected,
so ordinary transfers behave exactly as before.

Related, also fixed: `LinkedSession.Origin` recorded *where* borrowed items came from but not
*what* was borrowed, so the repay paths pushed whatever now sat in the slot into the lender's
chest. Swapping a grid ingredient between the pull and the close gave one of the player's own
items away.

---

## Bedrock / Geyser — tested, not assumed

Verified in-game on a Bedrock client through Geyser against Paper 26.2 (build 107), using a
throwaway `/craftbridge spike` command that registered two PDC-discriminated recipes (one
crafting, one furnace) and handed out stamped tokens alongside deliberately unstamped
look-alikes with an identical display name. The command has since been removed; it is in the
history of the PR that added this feature if it is ever needed again.

**The security property holds. A forged item never produces output, on either path.**

| What was tried | Result |
|---|---|
| Craft with a stamped item | Works — the real output |
| Craft with an unstamped look-alike | A result is *displayed*, but taking it yields nothing |
| Smelt a stamped item | Works — the real output |
| Smelt an unstamped look-alike | Nothing happens; it just sits in the input slot |
| Bedrock recipe book | Lists the recipe as craftable from unstamped items |

**Cooking is clean.** `AbstractFurnaceInventoryTranslator` contains no recipe logic; it is a
slot mapper. Placing the input is an ordinary transfer replayed as a Java click, and
`AbstractFurnaceBlockEntity.serverTick` does the real matching against the genuine
PDC-carrying stack. Confirmed in play: no phantom output, no ghost recipes, no flicker — an
unstamped item simply never starts cooking. **Prefer cooking for custom-item steps wherever
the design allows it.**

**Crafting works, but the Bedrock client lies about it.** Bedrock's item-descriptor union
(`DEFAULT`, `MOLANG`, `ITEM_TAG`, `DEFERRED`, `COMPLEX_ALIAS`) has no member that can carry
NBT, and `RecipeUtil.translateToInput` reduces every ingredient to
`DefaultDescriptor(id, aux)`. So the client is told "plain rotten flesh → diamond" and:

* it renders a **phantom output** for unstamped items, which cannot be taken; and
* it **inflates the craftable count** — a player holding 4 real and 8 forged items is told
  they can make 12, because the client counts every item of the base material.

Neither is fixable from the plugin side; the information the client would need is not
expressible in the protocol. Both are cosmetic — no item is created, and (contrary to what a
reading of `ClickPlan.simulateAction` suggests) clicking the phantom did **not** consume the
player's ingredients in testing.

The practical consequence is a support-ticket risk, not a correctness one: a Bedrock player
who renames an item and sees a craftable-looking result will believe the recipe is broken.
Choosing a base material that is rarely a crafting ingredient keeps the collision rare.

---

## Building the driving use case through the GUI

Nothing below is hardcoded; this is the click path.

### 1. The custom item

`/recipe` → **Custom items** → **New custom item**

- **Base item** → pick `Dried Kelp` from the picker.
- **Display name** → click, then type in chat: `<dark_gray>Burned Zombie Flesh`
- **Lore** → click, then type: `<dark_gray><italic>Charred past recognition.`
- **Save** → the id is derived from the name: `burned_zombie_flesh`.

### 2. The furnace recipe

`/recipe` → **New recipe**

- Click **Type** until it reads `Furnace`. The grid collapses to one input slot.
- Put `ROTTEN_FLESH` in the input (or click the empty slot to pick it).
- Click the empty result slot → **Custom items** filter → `Burned Zombie Flesh`.
- Cook time and XP default to 200 ticks / 0.1 — adjust if you want.
- **Save**. No shadow warning: rotten flesh has no vanilla furnace recipe.

### 3. The leather recipe

`/recipe` → **New recipe**

- Type stays `Shapeless`.
- Slot 1 → pick `Burned Zombie Flesh` from the **Custom items** filter. Its match indicator
  reads *Match: custom item*, with no "any material" alternative — matching is by tag.
- Slot 2 → `WATER_BUCKET`.
- Result → `LEATHER`.
- **Save**.

`WATER_BUCKET` has a vanilla craft remainder, so the empty bucket returns on its own. No
handling was added for it; verify it does before adding any.

### What to verify

- Recipes survive a restart (they are in `recipes.yml` / `custom-items.yml`).
- Plain dried kelp does **not** satisfy the leather recipe.
- Dried kelp anvil-renamed to "Burned Zombie Flesh" does **not** satisfy it either.
- All three appear in JEI, the furnace one under `minecraft:smelting`.
- `[+]` fills the leather recipe from a chest holding real Burned Zombie Flesh, and does
  **not** fill it from one holding plain dried kelp.
- A Bedrock client can do the full chain.
