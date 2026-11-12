"""World construction from the screenplay: materials, build commands, .schematic/.schem import.

Any texture in assets/blocks can be a material: `wool/14`, `brick`, `quartz_block/0` ...
or a Russian alias from MATERIALS, or a built-in block name (grass, planks, glass ...).
"""
import gzip
import math
import os
import re
import struct

import numpy as np

from . import world as W

COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
          "purple", "blue", "brown", "green", "red", "black"]
COLORS_RU = ["белая", "оранжевая", "пурпурная", "голубая", "жёлтая", "лаймовая", "розовая", "серая", "светло-серая",
             "бирюзовая", "фиолетовая", "синяя", "коричневая", "зелёная", "красная", "чёрная"]
WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak"]

MATERIALS = {
    "трава": "grass", "земля": "dirt", "камень": "stone", "булыжник": "cobble", "доски": "planks",
    "еловые_доски": "f:planks/1", "берёзовые_доски": "f:planks/2", "тропические_доски": "f:planks/3",
    "акациевые_доски": "f:planks/4", "тёмные_доски": "f:planks/5", "бревно": "log", "ель": "log_spruce",
    "берёза": "log_birch", "листва": "leaves", "стекло": "glass", "песок": "sand", "кирпич": "brick",
    "каменный_кирпич": "stonebrick", "мшистый_кирпич": "f:stonebrick/1", "треснутый_кирпич": "f:stonebrick/2",
    "мшистый_булыжник": "f:cobblestone_mossy", "кварц": "f:quartz_block/0", "песчаник": "f:sandstone/0",
    "обсидиан": "f:obsidian", "золото": "gold", "алмаз": "diamond", "железо": "f:iron_block", "изумруд": "f:emerald_block",
    "лазурит": "f:lapis_block", "редстоун": "f:redstone_block", "уголь": "f:coal_block", "незерак": "f:netherrack",
    "незер_кирпич": "f:nether_brick", "светокамень": "f:glowstone", "лёд": "f:ice", "снег": "snow", "глина": "f:clay",
    "гравий": "f:gravel", "бедрок": "f:bedrock", "губка": "f:sponge", "сено": "hay", "верстак": "crafting",
    "печь": "furnace", "тнт": "tnt", "динамит": "tnt", "тыква": "pumpkin", "арбуз": "melon", "кактус": "cactus",
    "книжная_полка": "bookshelf", "полка": "bookshelf", "вода": "water", "крыша": "roof", "шерсть": "wool",
    "терракота": "f:hardened_clay", "лампа": "f:redstone_lamp_on", "нотный_блок": "f:noteblock",
    "слизь": "f:slime_block", "эндерняк": "f:end_stone", "мицелий": "f:mycelium/0",
}
for i, c in enumerate(COLORS_RU):
    MATERIALS[c + "_шерсть"] = f"f:wool/{i}"
    MATERIALS[c + "_глина"] = f"f:stained_clay/{i}"

_registered = {}


def _tex_path(rel):
    p = os.path.join(W.ASSETS, "blocks", rel)
    if os.path.isdir(p):
        fs = sorted(os.listdir(p), key=lambda s: int(s.split(".")[0]) if s.split(".")[0].isdigit() else 0)
        return os.path.join(p, fs[0]) if fs else None
    if not p.endswith(".png"):
        p += ".png"
    return p if os.path.exists(p) else None


def register_texture(rel):
    """Register assets/blocks/<rel> as an atlas tile; returns tile name or None."""
    name = "f:" + rel
    if name in W.TID:
        return name
    p = _tex_path(rel)
    if p is None:
        return None
    from PIL import Image
    im = Image.open(p).convert("RGBA")
    alpha = np.array(im)[..., 3]
    W.TILES.append(name)
    W.TID[name] = len(W.TILES) - 1
    W.REAL[name] = (os.path.relpath(p, os.path.join(W.ASSETS, "blocks")), None)
    if alpha.min() < 250:
        W.TRANSPARENT.add(name)
        W.CUTOUT_BLOCKS.add(name)
    W.BLOCKS[name] = (name,) * 3
    return name


