"""Builds the Story Craft starter kit (assets + sample content) for the game/plugin developer."""
import json
import os
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
from engine.world import World, make_atlas, ASSETS  # noqa: E402
from engine.export_game import export_mob_rig, ogg, Exporter  # noqa: E402
from engine.audio import nb_music, game_sound, SR  # noqa: E402
from engine.render import TIMES  # noqa: E402
from engine.gltf import GLB  # noqa: E402

UP = "/mnt/user-data/uploads"
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/StoryCraft_kit"


def mk(*p):
    d = os.path.join(OUT, *p); os.makedirs(d, exist_ok=True); return d


# ------------------------------------------------------------------ branding
def branding():
    d = mk("assets", "branding")
    shutil.copy(f"{UP}/17d61291-image.png", f"{d}/storycraft_title.png")
    # RC logo: white→grey gradient letters on black -> transparent PNG keeping the gradient
    im = Image.open(f"{UP}/b9dea631-image.jpg").convert("RGB")
    a = np.array(im).astype(float)
    lum = a.max(2)
    alpha = np.clip((lum - 18) / 40, 0, 1)
    rgba = np.zeros(a.shape[:2] + (4,), np.uint8)
    rgba[..., :3] = np.clip(a / np.maximum(alpha[..., None], 1e-3), 0, 255)
    rgba[..., 3] = (alpha * 255).astype(np.uint8)
    ys, xs = np.nonzero(rgba[..., 3] > 10)
    Image.fromarray(rgba).crop((xs.min() - 20, ys.min() - 20, xs.max() + 20, ys.max() + 20)).save(f"{d}/rc_logo.png")
    # white mask version (game draws its own animated gradient over it)
    m = rgba.copy(); m[..., :3] = 255
    Image.fromarray(m).crop((xs.min() - 20, ys.min() - 20, xs.max() + 20, ys.max() + 20)).save(f"{d}/rc_logo_mask.png")
    shutil.copy(f"{UP}/b9dea631-image.jpg", f"{d}/rc_reference.jpg")
    md = mk("docs", "mockups")
    shutil.copy(f"{UP}/d2004c95-image.jpg", f"{md}/main_menu_layout.jpg")
    shutil.copy(f"{UP}/ec817076-image.jpg", f"{md}/adventures_layout.jpg")


def fonts():
    d = mk("assets", "fonts")
    for src, dst in (("4b85f770-minecraft_regular.ttf", "minecraft_regular.ttf"), ("aa4e6015-minecraft_bold.ttf", "minecraft_bold.ttf"),
                     ("7be47f78-minecraft_italic.ttf", "minecraft_italic.ttf"), ("af3cc355-minecraft_bitalic.ttf", "minecraft_bolditalic.ttf")):
        shutil.copy(f"{UP}/{src}", f"{d}/{dst}")


