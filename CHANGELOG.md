# Changelog

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
