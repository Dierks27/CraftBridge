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
  front faces the player who placed the block.
* Any shape works: under the display is an invisible barrier (`display.invisible-block: true`,
  the default since 0.16), so nothing hides the model. The Linked Workbench is a small table
  (Blockbench, 0.16). Keep the model inside the block's footprint: the barrier is the hitbox,
  and a part sticking out cannot be clicked and runs into the next block. With
  `invisible-block: false` the real crafting table or barrel is under the display, and the model
  must cover the whole block or the real one shows through.
* `java/assets/craftbridge/textures/block/` holds our own textures (16x16; 32x32 works too). A
  model may also use Minecraft's own textures (`minecraft:block/anvil`); every client has those,
  so they are not copied into the pack.
* The pack's `pack.mcmeta` is written at every build for the Minecraft version the server runs
  (read from the server itself: `min_format` [major, 0] to `max_format` [major, minor], 97.0 to
  97.1 on 26.3). `java/pack.mcmeta` in the jar is only the fallback when that cannot be read.
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
the mcmeta tells it to. So is an mcmeta with the mistakes Minecraft refuses: one that is not strict JSON
(no comments, no trailing commas, every key in double quotes), a `frametime` of 0, a frame
`width`/`height` that does not divide the image, or a `frames` list or `interpolate` that is not
what Minecraft expects. An animation has 256 frames at most.

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

Since 0.19.0 other art goes **into** CraftBridge's pack as a source (next section), and an item
definition from a source merges with CraftBridge's: a custom item built on a clock gets its
texture and every other clock still draws the clock pack's way. A pack sent separately (from
server.properties, say) still replaces the whole item definition, and only the one higher in the
player's pack list wins, so bring such packs in as sources instead.

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

## The server's one pack: sources, glyphs and the API

Since 0.19.0 CraftBridge's pack is the only pack the server sends. Everything else that needs
art (other plugins, a downloaded pack such as the clock pack, files Jeff adds by hand) goes in as
a **source**, and CraftBridge merges them all into one zip with one SHA-1 and one id.

### Sources

| Source | Where it comes from |
|---|---|
| `craftbridge` | Everything above: the jar's models, `pack/items/` art and `pack/overrides/java/`. Always first. |
| a plugin's source | Registered through the API (below), from a folder or from inside the plugin's jar. |
| a drop-in source | Every folder (or `.zip` file) in `plugins/CraftBridge/pack/sources/`. Its name is its id. |

A source is laid out like any resource pack:

```
plugins/CraftBridge/pack/sources/lilahcraft-clocks/
  assets/minecraft/items/clock.json
  assets/lilahcraft/textures/item/...
  glyphs.json        optional, see Glyphs
  pack.mcmeta        ignored: CraftBridge writes its own
  pack.png           used only if no higher source has one
```

* A source id is lower case: letters, digits, `_`, `.` and `-` (`hcm`, `lilahcraft-clocks`).
* A downloaded pack can go in as it is, unzipped or as the `.zip`. One that was zipped inside an
  extra folder (`Clocks/assets/...`) is read from that folder.
* Only `assets/` is read. Overlay folders and a `pack.mcmeta` `filter` are not supported (the
  build warns when a source has them).
* **Keep to your own namespace**: `craftbridge:`, `hcm:`, `collins:`, `safelamp:`,
  `lilahcraft:`. The build warns when two sources write into the same one (`minecraft` aside).

### How sources are merged

Sources are ranked by `resource-pack.priority` in config.yml (highest first; sources not listed
come after, by id), **never** by the order plugins happened to load in.

| Files | What happens when two sources have the same one |
|---|---|
| `assets/*/font/**.json` | the `providers` lists are joined, highest source first |
| `assets/*/atlases/**.json` | the `sources` lists are joined |
| `assets/*/sounds.json` | the sound events are joined; an event in both: the higher source's (warned) |
| `assets/*/lang/*.json` | the keys are joined; a key in both: the higher source's (warned) |
| `assets/minecraft/items/*.json` | a `minecraft:select` on `custom_model_data` (CraftBridge's custom items, or any source's) adds its cases; the first definition of another shape (the clock pack's) becomes the fallback. Two definitions of another shape are a conflict |
| everything else | a **conflict**: logged as an error naming both sources; the higher source's file is used. The same bytes in both is not a conflict |

Every file that changes Minecraft's own assets (anything under `assets/minecraft/` but fonts,
atlases, sounds and lang) is logged at every build, per source:

```
Resource pack: changes Minecraft's own assets (2, from lilahcraft-clocks): items/clock.json, models/item/clock_00.json
```

### Checks on every build

* Every `.json` and `.mcmeta` must parse. A file that does not is **left out** with a warning
  naming the source and the file; the rest of the pack is built. A file only a lenient parser
  reads (comments, unquoted keys) is kept with a warning: Minecraft may refuse it.
