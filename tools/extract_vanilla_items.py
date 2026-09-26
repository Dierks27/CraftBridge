#!/usr/bin/env python3
"""Extract Minecraft's own item definitions into the table the CraftBridge plugin bundles.

Writes, relative to the repository root, one file per Minecraft version in VERSIONS:
  src/main/resources/resourcepack/vanilla/items-<version>.json

The plugin reads these from resourcepack/vanilla/ inside its jar. When an admin gives a custom
item its own texture, the generated resource pack replaces assets/minecraft/items/<base>.json
with a minecraft:select on custom_model_data whose fallback is the vanilla definition of <base>.
That file redraws every item of that type for every player, so the fallback must be exactly
Minecraft's definition, never a guess. Hence this table comes straight out of Mojang's client
jars and is never edited by hand.

Each item line holds "definition" (items/<id>.json verbatim) and, only when the definition is a
bare minecraft:item/<x> model whose model file inherits (through models/item/) from
builtin/generated, "parent": the direct parent of models/item/<x>.json, such as
minecraft:item/handheld. The plugin gives the custom texture's model that parent, so a
textured sword is still held like a sword.

Plain Python 3, standard library only. Three modes:
  python3 tools/extract_vanilla_items.py
      download each version's client jar from Mojang, check its SHA-1, write the table(s)
  python3 tools/extract_vanilla_items.py --assets-dir 26.2=/path/to/extracted/client
      read assets/minecraft/... from an extracted jar or assets tree instead (offline use)
  python3 tools/extract_vanilla_items.py --check [--assets-dir ...]
      build the same bytes in memory and compare them with the committed files; exits 1
      on any difference (CI runs this before the Gradle build)
--version X (repeatable) replaces VERSIONS.
"""

import argparse
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile
from collections import Counter

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "resourcepack", "vanilla")
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
VERSIONS = ["26.2", "26.3"]

ITEMS = "assets/minecraft/items/"
ITEM_MODELS = "assets/minecraft/models/item/"
GENERATED = ("builtin/generated", "minecraft:builtin/generated")


def fail(message):
    sys.exit("error: " + message)


# ---- reading Mojang's files -----------------------------------------------------------------

def fetch(url, sha1=None):
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
    except OSError as e:  # urllib.error.URLError and timeouts are OSErrors
        fail("could not download %s (%s); offline? use --assets-dir VERSION=DIR" % (url, e))
    if sha1 is not None and hashlib.sha1(data).hexdigest() != sha1:
        fail("%s has SHA-1 %s, but Mojang lists %s; refusing to use it"
             % (url, hashlib.sha1(data).hexdigest(), sha1))
    return data


def download_client_jar(version):
    """The version's client jar as an in-memory zip, checked against Mojang's SHA-1."""
    manifest = json.loads(fetch(MANIFEST_URL))
    entry = next((v for v in manifest["versions"] if v["id"] == version), None)
    if entry is None:
        fail("Minecraft %s is not in Mojang's version manifest" % version)
    client = json.loads(fetch(entry["url"], entry.get("sha1")))["downloads"]["client"]
    jar = fetch(client["url"], client["sha1"])
    print("%s: client jar %s (SHA-1 verified, %d bytes)" % (version, client["sha1"], len(jar)))
    return zipfile.ZipFile(io.BytesIO(jar))


def json_files_in_jar(jar, prefix):
    """{name: parsed} for every <prefix><name>.json directly in that folder of the jar."""
    out = {}
    for path in jar.namelist():
        rest = path[len(prefix):]
        if path.startswith(prefix) and rest.endswith(".json") and "/" not in rest:
            out[rest[:-5]] = json.loads(jar.read(path).decode("utf-8"))
    return out


def json_files_in_dir(root, prefix):
    folder = os.path.join(root, *prefix.split("/"))
    if not os.path.isdir(folder):
        fail("%s does not exist; --assets-dir must contain %s" % (folder, prefix))
    out = {}
    for name in os.listdir(folder):
        if name.endswith(".json"):
            with open(os.path.join(folder, name), "rb") as f:
                out[name[:-5]] = json.loads(f.read().decode("utf-8"))
    return out


def load(version, assets_dir):
    """(definitions, item models) for one version, from a folder or from Mojang."""
    if assets_dir is None:
        jar = download_client_jar(version)
        return json_files_in_jar(jar, ITEMS), json_files_in_jar(jar, ITEM_MODELS)
    stamp = os.path.join(assets_dir, "version.json")
    if os.path.isfile(stamp):  # present in a client jar and in mcmeta's asset trees
        with open(stamp, "rb") as f:
            found = json.loads(f.read().decode("utf-8")).get("id")
        if found != version:
            fail("%s says it is Minecraft %s, not %s" % (stamp, found, version))
    print("%s: reading %s" % (version, assets_dir))
    return json_files_in_dir(assets_dir, ITEMS), json_files_in_dir(assets_dir, ITEM_MODELS)


# ---- building the table -----------------------------------------------------------------------

def plain_model(definition):
    """The model id when the definition is a bare {"type":"minecraft:model","model":...}."""
    model = definition.get("model")
    if isinstance(model, dict) and set(model) == {"type", "model"} \
            and model["type"] == "minecraft:model" and isinstance(model["model"], str):
        return model["model"]
    return None


def item_model_name(ref):
    """'minecraft:item/x' or 'item/x' -> 'x'; anything else (block models, None) -> None."""
    for prefix in ("minecraft:item/", "item/"):
        if isinstance(ref, str) and ref.startswith(prefix):
            return ref[len(prefix):]
    return None


