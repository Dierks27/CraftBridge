# CraftBridge

A Paper 26.2 plugin for the HomeCraft server that bridges the gap between a plugin
server and modded-client conveniences:

| # | Feature | Status |
|---|---------|--------|
| 3 | **Chest sorting** with a per-player trigger (`/sort`, `/sort settings`) | PR 1 |
| 4 | **Admin-defined custom recipes**, fully GUI-driven (`/recipe`) — fills the peaceful-mode gap | PR 2 |
| 2 | **Linked Workbench** — a crafting table that pulls from nearby chests | PR 3 |
| 1 | **JEI `[+]` recipe transfer** for Fabric/JEI clients on a plugin server | PR 4 |
| 2b | **Combo Chest** — one block that browses, pulls from and deposits into every chest in range | PR 9 |
| 1b | **Client link** — the optional [CraftBridge-Client](https://github.com/Dierks27/CraftBridge-Client) mod sees every item in range, not the 36 a server can fake | PR 24 |

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
| `/craftbridge page next\|prev` — turn the nearby-storage page at a Linked Workbench | none | everyone |
| `/craftbridge give <player> workbench|combochest [amount]` | `craftbridge.admin` | op |
| `/craftbridge workbench|combochest list` / `refresh` / `display <scale|x|y|z|yaw|transform> <value>` | `craftbridge.admin` | op |
| `/craftbridge jei` / `/craftbridge jei resync` — recipe-sync state, or re-encode and re-send it now | `craftbridge.admin` | op |
| `/sort` — sort the open container | `craftbridge.sort` | everyone |
| `/sort settings` — pick your trigger and toggles | `craftbridge.sort` | everyone |
| `/sort debug` — print the raw click your client sends when clicking outside a GUI | `craftbridge.sort` | everyone |
| `/recipe` (alias `/recipes`) — the recipe menu; hidden console fallbacks: `/recipe list`, `/recipe reload`, `/recipe remove <id>`, `/recipe import starter` | `craftbridge.recipes.admin` | op |

Every node is declared in `plugin.yml` with a description and a default, so LuckPerms
suggests them. `craftbridge.admin` (op) covers everything that hands out items or changes
server state; `craftbridge.recipes.admin` (op) gates `/recipe` **and every screen it opens**
— the check is re-run on each click, so revoking it mid-session closes the GUI immediately
rather than at the next login. `craftbridge.sort` (everyone) covers `/sort`, and
`craftbridge.sort.others` is reserved for a future "sort any container I'm looking at" admin
tool and does nothing yet.

`craftbridge.page` defaults to **everyone**, deliberately: `/craftbridge page` is the
fallback for the ◀ / ▶ buttons at a Linked Workbench, it changes nothing but the player's own
view of their own inventory, and making it op would break paging for ordinary players. It is
a declared node, so an admin who disagrees can restrict it.

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

**New recipe:** a 54-slot editor with a real 3x3 grid and a result slot.

* **The regions are framed** so they stand out from the background: a **cyan** frame down
  both sides of the crafting grid, an **orange** frame around the result with a labelled
  item-frame marker above it, a muted frame around the match-mode toggles with its own
  labelled marker, and a neutral dark background everywhere else. Colour is never the only
  cue — the markers are named items, and every frame pane names its region on hover.
* **The grid and result slots start genuinely empty.** No placeholder is ever placed in a
  slot the admin is meant to fill: `Menu#fill` skips editable slots, and
  `RecipeEditorLayout` keeps the editable and decorated slot sets disjoint (unit tested).
  Everything decorative sits in a slot with no handler, and `MenuListener` cancels every
  click, shift-click, drag, number-key swap and drop on those, so nothing can be taken out
  of the GUI. (Before this, the grid was pre-filled with glass panes that clicking handed
  to the admin as real items — a confusing infinite glass source.)
* **Two ways to fill a slot, and only one of them is a real item.** Put a real item in as
  before (fastest when you have it), or **click an empty slot with an empty hand** to open a
  paginated item picker: every item
  the server knows plus CraftBridge's own custom items (its blocks and every custom
  recipe's result), with the same All / Blocks / Tools & armor / Food / Misc quick filters
  used elsewhere and a Custom items filter. Picking sets the slot without consuming or
  requiring anything, so recipes for items that are unobtainable on this server can be
  defined without switching to creative. Chest GUIs only, no anvil text input, so it
  behaves the same on Geyser/Bedrock.
* **Picked ingredients are ghosts.** They are menu state, not inventory contents: while one
  is in a slot that slot is non-editable, so every click, shift-click, drag, number-key
  swap and drop on it is cancelled. Click one to replace it, right-click to clear it; on
  close it simply stops existing. Items you physically placed are the opposite — they stay
  real, behave like vanilla, and come back to you on Save, Cancel or close, exactly once.
  (In v0.3 a picked item was a real removable stack, which was an unlimited source of
  whatever the picker could reach.) The same rule covers the result slot and the copies
  used to seed an existing recipe for editing.
* **Result count:** +/- buttons beside the result slot (click ±1, right-click ±8, clamped
  to the item's stack size). If the result is a stack you physically put in, it is handed
  straight back to your inventory and the editor keeps a display copy, so changing the
  count can never mint items.
* Under each filled grid slot is a match toggle: *any item of this material* (default) or
  *this exact item* (name, enchantments, components). Buttons: Shaped/Shapeless, Save,
  Cancel. The id is generated from the result (`ender_pearl`, then `ender_pearl_2`…). Save
  validates that the result and grid are non-empty, then asks the server whether that
  layout already crafts something (`Bukkit.getCraftingRecipe`); if it does, a warning line
  appears and a second Save click adds the recipe anyway (the older recipe may still win at
  the table).
* Items you put in come back on Save, Cancel or close — including when you leave from the
  item picker rather than from the editor.

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
  those, so the whole server set is encodable — including every recipe a plugin added with
  `Bukkit.addRecipe`, because Paper's `RecipeMap#addRecipe` puts those in the same map the
  encoder walks (`byKey`, which `values()` returns, *and* `byType`, which CraftBukkit's own
  `RecipeIterator` walks — iterating either sees plugin recipes, so switching iterators
  changes nothing).
* Sent on join once the client has announced the channel (Fabric clients with the recipe
  API always do; vanilla/Bedrock clients never do, so nothing is sent to them), and
  re-sent to everyone whenever the server's recipe set changes — see *Keeping the snapshot
  fresh* below.
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

**Keeping the snapshot fresh.** The encoded payload is a snapshot, and the reason a
plugin's recipes can be missing from JEI is almost always that the snapshot predates them:
plugins register recipes in their own `onEnable`, and CraftBridge enables before anything
later in the alphabet. So the snapshot is rebuilt:

* when the server finishes loading (`ServerLoadEvent` — every plugin has enabled by then,
  and this also covers `/reload`), and pushed to everyone;
* whenever CraftBridge's own recipes change (add, edit, enable/disable, delete,
  `/recipe reload`), debounced to at most one re-encode and push per second so a burst of
  edits does not re-encode the whole set repeatedly. `Bukkit.updateRecipes()` still runs
  immediately for the vanilla recipe book; the JEI payload follows;
* on join and on the channel announcement, if the live recipe count no longer matches the
  snapshot's — so a joining player never gets a stale blob;
* every 30 seconds while anyone is online, by the same cheap count check, which catches
  recipes another plugin added or removed at any time without telling us.

**Checking it worked.** The boot line now breaks the payload down by namespace, e.g.
`JEI recipe sync: 1616 recipe(s), 212 KiB [minecraft=1599, craftbridge=4, homecraftmgmt=13]`
— if a plugin's namespace is missing or short, its recipes are not in the payload, and
CraftBridge cross-checks against `Bukkit.recipeIterator()` and logs a WARN naming the
namespace. `/craftbridge jei` prints the same line plus how many clients have it, and
`/craftbridge jei resync` re-encodes and re-sends to everyone on demand. Every successful
send also logs one INFO line naming the player and the channel
(`JEI recipe sync: sent to Dierks on fabric:recipe_sync (1605 recipe(s), 115 KiB [...])`),
and a client that registered JEI's own channels but not ours gets one WARN five seconds
after joining listing the channels it *did* register — that is what a JEI build whose
recipe sync is not `fabric:recipe_sync` (a NeoForge client) looks like, and recipe sync
needs a Fabric client. On the client
side, JEI itself says which set it is using: if it fell back to the client's own recipe
JSONs it prints a recipe-sync warning in chat and in `latest.log`
(`jei.message.server.recipe.sync.*`); no warning means it accepted the payload.

**Inspecting one recipe.** `/craftbridge jei dump <recipe key>` (e.g.
`/craftbridge jei dump homecraftmanagement:pc`) prints the recipe as the server holds it and
again after an encode/decode round trip through the same codecs the payload uses — result,
shape and every ingredient slot, including whether a choice is an `ExactChoice` and what
stacks it carries. That is how to tell what the wire does to a custom-item ingredient
without guessing. Every recipe is also round-tripped at encode time, and one that does not
survive is left out with a WARN naming it: the client decodes the payload in a single pass
and discards **all** of it on the first bad recipe, so shipping one is worse than dropping it.

**What does not survive the wire.** The result item keeps everything — custom name, lore,
PDC, components — because a shaped/shapeless recipe's result is a full `ItemStack`.
*Ingredients* do not: Paper stores an `ExactChoice`'s stacks in a CraftBukkit-only field
next to the vanilla `HolderSet<Item>`, and only the `HolderSet` is in the vanilla
serializer's stream codec, so an exact-match ingredient arrives at JEI as its plain item
type (a renamed barrel shows as a barrel *in the ingredient slot*; the recipe and its
result are correct). There is no way around it on this channel: JEI only accepts
`minecraft:` serializers here (`RecipeSyncImpl.isSynced`), and a payload naming any other
serializer — including JEI's own `jei:jei_shaped`, which *could* carry full stacks — is
rejected wholesale by the client decoder.

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
tracked in `plugins/CraftBridge/linked-workbenches.yml` (`type` + world + xyz + display UUID +
owner + yaw; the same file holds Combo Chests). Breaking it drops the head item back; burning or exploding it does too;
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

**Using it.** Right-click → a real vanilla crafting menu attached to the table
(`MenuType.CRAFTING`, `checkReachable`), tracked as a *linked session*. Placing a linked
table is the whole opt-in: no mode, no sneak-click, no activation step. Vanilla's reach
check closes it when the player is more than 8 blocks away or the table is gone; death,
teleport, quit, kick and Escape end it too.

**Phantom inventory slots (`linked-workbench.phantom-slots`).** JEI decides on the
*client* whether a transfer is possible: its handler scans the inventory slots of the open
menu for the recipe's ingredients and, if any are missing, shows the red highlight and
never sends `jei:recipe_transfer` — so server-side sourcing alone can never fill a grid
from chests. (Confirmable on the live server: with `debug: true`, click `[+]` with an
empty inventory and no "First JEI packet on …" line ever appears.) CraftBridge therefore
makes the client *see* nearby storage as inventory:

* On open, nearby storage (chests, barrels, shulkers, double chests within the radius,
  permission-checked; hoppers/furnaces and Combo Chest barrels excluded) is aggregated by
  item type. Each of the player's **empty** inventory slots (main + hotbar; armour and
  offhand excluded, and `phantom-reserve-empty-slots` left free so JEI can still shuffle
  leftovers) is shown — packet-only, via `ClientboundContainerSetSlotPacket` on the open
  crafting menu — holding one type, capped at the stack size, with the lore line
  *"From nearby storage (N available)"*. The real inventory is never touched.