* A model's `parent` and `textures`, an item definition's models, and a font's bitmap files must
  be in the pack. Missing ones are warned about (the client draws them as missing). References
  into `minecraft:` are taken as Minecraft's own assets, which are not in the pack.
* A file whose name has upper-case letters or other characters Minecraft refuses is left out
  with a warning (the client would ignore it anyway).

### Glyphs

Icons in chat, rank badges in LuckPerms prefixes and textured menu backgrounds are characters in
a font. CraftBridge hands out Private Use Area characters (U+E000 to U+F7FF) by key, so two
plugins can never pick the same one. A key keeps its character forever: assignments are stored
in `plugins/CraftBridge/pack/glyphs.yml`, written the moment a key is first asked for.

A source declares its glyphs in `glyphs.json` at its root:

```json
{
  "glyphs": {
    "hcm:coin":           { "texture": "hcm:font/coin.png", "height": 8, "ascent": 7 },
    "hcm:menu_market_bg": { "texture": "hcm:font/menu_market_bg.png", "height": 166, "ascent": 13 },
    "hcm:star":           "hcm:font/star"
  }
}
```

* The key is `namespace:name` in lower case; use your own namespace.
* `texture` is a PNG below `textures/`, as a font names it (`hcm:font/coin.png` is
  `assets/hcm/textures/font/coin.png`); `.png` may be left off. A bare string is the texture alone.
* `height` (default 8, one line of text) and `ascent` (default 7, how far it sits above the
  baseline; at most `height`) are the font's own `bitmap` settings.

CraftBridge writes the matching `bitmap` providers into `minecraft:default`, the font plain text
uses, so the characters work in titles, item names, lore, chat, the tab list, scoreboards and
LuckPerms prefixes without setting a font. Don't write font JSON for glyphs by hand. A glyph
whose texture is missing is warned about and draws as a box.

**Spaces** for moving text left and right (placing a menu background, lining up text) are fixed
and need no declaring. They follow the long-standing "negative space font" layout:

| Key | Character | Moves |
|---|---|---|
| `craftbridge:space/-1` ... `craftbridge:space/-8` | U+F801 ... U+F808 | 1 to 8 pixels left |
| `craftbridge:space/-16`, `-32`, `-64`, `-128`, `-256`, `-512`, `-1024` | U+F809 ... U+F80F | that many left |
| `craftbridge:space/1` ... `craftbridge:space/8` | U+F821 ... U+F828 | 1 to 8 pixels right |
| `craftbridge:space/16` ... `craftbridge:space/1024` | U+F829 ... U+F82F | that many right |

`space(int)` in the API builds any width from these.

`/craftbridge pack glyphs` lists every key, its character, its texture and the source that
declared it.

### The API for other plugins

Other plugins add their art with a four-method service. In their `plugin.yml`:

```yaml
softdepend: [CraftBridge]
```

and compile against the CraftBridge jar (`compileOnly`). On enable:

```java
CraftBridgePack pack = Bukkit.getServicesManager().load(CraftBridgePack.class);
if (pack != null) {                                   // CraftBridge missing: carry on without art
    pack.registerSource(this, "hcm", "pack/");        // src/main/resources/pack/assets/hcm/...
    // or: pack.registerSource(this, "hcm", getDataFolder().toPath().resolve("pack"));
    String coin = pack.glyph("hcm:coin");             // e.g. "", the same on every start
    String back = pack.space(-8);                     // move 8 pixels left
}
```

| Method | What it does |
|---|---|
| `registerSource(plugin, id, String jarRoot)` | a folder inside the plugin's jar (`"pack/"`; `""` for the jar's root, of which only `assets/`, `glyphs.json` and `pack.png` are read) |
| `registerSource(plugin, id, Path folder)` | a folder on disk, read again at every build |
| `glyph(key)` | the character for a glyph key, assigned the first time |
| `space(pixels)` | characters that move the text after them; negative is left |
| `requestRebuild()` | build again and re-send to everyone online (combined into one build on the next tick) |

* The interface is `com.dierks.craftbridge.api.CraftBridgePack`. It only grows: new methods
  come with defaults, so a plugin built against it keeps working (`API_VERSION` says which).
* The pack is built once **every** plugin has enabled (`ServerLoadEvent`), so a source
  registered in `onEnable` is in the first build. `requestRebuild()` is only for art that changes
  while the server runs.
* A source id is registered by one plugin. A source leaves the pack when its plugin is disabled.
* `glyph()` works at any time, also before the first build. A glyph needs its texture in a
  source's `glyphs.json` to draw.

### Sending the pack

