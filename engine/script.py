"""Russian screenplay format -> timeline (moves, actions, looks, speech, camera shots)."""
import math
import os
import re

from .anim import ACTIONS, BIG_ACTIONS
from .audio import tts, envelope, SR

ALIASES = {
    "walk": "идет идёт идти иди подходит подойти шагает пошел пошёл уходит walk go",
    "run": "бежит бежать беги бег побежал несется несётся run",
    "jump": "прыгает прыжок прыгнул прыгать подпрыгивает jump",
    "wave": "машет помахал махать привет wave",
    "nod": "кивает кивнул кивок да nod",
    "shake": "мотает нет качает shake",
    "look": "смотрит посмотрел глядит взгляд look",
    "turn": "поворачивается повернулся поворот разворачивается turn",
    "point": "показывает указывает тычет point",
    "sit": "садится сел сидит sit",
    "stand": "встает встаёт встал stand",
    "dance": "танцует танец танцевать dance",
    "laugh": "смеется смеётся ржет ржёт хохочет смех laugh",
    "surprise": "удивлен удивлён удивляется удивление ого surprise",
    "celebrate": "радуется ура празднует радость celebrate",
    "punch": "бьет бьёт ударяет удар punch",
    "facepalm": "фейспалм facepalm рукалицо",
    "shrug": "пожимает shrug",
    "think": "думает размышляет хм think",
    "scared": "боится дрожит испугался страх scared",
    "angry": "злится злой сердится бесится angry",
    "crouch": "приседает присел crouch",
    "sneak": "крадется крадётся подкрадывается sneak",
    "shoot": "стреляет выстрелила выстрелил shoot",
    "carry": "несет несёт тащит carry",
    "lookaround": "оглядывается озирается lookaround",
    "cry": "плачет рыдает cry",
    "bow": "кланяется поклон bow",
    "clap": "хлопает аплодирует clap",
    "spit": "плюет плюёт плюнул плевок spit",
    "struggle": "тужится пыжится открывает struggle",
    "grab": "хватает ловит тянет схватила схватил grab",
    "love": "влюбляется влюблена влюблен влюблён love",
    "eat": "ест жует жуёт кушает eat",
    "hide": "прячется спрятался hide",
    "lazy": "зевает ленится lazy",
    "flex": "хвастается понтуется качок flex",
    "fall": "падает упал упала fall",
    "sleep": "спит уснул sleep",
    "vanish": "исчезает пропадает vanish",
    "appear": "появляется appear",
    "place": "стоит ставится place",
    "remove": "скрыт скрыта скрыть убран убрана remove",
    "hold": "держит берет берёт достает достаёт hold",
    "drop": "убирает выбрасывает прячет_предмет drop",
}
ITEMS = {"записк": "paper", "бумаг": "paper", "сено": "hayblock/1", "ящик": "crafting_table/1", "коробк": "crafting_table/1", "тыкв": "pumpkin/1",
         "дротик": "arrow", "сумк": "leather", "пакет": "paper", "сундук": "crafting_table/1",
         "банк": "potion_bottle_empty", "бутыл": "potion_bottle_empty", "ведр": "bucket", "яблок": "apple",
         "хлеб": "bread", "торт": "cake", "рыб": "fish_raw_cod", "книг": "book_normal", "кост": "bone",
         "палк": "stick", "меч": "sword", "лопат": "shovel", "топор": "axe", "кирк": "pickaxe", "удочк": "fishing_rod",
         "морков": "carrot", "цвет": "flower1", "алмаз": "diamond", "золот": "gold_ingot", "яйц": "egg",
         "кольц": "gold_nugget", "сердц": "redstone_dust", "печень": "cookie", "огур": "melon", "арбуз": "melon",
         "компас": "compass_item", "карт": "map_filled", "пирог": "pumpkin_pie", "лук": "bow_standby", "зелье": "experience_bottle",
         "сковород": "bowl", "миск": "bowl", "мясо": "beef_cooked", "часы": "clock_item", "ножниц": "shears"}
SOUNDS_RU = {"взрыв": "random_explode", "дверь": "random_door_open", "отрыжка": "random_burp", "уровень": "random_levelup",
             "ой": "random_hurt", "удар": "damage_hit", "му": "mob_cow_say", "хрю": "mob_pig_say", "кудах": "mob_chicken_say",
             "житель": "mob_villager_idle", "хм": "mob_villager_idle", "ведьма": "mob_witch_ambient", "крипер": "random_fuse",
             "гром": "ambient_weather_thunder", "стекло": "random_glass", "поп": "random_pop", "еда": "random_eat",
             "сундук": "random_chestopen", "гав": "mob_wolf_bark", "бее": "mob_sheep_say", "зомби": "mob_zombie_say",
             "дождь": "ambient_weather_rain", "фейерверк": "random_explode", "лава": "liquid_lavapop", "всплеск": "random_splash",
             "лук": "random_bow", "наковальня": "random_anvil_land", "паук": "mob_spider_say", "скелет": "mob_skeleton_say",
             "эндермен": "mob_endermen_scream", "кот": "mob_cat_meow", "огонь": "fire_ignite", "стрела": "random_bowhit"}
ACT = {}
for k, v in ALIASES.items():
    for w in v.split():
        ACT[w] = k