* **Only phantom slots are special, and only in one direction.** Every interaction with the
  player's own slots, the crafting grid and the result is left to vanilla — pick up, place,
  shift-click, drag, number keys, double-click collect, offhand swap, craft, take the result.
  Hand-placing items into the grid and crafting with them works exactly as at a plain
  crafting table. `workbench.WorkbenchClicks` decides this and is unit tested for every click
  type against every kind of slot, because getting it wrong is what broke hand-crafting twice.
* **Putting something into a phantom slot is always allowed.** The slot genuinely is empty
  server-side — phantoms are packet-only — so placing there is an ordinary vanilla move and
  the phantom simply relocates on the next rebuild. Drags are never refused either: a drag
  only ever puts items down.
* **Clicking a phantom takes the items for real, in one click.** Left-click puts a stack
  straight **on the cursor** — like taking it out of a chest, ready to place — right-click
  half a stack, and shift-click fills the player's empty inventory slots instead (shift-click
  has no cursor semantics). The pull runs in the same tick as the click, and the slot's true
  contents and the cursor go to the client immediately rather than on the next scheduled
  rebuild: waiting for that is what left the client drawing a phantom the player had already
  taken, so the next click was spent re-syncing instead of doing what they meant. Amounts are
  bounded so a pull can never overflow (`workbench.PullPlanner`, unit tested). Storage is re-scanned
  first, so a container emptied or locked since the snapshot moves what is really there
  rather than what the snapshot claimed. Anything that somehow does not fit goes back into
  storage rather than onto the floor. Number keys, drops, double-click collect and offhand
  swaps on a phantom are cancelled and the slot re-sent — they have no sensible meaning on
  contents that are not really there.