The pack is offered to every Java player as they join (while their client is still configuring,
so the models are there on the first frame, or in game if that misses), with the pack id from
`resource-pack.id`, the download address, the SHA-1, `resource-pack.prompt` and
`resource-pack.required`. After `/craftbridge pack rebuild` (or `/craftbridge reload`, or a
plugin's `requestRebuild()`) everyone online is sent the new pack: the same id with a new hash,
so the client swaps it without a relog.

With the built-in web server, the address is `<public-url>/pack/<sha1>.zip`
(`resource-pack.host.public-url`, or `http://<public-address>:<port>` when that is blank). A
new pack is a new address, so no cache can hand out an old one. `/craftbridge-java.zip` keeps
working and always serves the newest.

**Nothing else may send a pack.** Leave `resource-pack`, `resource-pack-sha1`,
`resource-pack-id` and `require-resource-pack` blank in server.properties (in MC Server
Manager, the server's resource pack setting), or players get two prompts. While server.properties
also sends one, Paper can throw `IllegalStateException: Unexpected request for task finish,
current task: paper_event_handling, requested: server_resource_pack` on join: it is thrown only
for an answer to the server.properties pack that arrives while CraftBridge's pack is being
offered.

### Commands and settings

| | |
|---|---|
| `/craftbridge pack` | how the pack is sent, who loaded it, the custom item art, and (new) the sources, the vanilla changes, glyph count, conflicts and warnings |
| `/craftbridge pack rebuild` | read every source again, build, re-send to everyone online |
| `/craftbridge pack glyphs` | every glyph key and its character |
| `resource-pack.required` | `true` (the default since 0.19.0): no "decline" on the prompt. Menus drawn with glyphs only look right with the pack |
| `resource-pack.prompt` | the text on the download screen |
| `resource-pack.id` | the pack's id on clients; keep it |
| `resource-pack.priority` | source ids, highest first; decides conflicts |
| `resource-pack.host.public-url` | the whole address the built-in web server is reached at, e.g. `http://mc.lilahcraft.com:8765` |
| `resource-pack.host.*`, `resource-pack.url`, `resource-pack.enabled` | as before |

## A lid that opens, and an in-use look

Two optional models go next to a block's model, named after it. The plugin finds them in the jar
or in `plugins/CraftBridge/pack/overrides/java/` (same folders) on start and reload, and adds them
to the pack. They play while anyone has the block open, and stop when the last player closes it.

* **`<id>_lid.json`: a lid that swings open like a chest's.** Make the whole block in one
  Blockbench project, then export it twice: with the lid hidden as `<id>.json` (everything but the
  lid), and with everything but the lid hidden as `<id>_lid.json`. Leave the lid where it sits on
  the block; don't move it to the corner. The plugin draws the lid as a second piece on top of the
  block and turns it 90 degrees up on its hinge, with a chest's sound. The hinge is found from the
  lid itself: the middle of its back bottom edge (the back is the south side, since the front is
  north). A vanilla chest for scale: body from 1,0,1 to 15,10,15, lid from 1,9,1 to 15,14,15.
  Only over the invisible block (`display.invisible-block: true`, the default).
* **`<id>_active.json`: the whole block while in use**, e.g. a screen that lights up or a tool off
  its peg. The display swaps to it on open and back on close. It works with a lid too (the
  active model is then the block without the lid).

`<id>` is `linked_workbench` or `combo_chest`. Textures are named as for the block's own model.
The start log says which extras each block has ("Combo Chest: a lid that opens."). Bedrock players
do not see either: they get the plain block, and with GeyserDisplayEntity the lid may show as a
plain crafting table or barrel.

## Editing in Blockbench

1. In Blockbench: *File > Open Model* and pick `java/assets/craftbridge/models/block/linked_workbench.json`
   (or `combo_chest.json`). Blockbench finds the textures through the `assets` folder.
2. Edit the cubes and paint the textures. Keep the model inside the block's footprint and the
   front on the north side.
3. *File > Export > Export Block/Item Model* over the same file, and save the textures
   (right-click each texture > Save).
4. Check the texture names in the `.json`: our own are `craftbridge:block/<name>` (the PNG in
   `textures/block/`), Minecraft's are `minecraft:block/<name>`. Blockbench writes names without a
   namespace, or with the namespace of the pack you made the model in, and Minecraft then shows
   the purple and black "missing texture" squares. `ResourcePackAssetsTest` fails on a texture of
   ours that is missing, and on a Minecraft texture it has not been told about.

You do not have to rebuild the plugin to try a change. Copy the changed files, with the same
folders below `java/`, into `plugins/CraftBridge/pack/overrides/java/` on the server, e.g.
`plugins/CraftBridge/pack/overrides/java/assets/craftbridge/textures/block/combo_chest_top.png`,
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
first frame, when it is animated) as `textures/items/craftbridge/items/<id>.png` (an id of 43
characters or more gets a short hashed file name instead, because consoles cannot load pack paths
of 80 characters or more), an entry in
`item_texture.json`, and a mapping on its base item: `minecraft:dried_kelp` whose
`custom_model_data` string at index 0 is `craftbridge:item/<id>` becomes the Bedrock item
`craftbridge:item_<id>`. A custom item with only a model of its own and no `<id>.png` gets no
icon. When the export includes items, the Bedrock pack's manifest version follows its content,
because a Bedrock client that has cached a pack with the same version never downloads it again.
None of the Bedrock side has been tested in game.
