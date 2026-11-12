"""Procedural voxel world: textures (generated, original), terrain presets, meshing with AO."""
import math
import numpy as np

TILE = 16
ATLAS_N = 16  # 16x16 tiles -> 256px atlas
import os

TILES = ["grass_top", "grass_side", "dirt", "stone", "log_side", "log_top", "leaves", "planks",
         "cobble", "water", "flower_red", "flower_yellow", "tallgrass", "glass", "sand", "roof",
         "door_top", "door_bot", "brick", "snow", "lava", "farmland", "wheat", "torch",
         "hay_side", "hay_top", "crafting_top", "crafting_side", "tnt_side", "tnt_top", "pumpkin_side", "pumpkin_face",
         "pumpkin_top", "bookshelf", "furnace_front", "furnace_side", "furnace_top", "melon_side", "melon_top",
         "cactus_side", "cactus_top", "flower_blue", "flower_white", "flower_pink", "rose_bush", "spruce_planks",
         "stonebrick", "wool_white", "wool_red", "wool_pink", "gold", "diamond", "chest_front", "flower_tulip"]
TID = {n: i for i, n in enumerate(TILES)}

# block -> (top, side, bottom)
BLOCKS = {
    "grass": ("grass_top", "grass_side", "dirt"),
    "dirt": ("dirt",) * 3, "stone": ("stone",) * 3, "log": ("log_top", "log_side", "log_top"),
    "leaves": ("leaves",) * 3, "planks": ("planks",) * 3, "cobble": ("cobble",) * 3,
    "glass": ("glass",) * 3, "sand": ("sand",) * 3, "roof": ("roof",) * 3, "brick": ("brick",) * 3,
    "door_top": ("planks", "door_top", "planks"), "door_bot": ("planks", "door_bot", "planks"),
    "water": ("water",) * 3, "snow": ("snow",) * 3,
    "hay": ("hay_top", "hay_side", "hay_top"), "crafting": ("crafting_top", "crafting_side", "planks"),
    "tnt": ("tnt_top", "tnt_side", "tnt_top"), "pumpkin": ("pumpkin_top", "pumpkin_face", "pumpkin_top"),
    "bookshelf": ("planks", "bookshelf", "planks"), "furnace": ("furnace_top", "furnace_front", "furnace_top"),
    "melon": ("melon_top", "melon_side", "melon_top"), "cactus": ("cactus_top", "cactus_side", "cactus_top"),
    "spruce": ("spruce_planks",) * 3, "stonebrick": ("stonebrick",) * 3, "wool": ("wool_white",) * 3,
    "wool_red": ("wool_red",) * 3, "wool_pink": ("wool_pink",) * 3, "gold": ("gold",) * 3, "diamond": ("diamond",) * 3,
}
BLOCK_RU = {"сено": "hay", "верстак": "crafting", "динамит": "tnt", "тнт": "tnt", "тыква": "pumpkin",
            "полка": "bookshelf", "печь": "furnace", "арбуз": "melon", "кактус": "cactus", "доски": "planks",
            "бревно": "log", "камень": "stone", "булыжник": "cobble", "кирпич": "brick", "стекло": "glass",
            "листва": "leaves", "шерсть": "wool", "золото": "gold", "алмаз": "diamond", "песок": "sand",
            "земля": "dirt", "розовый": "wool_pink", "красный": "wool_red", "ель": "spruce", "кирпичи": "stonebrick"}
TRANSPARENT = {"leaves", "glass", "water", "cactus"}
PANES = {"f:iron_bars", "f:glass_pane"}
CUTOUT_BLOCKS = {"leaves", "glass", "door_top", "door_bot", "cactus"}
SPRITES = {"flower_red", "flower_yellow", "tallgrass", "torch", "wheat", "flower_blue", "flower_white", "flower_pink",
           "rose_bush", "flower_tulip"}