* **Nothing creates or destroys items.** Storage plus inventory plus the cursor is counted
  before and after in `StorageAccountingTest`, across every pull mode, stack size and
  full-inventory case, and across a session ending while the player is still holding a pulled
  stack. That last case needs no code of ours: Paper drops a carried item at the player's feet
  on disconnect (its own "Drop carried item when player has disconnected" patch) and vanilla
  does the same when a container closes, so the item is always visible and recoverable —
  intervening would risk handing it back *and* dropping it.
  Items pulled into the crafting grid by a JEI transfer can be taken into the inventory like
  any other — it is the same move as clicking the phantom, by a slower route — and whatever
  is still in the grid on close goes back to the container it came from.
* `[+]` / shift-`[+]`: when JEI's transfer draws from a phantom slot, the server pulls the
  real items out of the recorded containers (nearest first) straight into the grid and
  records the origin per grid slot. Items the engine "stows" into a phantom slot go back
  into storage. If a chest emptied between snapshot and click, the grid is trimmed by the
  shortfall. Real slots behave exactly as before; the shift-`[+]` top-up still runs after.
* After every click, craft and transfer the snapshot is rebuilt and re-sent so counts stay
  honest; slots that stopped being empty get their real content re-sent.
* On session end (close, death, teleport, out of range, quit, kick) `updateInventory()`
  re-syncs the real inventory; sessions are in-memory only.