def _wood_blocks():
    for i, wd in enumerate(["spruce", "birch", "jungle"]):
        side = register_texture(f"log/{2 + 2 * i}"); top = register_texture(f"log/{3 + 2 * i}")
        if side and top:
            W.BLOCKS["log_" + wd] = (top, side, top)
    for i, wd in enumerate(["acacia", "dark_oak"]):
        side = register_texture(f"log2/{2 * i}"); top = register_texture(f"log2/{2 * i + 1}")
        if side and top:
            W.BLOCKS["log_" + wd] = (top, side, top)


def material(name):
    """Resolve a material name to a block id usable in World.blocks."""
    if not name:
        return "stone"
    n = name.strip().lower().replace("ё", "е")
    n_yo = name.strip().lower()
    if not _registered:
        _wood_blocks(); _registered["ok"] = True
    for key in (n_yo, n):
        if key in MATERIALS:
            m = MATERIALS[key]
            if m.startswith("f:"):
                return register_texture(m[2:]) or "stone"
            return m
    for k, v in MATERIALS.items():
        if k.replace("ё", "е") == n:
            return register_texture(v[2:]) if v.startswith("f:") else v
    if n in W.BLOCKS:
        return n
    r = register_texture(name.strip())
    return r or "stone"


# ------------------------------------------------------------------- NBT
def _read_nbt(data):
    pos = [0]

    def rd(fmt):
        v = struct.unpack_from(">" + fmt, data, pos[0]); pos[0] += struct.calcsize(">" + fmt); return v[0]

    def rstr():
        n = rd("H"); s = data[pos[0]:pos[0] + n].decode("utf-8", "replace"); pos[0] += n; return s

    def payload(t):
        if t == 1: return rd("b")
        if t == 2: return rd("h")
        if t == 3: return rd("i")
        if t == 4: return rd("q")
        if t == 5: return rd("f")
        if t == 6: return rd("d")
        if t == 7:
            n = rd("i"); v = np.frombuffer(data, np.int8, n, pos[0]); pos[0] += n; return v
        if t == 8: return rstr()
        if t == 9:
            et = rd("b"); n = rd("i"); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = rd("b")
                if tt == 0:
                    return d
                k = rstr(); d[k] = payload(tt)
        if t == 11:
            n = rd("i"); v = np.frombuffer(data, ">i4", n, pos[0]); pos[0] += 4 * n; return v
        if t == 12:
            n = rd("i"); v = np.frombuffer(data, ">i8", n, pos[0]); pos[0] += 8 * n; return v
        raise ValueError(f"bad NBT tag {t}")
    t = rd("b"); rstr()
    return payload(t)


LEGACY = {1: "stone", 2: "grass", 3: "dirt", 4: "cobble", 5: "planks*", 7: "f:bedrock", 8: "water", 9: "water",
          12: "sand", 13: "f:gravel", 14: "f:gold_ore", 15: "f:iron_ore", 16: "f:coal_ore", 17: "log*", 18: "leaves",
          19: "f:sponge", 20: "glass", 21: "f:lapis_ore", 22: "f:lapis_block", 24: "f:sandstone/0", 31: "~tallgrass",
          35: "wool*", 37: "~flower_yellow", 38: "~flower_red", 41: "gold", 42: "f:iron_block", 43: "f:stone_slab/0",
          44: "f:stone_slab/0", 45: "brick", 46: "tnt", 47: "bookshelf", 48: "f:cobblestone_mossy", 49: "f:obsidian",
          50: "~torch", 53: "planks", 54: "planks", 56: "f:diamond_ore", 57: "diamond", 58: "crafting", 61: "furnace",
          62: "furnace", 64: "door*", 67: "cobble", 71: "door*", 73: "f:redstone_ore", 79: "f:ice", 80: "snow",
          81: "cactus", 82: "f:clay", 85: None, 86: "pumpkin", 87: "f:netherrack", 88: "f:soul_sand", 89: "f:glowstone",
          91: "pumpkin", 98: "stonebrick*", 102: "glass", 103: "melon", 108: "brick", 109: "stonebrick", 112: "f:nether_brick",
          114: "f:nether_brick", 121: "f:end_stone", 125: "planks*", 126: "planks*", 128: "f:sandstone/0", 133: "f:emerald_block",
          134: "f:planks/1", 135: "f:planks/2", 136: "f:planks/3", 139: "cobble", 152: "f:redstone_block",
          155: "f:quartz_block/0", 156: "f:quartz_block/0", 159: "clay*", 160: "glass", 162: "log2*", 170: "hay",
          172: "f:hardened_clay", 173: "f:coal_block", 26: "wool_red", 95: "glass", 89.5: None}


