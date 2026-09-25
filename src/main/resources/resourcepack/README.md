# CraftBridge resource packs

The models behind `display.mode: model` (the Linked Workbench and the Combo Chest). Everything
here is packed into the plugin jar; the plugin builds the actual pack files from it at runtime.

```
java/       the Java Edition pack (zipped by the plugin on every start)
bedrock/    the Bedrock pack for Geyser (zipped by /craftbridge geyser export)
geyser/     Geyser's custom item mapping and the GeyserDisplayEntity mapping
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

## Bedrock / Geyser

`geyser/craftbridge_mappings.json` is a Geyser v2 custom item mapping: `minecraft:crafting_table`
and `minecraft:barrel` items whose `custom_model_data` string at index 0 is ours become the Bedrock
items `craftbridge:linked_workbench` and `craftbridge:combo_chest`. `bedrock/` draws them:
`attachables/` (the 3D model when held or shown by an item display), `models/entity/` (the
geometry), `textures/item_texture.json` and `textures/items/` (inventory icons).

Geyser itself does not show item display entities. With the GeyserDisplayEntity extension it
does; `geyser/geyserdisplayentity_craftbridge.yml` is its mapping for our two items.
`/craftbridge geyser export` writes all of this out and copies it into Geyser's folders.
