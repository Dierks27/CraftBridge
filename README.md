# CraftBridge

A Paper 26.2 plugin for the HomeCraft server that bridges the gap between a plugin
server and modded-client conveniences:

| # | Feature | Status |
|---|---------|--------|
| 3 | **Chest sorting** with a per-player trigger (`/sort`, `/sort settings`) | PR 1 |
| 4 | **Admin-defined custom recipes**, fully GUI-driven (`/recipe`) — fills the peaceful-mode gap | PR 2 |
| 2 | **Linked Workbench** — a crafting table that pulls from nearby chests | PR 3 |
| 1 | **JEI `[+]` recipe transfer** for Fabric/JEI clients on a plugin server | PR 4 |

Every feature has its own master switch under `features:` in `config.yml`, so any one
of them can be shipped or turned off independently. `/craftbridge reload` re-reads the
config and rebuilds every feature.

## Target environment

* **Paper 26.2** (year-based versioning). Built against the `26.2.build.107-stable` dev bundle
  (paperweight-userdev), which provides `paper-api` at the same version plus the
  Mojang-mapped server for the one NMS class.
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

The jar lands in `build/libs/CraftBridge-<version>.jar`. The first build takes a few
minutes while paperweight downloads and decompiles the server; CI caches that. Nothing
is shaded — the plugin uses only the Paper API, Paper internals (one class) and the JDK.
Paper 1.20.5+ runs Mojang-mapped, so the plain jar is the production artifact. CI (`.github/workflows/build.yml`) builds every PR,
attaches the jar to the workflow run, and publishes a GitHub Release on `v*` tags.

Install: copy the jar into the server's `plugins/` folder and restart (or start once to
generate `plugins/CraftBridge/config.yml`).

## Commands and permissions

| Command | Permission | Default |
|---------|-----------|---------|
| `/craftbridge reload` / `/craftbridge version` (alias `/cb`) | `craftbridge.admin` | op |
| `/craftbridge workbench give [player] [amount]` / `list` / `refresh` / `display <scale|x|y|z|yaw|transform> <value>` | `craftbridge.admin` | op |
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
immediately. JEI clients get the new set live through the **JEI recipe sync** below —
no rejoin, no `/jeiproxy handshake`. (JEIServerProxy does not listen for recipe changes;
it unlocks the recipe book on join and answers a legacy `jei:network` handshake, neither
of which carries recipe data. With `features.jei-recipe-sync: false` the console prints
a rejoin reminder after each change instead.)

## Feature 1 — JEI `[+]` recipe transfer

JEI only enables its `[+]` (move items) button when it believes JEI is running on the
server, and then sends a custom payload that a server-side JEI would normally handle.
CraftBridge is that server side. Everything below was read from JEI's source, branch
`26.2` / tag `v26.2.0` (`Common/src/main/java/mezz/jei/common/network/…`,
`Fabric/src/main/java/mezz/jei/fabric/network/…`).

**How the client decides JEI is on the server.** Fabric `ConnectionToServer.isJeiOnServer()`
is `ClientPlayNetworking.canSend(PacketDeletePlayerItem.TYPE)`, i.e. "did the server
announce `jei:delete_player_item` in its `minecraft:register` list". Paper announces every
channel a plugin registered as *incoming*, so registering that channel is the whole
handshake — there is no version exchange. Each packet is sent only if its own channel was
announced too (`sendPacketToServer` checks `canSend(packet.type())`).

**Channels.** One payload id per packet type (no shared channel, no packet-id prefix):

| Channel | Direction | Handled |
|---------|-----------|---------|
| `jei:recipe_transfer_with_result` | client → server | yes (current) |
| `jei:recipe_transfer_counted_with_result` | client → server | yes (current, per-op counts) |
| `jei:recipe_transfer` / `jei:recipe_transfer_counted` | client → server | yes (legacy, no reply) |
| `jei:recipe_transfer_result` | server → client | reply: `VarInt transferId, bool success` |
| `jei:delete_player_item` | client → server | registered only as the presence marker; payload ignored |
| `jei:give_item_stack`, `jei:set_hotbar_item_stack`, `jei:request_cheat_permission`, `jei:cheat_permission` | cheat mode | **not registered** — JEI never sends what the server didn't announce, so cheat mode stays inert |

**Packet layout** (all four transfer channels; `counted` adds the per-op count,
`with_result` appends the id):