def _legacy_block(bid, meta):
    m = LEGACY.get(int(bid))
    if m is None:
        return None
    if m.startswith("~"):
        return m
    if m == "planks*":
        return register_texture(f"planks/{meta & 7 if (meta & 7) < 6 else 0}") or "planks"
    if m == "log*":
        return ["log", "log_spruce", "log_birch", "log_jungle"][meta & 3] if (meta & 3) else "log"
    if m == "log2*":
        return ["log_acacia", "log_dark_oak"][meta & 1]
    if m == "wool*":
        return register_texture(f"wool/{meta & 15}") or "wool"
    if m == "clay*":
        return register_texture(f"stained_clay/{meta & 15}") or "brick"
    if m == "stonebrick*":
        return register_texture(f"stonebrick/{meta & 3}") or "stonebrick"
    if m == "door*":
        return "door_top" if meta & 8 else "door_bot"
    if m.startswith("f:"):
        return register_texture(m[2:]) or "stone"
    return m


def _modern_block(name):
    n = name.split("[")[0].replace("minecraft:", "")
    props = name[name.find("[") + 1:-1] if "[" in name else ""
    if n in ("air", "cave_air", "void_air", "structure_void", "barrier", "light"):
        return None
    if any(s in n for s in ("fence", "button", "pressure_plate", "sign", "rail", "ladder", "lever", "carpet",
                            "redstone_wire", "tripwire", "banner", "item_frame", "pot", "bars", "chain")):
        return None
    if n in ("short_grass", "grass", "tall_grass", "fern", "large_fern"):
        return "~tallgrass"
    if n in ("poppy", "rose_bush", "red_tulip"):
        return "~flower_red"
    if n in ("dandelion", "sunflower"):
        return "~flower_yellow"
    if n in ("torch", "wall_torch", "lantern"):
        return "~torch"
    if n.endswith("_door"):
        return "door_top" if "half=upper" in props else "door_bot"
    for i, c in enumerate(COLORS):
        if n.startswith(c + "_"):
            rest = n[len(c) + 1:]
            if rest == "wool" or rest == "bed":
                return register_texture(f"wool/{i}")
            if rest in ("terracotta", "concrete", "concrete_powder", "glazed_terracotta"):
                return register_texture(f"stained_clay/{i}")
            if "glass" in rest:
                return "glass"
    for i, wd in enumerate(WOODS):
        if n.startswith(wd + "_"):
            rest = n[len(wd) + 1:]
            if rest in ("log", "wood", "stripped_log"):
                return "log" if wd == "oak" else "log_" + wd
            if rest == "leaves":
                return "leaves"
            if rest in ("planks", "stairs", "slab", "trapdoor"):
                return register_texture(f"planks/{i}")
    simple = {"stone": "stone", "cobblestone": "cobble", "mossy_cobblestone": "f:cobblestone_mossy",
              "grass_block": "grass", "dirt": "dirt", "coarse_dirt": "dirt", "podzol": "dirt", "sand": "sand",
              "gravel": "f:gravel", "glass": "glass", "glass_pane": "glass", "bricks": "brick", "brick_stairs": "brick",
              "brick_slab": "brick", "stone_bricks": "stonebrick", "stone_brick_stairs": "stonebrick",
              "stone_brick_slab": "stonebrick", "mossy_stone_bricks": "f:stonebrick/1", "cracked_stone_bricks": "f:stonebrick/2",
              "chiseled_stone_bricks": "f:stonebrick/3", "cobblestone_stairs": "cobble", "cobblestone_slab": "cobble",
              "cobblestone_wall": "cobble", "stone_slab": "f:stone_slab/0", "smooth_stone": "f:stone_slab/0",
              "smooth_stone_slab": "f:stone_slab/0", "sandstone": "f:sandstone/0", "sandstone_stairs": "f:sandstone/0",
              "sandstone_slab": "f:sandstone/0", "smooth_sandstone": "f:sandstone/0", "quartz_block": "f:quartz_block/0",
              "quartz_stairs": "f:quartz_block/0", "quartz_slab": "f:quartz_block/0", "quartz_pillar": "f:quartz_block/0",
              "bookshelf": "bookshelf", "crafting_table": "crafting", "furnace": "furnace", "chest": "planks",
              "barrel": "planks", "tnt": "tnt", "pumpkin": "pumpkin", "carved_pumpkin": "pumpkin", "jack_o_lantern": "pumpkin",
              "melon": "melon", "cactus": "cactus", "hay_block": "hay", "glowstone": "f:glowstone", "sea_lantern": "f:glowstone",
              "obsidian": "f:obsidian", "bedrock": "f:bedrock", "iron_block": "f:iron_block", "gold_block": "gold",
              "diamond_block": "diamond", "emerald_block": "f:emerald_block", "lapis_block": "f:lapis_block",
              "redstone_block": "f:redstone_block", "coal_block": "f:coal_block", "netherrack": "f:netherrack",
              "nether_bricks": "f:nether_brick", "end_stone": "f:end_stone", "snow_block": "snow", "snow": "snow",
              "ice": "f:ice", "packed_ice": "f:ice_packed", "clay": "f:clay", "terracotta": "f:hardened_clay",
              "water": "water", "lava": "f:still_lava", "sponge": "f:sponge", "slime_block": "f:slime_block",
              "note_block": "f:noteblock", "redstone_lamp": "f:redstone_lamp_on", "farmland": "dirt", "dirt_path": "dirt",
              "mycelium": "f:mycelium/0", "soul_sand": "f:soul_sand", "anvil": "f:iron_block", "cauldron": "f:iron_block"}
    if n in simple:
        v = simple[n]
        return (register_texture(v[2:]) or "stone") if v.startswith("f:") else v
    if "planks" in n or "stairs" in n or "slab" in n:
        return "planks"
    if "log" in n or "wood" in n or "stem" in n:
        return "log"
    if "leaves" in n:
        return "leaves"
    if "glass" in n:
        return "glass"
    if "ore" in n or "stone" in n or "deepslate" in n or "andesite" in n or "diorite" in n or "granite" in n:
        return "stone"
    return "stone"


