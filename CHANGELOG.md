# Changelog

## 0.14.0

**Needs CraftBridge Client 0.4.0.** The client link is now protocol version 3. A 0.3.0 client
is told once to update and keeps working with phantom slots, without the storage panel.
`config.yml` is upgraded automatically (config-version 4), keeping your own values and comments.

### New

* **Custom block models (resource pack).** The Linked Workbench and Combo Chest can show their own
  block models instead of the textured heads (`<block>.display.mode: model`, the default).
  * The plugin builds `plugins/CraftBridge/pack/craftbridge-java.zip` on every start and logs its
    SHA-1. Upload it to your website and set `resource-pack.url`. The plugin always sends its
    own hash, and at startup it checks that the uploaded copy matches, warning when it is stale.
    Re-upload after any update that changes the pack.
  * A built-in web server is available instead (`resource-pack.host`, off by default, port 8765).
  * **Until a URL (or the host) is set, the blocks keep their heads**, so upgrading changes
    nothing until you upload the zip.
  * Players who decline the pack see the plain crafting table or barrel. `display.mode: head`
    brings the heads back for everyone.
  * Change the models by dropping files into `plugins/CraftBridge/pack/overrides/java/`
    (see `resourcepack/README.md`, Blockbench).
  * Bedrock: `/craftbridge geyser export` writes a `.mcpack`, a Geyser item mapping and a
    GeyserDisplayEntity mapping (and copies them into Geyser when it runs on this server).
    `bedrock.show-displays` is off by default. The Bedrock side is untested.
  * `/craftbridge pack` shows the pack's hash, URL and how many players loaded it.
* **Golem chests.** Craft a Golem Chest Marker (copper, honeycomb and a stick) and right-click a
  chest, double chest or barrel to mark it. The Linked Workbench, JEI transfers, the storage
  panel and the Combo Chest then always leave one item in every slot of a marked container, so
  copper golems keep sorting into it. Holding the marker shows sparkles on marked containers
  nearby (only to you). No chest is affected until someone marks it. `golem-chests.enabled`
  turns the feature off.
* **Middle-click sort.** Players with the client mod can middle-click a container, or their own
  inventory, to sort it. It is a per-player toggle in `/sort settings`
  (`sorting.middle-click.allowed` / `.default`) and follows `/sort`'s locks, claims and rules.
* **Choose how many to craft.** JEI transfers can ask for an exact number of crafts, or "All but
  one", which leaves one of every ingredient in storage.
* **The storage panel at the Combo Chest.** With the client mod, the panel appears beside an open
  Combo Chest and pulls from the terminal's storage. The GUI itself is unchanged.
* The client's JEI list is refreshed whenever recipes or custom items change, and it includes
  custom items that are not the result of any recipe.

### Fixed

* Grid items at a Linked Workbench were lost when the player died with it open. They now drop
  where the player died.
* A recipe, custom item or workbench entry that failed to load was erased by the next save, and a
  YAML syntax error in `recipes.yml` (or the other stores) made the next save wipe the whole file.
  Unreadable entries are now kept as written, and a file that failed to load is never saved over.
* A client transfer naming grid slots outside the 3x3 grid could delete the items it had taken.
* A Linked Workbench session could outlive a menu another plugin kept from opening.
* After `/craftbridge reload`, client-mod players had to rejoin to get their panel back. They are
  re-linked automatically now.
* Client link messages are rate-limited, and a malformed message is ignored instead of unlinking
  the player with a misleading "update" message.
* Storage pulls and sneak-punch sorting now check the lock and claim of both halves of a double
  chest.
* Editing a custom item now updates the recipes that make it without a reload.

## 0.13.3

**Item duplication fixes.** All three were found in an audit; none needed a modified client
to reach, though a modified one made them larger.

* **JEI [+] at a Linked Workbench could create items.** The phantom slots that show nearby
  storage are counted when the player last clicked; if a hopper, another player or a pull
  emptied a chest after that, the transfer still planned with the old counts. Storage then
  came up short, and only the crafting grid was trimmed: whatever the transfer had put in
  spare inventory slots or handed over as overflow reached the player anyway. Storage is now
  counted again at the moment of the transfer, in the same tick the items are taken, and any
  shortfall is taken back from overflow first, then the inventory, then the grid.
* **A JEI transfer could turn one item into another.** When a transfer used up a phantom
  slot and then put a different item into that (really empty) slot, the plugin only compared
  counts: it put phantom items that never existed back into storage and lost the other item.
  That item now goes to the player, and the phantom counts as fully used.
* **The Combo Chest could pull from a broken shulker box.** It pulled from the list of
  containers it scanned when the page was drawn; a shulker box broken since (by another
  player or a piston) dropped with its contents and could still be emptied from the menu.
  A pull now re-scans right before it takes, and no pull or deposit ever touches a container
  whose block is no longer storage.

