# CraftBridge

A Paper 26.2 plugin for the HomeCraft server that bridges the gap between a plugin
server and modded-client conveniences:

| # | Feature | Status |
|---|---------|--------|
| 3 | **Chest sorting** with a per-player trigger (`/sort`, `/sort settings`) | PR 1 |
| 4 | **Admin-defined custom recipes**, fully GUI-driven (`/recipe`) — fills the peaceful-mode gap | PR 2 |
| 2 | **Linked Workbench** — a crafting table that pulls from nearby chests | planned |
| 1 | **JEI `[+]` recipe transfer** for Fabric/JEI clients on a plugin server | planned |

Every feature has its own master switch under `features:` in `config.yml`, so any one
of them can be shipped or turned off independently. `/craftbridge reload` re-reads the
config and rebuilds every feature.

## Target environment

* **Paper 26.2** (year-based versioning). Built against `io.papermc.paper:paper-api:26.2.build.107-stable`.
* **Java 25** (Paper 26.x requires it). Gradle 9.7 runs on JDK 25 directly, so CI and a
  dev machine need exactly one JDK.
* Mixed Java + Bedrock (Geyser/Floodgate). Every GUI is a plain chest layout so it renders
  through Geyser; JEI-specific features are Java-only by nature.
* Optional integrations (never required): Towny, WorldGuard, LuckPerms, Vault, ProtocolLib,
  JEIServerProxy (CraftBridge loads *before* it so custom recipes are in its snapshot).

## Build

```
./gradlew build
```

The jar lands in `build/libs/CraftBridge-<version>.jar`. Nothing is shaded — the plugin
uses only the Paper API and the JDK. CI (`.github/workflows/build.yml`) builds every PR,
attaches the jar to the workflow run, and publishes a GitHub Release on `v*` tags.

Install: copy the jar into the server's `plugins/` folder and restart (or start once to
generate `plugins/CraftBridge/config.yml`).

## Commands and permissions

| Command | Permission | Default |
|---------|-----------|---------|
| `/craftbridge reload` / `/craftbridge version` (alias `/cb`) | `craftbridge.admin` | op |
| `/sort` — sort the open container | `craftbridge.sort` | everyone |
| `/sort settings` — pick your trigger and toggles | `craftbridge.sort` | everyone |
| `/sort debug` — print the raw click your client sends when clicking outside a GUI | `craftbridge.sort` | everyone |
| `/recipe` (alias `/recipes`) — the recipe menu; hidden console fallbacks: `/recipe list`, `/recipe reload`, `/recipe remove <id>`, `/recipe import starter` | `craftbridge.recipes.admin` | op |

`craftbridge.sort.others` is reserved for a future "sort any container I'm looking at"
admin tool and does nothing yet.

## Feature 3 — chest sorting

**What gets sorted:** chests, double chests, trapped chests, barrels and placed shulker
boxes. Optionally the player's own main inventory rows (never the hotbar, armour or
offhand) — a per-player toggle, gated by `sorting.player-inventory.allowed` and off by
default.

**How it sorts:** every stack that is the same item (same material *and* meta) is merged
into full stacks, then stacks are ordered by category → material name → display name.
Categories come from `sorting.categories` in `config.yml` (tools, weapons, armor, food,
blocks, items by default; patterns are regexes on the `Material` name, plus the special
tokens `@edible`, `@block`, `@default`). Nothing ever leaves the container and the cursor
item is never touched. Locked containers are skipped, and for the sneak-punch trigger the
Towny plot / WorldGuard region is checked first (`sorting.respect-protection`).

**Triggers** (each player chooses in `/sort settings`; stored per UUID in
`plugins/CraftBridge/sort-players.yml`; default from `sorting.default-trigger`):

| Trigger | How |
|---------|-----|
| `DOUBLE_CLICK_OUTSIDE` (default) | click the dark area outside the GUI twice within `sorting.double-click-ms` (400 ms) with an empty cursor |
| `SHIFT_CLICK_OUTSIDE` | shift-click outside the GUI with an empty cursor — *see the caveat below* |
| `SNEAK_PUNCH_BLOCK` | sneak and left-click the container block (block damage is cancelled) |
| `COMMAND_ONLY` | only `/sort` |

### Reality check: what the client actually sends

* **Middle-click cannot be a trigger.** In survival the client does not send a middle-click
  inventory packet at all, so a server plugin never sees it. If middle-click sorts chests
  for you today, that is a client-side mod doing it. The settings GUI says so in its help
  book so players don't file bugs.