# ------------------------------------------------------------------ UI (pixel art, orange-wood)
def ui():
    d = mk("assets", "ui")
    planks = np.array(Image.open(f"{ASSETS}/blocks/planks/0.png").convert("RGB").resize((16, 16), Image.NEAREST)).astype(float)
    lum = planks.mean(2, keepdims=True) / planks.mean()

    def wood(col, w, h):
        tile = np.clip(lum * np.array(col, float), 0, 255)
        out = np.zeros((h, w, 3));
        for y in range(0, h, 16):
            for x in range(0, w, 16):
                out[y:y + 16, x:x + 16] = tile[:min(16, h - y), :min(16, w - x)]
        return out

    def button(name, col, hi, lo, border=(40, 22, 8)):
        w, h = 100, 20
        a = np.zeros((h, w, 4), np.uint8)
        body = wood(col, w, h)
        a[..., :3] = body; a[..., 3] = 255
        a[0, :, :3] = border; a[-1, :, :3] = border; a[:, 0, :3] = border; a[:, -1, :3] = border
        a[1, 1:-1, :3] = hi; a[1:-1, 1, :3] = hi
        a[-2, 1:-1, :3] = lo; a[1:-1, -2, :3] = lo
        Image.fromarray(a).save(f"{d}/{name}.png")
    button("button_normal", (238, 170, 60), (255, 224, 140), (150, 90, 25))
    button("button_hover", (255, 196, 80), (255, 240, 170), (170, 110, 30))
    button("button_pressed", (200, 130, 40), (140, 85, 20), (230, 170, 80))
    button("button_disabled", (130, 120, 110), (170, 160, 150), (80, 75, 70))
    # panel (dark wood, 9-slice 4px)
    w = h = 48
    p = np.zeros((h, w, 4), np.uint8); p[..., :3] = wood((120, 72, 30), w, h); p[..., 3] = 235
    p[0:2, :, :3] = (40, 22, 8); p[-2:, :, :3] = (40, 22, 8); p[:, 0:2, :3] = (40, 22, 8); p[:, -2:, :3] = (40, 22, 8)
    p[2, 2:-2, :3] = (170, 110, 50); p[2:-2, 2, :3] = (170, 110, 50)
    Image.fromarray(p).save(f"{d}/panel.png")
    # slider track / knob, checkbox, progress bar
    t = np.zeros((6, 64, 4), np.uint8); t[..., :3] = (60, 35, 12); t[..., 3] = 255; t[1:-1, 1:-1, :3] = (110, 70, 30)
    Image.fromarray(t).save(f"{d}/slider_track.png")
    k = np.zeros((12, 8, 4), np.uint8); k[..., :3] = (40, 22, 8); k[..., 3] = 255; k[1:-1, 1:-1, :3] = (255, 200, 90); k[1, 1:-1, :3] = (255, 240, 170)
    Image.fromarray(k).save(f"{d}/slider_knob.png")
    for on in (0, 1):
        c = np.zeros((12, 12, 4), np.uint8); c[..., :3] = (40, 22, 8); c[..., 3] = 255; c[1:-1, 1:-1, :3] = (110, 70, 30)
        if on:
            for i in range(3, 9):
                c[i, i - 1:i + 1, :3] = (255, 220, 100) if i > 5 else c[i, i - 1:i + 1, :3]
            for i, y in enumerate(range(8, 2, -1)):
                c[y, 3 + i + 2:3 + i + 4, :3] = (255, 220, 100)
            c[6:9, 3:5, :3] = (255, 220, 100)
        Image.fromarray(c).save(f"{d}/checkbox_{'on' if on else 'off'}.png")
    pb = np.zeros((8, 64, 4), np.uint8); pb[..., :3] = (40, 22, 8); pb[..., 3] = 255; pb[1:-1, 1:-1, :3] = (70, 45, 20)
    Image.fromarray(pb).save(f"{d}/progress_bg.png")
    pf = np.zeros((6, 62, 4), np.uint8); pf[..., :3] = (255, 160, 40); pf[..., 3] = 255; pf[0, :, :3] = (255, 220, 120)
    Image.fromarray(pf).save(f"{d}/progress_fill.png")
    # simple icons 16x16: pause, lock, back, play, settings(gear), wifi_off, tap ring
    def icon(name, pix):
        im = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); dr = ImageDraw.Draw(im)
        pix(dr); im.save(f"{d}/icon_{name}.png")
    W_, D_ = (255, 255, 255, 255), (40, 22, 8, 255)
    icon("pause", lambda dr: (dr.rectangle((4, 3, 6, 12), W_), dr.rectangle((9, 3, 11, 12), W_)))
    icon("play", lambda dr: dr.polygon([(5, 3), (12, 8), (5, 13)], W_))
    icon("back", lambda dr: (dr.polygon([(3, 8), (8, 3), (8, 13)], W_), dr.rectangle((8, 6, 13, 10), W_)))
    icon("lock", lambda dr: (dr.rectangle((3, 7, 12, 14), W_), dr.arc((4, 1, 11, 10), 180, 360, W_, 2)))
    icon("settings", lambda dr: (dr.ellipse((3, 3, 12, 12), W_), dr.ellipse((6, 6, 9, 9), (0, 0, 0, 0))))
    icon("wifi_off", lambda dr: (dr.arc((1, 3, 14, 16), 210, 330, W_, 2), dr.arc((4, 6, 11, 13), 210, 330, W_, 2),
                                 dr.rectangle((7, 11, 8, 12), W_), dr.line((2, 2, 13, 14), (255, 80, 80, 255), 2)))
    ring = Image.new("RGBA", (32, 32), (0, 0, 0, 0)); ImageDraw.Draw(ring).ellipse((2, 2, 29, 29), outline=(255, 230, 120, 255), width=3)
    ring.save(f"{d}/hotspot_ring.png")
    # menu.png preview (for docs)


