"""Export a whole adventure for Story Craft and pack it as <id>.zip (the folder itself is inside).

    python3 tools/export_adventure.py adventures/seed404 [out_dir]

Layout of the source folder:
    adventure.json           id, title, description, ...
    skins/                   shared skins
    ep01/сценарий.txt, ep01/логика.json   (logika "id" = episode folder name in the game)
    ep02/...
A scene with id starting with "cover" is the special cover shot: it is rendered as the episode cover
(and, for the first episode, the adventure cover) and is not exported to the game.
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)


def main(src, out_root="/tmp/storycraft_out"):
    sys.argv = [sys.argv[0]]
    from PIL import Image
    from mcanim import Film, zip_folder
    from engine.export_game import Exporter
    meta = json.load(open(os.path.join(src, "adventure.json"), encoding="utf-8"))
    aid = meta["id"]
    root = os.path.join(out_root, aid)
    if os.path.exists(root):
        shutil.rmtree(root)
    os.makedirs(os.path.join(root, "episodes"))
    eps = sorted(d for d in os.listdir(src) if d.startswith("ep") and os.path.isfile(os.path.join(src, d, "сценарий.txt")))
    for i, d in enumerate(eps):
        sdir = os.path.join(src, d)
        logic = json.load(open(os.path.join(sdir, "логика.json"), encoding="utf-8"))
        ep_dir = os.path.join(root, "episodes", logic["id"])
        film = Film(os.path.join(sdir, "сценарий.txt"))
        print(f"== {d}: {logic.get('title')}")
        Exporter(film, ep_dir, logic_path=os.path.join(sdir, "логика.json")).export()
        cov = next((s for s in film.tl.scenes if str(s.get("sid", "")).startswith("cover")), None)
        if cov:
            t = min(cov["t1"] - 0.05, cov["t0"] + 1.2)
            p = film.stills([t], os.path.join(sdir, "covers"))[0]
            im = Image.open(p).convert("RGB")
            im.resize((640, 360), Image.LANCZOS).save(os.path.join(ep_dir, "cover.png"))
            if i == 0:
                im.resize((1280, 720), Image.LANCZOS).save(os.path.join(root, "cover.png"))
        extra = os.path.join(sdir, "files")          # extra episode files (ui images for drag etc.)
        if os.path.isdir(extra):
            shutil.copytree(extra, ep_dir, dirs_exist_ok=True)
        em = json.load(open(os.path.join(ep_dir, "episode.json"), encoding="utf-8"))
        em["cover"] = "cover.png"
        json.dump(em, open(os.path.join(ep_dir, "episode.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(meta, open(os.path.join(root, "adventure.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return zip_folder(root)


if __name__ == "__main__":
    main(*sys.argv[1:])