* JEI's `delete_player_item` and cheat-mode packets are never acted on (the latter are not
  even registered), so nothing can be "deleted" out of a phantom slot.

**The 36-type ceiling, and paging.** A `CraftingMenu` has exactly 36 player-inventory
slots, JEI's transfer handler reads only the slots of the open container, and that menu has
no scrollable region a server can write into — so **at most 36 item types can be visible to
JEI at any instant**, and in practice fewer: only genuinely empty slots are used, minus
`phantom-reserve-empty-slots`. A base with 400 item types in range cannot show them all at
once, and no amount of server-side work changes that. (A fake container, a resized menu or
a custom menu type all lose `[+]` entirely: JEI's transfer handler is registered against the
vanilla crafting menu.) So instead:

* **Pages.** Storage types are split into pages of "however many free slots there are". With
  more than one page, the last two free slots become ◀ / ▶ buttons (barriers, which are an
  ingredient in no recipe, so JEI ignores them); clicking one turns the page. There is also
  `/craftbridge page next|prev` for when the buttons do not fit (fewer than three free
  slots) — the only `/craftbridge` subcommand that needs no permission.
* **Order, so page one is almost always the right page:** items that are ingredients in
  recipes *this player has unlocked* first, most-used first (`RecipeIngredientIndex`, built
  once from `Bukkit.recipeIterator()` on the first workbench open, weighted per player from
  `getDiscoveredRecipes()` when the table is opened), then raw count descending, then
  alphabetically.
