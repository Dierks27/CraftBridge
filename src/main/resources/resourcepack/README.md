# CraftBridge resource packs

The models behind `display.mode: model` (the Linked Workbench and the Combo Chest), and what
lets a custom item from `/recipe` have a texture of its own. Everything here is packed into the
plugin jar; the plugin builds the actual pack files from it at runtime.

```
java/       the Java Edition pack (zipped by the plugin on every start)
bedrock/    the Bedrock pack for Geyser (zipped by /craftbridge geyser export)
geyser/     Geyser's custom item mapping and the GeyserDisplayEntity mapping
vanilla/    Minecraft's own item definitions, one table per Minecraft version (for custom item art)
```

## How the Java pack works

The plugin never adds items. The display over each block, and the place-item, is a vanilla
`crafting_table` or `barrel` item whose `custom_model_data` has the string
`craftbridge:linked_workbench` or `craftbridge:combo_chest` at index 0.

* `java/assets/minecraft/items/crafting_table.json` and `barrel.json` replace the vanilla item
  definitions with a `minecraft:select` on that string. Our string picks our model; anything
  else (every ordinary crafting table and barrel) falls back to the vanilla block model, so
  the pack changes nothing else.
* `java/assets/craftbridge/models/block/linked_workbench.json` and `combo_chest.json` are the
  models: ordinary block models (`parent: minecraft:block/block`, so they look right in hands
  and inventories too). The front is the **north** face; the plugin turns the display so the
  front faces the player who placed the block. Stay inside the 16x16x16 block (the Combo Chest's
  panel sticks out 1 pixel at the front, which is fine).
* `java/assets/craftbridge/textures/block/` holds the textures (16x16; 32x32 works too).
* `java/pack.mcmeta` declares formats 88.0 (Minecraft 26.2) to 97.1 (26.3).
* Custom item art is not in the jar. The plugin adds it when it builds the pack, from the files
  you drop into `plugins/CraftBridge/pack/items/` (next section).

## Give a custom item its own look

A custom item is a vanilla item with a name and lore, so it looks like its base item: Burned
Zombie Flesh looks like dried kelp. You can give it a texture of its own without touching any
code:

1. Make the item in `/recipe` as usual. Its id is on the editor's Save button
   ("Id: burned_zombie_flesh"). The file is named after the id, not the display name.
2. Drop `burned_zombie_flesh.png` into `plugins/CraftBridge/pack/items/` on the server.
   CraftBridge makes that folder on its first start, with a short README.txt in it.