# ------------------------------------------------------------------ audio
def audio():
    d = mk("assets", "audio")
    ogg(f"{d}/menu_music.ogg", nb_music(64, "спокойно", seed=5) * 0.9)
    n = int(1.3 * SR); t = np.arange(n) / SR
    rng = np.random.default_rng(3)
    noise = rng.normal(0, 1, n)
    env = np.exp(-((t - 0.45) / 0.22) ** 2)
    sweep = np.zeros(n); acc = 0.0
    for i in range(n):  # moving one-pole low-pass -> "swoosh"
        a = 0.02 + 0.5 * env[i]; acc += a * (noise[i] - acc); sweep[i] = acc
    sw = sweep * env; sw = sw / np.abs(sw).max() * 0.8
    shimmer = np.sin(2 * np.pi * (600 + 900 * t) * t) * np.exp(-((t - 0.7) / 0.18) ** 2) * 0.12
    ogg(f"{d}/logo_swoosh.ogg", sw + shimmer)
    for name, src in (("ui_click", "random_click"), ("ui_select", "random_orb"), ("success", "random_levelup"),
                      ("fail", "random_hurt"), ("pop", "random_pop"), ("tick", "note_hat"), ("door", "random_door_open"),
                      ("splash", "random_splash"), ("hit", "damage_hit"), ("step_grass", "step_grass"),
                      ("chest_open", "random_chestopen"), ("explode", "random_explode"), ("eat", "random_eat"),
                      ("burp", "random_burp"), ("cow", "mob_cow_say"), ("pig", "mob_pig_say"), ("chicken", "mob_chicken_say"),
                      ("sheep", "mob_sheep_say")):
        a = game_sound(src, 0)
        if a is not None:
            ogg(f"{d}/{name}.ogg", a * 0.9)