SHOTS = {"общий": "wide", "wide": "wide", "крупно": "close", "close": "close", "сплеча": "ots",
         "из-за": "ots", "ots": "ots", "средний": "medium", "medium": "medium", "облет": "orbit", "облёт": "orbit",
         "orbit": "orbit", "сверху": "top", "top": "top", "следом": "follow", "follow": "follow", "авто": "auto",
         "auto": "auto", "двое": "two", "two": "two"}

STOP = {"к", "на", "в", "до", "у", "плечами", "головой", "и", "руками", "в ладоши", "от", "с", "со"}


def norm(s):
    return s.lower().replace("ё", "е").strip()


class Timeline:
    def __init__(self):
        self.title = ""; self.subtitle = ""; self.world = "plains"; self.time = "день"; self.seed = 3
        self.music = True; self.subs = True; self.mood = "весело"; self.end_text = "Конец"
        self.cast = {}
        self.moves, self.clips, self.looks, self.faces, self.sits = [], [], [], [], []
        self.speech, self.sfx, self.shots, self.scenes = [], [], [], []
        self.vis, self.items, self.lies, self.particles, self.music_plan = [], [], [], [], []
        self.duration = 0.0


def parse(path):
    """Returns header dict, cast list, scenes [(name, [beats])]."""
    base = os.path.dirname(os.path.abspath(path))
    lines = open(path, encoding="utf-8").read().splitlines()
    head, cast, scenes = {}, {}, []
    cur = None
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^(актер|актёр|персонаж|cast|моб|mob)\s+([^:]+):\s*(.*)$", line, re.I)
        if m:
            name = m.group(2).strip()
            if m.group(1).lower() in ("моб", "mob"):
                from .mobs import MOB_RU
                opts = dict(re.findall(r"(\S+?)=(\S+)", m.group(3)))
                kind = MOB_RU.get(norm(opts.get("тип", opts.get("type", "корова"))), "cow")
                p = opts.get("место", opts.get("pos", "0,0")).split(",")
                cast[name] = dict(name=name, mob=kind, pos=(float(p[0]), float(p[1])), color=opts.get("цвет", "#dddddd"),
                                  voice=None, pitch=None, rate=None, skin=None,
                                  hidden=norm(opts.get("скрыт", "нет")) in ("да", "yes"))
                if "поворот" in opts:
                    cast[name]["yaw"] = math.radians(float(opts["поворот"]))
                continue
            opts = dict(re.findall(r"(\S+?)=(\S+)", m.group(3)))
            cfg = dict(name=name, skin=os.path.join(base, opts.get("скин", opts.get("skin", name + ".png"))),
                       voice=opts.get("голос", opts.get("voice", "aleksandr-hq")),
                       pitch=opts.get("тон", opts.get("pitch")), rate=opts.get("скорость", opts.get("rate")),
                       color=opts.get("цвет", opts.get("color", "#ffd84a")))
            p = opts.get("место", opts.get("pos", "0,0")).split(",")
            cfg["pos"] = (float(p[0]), float(p[1]))
            if "поворот" in opts:
                cfg["yaw"] = math.radians(float(opts["поворот"]))
            cfg["hidden"] = norm(opts.get("скрыт", "нет")) in ("да", "yes")
            cfg["lying"] = norm(opts.get("лежит", opts.get("спит", "нет"))) in ("да", "yes")
            cast[name] = cfg
            continue
        m = re.match(r"^==+\s*(.*?)\s*=*$", line)
        if m:
            title = m.group(1) or f"Сцена {len(scenes) + 1}"
            params = {}
            if "|" in title:
                title, ps = title.split("|", 1)
                params = {norm(k): v.strip() for k, v in re.findall(r"(\S+?)=(\S+)", ps)}
            cur = (title.strip(), [], params)
            scenes.append(cur)
            continue
        if line.startswith("*") or line.startswith(">"):
            body = line[1:].strip()
            if cur is None:
                cur = ("Сцена 1", [], {}); scenes.append(cur)
            cur[1].append(("stage", body))
            continue
        m = re.match(r"^([^:\[\]]{1,40}):\s*(.*)$", line)
        if m:
            key = m.group(1).strip()
            if norm(key) in {norm(n) for n in cast}:
                if cur is None:
                    cur = ("Сцена 1", [], {}); scenes.append(cur)
                name = [n for n in cast if norm(n) == norm(key)][0]
                cur[1].append(("say", name, m.group(2).strip()))
                continue
            head[norm(key)] = m.group(2).strip()
            continue
        raise ValueError(f"Не понял строку: {raw}")
    return head, cast, scenes