REAL = {  # tile -> (file in assets/blocks, tint)
    "grass_top": ("grass/2.png", None), "grass_side": ("grass/3.png", None), "dirt": ("dirt/0.png", None),
    "stone": ("stone/0.png", None), "log_side": ("log/0.png", None), "log_top": ("log/1.png", None),
    "leaves": ("leaves/0.png", (0.42, 0.68, 0.24)), "planks": ("planks/0.png", None), "cobble": ("cobblestone.png", None),
    "water": ("still_water.png", None), "flower_red": ("flower2/0.png", None), "flower_yellow": ("flower1.png", None),
    "tallgrass": ("tallgrass/3.png", None), "glass": ("glass.png", None), "sand": ("sand/0.png", None),
    "roof": ("planks/5.png", None), "door_top": ("door/1.png", None), "door_bot": ("door/0.png", None),
    "brick": ("brick.png", None), "snow": ("snow.png", None), "wheat": ("wheat/7.png", None), "torch": ("torch_on.png", None),
    "hay_side": ("hayblock/1.png", None), "hay_top": ("hayblock/0.png", None), "crafting_top": ("crafting_table/0.png", None),
    "crafting_side": ("crafting_table/1.png", None), "tnt_side": ("tnt/0.png", None), "tnt_top": ("tnt/1.png", None),
    "pumpkin_side": ("pumpkin/1.png", None), "pumpkin_face": ("pumpkin/3.png", None), "pumpkin_top": ("pumpkin/0.png", None),
    "bookshelf": ("bookshelf.png", None), "furnace_front": ("furnace/0.png", None), "furnace_side": ("furnace/2.png", None),
    "furnace_top": ("furnace/3.png", None), "melon_side": ("melon/0.png", None), "melon_top": ("melon/1.png", None),
    "cactus_side": ("cactus/1.png", None), "cactus_top": ("cactus/2.png", None), "flower_blue": ("flower2/1.png", None),
    "flower_white": ("flower2/8.png", None), "flower_pink": ("flower2/2.png", None), "rose_bush": ("double_plant_bottom/4.png", None),
    "spruce_planks": ("planks/1.png", None), "stonebrick": ("stonebrick/0.png", None), "wool_white": ("wool/0.png", None),
    "wool_red": ("wool/14.png", None), "wool_pink": ("wool/6.png", None), "gold": ("gold_block.png", None),
    "diamond": ("diamond_block.png", None), "flower_tulip": ("flower2/4.png", None),
}
ASSETS = __import__("os").path.join(__import__("os").path.dirname(__import__("os").path.dirname(__import__("os").path.abspath(__file__))), "assets")


def _noise(rng, base, var, size=TILE):
    n = rng.normal(0, 1, (size, size, 1))
    return np.clip(np.array(base, float) + n * var, 0, 255)