* **The action bar** says where you are on open and on every change:
  *"Nearby storage — page 1/12 (417 types)"*.
* **No free slots at all** (a completely full inventory): nothing is shown, nothing real is
  ever overwritten, and the action bar says *"Nearby storage hidden — no free inventory
  slots."* once.

A search box would beat paging, but it needs text input (an anvil GUI or chat), which
breaks on Geyser/Bedrock — so it is deliberately not built. If it is ever wanted it should
land behind its own config flag, with paging staying the default.

Implementation: `workbench.PhantomManager`; the one NMS call lives in
`workbench.nms.PaperSlotPackets` (the same packet CraftBukkit's own
`CraftInventoryPlayer#setItem` sends) and is loaded reflectively, so a rename on a
future 26.x build disables just this feature with one WARN.

**Getting the block:** craft it, or `/craftbridge give <player> workbench [amount]`.

## Feature 2b — Combo Chest

A storage terminal: one block that shows everything in every chest around it, hands it
out, and takes deposits — no activation, no modes, and no sneak-clicking.

**The block.** Physically a `BARREL` (so Towny/WorldGuard/vanilla break-and-drop rules
apply unchanged) placed from a PDC-tagged item (`craftbridge:combo_chest`) and tracked
in the same `linked-workbenches.yml` as the workbench, with `type: combo_chest`. An
`ItemDisplay` sits on the barrel: the head from `combo-chest.head-texture` (blank uses
the built-in Combo Chest head, `none` falls back to `display-item` — a plain `CHEST`).
A head model is half a block, so both kinds default to scale 2.02 with the entity at the
top of the block; the chest item model wants scale 1.16 at the block centre instead.
Tune it live with
`/craftbridge combochest display <scale|x|y|z|yaw|transform> <value>`. Breaking it drops
the Combo Chest item back; burning/exploding do too; pistons cannot move it; the sweep
that fixes stray workbench displays covers Combo Chests as well. Craftable
(`craftbridge:combo_chest`; default shape `HEH / CBC / HRH`: barrel in the middle, 4
chests in the corners, 2 copper ingots, an ender pearl on top and a comparator below;
`combo-chest.recipe` in `config.yml`).

**Right-click** opens the terminal GUI instead of the barrel. The barrel underneath is
*never* storage, and nothing can put anything in it: it is skipped by the terminal itself,
by other Combo Chests, by the Linked Workbench and by the phantom-slot snapshot; it is
refused as a deposit destination at the point of writing rather than merely filtered out of
the caller's source list; and hoppers and droppers cannot move items into it either.

Exclusion is keyed by `world:x:y:z` (`workbench.BlockKeys`, unit tested) rather than by
comparing `Location` objects, whose equality folds in the world reference plus yaw and
pitch. A single mismatch there turns "skip this container" into "use this container", which
is how a deposit could end up inside the terminal it was made from — listed in the terminal
and reachable from no chest.

* **Range and permissions.** Every chest, trapped chest, double chest (once), barrel and
  shulker box within `combo-chest.radius` (8) blocks that the player may open — vanilla
  lock, Towny plot permission, then a synthetic `PlayerInteractEvent` any protection
  plugin can cancel. Hoppers and furnaces are never touched.