```
VarInt opCount, then per op: VarInt inventorySlotId, VarInt craftingSlotId [, VarInt count]
VarInt n, n × VarInt craftingSlotId        the recipe's target grid slots
VarInt n, n × VarInt inventorySlotId       slots the server may draw from
bool maxTransfer                           shift-click [+] = fill as many sets as possible
bool requireCompleteSets
[VarInt transferId]
```

Slot ids are vanilla container-menu indexes = Bukkit `InventoryView` raw slots: crafting
table 0 result / 1-9 grid / 10-45 inventory; player 2x2 grid 0 result / 1-4 grid /
9-44 inventory. **No ItemStacks are on the wire** — the server reads them from the
slots — so decoding needs no NMS codec (the handoff's `RegistryFriendlyByteBuf` plan was
unnecessary for 26.2; `paperweight-userdev` is only pulled in later for recipe sync).

**Server logic.** `jei.TransferEngine` is a line-by-line port of
`BasicRecipeTransferHandlerServer.setItemsWithResult`: validate slots, resolve each op
against the source slot, take one set (or, on shift-click, as many sets as fit — with
`requireCompleteSets` rolling back a partial set), clear the grid, place the sets
(respecting per-slot stack limits), stow cleared items and remainders back into the
inventory, overflow to the player / floor. It is Bukkit-free and covered by unit tests;
the feature wraps it over `player.getOpenInventory()` (must be `WORKBENCH` or
`CRAFTING`, anything else is rejected with a `false` result), writes changed slots back
through the view, calls `updateInventory()`, and replies. Spam-clicking cannot dupe: each
packet is applied synchronously on the main thread against the live slots.

**Logging.** Decode failures go to DEBUG with the byte count (set `debug: true` to see
them). The first packet on each channel is logged once at INFO. The packets carry no
protocol version, so there is nothing to compare — the console line at enable states the
JEI version this was built against (`JEI 26.2.0`).

**Missing items** stay client-side: JEI only sends a transfer when every ingredient is
visible in the player's inventory (or grid); otherwise it shows its red highlight and
never contacts the server.

### JEI recipe sync (`features.jei-recipe-sync`)

Found while reading JEI 26.2: on a server that is not Fabric, JEI loads its recipe list
from the recipe JSONs **bundled with the client** (`VanillaClientRecipeLoader`, "only a
fallback for connections where the server does not send recipe data to JEI"). Server
recipes — CraftBridge's, HomeCraftMgmt's, anyone's — never show up, and JEIServerProxy
does not change that (it only unlocks the recipe book and speaks a `jei:network`
handshake JEI 26.2 no longer has). What JEI does consume is Fabric API's recipe
synchronisation (`fabric-recipe-api-v1`), so CraftBridge speaks that protocol:

* Channel `fabric:recipe_sync` (server → client), payload:
  `VarInt entryCount, then per entry: Identifier serializerId, VarInt n, n × (ResourceKey recipeId, recipe via serializerId's stream codec)`.
  JEI syncs every `minecraft:` serializer; every Bukkit-registered recipe uses one of
  those, so the whole server set is encodable. Exact-item ingredients are sent as their
  item types (that is how Paper encodes them for any client).
* Sent on join once the client has announced the channel (Fabric clients with the recipe
  API always do; vanilla/Bedrock clients never do, so nothing is sent to them), and
  re-sent to everyone whenever CraftBridge's custom recipes change.
* After the payload the server re-sends vanilla's `ClientboundUpdateRecipesPacket` (and
  the recipe book) to that player: JEI (re)starts on that packet, and by the time a Paper
  plugin can act the join-time one has already gone out. Expect JEI to start twice on
  join — once with the fallback set, once with the synced set.
* Fabric's configuration-phase "supported serializers" request cannot be answered from
  the Bukkit API (there is no configuration-phase channel API), and is not needed: the
  client only uses it to *limit* what the server sends.
* The payload must fit vanilla's 1 MiB custom-payload limit — Fabric's packet splitter
  only exists on Fabric servers. `jei.recipe-sync.types` is the priority list; types are
  dropped from the end until it fits (a full vanilla set is roughly 100–300 KB).