def load_schematic(path):
    """Returns list of (x,y,z,block_or_~sprite) relative to the schematic origin."""
    raw = open(path, "rb").read()
    try:
        raw = gzip.decompress(raw)
    except OSError:
        pass
    root = _read_nbt(raw)
    if "Schematic" in root and isinstance(root["Schematic"], dict):
        root = root["Schematic"]
    Wd, Hh, Ln = int(root["Width"]), int(root["Height"]), int(root["Length"])
    out = []
    if "Blocks" in root and not isinstance(root["Blocks"], dict):  # MCEdit .schematic
        ids = np.asarray(root["Blocks"]).astype(np.uint8).astype(int)
        meta = np.asarray(root.get("Data", np.zeros_like(ids))).astype(np.uint8).astype(int)
        cache = {}
        for i in np.nonzero(ids)[0]:
            y, rem = divmod(int(i), Wd * Ln); z, x = divmod(rem, Wd)
            key = (ids[i], meta[i])
            if key not in cache:
                cache[key] = _legacy_block(*key)
            b = cache[key]
            if b:
                out.append((x, y, z, b))
        return out, (Wd, Hh, Ln)
    blocks = root["Blocks"] if isinstance(root.get("Blocks"), dict) else root
    pal = blocks["Palette"]
    data = bytes(np.asarray(blocks.get("Data", blocks.get("BlockData"))).astype(np.uint8))
    inv = {int(v): k for k, v in pal.items()}
    mapped = {k: _modern_block(name) for k, name in inv.items()}
    i = 0; idx = 0
    while i < len(data):
        v = 0; shift = 0
        while True:
            b = data[i]; i += 1
            v |= (b & 0x7F) << shift
            if not b & 0x80:
                break
            shift += 7
        blk = mapped.get(v)
        if blk:
            y, rem = divmod(idx, Wd * Ln); z, x = divmod(rem, Wd)
            out.append((x, y, z, blk))
        idx += 1
    return out, (Wd, Hh, Ln)