* **Shift-click outside is fuzzy.** Historically the vanilla client sends a click on the
  area outside a GUI as a `THROW`-mode click with slot `-999` and *without* the shift
  state, and while a screen is open it does not report sneaking either. CraftBridge
  therefore treats `SHIFT_CLICK_OUTSIDE` as "SHIFT_LEFT/SHIFT_RIGHT outside, **or** plain
  LEFT/RIGHT outside while `isSneaking()`", whichever the 26.2 client turns out to send.
  Run `/sort debug`, open a chest and shift-click outside it: the chat line shows the
  raw `ClickType`, `InventoryAction` and sneaking flag. If neither variant ever appears,
  leave the default on `DOUBLE_CLICK_OUTSIDE` — that one only needs two `-999` clicks,
  which every client sends.
* Bedrock players (Geyser) have no "outside the GUI" area; `SNEAK_PUNCH_BLOCK` or `/sort`
  are the reliable choices there.

## Feature 4 — admin-defined custom recipes

The server runs on peaceful, so mob-only drops are unobtainable. `/recipe` lets an admin
add shaped/shapeless crafting recipes entirely in-game, no datapacks, no ids typed.

**Main menu:** New Recipe · Browse Recipes · Import Starter Pack · Reload.

**New recipe:** a 54-slot editor with a real 3x3 grid and a result slot. Put real items
in (they come back on Save, Cancel or close). Under each filled grid slot is a match
toggle: *any item of this material* (default) or *this exact item* (name, enchantments,
components). Buttons: Shaped/Shapeless, Save, Cancel. The id is generated from the
result (`ender_pearl`, then `ender_pearl_2`…). Save validates that the result and grid
are non-empty, then asks the server whether that layout already crafts something
(`Bukkit.getCraftingRecipe`); if it does, a warning line appears and a second Save
click adds the recipe anyway (the older recipe may still win at the table).

**Browse:** paginated list of result items with the shape (`A G A` rows) and legend in
the lore. Left-click edits in place (the grid is seeded with display copies that are
discarded afterwards, so editing never duplicates items), right-click enables/disables
without deleting, shift-right-click deletes after an in-GUI confirm.

**Storage:** `plugins/CraftBridge/recipes.yml`, one entry per recipe, registered on
enable as real server recipes under `craftbridge:<id>`. GUI-written results and exact
ingredients store the full item as base64 (Paper's version-upgradeable
`serializeAsBytes`) plus a readable `material` mirror; hand-written entries need only
`material` (+ `amount`), and may use `{tag: 'minecraft:wool'}` for any item in a tag:

```yaml
recipes:
  ender_pearl:
    type: shaped            # or shapeless
    enabled: true
    result: {material: ENDER_PEARL, amount: 2}
    shape: ['AGA', 'GDG', 'AGA']
    ingredients:
      A: {material: AMETHYST_SHARD}
      G: {material: GLOWSTONE_DUST}
      D: {material: DIAMOND}
  string:
    type: shapeless
    result: {material: STRING, amount: 4}
    ingredients: [{tag: 'minecraft:wool'}, {tag: 'minecraft:wool'}, {material: BAMBOO}, {material: BAMBOO}]
```

**Starter pack:** `recipes-peaceful-starter.yml` ships in the jar (and is copied to the
plugin folder for tuning). *Import Starter Pack* adds every id that does not exist yet —
string, gunpowder, bone, rotten flesh, spider eye, slime ball, ender pearl, blaze rod,
ghast tear, phantom membrane, shulker shell, wither skeleton skull, nether star, totem of
undying, trident. They are "not free" rather than balanced; tune them in Browse or the
file.

**Clients:** every add/remove/toggle/reload calls `Bukkit.updateRecipes()` and unlocks
the recipes in online players' recipe books (and on join). Vanilla clients are current
immediately. JEI clients: JEI 26.2 on a non-Fabric server only knows the recipes
bundled with the *client* (it falls back to the vanilla recipe JSONs) — so custom
recipes will not show in JEI until CraftBridge's own recipe sync lands in a later PR.
JEIServerProxy does not listen for recipe changes; it unlocks the recipe book on join
and answers a legacy `jei:network` handshake, neither of which carries recipe data.
Until the sync PR, the console prints a reminder after each change.

## Configuration

See the comments in `src/main/resources/config.yml`. Everything reloads with
`/craftbridge reload`.

## Development notes

* Package layout: `com.dierks.craftbridge.<feature>`; each feature implements
  `CraftBridgePlugin.Feature` and is only constructed when its switch is on.
* `gui.Menu` / `gui.MenuListener` is the shared click-driven chest GUI base (with
  optional editable slots for menus that take real items).
* `integration.ContainerAccess` is the one place that answers "may this player use this
  container?": vanilla lock → Towny (reflection, optional) → a synthetic
  `PlayerInteractEvent` any protection plugin can cancel.
* Pure logic (`sort.SortAlgorithm`, `sort.SortCategoryRules`, `recipes.RecipeShape`) has
  no Bukkit dependency and is covered by JUnit tests; `./gradlew test` runs them.
* `recipes.RecipeRegistry#onChange` is the hook a recipe-sync feature uses to learn that
  the registered set changed.
