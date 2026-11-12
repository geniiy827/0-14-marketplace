"""Bedrock .geo.json mobs (from the game assets) -> parts compatible with the renderer."""
import json
import math
import os

import numpy as np
from PIL import Image

from .skin import box
from .world import ASSETS

MOB_RU = {"корова": "cow", "свинья": "pig", "курица": "chicken", "овца": "sheep", "волк": "wolf", "зомби": "zombie",
          "скелет": "skeleton", "крипер": "creeper", "эндермен": "enderman", "паук": "spider",
          "пещерный_паук": "cave_spider", "житель": "villager", "ведьма": "witch", "слизень": "slime",
          "голем": "iron_golem", "железный_голем": "iron_golem", "снеговик": "snow_golem", "кролик": "rabbit",
          "оцелот": "ocelot", "кот": "ocelot", "летучая_мышь": "bat", "мышь": "bat", "кальмар": "squid", "гаст": "ghast",
          "ифрит": "blaze", "блейз": "blaze", "свинозомби": "zombie_pigman", "зомби_свиночеловек": "zombie_pigman",
          "зомби_житель": "zombie_villager", "визер_скелет": "wither_skeleton", "грибная_корова": "mooshroom",
          "чешуйница": "silverfish", "магмовый_куб": "magma_cube", "вагонетка": "minecart", "лодка": "boat"}
for _v in list(MOB_RU.values()):
    MOB_RU[_v] = _v
QUAD_BODY = {"cow", "pig", "sheep", "mooshroom"}
GEO_FROM = {"cave_spider": "spider", "wither_skeleton": "skeleton", "mooshroom": "cow"}
TEX_NAME = {"zombie_pigman": "pigzombie", "iron_golem": "iron_golem", "snow_golem": "snow_golem"}
ZOMBIE_ARMS = {"zombie", "zombie_pigman", "zombie_villager"}  # body cube is modelled upright, rotated 90 at runtime
SOUNDS = {"cow": "mob_cow_say", "pig": "mob_pig_say", "chicken": "mob_chicken_say", "sheep": "mob_sheep_say",
          "wolf": "mob_wolf_bark", "zombie": "mob_zombie_say", "skeleton": "mob_skeleton_say", "creeper": "random_fuse",
          "enderman": "mob_endermen_idle", "spider": "mob_spider_say", "cave_spider": "mob_spider_say",
          "villager": "mob_villager_idle", "witch": "mob_witch_ambient", "slime": "mob_slime_small",
          "iron_golem": "mob_irongolem_hit", "rabbit": "mob_rabbit_idle", "ocelot": "mob_cat_meow", "bat": "mob_bat_idle",
          "ghast": "mob_ghast_moan", "blaze": "mob_blaze_breathe", "zombie_pigman": "mob_zombiepig_zpig",
          "zombie_villager": "mob_zombie_say", "wither_skeleton": "mob_skeleton_say", "mooshroom": "mob_cow_say",
          "silverfish": "mob_silverfish_say", "magma_cube": "mob_magmacube_small", "snow_golem": "random_pop"}
EYE = {"cow": 1.25, "pig": 0.8, "chicken": 0.6, "sheep": 1.2, "wolf": 0.75, "slime": 0.25, "magma_cube": 0.25,
       "spider": 0.55, "cave_spider": 0.4, "creeper": 1.4, "enderman": 2.6, "iron_golem": 2.4, "bat": 0.3, "rabbit": 0.4,
       "ocelot": 0.6, "silverfish": 0.15, "ghast": 2.0, "squid": 0.5, "snow_golem": 1.6, "villager": 1.55, "witch": 1.55}


def load_mob(kind, texture=None, tint=None):
    gk = GEO_FROM.get(kind, kind)
    d = os.path.join(ASSETS, "mobs", gk)
    gj = json.load(open(os.path.join(d, f"{gk}.geo.json")))
    geo = gj["minecraft:geometry"][0]
    tw = geo["description"].get("texture_width", 64)
    th = geo["description"].get("texture_height", 32)
    td = os.path.join(ASSETS, "mobs", kind)
    cands = [f for f in os.listdir(td) if f.endswith((".png", ".tga"))]
    pref = texture or TEX_NAME.get(kind, kind)
    texf = next((f for f in cands if f.split(".")[0] == pref), None) or \
        next((f for f in cands if f.startswith(pref)), None) or \
        next((f for f in cands if "armor" not in f and "saddle" not in f), cands[0])
    img = Image.open(os.path.join(td, texf)).convert("RGBA")
    if img.size != (tw, th):
        img = img.resize((tw, th), Image.NEAREST)
    if tint:  # recolour: keep shading (luminance), replace hue with the tint colour
        h = tint.lstrip("#")
        col = np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float) / 255
        a = np.array(img).astype(float)
        lum = (a[..., 0] * 0.3 + a[..., 1] * 0.59 + a[..., 2] * 0.11) / 255
        lum = lum / max(1e-3, np.percentile(lum[a[..., 3] > 0], 90) if (a[..., 3] > 0).any() else 1)
        a[..., :3] = np.clip(lum[..., None] * col * 255, 0, 255)
        img = Image.fromarray(a.astype(np.uint8))
    TS = max(64, tw, th)  # square padded texture; uv divided by TS
    tex = np.zeros((TS, TS, 4), np.uint8)
    tex[:th, :tw] = np.array(img)
    parts = {}
    for b in geo["bones"]:
        if b.get("neverRender"):
            continue
        piv = b.get("pivot", [0, 0, 0])
        # rotate the bedrock model 180deg about Y so it faces +Z like the players
        pivot = (-piv[0], piv[1], -piv[2])
        verts = []
        for c in b.get("cubes", []):
            ox, oy, oz = c["origin"]
            w, h, dd = c["size"]
            u, v = c["uv"]
            inf = c.get("inflate", 0.0)
            vv = box(-(ox + w), oy, -(oz + dd), -ox, oy + h, -oz, u, v, w, h, dd, inf, tex=float(TS))
            verts.append(vv)
        if not verts:
            continue
        v = np.concatenate(verts)
        rest = [math.radians(a) for a in (b.get("rotation") or [0, 0, 0])]
        rest = (-rest[0], rest[1], -rest[2])
        if b["name"] == "body" and kind in QUAD_BODY:
            rest = (math.pi / 2, 0, 0)
        parts[b["name"]] = dict(pivot=pivot, verts=v, rest=rest, parent=b.get("parent"))
    if kind == "sheep":
        # draw wool shell slightly inflated using the same texture region (sheep.tga holds the wool too)
        pass
    return parts, tex