# --------------------------------------------------------------- commands
def _nums(tok):
    return [float(v) for v in tok.split(",")]


def apply(world, cmd, base_dir):
    """Apply one build command (Russian) to a World."""
    toks = cmd.split()
    if not toks:
        return
    op = toks[0].lower().replace("ё", "е")
    kv = dict(re.findall(r"(\S+?)=(\S+)", cmd))
    pos = [t for t in toks[1:] if re.match(r"^-?\d+(\.\d+)?(,-?\d+(\.\d+)?)+$", t)]
    words = [t for t in toks[1:] if "=" not in t and t not in pos]
    mat = material(words[0]) if words else "stone"
    B = world.blocks

    def put(x, y, z, b):
        if b is None:
            B.pop((x, y, z), None)
        elif isinstance(b, str) and b.startswith("~"):
            world.sprites.append((x, y, z, b[1:]))
        else:
            B[(x, y, z)] = b

    def box3(p1, p2):
        x1, y1, z1 = [int(math.floor(v)) for v in p1]; x2, y2, z2 = [int(math.floor(v)) for v in p2]
        return range(min(x1, x2), max(x1, x2) + 1), range(min(y1, y2), max(y1, y2) + 1), range(min(z1, z2), max(z1, z2) + 1)

    if op in ("куб", "заполнить", "блок"):
        p1 = _nums(pos[0]); p2 = _nums(pos[1]) if len(pos) > 1 else p1
        X, Y, Z = box3(p1, p2)
        for x in X:
            for y in Y:
                for z in Z:
                    put(x, y, z, mat)
        world.sprites = [s for s in world.sprites if not (s[0] in X and s[2] in Z and (s[1] in Y or s[1] - 1 in Y))]
    elif op in ("убрать", "очистить", "воздух"):
        X, Y, Z = box3(_nums(pos[0]), _nums(pos[1]))
        for x in X:
            for y in Y:
                for z in Z:
                    B.pop((x, y, z), None)
        world.sprites = [s for s in world.sprites if not (s[0] in X and s[1] in Y and s[2] in Z)]
    elif op == "пол":
        x1, z1 = _nums(pos[0]); x2, z2 = _nums(pos[1]); y = int(kv.get("высота", -1))
        X, _, Z = box3((x1, 0, z1), (x2, 0, z2))
        for x in X:
            for z in Z:
                put(x, y, z, mat)
        world.sprites = [s for s in world.sprites if not (s[0] in X and s[2] in Z and s[1] == y + 1)]
    elif op == "стена":
        x1, z1 = _nums(pos[0]); x2, z2 = _nums(pos[1]); h = int(kv.get("высота", 3)); y0 = int(kv.get("от", 0))
        n = int(max(abs(x2 - x1), abs(z2 - z1))) + 1
        for k in range(n):
            u = k / max(1, n - 1)
            x, z = int(round(x1 + (x2 - x1) * u)), int(round(z1 + (z2 - z1) * u))
            for y in range(y0, y0 + h):
                put(x, y, z, mat)
    elif op in ("коробка", "комната", "здание", "дом"):
        # hollow building: walls of material, floor, flat roof, door and windows
        if op == "дом" and len(pos) == 1:
            cx, cz = _nums(pos[0]); w = int(kv.get("ширина", 7)); d = int(kv.get("глубина", 6)); h = int(kv.get("высота", 4))
            x1, z1, x2, z2, y1 = int(cx - w // 2), int(cz - d // 2), int(cx - w // 2 + w - 1), int(cz - d // 2 + d - 1), 0
            y2 = h - 1
        else:
            (x1, y1, z1), (x2, y2, z2) = [[int(math.floor(v)) for v in _nums(p)] for p in pos[:2]]
            x1, x2 = min(x1, x2), max(x1, x2); y1, y2 = min(y1, y2), max(y1, y2); z1, z2 = min(z1, z2), max(z1, z2)
        roof = material(kv["крыша"]) if "крыша" in kv else mat
        floor = material(kv["пол"]) if "пол" in kv else "planks"
        windows = kv.get("окна", "да") != "нет"
        for x in range(x1, x2 + 1):
            for z in range(z1, z2 + 1):
                put(x, y1 - 1, z, floor)
                put(x, y2 + 1, z, roof)
                edge = x in (x1, x2) or z in (z1, z2)
                for y in range(y1, y2 + 1):
                    if edge:
                        corner = x in (x1, x2) and z in (z1, z2)
                        win = windows and not corner and y == y1 + 1 and (x + z) % 3 == 0 and y2 - y1 >= 2
                        put(x, y, z, "glass" if win else mat)
                    else:
                        B.pop((x, y, z), None)
        side = kv.get("дверь", "юг")
        cx, cz = (x1 + x2) // 2, (z1 + z2) // 2
        door = {"юг": (cx, z2), "север": (cx, z1), "восток": (x2, cz), "запад": (x1, cz)}.get(side)
        if door and side != "нет":
            put(door[0], y1, door[1], "door_bot"); put(door[0], y1 + 1, door[1], "door_top")
            world.marks[kv.get("имя", "дверь")] = (door[0] + 0.5 + (1.6 if side == "восток" else -1.6 if side == "запад" else 0),
                                                   door[1] + 0.5 + (1.6 if side == "юг" else -1.6 if side == "север" else 0))
        if "имя" in kv:
            world.marks[kv["имя"]] = ((x1 + x2) / 2 + 0.5, (z1 + z2) / 2 + 0.5)
    elif op == "дерево":
        x, z = [int(v) for v in _nums(pos[0])]
        world._tree(x, z, np.random.default_rng(abs(x * 31 + z)))
    elif op in ("факел", "цветок", "трава"):
        x, y, z = [int(v) for v in _nums(pos[0])] if len(_nums(pos[0])) == 3 else (*[int(v) for v in _nums(pos[0])][:1], 0, int(_nums(pos[0])[1]))
        t = {"факел": "torch", "трава": "tallgrass"}.get(op, kv.get("вид", "flower_red"))
        world.sprites.append((x, y, z, t))
    elif op == "цветы":
        x1, z1 = _nums(pos[0]); x2, z2 = _nums(pos[1]); dens = float(kv.get("плотность", 0.25))
        rng = np.random.default_rng(abs(int(x1 * 7 + z1)))
        for x in range(int(min(x1, x2)), int(max(x1, x2)) + 1):
            for z in range(int(min(z1, z2)), int(max(z1, z2)) + 1):
                if rng.random() < dens:
                    world.sprites.append((x, int(world.ground(x, z)), z, str(rng.choice(
                        ["flower_red", "flower_yellow", "flower_blue", "flower_white", "flower_tulip", "tallgrass", "tallgrass"]))))
    elif op == "метка":
        world.marks[words[0].lower()] = tuple(_nums(pos[0])[:2])
    elif op in ("схематика", "схема", "постройка"):
        f = words[0]
        path = f if os.path.isabs(f) else os.path.join(base_dir, f)
        blocks, (Wd, Hh, Ln) = load_schematic(path)
        ox, oy, oz = (_nums(pos[0]) + [0, 0])[:3] if pos else (0, 0, 0)
        if len(_nums(pos[0])) == 2:
            ox, oz = _nums(pos[0]); oy = 0
        rot = int(float(kv.get("поворот", 0))) % 360
        cx, cz = (Wd - 1) / 2, (Ln - 1) / 2
        for (x, y, z, b) in blocks:
            dx, dz = x - cx, z - cz
            for _ in range(rot // 90):
                dx, dz = -dz, dx
            put(int(round(ox + dx)), int(oy + y), int(round(oz + dz)), b)
        if "имя" in kv:
            world.marks[kv["имя"]] = (ox, oz)
    else:
        raise ValueError(f"Неизвестная команда стройки: {cmd}")
    world._gcache = {}