3. Run `/craftbridge reload`. CraftBridge rebuilds the pack and offers the new one to everyone
   online. The item now shows its texture in inventories, in hands (yours and other players'),
   on the ground and in JEI.

If players download the pack from `resource-pack.url`, there is a fourth step: upload the new
zip (see [Uploading the zip](#uploading-the-zip)).

### The files

Everything goes directly in `plugins/CraftBridge/pack/items/`; files in subfolders are not
read. Files are named after the custom item's id. Upper and lower case do not matter, so a file
Windows saved as `Burned_Zombie_Flesh.PNG` works.

| File | What it is |
|---|---|
| `<id>.png` | The texture. Square: 16x16, 32x32, 64x64 or 128x128 pixels. Or a vertical strip of square frames, for an animation. |
| `<id>.png.mcmeta` | Optional. Animates the texture. A strip needs one. |
| `<id>.json` | Optional. A whole model of your own, for example a Blockbench export. Used exactly as it is. |
| any other `.png` | An extra texture for a `<id>.json` model, which names it as `craftbridge:item/<name>` (or `craftbridge:block/<name>`). |

File names may only use letters, digits, `_`, `-` and `.`. A file that belongs to no custom item
is listed as skipped ("no custom item has the id ..."). That is usually a typo in the name.

### Animation

Stack the frames on top of each other in one PNG: four 16x16 frames make a 16x64 strip. Put
`<id>.png.mcmeta` next to it. It is Minecraft's standard animation file:

```json
{ "animation": { "frametime": 4 } }
```

`frametime` is how many ticks each frame is shown (20 ticks is one second), a whole number of
at least 1. A strip without an mcmeta is skipped: Minecraft only cuts a strip into frames when
the mcmeta tells it to. So is an mcmeta Minecraft would refuse: one that is not strict JSON (no
comments, no trailing commas, every key in double quotes), a `frametime` of 0, or a frame
`width`/`height` that does not divide the image. An animation has 256 frames at most.

### What the item looks like

Without a model of your own, CraftBridge makes one from the base item:

* **A flat item** (dried kelp, string, a sword): your texture, held the way the base item is
  held. A custom item on a sword is held like a sword, on a fishing rod like a fishing rod.
* **A block** (cobblestone, a crafting table): a cube with your texture on every side.
* **Anything Minecraft draws in a special way**: tinted items (potions, leather armour), items
  that change with their state (a bow, a compass, a clock), and items with their own renderer
  (chests, shields, banners). These get a plain flat picture. A custom item on a bow shows your
  picture and no pull animation.
* **A filled map or a light block** gets no art at all. Minecraft 26.2 and 26.3 draw those two
  differently, and the same pack goes to clients of both versions (through ViaVersion, for
  example), so there is no one vanilla look for every other map to fall back to. The log says so;
  pick another base item.

If that is not what you want, make a model of your own.

### A model of your own (Blockbench)

Save the model as `<id>.json` in the same folder. CraftBridge puts it in the pack as it is. The
textures it uses go in the folder too, and the model must name them with the `craftbridge:`
namespace:

```json
"textures": {
  "0": "craftbridge:item/burned_zombie_flesh",
  "1": "craftbridge:item/flesh_side"
}
```

`craftbridge:item/flesh_side` is `flesh_side.png` in the same folder, and `<id>.png` is
`craftbridge:item/<id>`. A texture named `craftbridge:block/<name>` also comes from `<name>.png`
(a cube-shaped model usually uses the blocks atlas), and the `{"sprite": ..., "force_translucent":
true}` form works too. The model must be strict JSON, as Blockbench writes it: Minecraft does not
load a model with comments or trailing commas, so CraftBridge skips one and says why.

**Watch the namespace.** Blockbench writes `item/flesh_side`, without one. Minecraft reads that
as `minecraft:item/flesh_side`, a texture that does not exist, so the item shows the purple and
black "missing texture" squares. When the folder has a `flesh_side.png`, the log warns about it
("... which Minecraft reads as minecraft:item/flesh_side; write craftbridge:item/flesh_side to
use flesh_side.png"). Open the `.json` in a text editor and put `craftbridge:` in front.

In Blockbench, export with *File > Export > Export Block/Item Model*, and save each texture
(right-click the texture > Save) into the same folder.

### Who sees it

* **Java players who accepted the resource pack** see the texture.
* **Java players who declined it, and Bedrock players**, see the plain base item: dried kelp
  named "Burned Zombie Flesh". The name, the lore and the recipes are the same for everyone. The
  item works without the pack; the pack only changes how it looks. Bedrock players can get an
  icon too (see [Bedrock / Geyser](#bedrock--geyser)).
* **Custom items built on a player head** keep their head skin and never use pack art. A PNG for
  one is listed as skipped.

Every other item of the same type still looks exactly like vanilla. An ordinary dried kelp is
still dried kelp, with or without the pack ([How it works](#how-it-works) says why).

### Checking it worked

* **The log**, on start and after `/craftbridge reload`, has one line such as
  `Resource pack: 2 custom items have art (burned_zombie_flesh, wither_dust), 1 skipped (Flesh.png: no custom item has the id flesh)`,
  plus one warning line for each problem. The line is a warning when something was skipped.
* **`/craftbridge pack`** lists each item that has art (its id, base item, texture size, whether
  the model is generated or your own, and where the base item's definition comes from), and each
  skipped item or file with the reason.
* **The custom item editor** in `/recipe` has a button that says `Pack art: found (16x16)`, or
  `No art` with where to drop the file, or what is wrong with the file. It also says whether the
  pack already has the art (`Not in the pack yet: run /craftbridge reload`), or why the last
  build left it out. Items on a player head show their head texture button there instead.

### Uploading the zip

The pack is sent to players when either block uses `display.mode: model` or at least one custom
item has art. When a reload leaves nothing that needs it, players online are told to drop it, so
removed art goes back to the base look at once.

If players download it from `resource-pack.url`, the zip changes whenever you add, change or
remove art. The `/craftbridge reload` that picks up the change builds a new
`plugins/CraftBridge/pack/craftbridge-java.zip` with a new SHA-1. Upload that file after every
such reload. Until you do, players' clients refuse the old upload (its hash no longer matches)
and see plain base items and plain blocks. `/craftbridge pack` shows the SHA-1, and the check at
startup warns when the uploaded copy is out of date. With the built-in web server
(`resource-pack.host`) there is nothing to upload.

A pack with no custom item art is byte for byte the same as 0.14's (same SHA-1), so updating
from 0.14 does not by itself need a new upload.

### Other resource packs

To give a custom item its look, CraftBridge replaces the whole item definition of its base item
(`assets/minecraft/items/dried_kelp.json`). If another resource pack also changes that item,
only one of the two wins: the one higher in the player's pack list. The separate clock pack
changes the clock, for example, so don't give a texture to a custom item built on a clock. Pick
a base item no other pack changes.

### How it works

Every custom item carries the string `craftbridge:item/<id>` at index 0 of its
`custom_model_data`. It is on every custom item, with or without art, heads included, so adding
art later needs no change to the items already out there. The string is only for the look:
nothing matches on it, and the item's identity is still its `cb_item` tag.

For each base item with at least one textured custom item, the pack gets
`assets/minecraft/items/<base>.json`: a `minecraft:select` on that string, with one case per
textured custom item, and **Minecraft's own definition of the base item, copied as it is, as the
fallback**. That file draws every item of that type, which is why the fallback has to be exact:
it is what every ordinary dried kelp is drawn with. It comes from the tables in `vanilla/`
([The vanilla item tables](#the-vanilla-item-tables)), never from a guess. CraftBridge's own
`crafting_table.json` and `barrel.json` get the extra cases next to the block models' instead.

The generated model is `assets/craftbridge/models/item/<id>.json`. Its texture goes in
`assets/craftbridge/textures/item/`, or in `textures/block/` for a cube, because block models
draw from the blocks atlas.

A file of your own at
`plugins/CraftBridge/pack/overrides/java/assets/minecraft/items/<base>.json` wins. CraftBridge
then does not write that base item's definition, still adds the model and the texture, and logs
that your file must select `craftbridge:item/<id>` itself: a case with
`"when": "craftbridge:item/<id>"` and the model `craftbridge:item/<id>`.

Custom items made before 0.15 do not have the string yet. CraftBridge adds it as players come
across them (see "Give a custom item its own look" in `docs/cooking-and-custom-items.md`).

If the server runs a Minecraft version CraftBridge has no table for (26.4 before a CraftBridge
update, say), no custom item gets art, and a warning in the log says so. The items keep the look
of their base item; the block models are not affected.

## Editing in Blockbench

1. In Blockbench: *File > Open Model* and pick `java/assets/craftbridge/models/block/linked_workbench.json`
   (or `combo_chest.json`). Blockbench finds the textures through the `assets` folder.
2. Edit the cubes and paint the textures. Keep the model inside the block and the front on the
   north side.
3. *File > Export > Export Block/Item Model* over the same file, and save the textures
   (right-click each texture > Save).

You do not have to rebuild the plugin to try a change. Copy the changed files, with the same
folders below `java/`, into `plugins/CraftBridge/pack/overrides/java/` on the server, e.g.
`plugins/CraftBridge/pack/overrides/java/assets/craftbridge/textures/block/linked_workbench_top.png`,
and run `/craftbridge reload`. The plugin builds a new `plugins/CraftBridge/pack/craftbridge-java.zip`
and logs its new SHA-1. If players download the pack from `resource-pack.url`, upload the new
zip there; clients refuse a file whose hash does not match, so a forgotten upload shows the plain
blocks rather than an old model. To make the change permanent, put the files here and rebuild.

The Bedrock pack is edited the same way (Blockbench's *Bedrock Model* format for
`bedrock/models/entity/*.geo.json`, one texture atlas per block in `bedrock/textures/craftbridge/`),
with overrides in `plugins/CraftBridge/pack/overrides/bedrock/` and `/craftbridge geyser export`
instead of a reload.

## Regenerating the starter textures

The starter textures (Java faces, Bedrock atlases and icons, both pack icons) come from
`tools/generate_pack_textures.py` at the root of the repository:

```
python3 tools/generate_pack_textures.py
```

It needs only Python 3 and overwrites every texture it makes, so once you have painted a
texture by hand, stop using it (or remove that texture from the script).

## The vanilla item tables

`vanilla/items-26.2.json` and `vanilla/items-26.3.json` hold Minecraft's own item definitions
(every `assets/minecraft/items/*.json` in that version's client jar), plus, for a flat item, the
template its model is built on (`item/handheld` for a sword), so a custom item on it is held the
same way.

They must be exact. The definition CraftBridge writes for a base item draws every item of that
type for every player with the pack, and the vanilla definition is its fallback. A wrong or
outdated fallback would change how ordinary items look. So the tables are never edited by hand:
`tools/extract_vanilla_items.py` copies them out of Mojang's client jars, and CI runs
`python3 tools/extract_vanilla_items.py --check` on every build, which fetches the jars again and
fails the build if a table differs from them.

The plugin picks the table for the server's Minecraft version. A patch release uses its minor
version's table (26.3.1 uses 26.3). A version with no table gets no custom item art and a
warning, rather than a guessed fallback.

For a new Minecraft version (26.4, say):

1. Add `"26.4"` to `VERSIONS` at the top of `tools/extract_vanilla_items.py`. (To try a version
   without changing the list, run it with `--version 26.4`; that extracts only the versions you
   name. CI checks only what is in `VERSIONS`.)
2. From the root of the repository, run `python3 tools/extract_vanilla_items.py`. It downloads
   each version's client jar from Mojang, checks the jar's SHA-1 and writes
   `vanilla/items-<version>.json`. It needs only Python 3.
3. Raise `max_format` in `java/pack.mcmeta` to the new version's resource pack format, so clients
   on that version accept the pack.
4. `VanillaItemsTest` pins which tables exist and how many items each has; update it, then
   rebuild.

Without internet access, extract the client jar (or get its `assets` folder) and point the tool
at it: `python3 tools/extract_vanilla_items.py --assets-dir 26.4=/path/to/extracted/client`. The
folder must contain `assets/minecraft/items/` and `assets/minecraft/models/item/`. `--check`
takes `--assets-dir` too.

## Bedrock / Geyser

`geyser/craftbridge_mappings.json` is a Geyser v2 custom item mapping: `minecraft:crafting_table`
and `minecraft:barrel` items whose `custom_model_data` string at index 0 is ours become the Bedrock
items `craftbridge:linked_workbench` and `craftbridge:combo_chest`. `bedrock/` draws them:
`attachables/` (the 3D model when held or shown by an item display), `models/entity/` (the
geometry), `textures/item_texture.json` and `textures/items/` (inventory icons).

Geyser itself does not show item display entities. With the GeyserDisplayEntity extension it
does; `geyser/geyserdisplayentity_craftbridge.yml` is its mapping for our two items.
`/craftbridge geyser export` writes all of this out and copies it into Geyser's folders: those
of `plugins/Geyser-Spigot` next to CraftBridge, or the folder in `bedrock.geyser-folder` when
Geyser runs on a proxy on the same machine. Restart whatever Geyser runs on afterwards; Geyser
only loads new packs and mappings when it starts.

Custom items with pack art get a Bedrock icon too. The export adds, for each one, its PNG (the
first frame, when it is animated) as `textures/items/craftbridge/items/<id>.png`, an entry in
`item_texture.json`, and a mapping on its base item: `minecraft:dried_kelp` whose
`custom_model_data` string at index 0 is `craftbridge:item/<id>` becomes the Bedrock item
`craftbridge:item_<id>`. A custom item with only a model of its own and no `<id>.png` gets no
icon. When the export includes items, the Bedrock pack's manifest version follows its content,
because a Bedrock client that has cached a pack with the same version never downloads it again.
None of the Bedrock side has been tested in game.