* **Browse.** Contents are aggregated by item type (same type + same components), sorted
  count-descending, 45 per page with Previous/Next arrows in the bottom row. Each entry
  shows the total available across all containers. Quick filters — *All / Blocks / Tools
  & armor / Food / Misc*, derived from the sorter's category rules — sit in the bottom row.
* **Pull.** Click an entry = one stack into your inventory; shift-click = as many as fit.
  Items come out of the nearest containers first. "No room in your inventory" if full.
* **Deposit.** Four gestures, all handled by the shared GUI base: left-click anywhere in
  the terminal with an item on the cursor (the whole stack), right-click (one item),
  shift-click an item in your own inventory, or drag over the terminal (a drag that spans
  both inventories deposits only the part aimed at the terminal; the rest stays on the
  cursor). Your own inventory keeps ordinary click behaviour the whole time, so an item can
  always be picked up onto the cursor to start a deposit. Where it lands is a fixed order (`workbench.DepositPlanner`, unit
  tested), the same for a single click and a shift-click bulk move:
  1. top up partial stacks of the same item (same components), nearest container first,
     up to the item's stack size;
  2. then an empty slot in a container that already holds that item, nearest first;
  3. then an empty slot in the nearest container with space;
  4. anything still left stays with you, with "No room in nearby storage" — a deposit
     never destroys part of a stack.

  So 5 cobblestone deposited while a nearby chest holds a stack of 10 makes one stack of
  15, rather than a second stack in a free slot. Locked or no-permission containers are
  never written to because they never make it into the scan, and a shulker box is never
  put inside another shulker box.
* **Live.** Every click (pull, deposit, page, filter) re-scans, so hoppers and other
  players' changes show on the next click. The info icon in the middle of the bottom row
  shows the container count, item-type count and page.

**Getting the block:** craft it, or `/craftbridge give <player> combochest [amount]`.
Both blocks share the code in `workbench.*`: `BlockKind` picks the physical block,
tag, recipe key, config section and display defaults per kind.

## Feature 1b — the client link

