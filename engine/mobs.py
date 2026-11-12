"""Bedrock .geo.json mobs (from the game assets) -> parts compatible with the renderer."""
import json
import math
import os

import numpy as np
from PIL import Image

from .skin import box
from .world import ASSETS

MOB_RU = {"корова": "cow", "свинья": "pig", "курица": "chicken", "овца": "sheep", "волк": "wolf",
          "cow": "cow", "pig": "pig", "chicken": "chicken", "sheep": "sheep", "wolf": "wolf"}
QUAD_BODY = {"cow", "pig", "sheep"}  # body cube is modelled upright, rotated 90 at runtime
SOUNDS = {"cow": "mob_cow_say", "pig": "mob_pig_say", "chicken": "mob_chicken_say", "sheep": "mob_sheep_say",
          "wolf": "mob_wolf_bark"}
EYE = {"cow": 1.25, "pig": 0.8, "chicken": 0.6, "sheep": 1.2, "wolf": 0.75}


def load_mob(kind):
    d = os.path.join(ASSETS, "mobs", kind)
    gj = json.load(open(os.path.join(d, f"{kind}.geo.json")))
    geo = gj["minecraft:geometry"][0]
    tw = geo["description"].get("texture_width", 64)
    th = geo["description"].get("texture_height", 32)
    texf = [f for f in os.listdir(d) if f.startswith(kind) and f.endswith((".png", ".tga"))][0]
    img = Image.open(os.path.join(d, texf)).convert("RGBA")
    if img.size != (tw, th):
        img = img.resize((tw, th), Image.NEAREST)
    # pad texture into 64x64 so the renderer's texture size is uniform; uv scaled accordingly
    tex = np.zeros((64, 64, 4), np.uint8)
    tex[:th, :tw] = np.array(img)[:64, :64]
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
            vv = box(-(ox + w), oy, -(oz + dd), -ox, oy + h, -oz, u, v, w, h, dd, inf, tex=64.0)
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