## 0.13.2

**Updating is now drop-in-the-jar: an older `config.yml` is upgraded automatically.**
Bukkit never adds new keys to an existing config.yml, so until now every release that added a
setting left existing servers behind. The one you may have seen in the log:
`combo-chest.recipe.shape is unusable — row 1 uses 'H', which has no entry under ingredients
(found [HEH, CBC, HRH] with ingredients [])`. That came from a config.yml older than the
Combo Chest.

On startup (and on `/craftbridge reload`), a config.yml older than the jar is upgraded once:

* **Backed up first**, byte for byte, to `config.yml.bak-v<old version>`. A backup that
  already exists is never overwritten.
* **Missing settings are added** with their default values and their comments. A value you
  have set is never changed.
* **Lists get only what is new in that release, once.** For example,
  `jei.recipe-sync.types` gets `minecraft:brewing` (new in 0.13.1) when upgrading from before
  it, skipped if it is already there. A type you remove afterwards stays removed. An empty
  list, which means "all the built-ins", is left empty.
* **Incomplete recipe blocks are repaired.** `combo-chest.recipe` or `linked-workbench.recipe`
  with an empty or missing ingredient map, and a shape drawn with the stock letters, gets the
  stock ingredients. A recipe with your own letters is never merged with the stock ones.
* **One log line says what changed**, e.g.
  `Config migrated v0 → v2: added features.client-link, linked-workbench.phantom-slots,
  linked-workbench.phantom-reserve-empty-slots, combo-chest; added jei.recipe-sync.types
  [minecraft:brewing]; the old file is config.yml.bak-v0`.
* **Nothing happens once the file is current.** The new `config-version` key at the end of
  the file records that; a second boot changes nothing.

**Upgrading from 0.13 or 0.13.1 keeps your file exactly as you wrote it.** When an upgrade
only needs to add list entries and the version stamp, those lines are inserted into your text
and every other byte stays put: your layout, inline lists, quotes, and comments inside lists
(such as an entry you commented out). The edited text is parsed back and has to read exactly
like a full migration, or the full migration is used instead.

A bigger upgrade, such as a file from before the Combo Chest that needs whole sections, goes
through Paper's YAML writer. That keeps the comment above every setting and at the end of a
setting's line, but lays some things out anew, once. Inline lists like `['HEH', 'CBC', 'HRH']`
become one item per line, unneeded quotes go, and comments written *between the items of a
list* or after an inline list are dropped. New sections go at the end of their parent. Your
old layout is in the `.bak` file.

The migrator won't touch a file in these cases:

* **Not valid YAML:** it is left untouched, with an error in the log, rather than replaced.
* **Not UTF-8** (e.g. saved as Windows-1252): it is left untouched, with a warning, rather
  than having its accented characters damaged. Save it as UTF-8 and restart.
* **A newer `config-version` than the jar**, after a downgrade: it is left as it is, with a
  warning.

In the cases it does migrate:

* **A symlinked config.yml stays a link**, and the file keeps its permissions.
* **A setting deliberately left out stays out when its absence means something.**
  `linked-workbench.head-texture` missing means "show `display-item`", so it is never filled
  in with the textured head.
* **One entry can come back once.** A 0.13.1 install from which you had already removed
  `minecraft:brewing` gets it back on this one upgrade, because a file without
  `config-version` cannot say which release wrote it. The log line names it.

Only `config.yml` needs this. `recipes.yml`, `custom-items.yml`, `linked-workbenches.yml` and
`sort-players.yml` are written by the plugin, have no bundled defaults, and every change to
their layout so far has been additive with a fallback when reading. `recipes-peaceful-starter.yml`
has not changed since 0.1.0 and is only read by `/recipe import starter`.

Also fixed:

* **The "config.yml has no … section" notice never appeared.** It checked the jar's defaults
  instead of the file. It now names a section the file really lacks.
* **An empty `ingredients: {}` could be written into config.yml** by any
  `/craftbridge workbench|combochest display` tweak on an older config. Reading a missing
  ingredient map created an empty one in memory, and the tweak's save wrote it out. A missing
  map now reads as the built-in ingredients without creating anything.

## 0.13.1

* **Storage-panel pulls work on Paper 26.3.** 26.3 removed the
  `CraftItemStack.asBukkitCopy(ItemStack)` overload that a jar built against 26.2 calls. The
  first pull from the CraftBridge-Client storage panel threw `NoSuchMethodError`, and the
  client got no reply.
* **JEI's Brewing tab is filled on 26.3.** Minecraft 26.3 made brewing a recipe type and
  JEI 26.3 reads it from the server, so `minecraft:brewing` joins `jei.recipe-sync.types`. On
  26.2 it matches nothing and is skipped.