JEI decides whether a recipe's `[+]` is available by scanning the slots of the open menu, so
a server on its own can show it at most **36 item types** — the free slots of a crafting
table's inventory (Feature 2's phantom slots). A real base has hundreds. The optional
[CraftBridge-Client](https://github.com/Dierks27/CraftBridge-Client) mod removes that ceiling
by being told what is in range directly.

**Nobody has to install it.** A player without the mod never says hello, is never sent
anything, and keeps phantom slots and the `jei:recipe_transfer` path exactly as before. A
player with it gets their phantom slots turned off — the two mechanisms answer the same
question, and running both would show every item twice.

* **The wire contract** is `link.LinkProtocol` (channels, payloads, sizing) and
  `docs/link-protocol.md`. Both halves keep the same copy of it and every payload starts with
  a protocol version, so a mismatched pair says so in chat and stays dormant rather than
  misreading each other.
* **Storage** is sent as a full snapshot when the workbench opens and as deltas afterwards,
  each with a sequence number; a client that sees a gap asks for a fresh snapshot rather than
  acting on a view that lies about counts. Changes are found by re-reading the containers once
  a second, because a hopper filling a chest is not something the plugin is told about — the
  phantom slots this replaces re-scanned on every click for the same reason. A quiet second
  sends nothing.
* **A transfer is the server's work, not the client's.** The request names a recipe (and, for
  a display with no registered recipe, what each slot would accept) and nothing else — never
  what the player has, never how much. The server looks the recipe up in its own registry,
  counts the player's inventory and the containers in range itself, and decides what may be
  taken and from where (`link.GridPlanner`, unit tested: one item type per slot decided once,
  the scarcest ingredient setting how many sets are made, stack sizes respected, the player's
  own items spent before storage is touched). The worst a modified client can do is ask for a
  recipe it could have asked for by clicking.
* **Before the grid is refilled it is emptied** by the same rules a close uses — items that
  came from a container go back to it, the rest to the player — so nothing is lost and nothing
  is duplicated. Items pulled from storage remember which chest they came from and go back
  there when the session ends.
* **Custom items** (CraftBridge's own blocks, every custom recipe's result) are sent as a
  catalog on hello. They are renamed vanilla items carrying plugin data rather than registry
  entries of their own, so without this JEI has no tile for them and nothing to look their
  recipes up from.
* **`link.nms.PaperItemBlobs`** is the only new server-internals class: item stacks travel as
  the bytes vanilla's own slot codec produces, so components survive the trip and neither side
  has to know what they mean. If those names ever move, the link switches itself off at boot
  and the plugin carries on with phantom slots.

## Configuration

See the comments in `src/main/resources/config.yml`. Everything reloads with
`/craftbridge reload`.

**A config from an older version keeps working.** Every setting falls back to a built-in
default, and any top-level section your `config.yml` does not have is named once at boot
with a line saying the defaults are in use — nothing throws, and nothing silently stops
working. In particular a recipe block that cannot be parsed (missing, empty, or left over
from a version with a different shape) is reported by *key* with what was wrong —
"`combo-chest.recipe.shape` is unusable — no rows: expected 1 to 3 rows like
['HEH', 'CBC', 'HRH']" — and the built-in recipe is registered instead, so the block is
never left uncraftable. `config.ShapeSpec` does that validation and is unit tested.

## Development notes

* Package layout: `com.dierks.craftbridge.<feature>`; each feature implements
  `CraftBridgePlugin.Feature` and is only constructed when its switch is on.
* `gui.Menu` / `gui.MenuListener` is the shared click-driven chest GUI base (with
  optional editable slots for menus that take real items). Two rules hold everywhere: a
  slot with no handler cancels every interaction, and `fill` never puts a decorative item
  in an editable slot — so no GUI item is ever removable unless it is explicitly an item
  the player is meant to receive. Clicking an *empty* editable slot with an empty cursor
  (a no-op in vanilla) is routed to the menu, which is how the editor opens its picker.
  Menus can also opt into deposits (`acceptsDeposits()` / `deposit(...)`): cursor clicks,
  shift-clicks from the player inventory and drags over the menu are cancelled and
  routed through `deposit`, which returns whatever did not fit.
* **What a click means is a table, not a chain of ifs.** `gui.MenuClicks` maps
  (region x click type x cursor state x slot state) to one action, with a test per cell,
  and `MenuListener` only carries the decision out. The rule it enforces is that a gesture
  which merely *puts items down* is never refused by default: in the player's own
  inventory the fallback is plain vanilla behaviour, and exactly two gestures are refused,
  each because it reaches up into the menu — shift-click (which would shove items into
  button slots) and double-click collect (which would vacuum the menu's icons onto the
  cursor, minting items). This is the same shape as `workbench.WorkbenchClicks`, and for
  the same reason: a blanket "cancel anything we did not build" broke an ordinary
  put-things-down gesture twice — at the workbench, and then in the Combo Chest, where
  cancelling every click in the player's own inventory meant an item could not be picked
  up onto the cursor at all, so no cursor or drag deposit could even begin. Item loss and
  item minting both come down to one sum, `MenuClicks#keptOnCursor`, which is stated once
  and asserted.
* `integration.ContainerAccess` is the one place that answers "may this player use this
  container?": vanilla lock → Towny (reflection, optional) → a synthetic
  `PlayerInteractEvent` any protection plugin can cancel.
* Pure logic (`sort.SortAlgorithm`, `sort.SortCategoryRules`, `recipes.RecipeShape`) has
  no Bukkit dependency and is covered by JUnit tests; `./gradlew test` runs them.
* `recipes.RecipeRegistry#onChange` is the hook a recipe-sync feature uses to learn that
  the registered set changed.
