"""Russian screenplay format -> timeline (moves, actions, looks, speech, camera shots)."""
import math
import os
import re

from .anim import ACTIONS, BIG_ACTIONS
from . import anim as _anim
from .library import Library
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
    "pose": "поза принимает_позу pose",
    "fly": "летит полетел полетела взлетает fly",
    "spin": "вращается крутится кружится spin",
    "wobble": "качается шатается wobble",
    "resize": "размер растет растёт уменьшается увеличивается resize",
    "attach": "крепится цепляется прилипает attach",
    "detach": "отцепляется отпадает detach",
    "effect": "эффект effect",
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
         "auto": "auto", "двое": "two", "two": "two", "точка": "fixed", "fixed": "fixed", "путь": "path", "path": "path",
         "проезд": "path"}

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
        self.poses = []
        self.attach, self.scales = [], []
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
        if Library.is_lib_line(line):
            head.setdefault("_lib", []).append(line)
            continue
        m = re.match(r"^(строить|стройка|build)\s+([^:]+):\s*(.*)$", line, re.I)
        if m:
            head.setdefault("_builds", []).append((norm(m.group(2)), m.group(3).strip()))
            continue
        m = re.match(r"^(актер|актёр|персонаж|cast|моб|mob|объект|object)\s+([^:]+):\s*(.*)$", line, re.I)
        if m:
            name = m.group(2).strip()
            if m.group(1).lower() in ("объект", "object"):
                opts = dict(re.findall(r"(\S+?)=(\S+)", m.group(3)))
                p = opts.get("место", "0,0").split(",")
                if "модель" in opts:
                    f = opts["модель"]
                    spec = dict(kind="model", model=f if os.path.isabs(f) else os.path.join(base, f))
                elif "предмет" in opts:
                    it = opts["предмет"]
                    spec = dict(kind="item", item=next((v for k, v in ITEMS.items() if norm(it).startswith(k)), it))
                else:
                    spec = dict(kind="block", block=opts.get("блок", "тнт"))
                spec["size"] = float(opts.get("размер", 1.0))
                cast[name] = dict(name=name, prop=spec, pos=(float(p[0]), float(p[1])), color="#dddddd", voice="pavel",
                                  pitch=None, rate=None, skin=None, lift=float(opts.get("высота", 0)),
                                  hidden=norm(opts.get("скрыт", "нет")) in ("да", "yes"))
                if "поворот" in opts:
                    cast[name]["yaw"] = math.radians(float(opts["поворот"]))
                continue
            if m.group(1).lower() in ("моб", "mob"):
                from .mobs import MOB_RU
                opts = dict(re.findall(r"(\S+?)=(\S+)", m.group(3)))
                kind = MOB_RU.get(norm(opts.get("тип", opts.get("type", "корова"))), "cow")
                p = opts.get("место", opts.get("pos", "0,0")).split(",")
                cast[name] = dict(name=name, mob=kind, pos=(float(p[0]), float(p[1])), color=opts.get("цвет", "#dddddd"),
                                  voice=None, pitch=None, rate=None, skin=None, texture=opts.get("текстура"),
                                  lift=float(opts.get("высота", 0)), scale=float(opts.get("размер", 1)),
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
            cfg["scale"] = float(opts.get("размер", 1)); cfg["lift"] = float(opts.get("высота", 0))
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
        lib = Library()
        libdir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "library")
        if os.path.isdir(libdir):
            for f in sorted(os.listdir(libdir)):
                if f.endswith(".txt"):
                    lib.load_file(os.path.join(libdir, f))
        lib.load_lines(self.head.get("_lib", []))
        self.lib = lib
        _anim.LIB = lib
        for nm, act in lib.actions.items():
            ACTIONS[nm] = (act, act.dur)
            ACT[nm.replace("ё", "е")] = nm
            for al in act.aliases:
                ACT[al.lower().replace("ё", "е")] = nm
            if act.big:
                BIG_ACTIONS.add(nm)
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
        self.hgt = {}
        from .build import material as _mat
        for c in castcfg.values():
            if c.get("prop") and c["prop"]["kind"] == "block":
                c["prop"]["block"] = _mat(c["prop"]["block"])

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
            for j in (4, 3, 2):
                cand = "_".join(norm(x).strip(",.!?") for x in toks[i:i + j])
                if len(toks[i:i + j]) == j and cand in ACT:
                    kind = ACT[cand]; toks = toks[i + j:]; break
            if kind:
                break
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
        self.last_tokens = [norm(x).strip(",.!?") for x in toks]
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
        if kind == "pose":
            nm = None
            toks = getattr(self, "last_tokens", [])
            for j in (3, 2, 1):
                for i in range(len(toks)):
                    cand = "_".join(toks[i:i + j])
                    if cand in self.lib.actions or ACT.get(cand) in self.lib.actions:
                        nm = self.lib.actions.get(cand) and cand or ACT.get(cand); break
                if nm:
                    break
            tl.poses.append(dict(char=subj, t=t0, name=nm))
            return t0 + (num or 0.6)
        if kind == "effect":
            toks = getattr(self, "last_tokens", [])
            nm = next((x for x in toks if x in self.lib.effects), None)
            if nm:
                ev = dict(kind="gen", name=nm, t0=t0, t1=t0 + (num or 2.0), char=subj)
                if target and target[0] == "char":
                    ev["char"] = target[1]
                elif target and target[0] == "point":
                    ev["char"] = None
                    ev["point"] = (target[1][0], self.world.ground(*target[1]) + 0.8, target[1][1])
                P.append(ev)
                if nm == "взрыв":
                    tl.sfx.append((t0, "random_explode", 1.0))
            return t0 + 0.2
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
        if kind == "resize":
            tl.scales.append(dict(char=subj, t=t0, v=num or 1.0))
            tl.sfx.append((t0, "random_pop", 0.6))
            return t0 + 0.6
        if kind == "attach":
            if target and target[0] == "char":
                toks = getattr(self, "last_tokens", [])
                slot = "голова" if any(x.startswith("голов") for x in toks) else "спина" if any(x.startswith("спин") for x in toks) \
                    else "левая_рука" if "левую" in toks or "левой" in toks else "рука"
                tl.attach.append(dict(char=subj, t=t0, to=target[1], slot=slot))
                tl.vis.append(dict(char=subj, t=t0, on=True))
            return t0 + 0.3
        if kind == "detach":
            owner = next((a["to"] for a in reversed(tl.attach) if a["char"] == subj and a["t"] <= t0 and a["to"]), None)
            tl.attach.append(dict(char=subj, t=t0, to=None, slot=None))
            if owner:
                ox, oz = self.pos[owner]
                tl.moves.append(dict(char=subj, t0=t0, t1=t0, **{"from": (ox, oz)}, to=(ox + 0.6, oz), dist=0.0, run=False))
                self.pos[subj] = (ox + 0.6, oz)
            return t0 + 0.3
        if kind == "fly":
            dst = self.target_xz(target) or self.pos[subj]
            ax, az = self.pos[subj]
            h0 = self.hgt.get(subj, tl.cast[subj].get("lift", 0.0))
            h1 = num if num is not None else h0
            dist = math.hypot(dst[0] - ax, dst[1] - az)
            dur = max(0.8, math.hypot(dist, h1 - h0) / 6.0 + 0.3)
            tl.moves.append(dict(char=subj, t0=t0, t1=t0 + dur, **{"from": (ax, az)}, to=dst, dist=dist, run=False,
                                 fly=True, h0=h0, h1=h1))
            self.pos[subj] = dst; self.hgt[subj] = h1
            return t0 + dur
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
                tl.poses.append(dict(char=subj, t=t0, name=None))
                for p in tl.particles:
                    if p["char"] == subj and p["kind"] == "zzz" and p["t1"] > t0:
                        p["t1"] = t0
            return t0 + (num or 0.7)
        fn, dflt = ACTIONS[kind]
        d = num or dflt
        if kind in self.lib.actions:
            la = self.lib.actions[kind]
            for sd in la.sounds:
                nm, _, at = sd.partition("@")
                tl.sfx.append((t0 + float(at or 0), nm, 1.0))
            for ef in la.effects:
                nm, _, at = ef.partition("@")
                P.append(dict(kind="gen", name=nm, t0=t0 + float(at or 0), t1=t0 + d, char=subj))
            if target and target[0] == "char":
                tl.faces.append(dict(char=subj, t=t0, target=target)); tl.looks.append(dict(char=subj, t=t0, target=target))
                fly = next((x for x in getattr(self, "last_tokens", []) if x in self.lib.effects and self.lib.effects[x].fly), None)
                if fly or kind == "бросает":
                    fly = fly or "бросок"
                    P.append(dict(kind="gen", name=fly, t0=t0 + 0.25, t1=t0 + 0.7, char=subj, target=target))
                    tl.sfx.append((t0 + 0.25, "random_bow", 0.6))
                    tl.sfx.append((t0 + 0.7, "random_pop", 0.9))
                    tl.clips.append(dict(char=target[1], kind="surprise", t0=t0 + 0.7, t1=t0 + 1.2))
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
            placed = []
            for i in range(cnt):
                nm = f"Прохожая{i + 1}"
                for _try in range(60):
                    a = rr.uniform(0, 6.28); rad = rr.uniform(3.5, self.crowd["R"])
                    q = (cc[0] + rad * math.cos(a), cc[1] + rad * math.sin(a))
                    if all(math.hypot(q[0] - o[0], q[1] - o[1]) > 1.5 for o in placed) and \
                            all((math.floor(q[0] + dx_), 0, math.floor(q[1] + dz_)) not in self.world_factory(opts.get("мир", "город")).blocks
                                for dx_ in (-0.3, 0.3) for dz_ in (-0.3, 0.3)):
                        break
                placed.append(q)
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
                        spec = ("wide", who, None)
                    elif big and big[1] in ("walk", "run"):
                        spec = ("wide", None, None)
                    elif big:
                        spec = ("medium", big[0], addr)
                    elif prev_spec and prev_spec[1] == who and prev_spec[0] in ("close", "ots") and dur < 2.5:
                        spec = prev_spec
                    else:
                        k = nline % 5
                        spec = ("two", who, addr) if k == 4 else (("ots", who, addr) if k % 2 == 0 else ("close", who, addr))
                        if addr is None:
                            spec = ("medium", who, None)
                        elif tl.cast.get(addr, {}).get("extra") and spec[0] in ("ots", "two"):
                            spec = ("close", who, addr)  # never frame over a wandering extra's shoulder
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
                        elif kind in ("fixed", "path"):
                            pts = [tuple(float(v) for v in tk.split(",")) for tk in toks[1:]
                                   if re.match(r"^-?[\d.]+,-?[\d.]+,-?[\d.]+$", tk)]
                            look = None
                            lt = [norm(x) for x in toks]
                            if "смотрит" in lt:
                                k_ = lt.index("смотрит")
                                if k_ + 1 < len(toks):
                                    nx_ = toks[k_ + 1]
                                    c_ = self.find_char(nx_)
                                    look = c_ if c_ else (tuple(float(v) for v in nx_.split(",")) if "," in nx_ else None)
                                    if look and not isinstance(look, str) and len(look) == 2:
                                        look = (look[0], 1.0, look[1])
                                    if not isinstance(look, str) and look in [pts[-1] if pts else None]:
                                        pts = pts[:-1]
                            fv = re.search(r"поле=(\d+)", body)
                            p1 = pts[0] if pts else (0.0, 3.0, 8.0)
                            p2 = pts[1] if len(pts) > 1 else p1
                            cam_mode = (kind, None, None, (p1, p2, look, float(fv.group(1)) if fv else 45.0))
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
                            if subj is None and kind == "effect":
                                subj = names[0]
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
                heroes = [n for n in tl.cast if n not in self.crowd["names"] and not tl.cast[n].get("prop")]
                hero_paths = [m for m in tl.moves if m["char"] in heroes and m["t1"] >= s0 and m["t0"] <= t]
                taken = {}

                def free(p, n_, tt_):
                    for o, q in taken.items():
                        if o != n_ and math.hypot(p[0] - q[0], p[1] - q[1]) < 1.6:
                            return False
                    for h in heroes:  # keep away from where heroes stand / walk in this scene
                        if math.hypot(p[0] - self.pos[h][0], p[1] - self.pos[h][1]) < 2.2:
                            return False
                    for m in hero_paths:
                        for k in range(6):
                            q = (m["from"][0] + (m["to"][0] - m["from"][0]) * k / 5, m["from"][1] + (m["to"][1] - m["from"][1]) * k / 5)
                            if math.hypot(p[0] - q[0], p[1] - q[1]) < 1.6:
                                return False
                    return True
                for n in self.crowd["names"]:
                    taken[n] = self.pos[n]
                cm = {n: [] for n in self.crowd["names"]}
                start = dict(self.pos)
                for h in heroes:  # heroes' own paths in this scene take part in collision checks
                    hm = sorted([m for m in tl.moves if m["char"] == h and m["t1"] >= s0 and m["t0"] <= t], key=lambda m: m["t0"])
                    if hm:
                        cm[h] = [(m["t0"], m["t1"], m["from"], m["to"]) for m in hm]
                        start[h] = hm[0]["from"]

                def cpos(n_, tq):
                    p = start[n_]
                    for (a0, a1, f_, to_) in cm[n_]:
                        if tq >= a1:
                            p = to_
                        elif tq >= a0:
                            k_ = (tq - a0) / max(1e-6, a1 - a0)
                            k_ = k_ - 0.85 * math.sin(2 * math.pi * k_) / (2 * math.pi)
                            return (f_[0] + (to_[0] - f_[0]) * k_, f_[1] + (to_[1] - f_[1]) * k_)
                    return p

                def collides(n_, a0, a1, f_, to_):
                    for k_ in range(17):
                        uu = k_ / 16
                        tq = a0 + (a1 - a0) * uu
                        ss = uu - 0.85 * math.sin(2 * math.pi * uu) / (2 * math.pi)
                        p = (f_[0] + (to_[0] - f_[0]) * ss, f_[1] + (to_[1] - f_[1]) * ss)
                        for o in cm:
                            if o != n_ and cm[o] is not None:
                                q = cpos(o, tq)
                                if math.hypot(p[0] - q[0], p[1] - q[1]) < 0.9:
                                    return True
                    # standing at the destination afterwards
                    for o in cm:
                        if o != n_:
                            for k_ in range(13):
                                q = cpos(o, a1 + k_ * 0.5)
                                if math.hypot(to_[0] - q[0], to_[1] - q[1]) < 1.0:
                                    return True
                    # path must be walkable (no fountain / benches / trees)
                    for k_ in range(13):
                        p = (f_[0] + (to_[0] - f_[0]) * k_ / 12, f_[1] + (to_[1] - f_[1]) * k_ / 12)
                        for dx_, dz_ in ((0.3, 0.3), (-0.3, 0.3), (0.3, -0.3), (-0.3, -0.3)):
                            b_ = self.world.blocks.get((math.floor(p[0] + dx_), 0, math.floor(p[1] + dz_)))
                            if b_ is not None:
                                return True
                    return False
                for n in self.crowd["names"]:
                    if n in busy:
                        continue
                    if n not in cm:
                        cm[n] = []
                    tt = s0 + rr.uniform(0, 2.5)
                    tries = 0
                    while tt < t:
                        tries += 1
                        a = rr.uniform(0, 6.28); rad = rr.uniform(3.6, R)
                        dst = (c0[0] + rad * math.cos(a), c0[1] + rad * math.sin(a))
                        ax, az = self.pos[n]
                        dist = math.hypot(dst[0] - ax, dst[1] - az)
                        if dist < 1.5 or (not free(dst, n, tt) and tries < 40):
                            continue
                        dur_ = dist / 1.7
                        if collides(n, tt, tt + dur_, (ax, az), dst):
                            if tries < 40:
                                continue
                            tt += 1.0; tries = 0
                            continue
                        tries = 0
                        taken[n] = dst
                        cm[n].append((tt, tt + dur_, (ax, az), dst))
                        dur = dur_
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
from .director import Director  # noqa: E402,F401