class Compiler:
    def __init__(self, path, world, cache="cache", world_factory=None):
        self.path = path
        self.head, castcfg, self.scenes_src = parse(path)
        self.world = world
        self.world0 = world
        self.world_factory = world_factory
        self.cache = cache
        tl = self.tl = Timeline()
        h = self.head
        tl.title = h.get("название", ""); tl.subtitle = h.get("подзаголовок", "")
        tl.world = h.get("мир", "равнина"); tl.time = h.get("время", "день")
        tl.seed = int(h.get("сид", 3)); tl.music = norm(h.get("музыка", "да")) not in ("нет", "no", "0")
        tl.subs = norm(h.get("субтитры", "да")) not in ("нет", "no", "0")
        tl.mood = norm(h.get("настроение", "весело")); tl.end_text = h.get("конец", "Конец")
        tl.cast = castcfg
        self.pos = {n: c["pos"] for n, c in castcfg.items()}

    # ---------------------------------------------------------- helpers
    def find_char(self, tok):
        t = norm(tok).strip(",.!?")
        for n in self.tl.cast:
            nn = norm(n)
            if t == nn or (len(nn) >= 3 and t.startswith(nn[:max(3, len(nn) - 1)])):
                return n
        return None

    def resolve(self, toks, subject):
        num = None
        target = None
        self.last_item = None
        for tok in toks:
            tl_ = norm(tok).strip(",.!?")
            for k_, v_ in ITEMS.items():
                if tl_.startswith(k_):
                    self.last_item = v_
            t = norm(tok).strip(",.!?")
            if not t or t in STOP:
                continue
            if re.match(r"^-?\d+(\.\d+)?,-?\d+(\.\d+)?$", t):
                a, b = t.split(","); target = ("point", (float(a), float(b))); continue
            if re.match(r"^\d+(\.\d+)?(с|c|сек)?$", t):
                num = float(re.sub(r"[^\d.]", "", t)); continue
            c = self.find_char(t)
            if c and c != subject:
                target = ("char", c); continue
            if t.startswith("камер") or t.startswith("зрител"):
                target = ("cam",); continue
            for mk, mp in self.world.marks.items():
                if t.startswith(mk[:3]):
                    target = ("point", mp)
        return target, num

    def parse_action(self, text, default_subject):
        toks = text.split()
        subject = default_subject
        if toks and self.find_char(toks[0]):
            subject = self.find_char(toks[0]); toks = toks[1:]
        kind = None
        for i, tok in enumerate(toks):
            t = norm(tok).strip(",.!?")
            if t in ACT:
                kind = ACT[t]; toks = toks[i + 1:]; break
            for alias, k in ACT.items():
                if len(t) >= 5 and t.startswith(alias[:5]):
                    kind = k; break
            if kind:
                toks = toks[i + 1:]; break
        if kind is None:
            raise ValueError(f"Неизвестное действие: «{text}». Доступны: " + ", ".join(sorted(ALIASES)))
        target, num = self.resolve(toks, subject)
        return subject, kind, target, num

    def target_xz(self, target):
        if target is None:
            return None
        if target[0] == "char":
            return self.pos[target[1]]
        if target[0] == "point":
            return target[1]
        return None

    # ---------------------------------------------------------- schedule
    def schedule(self, subj, kind, target, num, t0, slot=0):
        tl = self.tl
        P = tl.particles
        if kind in ("place", "appear"):
            dst = self.target_xz(target) if target and target[0] == "point" else None
            if dst:
                tl.moves.append(dict(char=subj, t0=t0, t1=t0, **{"from": dst}, to=dst, dist=0.0, run=False))
                self.pos[subj] = dst
            tl.vis.append(dict(char=subj, t=t0, on=True))
            if num is not None:
                tl.faces.append(dict(char=subj, t=t0, target=("yaw", math.radians(num))))
                tl.looks.append(dict(char=subj, t=t0, target=("yaw", math.radians(num))))
            elif target and target[0] == "char":
                tl.faces.append(dict(char=subj, t=t0, target=target))
            if kind == "appear":
                P.append(dict(kind="poof", t0=t0, t1=t0 + 0.6, char=subj))
                tl.sfx.append((t0, "pop", 1.0))
                return t0 + 0.6
            return t0
        if kind == "remove":
            tl.vis.append(dict(char=subj, t=t0, on=False))
            return t0
        if kind == "vanish":
            P.append(dict(kind="poof", t0=t0, t1=t0 + 0.6, char=subj))
            tl.sfx.append((t0, "pop", 1.0))
            tl.vis.append(dict(char=subj, t=t0 + 0.05, on=False))
            return t0 + 0.6
        if kind in ("hold", "carry"):
            tl.items.append(dict(char=subj, t=t0, item=self.last_item or ("hayblock/1" if kind == "carry" else "stick"),
                                 carry=kind == "carry"))
            return t0 + (num or 0.3)
        if kind == "shoot":
            tl.clips.append(dict(char=subj, kind="point", t0=t0, t1=t0 + 1.0))
            tl.items.append(dict(char=subj, t=t0, item="bow_standby"))
            tl.items.append(dict(char=subj, t=t0 + 1.2, item=None))
            if target:
                tl.faces.append(dict(char=subj, t=t0, target=target)); tl.looks.append(dict(char=subj, t=t0, target=target))
            tl.sfx.append((t0 + 0.35, "random_bow", 0.9))
            if target and target[0] == "char":
                v = target[1]; hit = t0 + 0.55
                P.append(dict(kind="dart", t0=t0 + 0.35, t1=hit, char=subj, target=target))
                P.append(dict(kind="dart_stuck", t0=hit, t1=9999.0, char=v))
                tl.sfx.append((hit, "random_bowhit", 1.0))
                tl.sfx.append((hit + 0.05, "hurt", 0.8))
                tl.clips.append(dict(char=v, kind="surprise", t0=hit, t1=hit + 0.5))
                tl.lies.append(dict(char=v, t=hit + 0.7, on=True))
                tl.sfx.append((hit + 1.0, "land", 1.0))
                P.append(dict(kind="zzz", t0=hit + 1.3, t1=9999.0, char=v))
                tl.moves.append(dict(char=v, t0=hit, t1=hit, **{"from": self.pos[v]}, to=self.pos[v], dist=0.0, run=False))
            return t0 + 1.0
        if kind == "drop":
            tl.items.append(dict(char=subj, t=t0, item=None))
            return t0 + (num or 0.3)
        if kind in ("fall", "sleep"):
            tl.lies.append(dict(char=subj, t=t0, on=True))
            tl.sfx.append((t0 + 0.3, "land", 1.0))
            if kind == "sleep":
                P.append(dict(kind="zzz", t0=t0 + 0.5, t1=t0 + (num or 3.0), char=subj))
                return t0 + (num or 1.0)
            return t0 + (num or 0.9)
        if kind in ("walk", "run", "sneak"):
            dst = self.target_xz(target)
            if dst is None:
                return t0
            ax, az = self.pos[subj]
            bx, bz = dst
            dx, dz = bx - ax, bz - az
            L = math.hypot(dx, dz)
            if target[0] == "char":
                stop = 2.0
                if L <= stop:
                    return t0
                bx, bz = ax + dx / L * (L - stop), az + dz / L * (L - stop)
            elif slot:
                px, pz = -dz / (L + 1e-6), dx / (L + 1e-6)
                off = (slot + 1) // 2 * 1.3 * (1 if slot % 2 else -1)
                bx, bz = bx + px * off, bz + pz * off
            dist = math.hypot(bx - ax, bz - az)
            speed = 5.2 if kind == "run" else (1.5 if kind == "sneak" else 2.7)
            dur = max(0.5, dist / speed + 0.25)
            if num:
                dur = num
            t1 = t0 + dur
            tl.moves.append(dict(char=subj, t0=t0, t1=t1, **{"from": (ax, az)}, to=(bx, bz), dist=dist,
                                 run=kind == "run", sneak=kind == "sneak"))
            nsteps = int(dist / (0.62 if kind == "run" else 0.48))
            if kind != "sneak" and not tl.cast[subj].get("extra"):
                for i in range(nsteps):
                    tl.sfx.append((t0 + (i + 0.5) * dur / max(1, nsteps), "step", 0.8 if kind == "run" else 0.6))
            self.pos[subj] = (bx, bz)
            if target[0] == "char":
                tl.faces.append(dict(char=subj, t=t1, target=target))
                tl.looks.append(dict(char=subj, t=t1 - 0.3, target=target))
            return t1
        if kind == "look":
            if target:
                tl.looks.append(dict(char=subj, t=t0, target=target))
            return t0 + (num or 0.5)
        if kind == "turn":
            if target:
                tl.faces.append(dict(char=subj, t=t0, target=target))
                tl.looks.append(dict(char=subj, t=t0, target=target))
            return t0 + (num or 0.6)
        if kind in ("sit", "stand"):
            tl.sits.append(dict(char=subj, t=t0, on=kind == "sit"))
            if kind == "stand":
                tl.lies.append(dict(char=subj, t=t0, on=False))
                for p in tl.particles:
                    if p["char"] == subj and p["kind"] == "zzz" and p["t1"] > t0:
                        p["t1"] = t0
            return t0 + (num or 0.7)
        fn, dflt = ACTIONS[kind]
        d = num or dflt
        if target and kind in ("point", "punch", "wave", "laugh", "spit", "grab", "love", "flex", "angry"):
            tl.faces.append(dict(char=subj, t=t0, target=target))
            tl.looks.append(dict(char=subj, t=t0, target=target))
        tl.clips.append(dict(char=subj, kind=kind, t0=t0, t1=t0 + d))
        if kind == "jump":
            tl.sfx += [(t0 + 0.1, "jump", 1.0), (t0 + 0.6, "land", 1.0)]
        if kind == "celebrate":
            k = 0.0
            while k < d - 0.3:
                tl.sfx += [(t0 + k + 0.05, "jump", 0.6), (t0 + k + 0.64, "land", 0.7)]; k += 0.65
        if kind == "punch":
            tl.sfx.append((t0 + d * 0.3, "punch", 1.0))
            if target and target[0] == "char":
                P.append(dict(kind="stars", t0=t0 + d * 0.3, t1=t0 + d * 0.3 + 1.2, char=target[1]))
                tl.clips.append(dict(char=target[1], kind="surprise", t0=t0 + d * 0.3, t1=t0 + d * 0.3 + 0.6))
        if kind == "spit":
            tl.sfx.append((t0 + 0.12, "spit", 1.0))
            P.append(dict(kind="spit", t0=t0 + 0.12, t1=t0 + 0.5, char=subj, target=target))
            if target and target[0] == "char":
                tl.sfx.append((t0 + 0.47, "random_splash", 0.5))
        if kind in ("struggle", "scared"):
            P.append(dict(kind="sweat", t0=t0, t1=t0 + d, char=subj))
        if kind == "angry":
            P.append(dict(kind="anger", t0=t0, t1=t0 + d, char=subj))
        if kind == "love":
            P.append(dict(kind="hearts", t0=t0, t1=t0 + d, char=subj))
            tl.sfx.append((t0, "orb", 1.0))
        if kind == "eat":
            for k in range(int(d / 0.35)):
                tl.sfx.append((t0 + 0.1 + k * 0.35, "eat", 0.9))
        if kind == "flex":
            tl.sfx.append((t0 + 0.2, "levelup", 0.6))
        if kind == "clap":
            k = 0.0
            while k < d:
                tl.sfx.append((t0 + k + 0.11, "clap", 1.0)); k += math.pi / 14
        return t0 + d

    # ---------------------------------------------------------- compile
    def compile(self, log=print):
        tl = self.tl
        t = 0.0
        last_speaker = None
        cam_mode = None  # manual shot spec
        names = list(tl.cast)
        crowd = self.head.get("толпа")
        if crowd:
            import glob, os, random as _r
            opts = dict(re.findall(r"(\S+?)=(\S+)", crowd))
            cnt = int(re.match(r"\s*(\d+)", crowd).group(1)) if re.match(r"\s*(\d+)", crowd) else 10
            sk_dir = os.path.join(os.path.dirname(os.path.abspath(self.path)), opts.get("скины", "skins/crowd"))
            skins = sorted(glob.glob(os.path.join(sk_dir, "*.png")))
            cc = [float(v) for v in opts.get("центр", "0,0").split(",")]
            self.crowd = dict(world=opts.get("мир", "город"), R=float(opts.get("радиус", 8)), c=cc, names=[])
            rr = _r.Random(7)
            for i in range(cnt):
                nm = f"Прохожая{i + 1}"
                a = rr.uniform(0, 6.28); rad = rr.uniform(3.5, self.crowd["R"])
                tl.cast[nm] = dict(name=nm, skin=skins[i % len(skins)], voice="anna", pitch=None, rate=None,
                                   color="#ffb0d0", pos=(cc[0] + rad * math.cos(a), cc[1] + rad * math.sin(a)),
                                   hidden=True, extra=True, yaw=rr.uniform(-3, 3))
                self.pos[nm] = tl.cast[nm]["pos"]
                self.crowd["names"].append(nm)
        else:
            self.crowd = None
        for n, c in tl.cast.items():
            if c.get("lying"):
                tl.lies.append(dict(char=n, t=-1.0, on=True))
                tl.particles.append(dict(kind="zzz", t0=0.0, t1=9999.0, char=n))
        cur_mood = tl.mood
        cur_time = tl.time
        cur_world = tl.world
        for si, (sname, beats, params) in enumerate(self.scenes_src):
            s0 = t
            cur_world = params.get("мир", cur_world)
            cur_time = params.get("время", cur_time)
            cur_mood = params.get("настроение", params.get("музыка", cur_mood))
            if self.world_factory:
                self.world = self.world_factory(cur_world)
            nline = 0
            participants = set()
            for b in beats:
                if b[0] == "say":
                    participants.add(b[1])
                else:
                    for part in b[1].split(";"):
                        for tok in part.split():
                            c = self.find_char(tok)
                            if c:
                                participants.add(c)
            participants = participants or set(names)
            for n, cc in tl.cast.items():  # background mobs live only in the main location
                if cc.get("mob") and n not in participants and not cc.get("hidden"):
                    tl.vis.append(dict(char=n, t=s0 - 1e-3, on=norm(cur_world) == norm(tl.world)))
            crowd_here = bool(self.crowd) and norm(cur_world) == norm(self.crowd["world"])
            if self.crowd:
                for n in self.crowd["names"]:
                    tl.vis.append(dict(char=n, t=s0 - 1e-3, on=crowd_here and n not in participants or n in participants))
            scene_shots = []
            if si == 0 and tl.title:
                scene_shots.append([t, t + 3.2, ("orbit", None, None)])
                t += 3.2
            first = True
            prev_spec = None
            for b in beats:
                bt0 = t
                spec = None
                if b[0] == "say":
                    _, who, text = b
                    others = [n for n in sorted(participants) if n != who]
                    addr = last_speaker if last_speaker in others else (
                        min(others, key=lambda n: math.dist(self.pos[n], self.pos[who])) if others else None)
                    cfg = tl.cast[who]
                    if cfg.get("mob"):
                        from .mobs import SOUNDS
                        from .audio import game_sound
                        import numpy as _np
                        a1 = game_sound(SOUNDS[cfg["mob"]], 0)
                        audio = (a1 * 0.9).astype(_np.float32) if a1 is not None else _np.zeros(SR // 2, _np.float32)
                    else:
                        audio = tts(text, cfg["voice"], cfg["pitch"], cfg["rate"], self.cache)
                    dur = len(audio) / SR
                    st = t + 0.12
                    clean = re.sub(r"\s+", " ", re.sub(r"\[[^\]]*\]", " ", text)).strip()
                    pan = 0.0
                    tl.speech.append(dict(char=who, t0=st, t1=st + dur, audio=audio,
                                          env=envelope(audio) if not cfg.get("mob") else [0.0], text=clean, pan=pan))
                    if addr:
                        tl.looks.append(dict(char=who, t=t, target=("char", addr)))
                        tl.faces.append(dict(char=who, t=t, target=("char", addr)))
                        for o in others:
                            tl.looks.append(dict(char=o, t=t + 0.15, target=("char", who)))
                        tl.faces.append(dict(char=addr, t=t + 0.2, target=("char", who)))
                    end = st + dur + 0.35
                    big = None
                    plain = re.sub(r"\[[^\]]*\]", "", text)
                    for mm in re.finditer(r"\[([^\]]+)\]", text):
                        frac = len(re.sub(r"\[[^\]]*\]", "", text[:mm.start()])) / max(1, len(plain))
                        at = st + frac * dur
                        for part in mm.group(1).split(";"):
                            subj, kind, target, num = self.parse_action(part, who)
                            e = self.schedule(subj, kind, target, num, at)
                            end = max(end, e + 0.2)
                            if kind in BIG_ACTIONS or kind in ("walk", "run", "sneak", "shoot"):
                                big = (subj, kind)
                    if cam_mode:
                        spec = cam_mode
                    elif first:
                        spec = ("wide", None, None)
                    elif big and big[1] in ("walk", "run"):
                        spec = ("wide", None, None)
                    elif big:
                        spec = ("medium", big[0], addr)
                    elif prev_spec and prev_spec[1] == who and prev_spec[0] in ("close", "ots") and dur < 2.5:
                        spec = prev_spec
                    else:
                        k = nline % 5
                        spec = ("two", None, None) if k == 4 else (("ots", who, addr) if k % 2 == 0 else ("close", who, addr))
                        if addr is None:
                            spec = ("medium", who, None)
                    nline += 1
                    last_speaker = who
                    t = end
                else:
                    body = b[1]
                    low = norm(body)
                    if low.startswith("пауза") or low.startswith("pause"):
                        nums = re.findall(r"\d+(?:\.\d+)?", low)
                        t += float(nums[0]) if nums else 1.0
                        spec = cam_mode or prev_spec or ("wide", None, None)
                    elif low.startswith("звук") or low.startswith("sound"):
                        toks = low.split()[1:]
                        for tk in toks:
                            snd = SOUNDS_RU.get(tk, tk)
                            tl.sfx.append((t, snd, 1.0))
                        continue
                    elif low.startswith("камера") or low.startswith("camera"):
                        toks = body.split()[1:]
                        kind = SHOTS.get(norm(toks[0]) if toks else "авто", "auto")
                        if kind == "auto":
                            cam_mode = None
                        else:
                            subj = self.find_char(toks[1]) if len(toks) > 1 else None
                            other = None
                            if subj:
                                others = [n for n in sorted(participants) if n != subj]
                                other = others[0] if others else None
                            cam_mode = (kind, subj, other)
                        continue
                    else:
                        end = t
                        kinds = []
                        movers = 0
                        for part in body.split(";"):
                            if not part.strip():
                                continue
                            subj, kind, target, num = self.parse_action(part.strip(), None)
                            if subj is None:
                                raise ValueError(f"Кто делает действие? «{part}»")
                            slot = movers if kind in ("walk", "run", "sneak") and target and target[0] == "point" else 0
                            if kind in ("walk", "run", "sneak"):
                                movers += 1
                            end = max(end, self.schedule(subj, kind, target, num, t, slot))
                            kinds.append((subj, kind))
                        if all(k in ("place", "remove") for _, k in kinds):
                            continue
                        t = max(end, t + 0.3)
                        if cam_mode:
                            spec = cam_mode
                        elif any(k in ("walk", "run", "sneak") for _, k in kinds) or len(kinds) > 1 or first:
                            spec = ("wide", None, None)
                        elif kinds and kinds[0][1] in ("look", "turn"):
                            spec = prev_spec or ("wide", None, None)
                        else:
                            spec = ("medium", kinds[0][0], None)
                first = False
                if scene_shots and scene_shots[-1][2] == spec and abs(scene_shots[-1][1] - bt0) < 1e-6:
                    scene_shots[-1][1] = t
                else:
                    scene_shots.append([bt0, t, spec])
                prev_spec = spec
            # merge too-short shots
            merged = []
            for sh in scene_shots:
                if merged and (sh[1] - sh[0] < 1.0):
                    merged[-1][1] = sh[1]
                else:
                    merged.append(sh)
            for sh in merged:
                tl.shots.append(dict(t0=sh[0], t1=sh[1], spec=sh[2], participants=sorted(participants)))
            t += 0.3
            if crowd_here:  # extras wander around the plaza for the whole scene
                import random as _r
                rr = _r.Random(int(s0 * 10) + 3)
                c0 = self.crowd["c"]; R = self.crowd["R"]
                busy = {m["char"] for m in tl.moves if s0 <= m["t0"] < t}
                for n in self.crowd["names"]:
                    if n in busy:
                        continue
                    tt = s0 + rr.uniform(0, 2.5)
                    while tt < t:
                        a = rr.uniform(0, 6.28); rad = rr.uniform(3.6, R)
                        dst = (c0[0] + rad * math.cos(a), c0[1] + rad * math.sin(a))
                        ax, az = self.pos[n]
                        dist = math.hypot(dst[0] - ax, dst[1] - az)
                        if dist < 1.5:
                            continue
                        dur = dist / rr.uniform(1.3, 2.2)
                        tl.moves.append(dict(char=n, t0=tt, t1=tt + dur, **{"from": (ax, az)}, to=dst, dist=dist, run=False))
                        self.pos[n] = dst
                        tt += dur + rr.uniform(1.0, 5.0)
            tl.scenes.append(dict(name=sname, t0=s0, t1=t, participants=sorted(participants), world=cur_world,
                                  time=cur_time, mood=cur_mood))
            tl.music_plan.append((s0, t, cur_mood))
        tl.duration = t + (1.5 if tl.end_text else 0.5)
        if tl.music_plan:
            a, _, md = tl.music_plan[-1]
            tl.music_plan[-1] = (a, tl.duration, md)
        if tl.scenes:
            tl.scenes[-1]["t1"] = tl.duration
            tl.shots[-1]["t1"] = tl.duration
        return tl


# ---------------------------------------------------------------- camera
class Director:
    def __init__(self, tl, world):
        self.tl = tl
        self.world = world
        self.cur = None
        self.eye = self.tgt = None
        self.side = (0.0, 1.0)  # camera stays on +z side (house is behind)

    def shot_at(self, t):
        for s in self.tl.shots:
            if s["t0"] <= t < s["t1"]:
                return s
        return self.tl.shots[-1]

    def frame(self, t, dt, poses, snap=False):
        sh = self.shot_at(t)
        cut = sh is not self.cur or snap
        self.cur = sh
        kind, a, b = sh["spec"]
        u = (t - sh["t0"]) / max(0.1, sh["t1"] - sh["t0"])
        P = {p["name"]: p for p in poses if not p.get("hidden")}
        group = [P[n] for n in sh["participants"] if n in P] or list(P.values())
        if len(group) > 2:  # keep the biggest cluster (around the shot subject if any)
            def cluster(c):
                return [p for p in group if math.hypot(p["x"] - c["x"], p["z"] - c["z"]) < 16]
            if a in P:
                group = cluster(P[a]) or group
            else:
                best = max(group, key=lambda c: (len(cluster(c)), -len([1 for p in cluster(c) if p.get("mob")])))
                group = cluster(best)
        if kind in ("close", "ots", "medium", "follow") and a not in P:
            kind, a, b = "two", None, None
        if a in P and group and all(math.hypot(P[a]["x"] - p["x"], P[a]["z"] - p["z"]) > 16 for p in group if p is not P[a]):
            kind, a, b = "two", None, None  # speaker is off-screen: show the listeners
        head = lambda p: (p["x"], p["y"] + 1.5, p["z"])
        cx = sum(p["x"] for p in group) / len(group)
        cz = sum(p["z"] for p in group) / len(group)
        cy = sum(p["y"] for p in group) / len(group)
        spread = max([math.dist((p["x"], p["z"]), (q["x"], q["z"])) for p in group for q in group] + [0])
        # axis normal on camera side
        if len(group) >= 2:
            ax, az = group[-1]["x"] - group[0]["x"], group[-1]["z"] - group[0]["z"]
            L = math.hypot(ax, az) or 1
            nx, nz = -az / L, ax / L
            if nx * self.side[0] + nz * self.side[1] < 0:
                nx, nz = -nx, -nz
        else:
            nx, nz = self.side
        fov = 45
        if kind in ("wide", "two"):
            dist = max(5.0, spread * 0.95 + 4.0) if kind == "wide" else max(3.2, spread * 0.75 + 2.4)
            extra_fov = 0.0
            if dist > 10.0:
                extra_fov = min(20.0, (dist - 10.0) * 2.2)
                dist = 10.0
            ang = (0.22 if kind == "wide" else 0.0) - 0.06 + 0.12 * u
            rx = nx * math.cos(ang) - nz * math.sin(ang)
            rz = nx * math.sin(ang) + nz * math.cos(ang)
            dist *= (1 - 0.06 * u)
            hgt = 1.9 if kind == "wide" else 1.55
            eye = (cx + rx * dist, cy + hgt + 0.06 * dist, cz + rz * dist)
            tgt = (cx, cy + (0.95 if kind == "wide" else 1.15), cz)
            fov = (45 if kind == "wide" else 40) + extra_fov
        elif kind in ("close", "ots", "medium") and a in P:
            A = P[a]
            Bp = P.get(b) if b else None
            if Bp is not None:
                dx, dz = Bp["x"] - A["x"], Bp["z"] - A["z"]
                L = math.hypot(dx, dz) or 1
                dx, dz = dx / L, dz / L
            else:
                dx, dz = math.sin(A["yaw"]), math.cos(A["yaw"])
            sgn = 1 if (dx * -nz + dz * nx) * 0 + (nx * dx + nz * dz) >= 0 else -1
            # rotate the A->B direction toward the camera side
            def rot(x, z, ang):
                return x * math.cos(ang) - z * math.sin(ang), x * math.sin(ang) + z * math.cos(ang)
            spread_ang = 1.05 if kind == "medium" else 0.6
            r1 = rot(dx, dz, spread_ang); r2 = rot(dx, dz, -spread_ang)
            side_dir = r1 if (r1[0] * nx + r1[1] * nz) > (r2[0] * nx + r2[1] * nz) else r2
            hx, hy, hz = head(A)
            if kind == "close":
                d = 2.05 * (1 - 0.05 * u)
                eye = (hx + side_dir[0] * d, hy + 0.08, hz + side_dir[1] * d)
                tgt = (hx, hy - 0.12, hz)
                fov = 36
            elif kind == "medium":
                d = 3.9 * (1 - 0.05 * u)
                eye = (A["x"] + side_dir[0] * d, A["y"] + 1.45, A["z"] + side_dir[1] * d)
                tgt = (A["x"], A["y"] + 1.05, A["z"])
                fov = 42
            else:  # ots: behind B's shoulder looking at A
                if Bp is None:
                    eye = (hx + side_dir[0] * 2.4, hy + 0.1, hz + side_dir[1] * 2.4); tgt = (hx, hy - 0.1, hz)
                else:
                    bx, by, bz = head(Bp)
                    px, pz = -dz, dx  # perpendicular
                    if px * nx + pz * nz < 0:
                        px, pz = -px, -pz
                    back = 1.3 * (1 - 0.05 * u)
                    eye = (bx + dx * back + px * 1.05, by + 0.25, bz + dz * back + pz * 1.05)
                    tgt = (hx - px * 0.15, hy - 0.3, hz - pz * 0.15)
                fov = 38
        elif kind == "orbit":
            ang = math.atan2(nx, nz) + (t - sh["t0"]) * 0.12 - 0.35
            dist = min(9.0, max(6.0, spread * 0.4 + 4.5))
            eye = (cx + math.sin(ang) * dist, cy + 3.0, cz + math.cos(ang) * dist)
            tgt = (cx, cy + 1.2, cz - 1.5)
            fov = 50
        elif kind == "top":
            eye = (cx + nx * 4, cy + 9, cz + nz * 4); tgt = (cx, cy, cz); fov = 50
        elif kind == "follow" and a in P:
            A = P[a]
            fx, fz = math.sin(A["yaw"]), math.cos(A["yaw"])
            eye = (A["x"] - fx * 4.2 + nx * 1.6, A["y"] + 2.3, A["z"] - fz * 4.2 + nz * 1.6)
            tgt = (A["x"] + fx * 2, A["y"] + 1.0, A["z"] + fz * 2)
            fov = 48
        else:
            eye = (cx + nx * 6, cy + 2, cz + nz * 6); tgt = (cx, cy + 1, cz)
        # pull the desired camera in front of blocking blocks BEFORE smoothing, so it never pops
        g0 = self.world.ground(eye[0], eye[2])
        eye = (eye[0], max(eye[1], g0 + 0.5), eye[2])
        for p in group:
            eye = self.unblock((p["x"], p["y"] + 1.4, p["z"]), eye)
        eye = self.unblock(tgt, eye)
        if cut or self.eye is None:
            self.eye, self.tgt, self.fov = list(eye), list(tgt), fov
            self.ve, self.vt = [0.0] * 3, [0.0] * 3
        else:
            self.eye, self.ve = self.damp(self.eye, eye, self.ve, 0.45, 7.0, dt)
            self.tgt, self.vt = self.damp(self.tgt, tgt, self.vt, 0.35, 8.0, dt)
            self.fov += (fov - self.fov) * (1 - math.exp(-dt * 3))
        # keep camera above ground and out of blocks (pull in toward target)
        g = self.world.ground(self.eye[0], self.eye[2])
        self.eye[1] = max(self.eye[1], g + 0.5)
        return tuple(self.eye), tuple(self.tgt), self.fov

    @staticmethod
    def damp(cur, target, vel, smooth_time, max_speed, dt):
        """critically damped spring with a speed limit (no camera pops)"""
        omega = 2.0 / smooth_time
        x = omega * dt
        e = 1.0 / (1 + x + 0.48 * x * x + 0.235 * x * x * x)
        ch = [c - t for c, t in zip(cur, target)]
        mag = math.sqrt(sum(c * c for c in ch))
        lim = max_speed * smooth_time
        if mag > lim:
            ch = [c * lim / mag for c in ch]
        tgt2 = [c - d for c, d in zip(cur, ch)]
        temp = [(v + omega * d) * dt for v, d in zip(vel, ch)]
        vel = [(v - omega * tp) * e for v, tp in zip(vel, temp)]
        out = [t2 + (d + tp) * e for t2, d, tp in zip(tgt2, ch, temp)]
        return out, vel

    def unblock(self, tgt, eye):
        B = self.world.blocks
        tx, ty, tz = tgt
        ex, ey, ez = eye
        L = math.dist(tgt, eye)
        n = max(2, int(L * 6))
        last_free = 0.0
        for i in range(2, n + 1):
            k = i / n
            p = (tx + (ex - tx) * k, ty + (ey - ty) * k, tz + (ez - tz) * k)
            hit = False
            for ox in (-0.25, 0.25):
                for oz in (-0.25, 0.25):
                    b = B.get((math.floor(p[0] + ox), math.floor(p[1]), math.floor(p[2] + oz)))
                    if b is not None and b != "water":
                        hit = True
            if hit:
                k2 = max(0.15, last_free - 0.04)
                return (tx + (ex - tx) * k2, ty + (ey - ty) * k2, tz + (ez - tz) * k2)
            last_free = k
        return eye