# ------------------------------------------------------------------ menu scene
def menu_scene():
    d = mk("assets", "menu")
    w = World("лес", 11)
    from engine import build
    build.apply(w, "цветы -7,-7 7,7 плотность=0.22", HERE)
    build.apply(w, "куб сено 4,0,-3 4,0,-3", HERE)
    build.apply(w, "куб верстак -4,0,-4 -4,0,-4", HERE)
    build.apply(w, "куб вода 2,-1,3 4,-1,5", HERE)
    from engine.export_game import ascii_id
    op, wa = w.build_mesh(); cut = w.cutout
    atlas = make_atlas(11)
    g = GLB(); tex = g.texture(atlas, "atlas")
    ms, mc, mw = g.material(tex, "OPAQUE", "solid"), g.material(tex, "MASK", "cutout"), g.material(tex, "BLEND", "water")
    for arr, mat, tag in ((op, ms, "solid"), (cut, mc, "cutout"), (wa, mw, "water")):
        if len(arr) == 0:
            continue
        tri = arr.reshape(-1, 3, 9)
        cx = np.floor(tri[:, :, 0].mean(1) / 16).astype(int); cz = np.floor(tri[:, :, 2].mean(1) / 16).astype(int)
        for k in np.unique(cx * 1000 + cz):
            sel = tri[(cx * 1000 + cz) == k].reshape(-1, 9)
            ao = np.clip(sel[:, 8:9], 0, 1); em = sel[:, 8:9] > 1.5
            col = np.concatenate([np.where(em, 1.0, ao)] * 3 + [np.ones_like(ao)], 1)
            g.node(f"{tag}_{k}", mesh=g.mesh(f"{tag}_{k}", sel, mat, color=col))
    g.save(f"{d}/menu_world.glb")
    md = mk("assets", "menu", "mobs")
    mobs = [("cow", 2), ("pig", 2), ("sheep", 2), ("chicken", 3)]
    for kind, _ in mobs:
        export_mob_rig(kind, f"{md}/{kind}.glb")
    env = TIMES["день"]
    sd = np.array(env["sun"], float); sd /= np.linalg.norm(sd)
    walk = []
    for x in range(-7, 8):
        for z in range(-7, 8):
            if x * x + z * z <= 49 and (x, 0, z) not in w.blocks and (x, -1, z) in w.blocks and w.blocks[(x, -1, z)] != "water":
                walk.append([x + 0.5, z + 0.5])
    legs = {"quadruped": {"swing": [["leg0", 0], ["leg3", 0], ["leg1", 3.14159], ["leg2", 3.14159]], "axis": "x", "amp": 0.6},
            "chicken": {"swing": [["leg0", 0], ["leg1", 3.14159]], "axis": "x", "amp": 0.7,
                        "flap": [["wing0", -1], ["wing1", 1]]}}
    menu = dict(world="assets/menu/menu_world.glb", ground_y=0.0, walkable_cells=walk,
                camera=dict(type="orbit", center=[0.0, 1.0, 0.0], radius=10.5, height=3.6, look_height=0.8,
                            speed_deg_per_s=2.0, fov=50),
                env=dict(sun_dir=[round(x, 3) for x in sd], sun_color=list(env["sun_col"]), ambient=list(env["amb"]),
                         sky_zenith=list(env["zen"]), sky_horizon=list(env["hor"]), fog_color=[round(x * 0.95, 3) for x in env["hor"]],
                         fog_near=18, fog_far=46),
                mobs=[dict(kind=k, rig=f"assets/menu/mobs/{k}.glb", count=c, speed=0.8 if k != "chicken" else 0.9,
                           anim="chicken" if k == "chicken" else "quadruped", head="head",
                           sound=f"assets/audio/{k}.ogg") for k, c in mobs],
                music="assets/audio/menu_music.ogg")
    json.dump(menu, open(f"{d}/menu.json", "w"), ensure_ascii=False, indent=1)
    # preview render for docs
    return w


# ------------------------------------------------------------------ sample content (server folder)
def sample_content():
    root = mk("server_content", "adventures", "last_men")
    ep_dir = os.path.join(root, "episodes", "01_escape")
    sys.argv = [sys.argv[0]]
    from mcanim import Film
    src = os.path.join(HERE, "game_episodes", "last_men_01")
    film = Film(os.path.join(src, "сценарий.txt"))
    Exporter(film, ep_dir, logic_path=os.path.join(src, "логика.json")).export()
    # covers from real renders
    from PIL import Image as I
    ps = film.stills([9.0, 24.5], os.path.join(src, "covers"))
    I.open(ps[0]).resize((640, 360)).save(os.path.join(root, "cover.png"))
    I.open(ps[1]).resize((480, 270)).save(os.path.join(ep_dir, "cover.png"))
    json.dump(dict(id="last_men", title="Последние мужики", description="Все мужчины на Земле исчезли. Кроме троих.",
                   order=1, accent="#ff8a3a", cover="cover.png"),
              open(os.path.join(root, "adventure.json"), "w"), ensure_ascii=False, indent=1)
    meta = json.load(open(os.path.join(ep_dir, "episode.json")))
    meta["cover"] = "cover.png"
    json.dump(meta, open(os.path.join(ep_dir, "episode.json"), "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    branding(); fonts(); ui(); audio(); menu_scene(); sample_content()
    shutil.copy(os.path.join(HERE, "tools", "verify_export.py"), mk("tools") + "/reference_player.py")
    print("kit ->", OUT)