This is the only feature that touches server internals (`jei.nms.PaperRecipeSyncEncoder`,
compiled with paperweight-userdev against the pinned dev bundle). It is loaded
reflectively: if a future 26.x build renames something, the feature logs one WARN and
disables itself, and the rest of the plugin is unaffected. Names it depends on:
`RecipeManager.recipes.values()`, `RecipeHolder#id()/value()`, `Recipe#getSerializer()/getType()`,
`RecipeSerializer#streamCodec()`, `RegistryFriendlyByteBuf` + `writeIdentifier`/`writeResourceKey`,
`ClientboundUpdateRecipesPacket(getSynchronizedItemProperties(), getSynchronizedStonecutterRecipes())`,
`ServerRecipeBook#sendInitialRecipeBook`.

## Feature 2 — Linked Workbench

A crafting table that can pull ingredients from the chests around it.

**The block.** Physically a normal `CRAFTING_TABLE`, so vanilla interaction, breaking,
Towny/WorldGuard checks and the vanilla crafting menu (which JEI knows how to fill) all
keep working. It is placed from a special item — a player head wearing the configured
texture, named *Linked Workbench*, PDC-tagged `craftbridge:linked_workbench` — and
tracked in `plugins/CraftBridge/linked-workbenches.yml` (world + xyz + display UUID +
owner + yaw). Breaking it drops the head item back; burning or exploding it does too;
pistons cannot move it. Craftable (`craftbridge:linked_workbench`, shape and
ingredients in `linked-workbench.recipe`; default: crafting table in the middle, 4
chests in the corners, 3 copper ingots, 1 ender pearl at the bottom).
Admins: `/craftbridge workbench give`.

**The look.** On placement an `ItemDisplay` holding the head is spawned at the block,
scaled ≈ 2.02 so the half-block head model covers the table with a hair of overlap,
rotated to face the player who placed it (yaw snapped to 90°), persistent, tagged
`craftbridge:linked_display` = the table key, with its UUID stored on the record. It
has no hitbox, so the real table underneath stays the interaction target.
`transform`, `scale`, the offsets and `yaw-offset` live in `config.yml` and can be
dialled in live with `/craftbridge workbench display <scale|x|y|z|yaw|transform>
<value>` (saves config + respawns every loaded display) — the defaults (`NONE`, 2.02,
entity at block centre-top) are derived from the item renderer's `-0.5` model
translation and the skull renderer's geometry, not from an in-game test, so expect to
tune them once. If `NONE` looks wrong, `HEAD` is the other candidate; `FIXED` is
half-size and rotated 180°, so it needs scale ≈ 4.04 and `yaw-offset: 180`.

A startup sweep (and a sweep whenever a chunk's entities load) removes any tagged
display whose table is gone (WorldEdit / Towny regen) and respawns missing displays for
tables that still exist. A record whose block is no longer a crafting table is
forgotten — the head item is not refunded in that case. Bedrock/Geyser players may see
the display as a generic head or not at all; the block still works for them because it
is a real crafting table. *(Observed Geyser behaviour: to be filled in after the first
live test.)*

**Nearby storage** = every chest, trapped chest, double chest (counted once), barrel and
placed shulker box within `linked-workbench.radius` (8) blocks that the player may use:
vanilla lock, Towny plot permission (reflection), then a synthetic `PlayerInteractEvent`
any protection plugin can cancel (`respect-protection`). Hoppers and furnaces are never
read.

**Using it.**
* **Right-click** → opens a real vanilla crafting menu attached to the table
  (`MenuType.CRAFTING`, `checkReachable`), tracked as a *linked session* for the player.
  Vanilla's reach check closes it when the player is more than 8 blocks away or the table
  is gone; death, teleport, quit and Escape end it too.
* **Sneak + right-click** → the storage GUI: everything in nearby storage aggregated by
  item with counts, 45 per page, ordered like the sorter. Click takes one stack into your
  inventory, shift-click takes as many as fit. This is the manual / Bedrock path.
* **JEI `[+]`** inside a linked session sources from the player's inventory first (that
  is JEI's own server logic, PR 4) and then, on **shift-`[+]`**, tops the grid up with
  more sets from nearby storage — items are taken out of the containers at transfer
  time, the same number per slot when the recipe wants complete sets, limited by the
  scarcest ingredient and the stack limit. The session records, per grid slot, which
  container fed it and how many; on close those items go back to that container if it
  has room, and anything else left in the grid is returned by vanilla to the player (or
  dropped at the player if full). Ingredients the player does not carry at all cannot
  be sourced this way — JEI greys the button out client-side before the server hears
  anything — so fetch those with the storage GUI first. Container items that a later
  `[+]` moves out of the grid land in the player's inventory (JEI's stow), not back in
  the chest.

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