def make_atlas(seed=1):
    rng = np.random.default_rng(seed)
    A = np.zeros((TILE * ATLAS_N, TILE * ATLAS_N, 4), np.uint8)
    T = {}
    g = _noise(rng, (96, 160, 60), 14)
    T["grass_top"] = g
    d = _noise(rng, (134, 96, 67), 16)
    T["dirt"] = d
    gs = d.copy()
    edge = 3 + (rng.random(TILE) * 3).astype(int)
    for x in range(TILE):
        gs[:edge[x], x] = g[:edge[x], x] * 0.92
    T["grass_side"] = gs
    st = _noise(rng, (125, 125, 125), 10)
    for _ in range(10):
        x, y = rng.integers(0, 15, 2)
        st[y:y + 2, x:x + 2] *= 0.8
    T["stone"] = st
    ls = _noise(rng, (102, 81, 51), 8)
    for x in range(0, TILE, 4):
        ls[:, x] *= 0.75
    T["log_side"] = ls
    lt = _noise(rng, (160, 130, 80), 6)
    yy, xx = np.mgrid[0:TILE, 0:TILE]
    r = np.maximum(abs(yy - 7.5), abs(xx - 7.5))
    lt[(r.astype(int) % 3) == 0] *= 0.8
    lt[r > 6.5] = ls[r > 6.5]
    T["log_top"] = lt
    lv = _noise(rng, (60, 130, 40), 22)
    T["leaves"] = lv
    pl = _noise(rng, (176, 140, 88), 7)
    for y in (3, 7, 11, 15):
        pl[y] *= 0.72
    for y0, xs in ((0, 5), (4, 12), (8, 3), (12, 9)):
        pl[y0:y0 + 3, xs] *= 0.78
    T["planks"] = pl
    cb = _noise(rng, (120, 120, 120), 18)
    cb[(xx % 5 == 0) | (yy % 4 == 0)] *= 0.6
    T["cobble"] = cb
    T["water"] = _noise(rng, (50, 90, 200), 6)
    T["sand"] = _noise(rng, (220, 210, 160), 8)
    rf = _noise(rng, (150, 60, 50), 8)
    for y in range(0, TILE, 4):
        rf[y] *= 0.7
    T["roof"] = rf
    br = _noise(rng, (150, 80, 65), 8)
    br[(yy % 4 == 3) | (((xx + (yy // 4) * 4) % 8) == 0)] = (200, 190, 180)
    T["brick"] = br
    T["snow"] = _noise(rng, (240, 245, 250), 4)
    T["lava"] = _noise(rng, (230, 110, 20), 25)
    T["farmland"] = _noise(rng, (90, 60, 40), 8)
    T["glass"] = np.zeros((TILE, TILE, 3)) + (200, 230, 240)
    dt = pl.copy()
    dt[2:7, 3:7] = (180, 220, 235); dt[2:7, 9:13] = (180, 220, 235)
    T["door_top"] = dt
    db = pl.copy(); db[8, 11:13] = (60, 60, 60)
    T["door_bot"] = db
    for name, img in T.items():
        i = TID[name]
        ty, tx = divmod(i, ATLAS_N)
        A[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE, :3] = img
        A[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE, 3] = 255

    def put_alpha(name, mask, colors):
        i = TID[name]; ty, tx = divmod(i, ATLAS_N)
        tile = np.zeros((TILE, TILE, 4), np.uint8)
        for (m, c) in zip(mask, colors):
            tile[m] = (*c, 255)
        A[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE] = tile

    # leaves holes
    i = TID["leaves"]; ty, tx = divmod(i, ATLAS_N)
    holes = rng.random((TILE, TILE)) < 0.18
    A[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE, 3][holes] = 0
    # glass frame
    i = TID["glass"]; ty, tx = divmod(i, ATLAS_N)
    gl = A[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE]
    gl[..., 3] = 60
    gl[[0, -1], :, 3] = 255; gl[:, [0, -1], 3] = 255
    gl[[0, -1], :, :3] = 230; gl[:, [0, -1], :3] = 230
    i = TID["water"]; ty, tx = divmod(i, ATLAS_N)
    A[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE, 3] = 170
    # sprites
    stem = (yy >= 8) & (abs(xx - 7.5) < 1)
    head = (abs(yy - 5.5) + abs(xx - 7.5) < 3)
    put_alpha("flower_red", [stem, head, (abs(yy - 5.5) + abs(xx - 7.5) < 1)],
              [(50, 120, 40), (210, 40, 40), (250, 220, 60)])
    put_alpha("flower_yellow", [stem, head, (abs(yy - 5.5) + abs(xx - 7.5) < 1)],
              [(50, 120, 40), (240, 210, 40), (255, 250, 200)])
    blades = np.zeros((TILE, TILE), bool)
    for x in range(1, 15, 2):
        h = rng.integers(6, 14)
        blades[TILE - h:, x] = True
    put_alpha("tallgrass", [blades], [(80, 150, 50)])
    t_stick = (yy >= 6) & (abs(xx - 7.5) < 1)
    t_fl = (yy >= 3) & (yy < 6) & (abs(xx - 7.5) < 1)
    put_alpha("torch", [t_stick, t_fl], [(120, 90, 50), (255, 210, 80)])
    wh = np.zeros((TILE, TILE), bool)
    for x in range(1, 15, 3):
        wh[4:, x] = True
    put_alpha("wheat", [wh], [(200, 180, 70)])
    from PIL import Image
    bdir = os.path.join(ASSETS, "blocks")
    if os.path.isdir(bdir):
        for name, (f, tint) in REAL.items():
            p = os.path.join(bdir, f)
            if not os.path.exists(p) or name not in TID:
                continue
            im = Image.open(p).convert("RGBA")
            im = im.crop((0, 0, im.size[0], im.size[0])).resize((TILE, TILE), Image.NEAREST)
            a = np.array(im).astype(float)
            if tint:
                a[..., :3] *= np.array(tint)
            if name == "water":
                a[..., 3] = 175
            ty, tx = divmod(TID[name], ATLAS_N)
            A[ty * TILE:(ty + 1) * TILE, tx * TILE:(tx + 1) * TILE] = np.clip(a, 0, 255).astype(np.uint8)
    return A


def tile_uv(name):
    i = TID[name]
    ty, tx = divmod(i, ATLAS_N)
    s = 1.0 / ATLAS_N
    e = 0.0005
    return tx * s + e, ty * s + e, (tx + 1) * s - e, (ty + 1) * s - e


# ------------------------------------------------------------------ terrain
def value_noise(seed, x, z, scale):
    def h(ix, iz):
        n = (ix * 374761393 + iz * 668265263 + seed * 1442695041) & 0xFFFFFFFF
        n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
        return (n & 0xFFFF) / 65535.0
    fx, fz = x / scale, z / scale
    ix, iz = math.floor(fx), math.floor(fz)
    tx, tz = fx - ix, fz - iz
    tx, tz = tx * tx * (3 - 2 * tx), tz * tz * (3 - 2 * tz)
    a = h(ix, iz) * (1 - tx) + h(ix + 1, iz) * tx
    b = h(ix, iz + 1) * (1 - tx) + h(ix + 1, iz + 1) * tx
    return a * (1 - tz) + b * tz


class World:
    def __init__(self, preset="plains", seed=3, radius=36):
        self.blocks = {}
        self.sprites = []  # (x,y,z,tile)
        self.marks = {"центр": (0.0, 0.0)}
        self.seed = seed
        self.R = radius
        self.preset = preset
        rng = np.random.default_rng(seed)
        self.height = {}
        snowy = preset in ("winter", "зима")
        desert = preset in ("desert", "пустыня")
        forest = preset in ("forest", "лес")
        empty = preset in ("flat", "пусто", "empty")
        self.house_origin = (999, 999)
        if preset in ("city", "город"):
            self._city(rng, radius)
            return
        for x in range(-radius, radius):
            for z in range(-radius, radius):
                d = math.hypot(x, z)
                hill = value_noise(seed, x, z, 11) * 0.65 + value_noise(seed + 7, x, z, 5) * 0.35
                w = min(1.0, max(0.0, (d - 9) / 10))
                h = int(round(w * (hill * 9 - 1.5)))
                h = max(h, -1) if d > 12 else 0
                self.height[(x, z)] = h
                top = "sand" if desert else ("snow" if snowy else "grass")
                for y in range(h - 3, h):
                    self.blocks[(x, y, z)] = top if y == h - 1 else ("sand" if desert else "dirt")
        if not forest and not empty:
            self._house(-3, -12)
        if not desert and not forest and not empty:
            self._pond(11, -5, rng)
        # trees & flora
        tries = 0
        trees = 0
        ntrees = 6 if desert else (120 if forest else (14 if empty else 30))
        clear = 8 if forest else (24 if empty else 13)
        while trees < ntrees and tries < 2000:
            tries += 1
            x, z = int(rng.integers(-radius + 3, radius - 3)), int(rng.integers(-radius + 3, radius - 3))
            if math.hypot(x, z) < clear or self._near_house(x, z, 4) or self._in_pond(x, z, 3):
                continue
            if desert:
                self._cactus(x, z, rng)
            else:
                self._tree(x, z, rng)
            trees += 1
        if not desert:
            for _ in range(500):
                x, z = int(rng.integers(-radius, radius)), int(rng.integers(-radius, radius))
                if self._near_house(x, z, 1) or self._in_pond(x, z, 1):
                    continue
                y = self.height[(x, z)]
                if (x, y, z) in self.blocks or (x, y - 1, z) not in self.blocks:
                    continue
                if self.blocks[(x, y - 1, z)] not in ("grass",):
                    continue
                if abs(x) < 4 and abs(z) < 3:
                    continue
                r = rng.random()
                t = "tallgrass" if r < 0.68 else str(rng.choice(["flower_red", "flower_yellow", "flower_blue",
                                                                "flower_white", "flower_tulip", "rose_bush"]))
                self.sprites.append((x, y, z, t))
        if hasattr(self, "house_door"):
            self.marks["дом"] = (self.house_door[0] + 0.5, self.house_door[1] + 2.0)
        else:
            self.marks["дом"] = (0.0, -6.0)
        self.marks["лес"] = (0.0, -9.0)
        self.marks["пруд"] = (self.pond[0] - 4.0, self.pond[1] + 0.5) if hasattr(self, "pond") else (8.0, 0.0)

    # ------------------------------------------------------------------ city
    def _city(self, rng, R):
        self.marks = {"центр": (0.0, 0.0), "площадь": (0.0, 3.0)}
        for x in range(-R, R):
            for z in range(-R, R):
                d = math.hypot(x, z)
                h = 0
                if d > 30:
                    h = int(round((d - 30) / 2.5 * value_noise(self.seed, x, z, 7) * 2))
                self.height[(x, z)] = h
                for y in range(h - 3, h):
                    self.blocks[(x, y, z)] = "grass" if (y == h - 1 and d > 27) else "dirt"
                if d <= 27:
                    self.blocks[(x, -1, z)] = "cobble"
        # plaza
        for x in range(-8, 9):
            for z in range(-8, 9):
                if abs(x) + abs(z) <= 12:
                    self.blocks[(x, -1, z)] = "stonebrick"
        # fountain
        for x in range(-2, 3):
            for z in range(-2, 3):
                edge = abs(x) == 2 or abs(z) == 2
                self.blocks[(x, 0, z)] = "stonebrick" if edge else "water"
                if not edge:
                    self.blocks[(x, -1, z)] = "stonebrick"
        self.blocks[(0, 1, 0)] = "stonebrick"; self.blocks[(0, 2, 0)] = "water"
        mats = ["brick", "stonebrick", "planks", "wool", "spruce", "brick", "wool_pink"]
        lots = []
        for i in range(-2, 3):
            for j in range(-2, 3):
                if i == 0 and j == 0:
                    continue
                lots.append((i * 13, j * 13))
        self.warehouse = (13, 0)
        for (cx, cz) in lots:
            if (cx, cz) == self.warehouse:
                self._warehouse(cx, cz)
                continue
            if (cx, cz) == (0, 13) or (cx, cz) == (0, 26):  # keep the south avenue open (exit)
                continue
            w = int(rng.integers(6, 10)); dpt = int(rng.integers(6, 10)); hgt = int(rng.integers(5, 13))
            mat = mats[int(rng.integers(0, len(mats)))]
            x0, z0 = cx - w // 2, cz - dpt // 2
            for x in range(x0, x0 + w):
                for z in range(z0, z0 + dpt):
                    edge = x in (x0, x0 + w - 1) or z in (z0, z0 + dpt - 1)
                    corner = x in (x0, x0 + w - 1) and z in (z0, z0 + dpt - 1)
                    for y in range(0, hgt):
                        if not edge:
                            continue
                        win = (not corner) and y % 3 == 1 and (x + z) % 2 == 0 and y < hgt - 1
                        self.blocks[(x, y, z)] = "glass" if win else ("log" if corner and mat == "planks" else mat)
                    self.blocks[(x, hgt, z)] = "stonebrick" if mat != "planks" else "roof"
            # door facing the plaza
            dx = 0 if abs(cz) >= abs(cx) else (-1 if cx > 0 else 1)
            dz = 0 if dx != 0 else (-1 if cz > 0 else 1)
            if dx:
                ddx = x0 if dx < 0 else x0 + w - 1; ddz = cz
            else:
                ddx = cx; ddz = z0 if dz < 0 else z0 + dpt - 1
            self.blocks[(ddx, 0, ddz)] = "door_bot"; self.blocks[(ddx, 1, ddz)] = "door_top"
            self.sprites.append((ddx + dx, 1, ddz + dz, "torch"))
        # trees in planters and flowers around the plaza
        for (px, pz) in [(-7, -7), (7, -7), (-7, 7), (7, 7)]:
            self.blocks[(px, -1, pz)] = "grass"
            self._tree(px, pz, rng)
            for (fx, fz) in ((px + 1, pz), (px - 1, pz), (px, pz + 1), (px, pz - 1)):
                self.blocks[(fx, -1, fz)] = "grass"
                self.sprites.append((fx, 0, fz, str(rng.choice(["flower_red", "flower_pink", "flower_tulip", "flower_white"]))))
        # benches
        for bx in (-4, 4):
            self.blocks[(bx, 0, 6)] = "spruce"
        self.marks["выход"] = (0.0, 24.0)
        self.marks["ворота"] = (0.0, 24.0)
        self.marks["склад"] = (12.0, 0.5)
        self.marks["переулок"] = (-6.5, -6.5)
        self.marks["фонтан"] = (0.0, 3.5)

    def _warehouse(self, cx, cz):
        x0, z0 = cx - 5, cz - 5
        for x in range(x0, x0 + 11):
            for z in range(z0, z0 + 11):
                self.blocks[(x, -1, z)] = "planks"
                edge = x in (x0, x0 + 10) or z in (z0, z0 + 10)
                if edge and not (x == x0 and abs(z - cz) <= 1):  # opening towards the plaza
                    self.blocks[(x, 0, z)] = "log" if (x + z) % 4 == 0 else "spruce"
        for (x, z, b) in [(cx + 3, cz - 3, "hay"), (cx + 4, cz - 3, "hay"), (cx + 3, cz - 2, "hay"), (cx + 4, cz - 4, "hay"),
                          (cx + 3, cz + 3, "crafting"), (cx + 4, cz + 3, "furnace"), (cx + 1, cz + 4, "pumpkin"),
                          (cx + 4, cz + 1, "melon"), (cx + 4, cz, "melon")]:
            self.blocks[(x, 0, z)] = b
        self.blocks[(cx + 4, 1, cz - 3)] = "hay"

    def place(self, block, x, y, z):
        self.blocks[(int(x), int(y), int(z))] = block

    def ground(self, x, z):
        """terrain height, raised if something was built on top (stand on platforms)"""
        k = (int(math.floor(x)), int(math.floor(z)))
        gc = getattr(self, "_gcache", None)
        if gc is None:
            gc = self._gcache = {}
        if k in gc:
            return gc[k]
        g = self._terrain(x, z)
        gi = int(g)
        n = 0
        while (k[0], gi, k[1]) in self.blocks and self.blocks[(k[0], gi, k[1])] not in ("water",) and n < 12:
            gi += 1; n += 1
        if n > 4:  # tall column = wall/tree, not a platform: keep terrain level
            gi = int(g)
        gc[k] = float(gi)
        return gc[k]

    def _terrain(self, x, z):
        fl = getattr(self, "_floor", None)
        if fl:
            k = (int(math.floor(x)), int(math.floor(z)))
            if k in fl:
                return float(fl[k])
        if self.preset in ("city", "город"):
            return float(self.height.get((int(math.floor(x)), int(math.floor(z))), 0)) if math.hypot(x, z) > 29 else 0.0
        return float(self.height.get((int(math.floor(x)), int(math.floor(z))), 0)) \
            if math.hypot(x, z) > 11 else 0.0

    def _near_house(self, x, z, m):
        hx, hz = self.house_origin
        return hx - m <= x <= hx + 7 + m and hz - m <= z <= hz + 6 + m

    def _in_pond(self, x, z, m):
        if not hasattr(self, "pond"):
            return False
        px, pz = self.pond
        return math.hypot((x - px) / 1.3, z - pz) < 3.5 + m

    def _set(self, x, y, z, b):
        self.blocks[(x, y, z)] = b

    def _house(self, ox, oz):
        self.house_origin = (ox, oz)
        W, D, H = 7, 6, 4
        for x in range(ox - 1, ox + W + 1):
            for z in range(oz - 1, oz + D + 1):
                self.height[(x, z)] = 0
                for y in range(0, 12):
                    self.blocks.pop((x, y, z), None)
                for y in range(-3, 0):
                    self._set(x, y, z, "grass" if y == -1 else "dirt")
        for x in range(ox, ox + W):
            for z in range(oz, oz + D):
                self._set(x, -1, z, "cobble")
                for y in range(0, H):
                    edge = x in (ox, ox + W - 1) or z in (oz, oz + D - 1)
                    corner = x in (ox, ox + W - 1) and z in (oz, oz + D - 1)
                    if edge:
                        self._set(x, y, z, "log" if corner else "planks")
        door_x = ox + W // 2
        fz = oz + D - 1
        self.house_door = (door_x, fz)
        self._set(door_x, 0, fz, "door_bot")
        self._set(door_x, 1, fz, "door_top")
        for wx in (ox + 1, ox + W - 2):
            self._set(wx, 1, fz, "glass")
            self._set(wx, 2, fz, "glass")
        for wz in (oz + 2, oz + 3):
            self._set(ox, 1, wz, "glass"); self._set(ox + W - 1, 1, wz, "glass")
        # gable roof sloping along z
        k = 0
        while True:
            z1, z2 = oz - 1 + k, oz + D - k
            if z1 > z2:
                break
            for x in range(ox - 1, ox + W + 1):
                self._set(x, H + k - 1, z1, "roof")
                self._set(x, H + k - 1, z2, "roof")
            for z in range(z1 + 1, z2):
                for x in (ox, ox + W - 1):
                    self._set(x, H + k - 1, z, "planks")
            k += 1
        self.sprites.append((door_x - 1, 1, fz + 1, "torch"))
        self.sprites.append((door_x + 1, 1, fz + 1, "torch"))

    def _pond(self, px, pz, rng):
        self.pond = (px, pz)
        for x in range(px - 6, px + 7):
            for z in range(pz - 5, pz + 6):
                d = math.hypot((x - px) / 1.3, z - pz)
                if d < 4.3:
                    self.height[(x, z)] = 0
                    for y in range(-3, 6):
                        self.blocks.pop((x, y, z), None)
                    self._set(x, -3, z, "sand")
                    self._set(x, -2, z, "sand" if d > 3.3 else "dirt")
                    if d <= 3.3:
                        self._set(x, -1, z, "water")
                    else:
                        self._set(x, -1, z, "sand")
                        self._set(x, -2, z, "dirt")

    def _tree(self, x, z, rng):
        y0 = self.height[(x, z)]
        h = int(rng.integers(4, 7))
        for y in range(y0, y0 + h):
            self._set(x, y, z, "log")
        top = y0 + h
        for dy in range(-2, 2):
            r = 2 if dy < 0 else 1
            for dx in range(-r, r + 1):
                for dz in range(-r, r + 1):
                    if abs(dx) == r and abs(dz) == r and (dy == 1 or rng.random() < 0.5):
                        continue
                    p = (x + dx, top + dy, z + dz)
                    if p not in self.blocks:
                        self._set(*p, "leaves")
        self._set(x, top, z, "leaves")

    def _cactus(self, x, z, rng):
        y0 = self.height[(x, z)]
        for y in range(y0, y0 + int(rng.integers(2, 4))):
            self._set(x, y, z, "cactus")

    # -------------------------------------------------------------- meshing
    def build_mesh(self):
        """Returns (opaque verts, water verts) float32 (N,9): pos3 normal3 uv2 ao1."""
        B = self.blocks
        solid = lambda p: p in B and B[p] not in TRANSPARENT
        dirs = [((0, 1, 0), 0), ((0, -1, 0), 2), ((1, 0, 0), 1), ((-1, 0, 0), 1), ((0, 0, 1), 1), ((0, 0, -1), 1)]
        opaque, water, cut = [], [], []
        CUT = CUTOUT_BLOCKS
        for (x, y, z), b in B.items():
            if b in PANES:
                cut.extend(self._pane(x, y, z, b))
                continue
            tiles = BLOCKS.get(b) or (b,) * 3
            for (dx, dy, dz), ti in dirs:
                nb = (x + dx, y + dy, z + dz)
                if b == "water":
                    if nb in B and (B[nb] == "water" or solid(nb)):
                        continue
                elif b in TRANSPARENT:
                    if nb in B and (solid(nb) or B[nb] == b):
                        continue
                elif solid(nb):
                    continue
                quad = self._face(x, y, z, (dx, dy, dz), tiles[ti], b)
                (water if b == "water" else (cut if b in CUT else opaque)).extend(quad)
        for (x, y, z, t) in self.sprites:
            cut.extend(self._sprite(x, y, z, t))
        self.cutout = np.array(cut, np.float32) if cut else np.zeros((0, 9), np.float32)
        return np.array(opaque, np.float32), np.array(water, np.float32) if water else np.zeros((0, 9), np.float32)

    def _occ(self, p):
        b = self.blocks.get(p)
        return 1 if b is not None and b not in ("water", "glass") and b not in PANES else 0

    def _face(self, x, y, z, n, tile, b):
        dx, dy, dz = n
        # axis setup: corners in face plane
        if dy != 0:
            yy = y + (1 if dy > 0 else 0)
            cs = [(x, yy, z), (x + 1, yy, z), (x + 1, yy, z + 1), (x, yy, z + 1)]
            if dy < 0:
                cs = cs[::-1]
        elif dx != 0:
            xx = x + (1 if dx > 0 else 0)
            cs = [(xx, y + 1, z + 1), (xx, y + 1, z), (xx, y, z), (xx, y, z + 1)] if dx > 0 else \
                 [(xx, y + 1, z), (xx, y + 1, z + 1), (xx, y, z + 1), (xx, y, z)]
        else:
            zz = z + (1 if dz > 0 else 0)
            cs = [(x, y + 1, zz), (x + 1, y + 1, zz), (x + 1, y, zz), (x, y, zz)] if dz > 0 else \
                 [(x + 1, y + 1, zz), (x, y + 1, zz), (x, y, zz), (x + 1, y, zz)]
        u0, v0, u1, v1 = tile_uv(tile)
        uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
        if dy != 0:
            uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
        # ambient occlusion
        aos = []
        for c in cs:
            # neighbours of corner in the layer outside the face
            ox, oy, oz = x + dx, y + dy, z + dz
            sx = 1 if c[0] > x + 0.5 else -1
            sy = 1 if c[1] > y + 0.5 else -1
            sz = 1 if c[2] > z + 0.5 else -1
            if dy != 0:
                s1 = self._occ((ox + sx, oy, oz)); s2 = self._occ((ox, oy, oz + sz)); cc = self._occ((ox + sx, oy, oz + sz))
            elif dx != 0:
                s1 = self._occ((ox, oy + sy, oz)); s2 = self._occ((ox, oy, oz + sz)); cc = self._occ((ox, oy + sy, oz + sz))
            else:
                s1 = self._occ((ox + sx, oy, oz)); s2 = self._occ((ox, oy + sy, oz)); cc = self._occ((ox + sx, oy + sy, oz))
            lvl = 0 if (s1 and s2) else 3 - (s1 + s2 + cc)
            aos.append(0.55 + 0.15 * lvl)
        wy = -0.12 if b == "water" and dy > 0 else 0.0
        out = []
        for k in (0, 3, 2, 0, 2, 1):
            p = cs[k]
            out.append((p[0], p[1] + wy, p[2], dx, dy, dz, uvs[k][0], uvs[k][1], aos[k]))
        return out

    def _pane(self, x, y, z, b):
        """Iron bars / panes: thin vertical planes through the block centre that reach towards
        neighbouring panes or solid blocks (like Minecraft); a lone pane is a cross."""
        B = self.blocks
        def link(p):
            return p in B and (B[p] in PANES or B[p] not in TRANSPARENT)
        e, w_, s_, n_ = link((x + 1, y, z)), link((x - 1, y, z)), link((x, y, z + 1)), link((x, y, z - 1))
        if not (e or w_ or s_ or n_):
            e = w_ = s_ = n_ = True
        if (e or w_) and not (s_ or n_):
            e = w_ = True          # a straight wall along X spans the whole block
        if (s_ or n_) and not (e or w_):
            s_ = n_ = True
        u0, v0, u1, v1 = tile_uv(b)
        um = (u0 + u1) / 2
        cx, cz = x + 0.5, z + 0.5
        out = []
        def quad(a, c, ua, ub, nrm):
            cs = [(a[0], y + 1, a[1]), (c[0], y + 1, c[1]), (c[0], y, c[1]), (a[0], y, a[1])]
            uvs = [(ua, v0), (ub, v0), (ub, v1), (ua, v1)]
            for k in (0, 3, 2, 0, 2, 1):
                p = cs[k]
                out.append((p[0], p[1], p[2], nrm[0], nrm[1], nrm[2], uvs[k][0], uvs[k][1], 1.0))
        if w_: quad((x, cz), (cx, cz), u0, um, (0, 0, 1))
        if e:  quad((cx, cz), (x + 1, cz), um, u1, (0, 0, 1))
        if n_: quad((cx, z), (cx, cz), u0, um, (1, 0, 0))
        if s_: quad((cx, cz), (cx, z + 1), um, u1, (1, 0, 0))
        return out

    def _sprite(self, x, y, z, t):
        u0, v0, u1, v1 = tile_uv(t)
        out = []
        s = 0.85 if t != "torch" else 0.5
        h = 1.0 if t != "torch" else 0.8
        cx, cz = x + 0.5, z + 0.5
        for a in (math.pi / 4, -math.pi / 4):
            ex, ez = math.cos(a) * s / 2 * 1.41, math.sin(a) * s / 2 * 1.41
            if t == "torch":
                ex, ez = (s / 2, 0) if a > 0 else (0, s / 2)
            cs = [(cx - ex, y + h, cz - ez), (cx + ex, y + h, cz + ez), (cx + ex, y, cz + ez), (cx - ex, y, cz - ez)]
            uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            for k in (0, 3, 2, 0, 2, 1):
                p = cs[k]
                out.append((p[0], p[1], p[2], 0, 1, 0, uvs[k][0], uvs[k][1], 1.0 if t != "torch" else 2.0))
        return out