def model_file(models, name, item_id):
    if name not in models:
        fail("items/%s.json leads to models/item/%s.json, which is missing" % (item_id, name))
    return models[name]


def generated_parent(item_id, definition, models):
    """The layer0 template (e.g. minecraft:item/handheld) the item's model inherits, or None."""
    ref = plain_model(definition)
    if ref is None or not ref.startswith("minecraft:item/"):
        return None
    parent = model_file(models, item_model_name(ref), item_id).get("parent")
    if item_model_name(parent) is None:
        return None  # no parent, or a block model (big_dripleaf, small_dripleaf)
    current, seen = parent, set()
    while item_model_name(current) is not None:
        name = item_model_name(current)
        if name in seen:
            fail("models/item/%s.json is part of a parent loop" % name)
        seen.add(name)
        current = model_file(models, name, item_id).get("parent")
    if current not in GENERATED:
        return None
    return "minecraft:item/" + item_model_name(parent)


def build_items(definitions, models):
    items = {}
    for item_id in sorted(definitions):
        entry = {"definition": definitions[item_id]}
        parent = generated_parent(item_id, definitions[item_id], models)
        if parent is not None:
            entry["parent"] = parent
        items[item_id] = entry
    return items


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def render(version, items):
    """The exact file text: a fixed header, then one item per line, sorted by id."""
    lines = ["{", '"minecraft_version": %s,' % json.dumps(version), '"items": {']
    ids = list(items)
    for n, item_id in enumerate(ids):
        comma = "," if n < len(ids) - 1 else ""
        lines.append("%s: %s%s" % (json.dumps(item_id, ensure_ascii=False), compact(items[item_id]), comma))
    lines += ["}", "}"]
    return "\n".join(lines) + "\n"


def summarize(version, items):
    refs = {i: plain_model(e["definition"]) or "" for i, e in items.items()}
    block = sum(r.startswith("minecraft:block/") for r in refs.values())
    item = sum(r.startswith("minecraft:item/") for r in refs.values())
    print("%s: %d definitions (%d plain block refs, %d plain item refs, %d other)"
          % (version, len(items), block, item, len(items) - block - item))
    for parent, count in Counter(e["parent"] for e in items.values() if "parent" in e).most_common():
        print("    %4d  parent %s" % (count, parent))
    orphans = [i for i, r in refs.items() if r.startswith("minecraft:item/") and "parent" not in items[i]]
    print("    plain item refs without a parent: %s" % (", ".join(orphans) or "none"))


# ---- write / check ---------------------------------------------------------------------------

def check(version, path, text):
    rel = os.path.relpath(path, ROOT)
    if not os.path.isfile(path):
        print("%s: MISSING %s" % (version, rel))
        return False
    with open(path, "rb") as f:
        committed = f.read()
    if committed == text.encode("utf-8"):
        print("%s: OK, %s matches Mojang's client jar" % (version, rel))
        return True
    print("%s: MISMATCH, %s differs from Mojang's client jar" % (version, rel))
    try:
        old = json.loads(committed.decode("utf-8"))
        old_items, new_items = old["items"], json.loads(text)["items"]
    except (ValueError, KeyError, TypeError) as e:
        print("    the committed file does not parse as a table (%s)" % e)
        return False
    if old.get("minecraft_version") != version:
        print("    minecraft_version is %r" % old.get("minecraft_version"))
    same = True
    for label, ids in (("added (in the jar, not committed)", sorted(set(new_items) - set(old_items))),
                       ("removed (committed, not in the jar)", sorted(set(old_items) - set(new_items))),
                       ("changed", sorted(i for i in set(new_items) & set(old_items)
                                          if new_items[i] != old_items[i]))):
        if ids:
            same = False
            print("    %d %s: %s" % (len(ids), label, ", ".join(ids)))
    if same:
        print("    the items are equal; only the layout or key order differs")
    print("    re-run without --check to regenerate it")
    return False


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--version", action="append", metavar="X",
                        help="Minecraft version to extract (repeatable; default: %s)" % " ".join(VERSIONS))
    parser.add_argument("--assets-dir", action="append", default=[], metavar="VERSION=DIR",
                        help="read VERSION from DIR/assets/minecraft/... instead of downloading (repeatable)")
    parser.add_argument("--check", action="store_true",
                        help="compare with the committed files instead of writing them; exit 1 on a difference")
    args = parser.parse_args()
    args.versions = args.version or VERSIONS
    args.dirs = {}
    for spec in args.assets_dir:
        version, sep, folder = spec.partition("=")
        if not sep or not version or not folder:
            parser.error("--assets-dir wants VERSION=DIR, got %r" % spec)
        if version not in args.versions:
            parser.error("--assets-dir names %s, which is not being extracted (add --version %s)"
                         % (version, version))
        args.dirs[version] = folder
    return args


def main():
    args = parse_args()
    ok = True
    for version in args.versions:
        definitions, models = load(version, args.dirs.get(version))
        items = build_items(definitions, models)
        summarize(version, items)
        text = render(version, items)
        path = os.path.join(OUT_DIR, "items-%s.json" % version)
        if args.check:
            ok = check(version, path, text) and ok
        else:
            os.makedirs(OUT_DIR, exist_ok=True)
            with open(path, "wb") as f:
                f.write(text.encode("utf-8"))
            print("%s: wrote %s (%d bytes)" % (version, os.path.relpath(path, ROOT), len(text.encode("utf-8"))))
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
