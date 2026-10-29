"""Build the NOVA entity tree: assets/entities/<id>/{textures, <id>.geo.json,
<id>.animation.json, <id>.entity.json}.

Geometry is converted from the game's legacy Mobs.json/Vanilla.json into the
modern Bedrock / Blockbench format (format_version 1.12.0), with parent
geometries flattened so every file stands alone.  At runtime NovaActivity
(EntityRegistry.java) turns these files back into the legacy Mobs.json the
engine reads, and redirects every mob texture into the entity folders.

usage: gen_entities.py GAME_ASSETS OUT_ASSETS
"""
import json, os, re, shutil, sys, copy

SRC, OUT = sys.argv[1], sys.argv[2]
BASE = os.path.join(SRC, 'images/mob/skins/Base')


def load_legacy(fn):
    s = open(fn, encoding='utf-8').read()
    s = re.sub(r'//[^\n]*', '', s)
    s = re.sub(r'([\]\}\d"e])(\s*\n\s*")', r'\1,\2', s)   # Mobs.json has a missing comma
    s = re.sub(r',(\s*[\]\}])', r'\1', s)
    return json.loads(s)


legacy = {}
for fn in ('Vanilla.json', 'Mobs.json'):
    for k, v in load_legacy(os.path.join(BASE, fn)).items():
        name, _, parent = k.partition(':')
        legacy[name] = (parent or None, v)


def flatten(name):
    parent, g = legacy[name]
    if parent:
        out = flatten(parent)
    else:
        out = {'bones': []}
    for k in ('texturewidth', 'textureheight'):
        if k in g:
            out[k] = g[k]
    bones = out['bones']
    for b in g.get('bones', []):
        for i, ob in enumerate(bones):
            if ob['name'] == b['name']:
                # engine semantics: an inherited bone keeps its cubes and the
                # child's cubes are appended, unless the child says "reset"
                upd = copy.deepcopy(b)
                if 'cubes' in upd and not truthy(upd.get('reset')):
                    upd['cubes'] = copy.deepcopy(ob.get('cubes', [])) + upd['cubes']
                upd.pop('reset', None)
                nb = dict(ob); nb.update(upd); bones[i] = nb
                break
        else:
            nb = copy.deepcopy(b); nb.pop('reset', None)
            bones.append(nb)
    return out


def truthy(v):
    return v is True or v == 'true'


def to_modern(ident, flat, tex_size=None):
    """legacy geometry -> minecraft:geometry entry (1.12.0)"""
    bones = []
    for b in flat['bones']:
        nb = {'name': b['name']}
        if 'parent' in b: nb['parent'] = b['parent']
        nb['pivot'] = b.get('pivot', [0, 0, 0])
        if 'rotation' in b: nb['rotation'] = b['rotation']
        if truthy(b.get('mirror')): nb['mirror'] = True
        if 'inflate' in b: nb['inflate'] = b['inflate']
        if truthy(b.get('neverRender')): nb['never_render'] = True
        if truthy(b.get('reset')): nb['reset'] = True
        # engine-specific extras, kept verbatim
        if 'material' in b: nb['material'] = b['material']
        if 'pos' in b: nb['pos'] = b['pos']
        cubes = []
        for c in b.get('cubes', []):
            nc = {'origin': c['origin'], 'size': c['size'], 'uv': c.get('uv', [0, 0])}
            if 'inflate' in c: nc['inflate'] = c['inflate']
            cubes.append(nc)
        if cubes: nb['cubes'] = cubes
        bones.append(nb)
    return {
        'description': {
            'identifier': ident,
            # no size in the old file: the engine took it from the texture itself
            'texture_width': flat.get('texturewidth', tex_size[0] if tex_size else 64),
            'texture_height': flat.get('textureheight', tex_size[1] if tex_size else 32),
        },
        'bones': bones,
    }


# --- hand-written geometry for models that 0.14 still builds in C++ --------
# (stored in the same format; the native loader for these comes in the next
#  stage, until then they are reference files)
# --- models the game builds in C++ ------------------------------------------
# tools/emu_models.py runs their constructors (and setupAnim for the bind pose)
# in an emulator; here the result becomes Blockbench geometry.  libnova then
# rebuilds those ModelParts from the geometry ("rebuild" in nova:legacy).
import math
NATIVE_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'native_models.json')
NM = json.load(open(NATIVE_JSON))
REBUILD = {
    # model key: (geometry id, C++ class (vtable), condition, {offset: bone})
    'chicken': ('geometry.chicken', 'ChickenModel', None,
                {0xb4: 'head', 0x514: 'beak', 0x5b4: 'comb', 0x1f4: 'body', 0x294: 'leg0', 0x334: 'leg1',
                 0x3d4: 'wing0', 0x474: 'wing1'}),
    'rabbit': ('geometry.rabbit', 'RabbitModel', None,
               {0xb4: 'rearFootLeft', 0x154: 'rearFootRight', 0x1f4: 'haunchLeft', 0x294: 'haunchRight',
                0x334: 'body', 0x3d4: 'frontLegLeft', 0x474: 'frontLegRight', 0x514: 'head', 0x5b4: 'earRight',
                0x654: 'earLeft', 0x6f4: 'tail', 0x794: 'nose'}),
    'blaze': ('geometry.blaze', 'BlazeModel', None,
              dict([(0xb4 + 0xa0 * i, 'upperBodyParts%d' % i) for i in range(12)] + [(0x834, 'head')])),
    'slime': ('geometry.slime', 'SlimeModel', (0xb4 + 0x44, 16),
              {0xb4: 'cube', 0x158: 'eye0', 0x1f8: 'eye1', 0x298: 'mouth'}),
    'slime_outer': ('geometry.slime.armor', 'SlimeModel', (0xb4 + 0x44, 0),
                    {0xb4: 'cube', 0x158: 'eye0', 0x1f8: 'eye1', 0x298: 'mouth'}),
    'magma_cube': ('geometry.magma_cube', 'LavaSlimeModel', None,
                   dict([(0xb4 + 0xa0 * i, 'bodyCube_%d' % i) for i in range(8)] + [(0x5b4, 'insideCube')])),
    'silverfish': ('geometry.silverfish', 'SilverfishModel', None,
                   dict([(0xdc + 0xa0 * i, 'bodyPart_%d' % i) for i in range(7)] +
                        [(0x53c, 'bodyLayer_0'), (0x5dc, 'bodyLayer_1'), (0x67c, 'bodyLayer_2')])),
    'boat': ('geometry.boat', 'BoatModel', None,
             {0xb4: 'bottom', 0x154: 'back', 0x1f4: 'front', 0x294: 'right', 0x334: 'left'}),
    'minecart': ('geometry.minecart', 'MinecartModel', None,
                 {0xb4: 'bottom', 0x154: 'back', 0x1f4: 'front', 0x294: 'right', 0x334: 'left'}),
}


def blaze_pose(name):
    """Blaze rods are placed by setupAnim; the emulator lacks the game's sin table."""
    i = int(name[len('upperBodyParts'):])
    if i < 4: f, r, y = 0 + i, 9.0, -2.0 + math.cos((i * 2) * 0.25)
    elif i < 8: f, r, y = 0.7853982 + (i - 4), 7.0, 2.0 + math.cos((i * 2) * 0.25)
    else: f, r, y = 0.47123894 + (i - 8), 5.0, 11.0 + math.cos((i * 1.5) * 0.5)
    return [round(math.cos(f) * r, 4), round(y, 4), round(math.sin(f) * r, 4)]


def r4(v): return round(v + 0.0, 4)


def native_geometry(key):
    gid, cls, cond, names = REBUILD[key]
    parts = {p['offset']: p for p in NM[key] if p['offset'] is not None}
    tw, th = parts[min(names)]['texsize']
    bones = []
    for off, name in sorted(names.items(), key=lambda kv: kv[0]):
        p = parts[off]
        px, py, pz = blaze_pose(name) if name.startswith('upperBodyParts') else p['pos']
        b = {'name': name, 'pivot': [r4(px), r4(24 - py), r4(pz)]}
        rot = [round(math.degrees(a), 3) for a in p['rot']]
        if any(abs(a) > 1e-4 for a in rot): b['rotation'] = [r4(a) for a in rot]
        if p.get('mirror'): b['mirror'] = True
        cubes = []
        for bx in p['boxes']:
            (ox, oy, oz), (sx, sy, sz) = bx['origin'], bx['size']
            c = {'origin': [r4(px + ox), r4(24 - py - oy - sy), r4(pz + oz)], 'size': [r4(sx), r4(sy), r4(sz)],
                 'uv': bx['uv']}
            if bx['inflate']: c['inflate'] = bx['inflate']
            if bx['mirror'] and not p.get('mirror'): c['mirror'] = True
            cubes.append(c)
        if cubes: b['cubes'] = cubes
        bones.append(b)
    return {'description': {'identifier': gid, 'texture_width': tw, 'texture_height': th}, 'bones': bones}


HAND = {REBUILD[k][0]: native_geometry(k) for k in REBUILD}
REBUILD_BY_GEO = {v[0]: v for v in REBUILD.values()}


# --- animations (Bedrock 1.8.0 animation format, Molang) --------------------
# Played by libnova after the game's own setupAnim; a bone named here gets
# rotation = bind pose + animation (the game's motion for it is replaced),
# bones not named keep the vanilla behaviour (attacks, sitting, eating...).
# The defaults reproduce vanilla motion; packs change or add to them.
DIST = 'query.modified_distance_moved'


def look():
    return {'loop': True, 'bones': {'head': {'rotation': ['query.target_x_rotation', 'query.target_y_rotation', 0.0]}}}


def legs(pairs, amp=80.0):
    """pairs: [(bone, phase)] - phase 0 or 180"""
    return {'loop': True, 'anim_time_update': DIST, 'bones': {
        b: {'rotation': ['math.cos(query.anim_time * 38.17%s) * %.1f * query.modified_move_speed'
                         % (' + 180.0' if ph else '', amp), 0.0, 0.0]} for b, ph in pairs}}


QUAD = [('leg0', 0), ('leg1', 180), ('leg2', 180), ('leg3', 0)]
HUM = [('rightLeg', 0), ('leftLeg', 180)]
ANIM = {
    'quadruped': {'animation.quadruped.walk': legs(QUAD), 'animation.common.look_at_target': look()},
    'sheep': {'animation.quadruped.walk': legs(QUAD)},          # head: vanilla (grass eating)
    'creeper': {'animation.creeper.legs': legs(QUAD), 'animation.common.look_at_target': look()},
    'humanoid': {'animation.humanoid.legs': legs(HUM), 'animation.common.look_at_target': look()},
    'villager': {'animation.villager.legs': legs([('leg0', 0), ('leg1', 180)], 40.0),
                 'animation.common.look_at_target': look()},
    'look': {'animation.common.look_at_target': look()},
}

# --- entity table ----------------------------------------------------------
# id: (identifier, [geometry ids owned by this folder], {legacy texture path},
#      animation set, model source)
E = {
    'chicken':        ('minecraft:chicken', ['geometry.chicken'], ['chicken.png'], None, 'rebuild'),
    'cow':            ('minecraft:cow', ['geometry.cow'], ['cow.png'], 'quadruped', 'json'),
    'mooshroom':      ('minecraft:mooshroom', [], ['mooshroom.png'], 'quadruped', 'json'),
    'pig':            ('minecraft:pig', ['geometry.pig'], ['pig.png', 'saddle.png'], 'quadruped', 'json'),
    'sheep':          ('minecraft:sheep', ['geometry.sheep', 'geometry.sheep.sheared'], ['sheep.tga'], 'sheep', 'json'),
    'wolf':           ('minecraft:wolf', ['geometry.wolf'], ['wolf.png', 'wolf_angry.png', 'wolf_tame.tga'], None, 'json'),
    'ocelot':         ('minecraft:ocelot', ['geometry.ocelot'], ['cat/ocelot.png', 'cat/blackcat.png', 'cat/red.png', 'cat/siamese.png'], None, 'json'),
    'rabbit':         ('minecraft:rabbit', ['geometry.rabbit'], ['rabbit/brown.png', 'rabbit/white.png', 'rabbit/blackrabbit.png', 'rabbit/gold.png', 'rabbit/salt.png', 'rabbit/white_splotched.png', 'rabbit/toast.png'], None, 'rebuild'),
    'bat':            ('minecraft:bat', ['geometry.bat'], ['bat.png'], None, 'json'),
    'squid':          ('minecraft:squid', ['geometry.squid'], ['squid.png'], None, 'json'),
    'villager':       ('minecraft:villager', ['geometry.villager'], ['villager/villager.png', 'villager/farmer.png', 'villager/librarian.png', 'villager/priest.png', 'villager/smith.png', 'villager/butcher.png'], 'villager', 'json'),
    'witch':          ('minecraft:witch', ['geometry.villager.witch'], ['witch.png'], 'villager', 'json'),
    'iron_golem':     ('minecraft:iron_golem', ['geometry.irongolem'], ['iron_golem.png'], 'look', 'json'),
    'snow_golem':     ('minecraft:snow_golem', ['geometry.snowgolem'], ['snow_golem.png'], 'look', 'json'),
    'zombie':         ('minecraft:zombie', ['geometry.zombie'], ['zombie.png'], 'humanoid', 'json'),
    'zombie_villager':('minecraft:zombie_villager', ['geometry.zombie.villager'], ['zombie_villager/zombie_villager.png', 'zombie_villager/zombie_farmer.png', 'zombie_villager/zombie_librarian.png', 'zombie_villager/zombie_priest.png', 'zombie_villager/zombie_smith.png', 'zombie_villager/zombie_butcher.png'], 'humanoid', 'json'),
    'zombie_pigman':  ('minecraft:zombie_pigman', ['geometry.pigzombie', 'geometry.pigzombie.baby'], ['pigzombie.png'], 'humanoid', 'json'),
    'skeleton':       ('minecraft:skeleton', ['geometry.skeleton'], ['skeleton.png'], 'humanoid', 'json'),
    'wither_skeleton':('minecraft:wither_skeleton', [], ['wither_skeleton.png'], 'humanoid', 'json'),
    'creeper':        ('minecraft:creeper', ['geometry.creeper', 'geometry.creeper.charged'], ['creeper.png', 'creeper_armor.png'], 'creeper', 'json'),
    'spider':         ('minecraft:spider', ['geometry.spider'], ['spider.tga'], 'look', 'json'),
    'cave_spider':    ('minecraft:cave_spider', [], ['cave_spider.tga'], 'look', 'json'),
    'enderman':       ('minecraft:enderman', ['geometry.enderman'], ['enderman.tga'], 'look', 'json'),
    'silverfish':     ('minecraft:silverfish', ['geometry.silverfish'], ['silverfish.png'], None, 'rebuild'),
    'slime':          ('minecraft:slime', ['geometry.slime', 'geometry.slime.armor'], ['slime.png'], None, 'rebuild'),
    'magma_cube':     ('minecraft:magma_cube', ['geometry.magma_cube'], ['magmacube.tga'], None, 'rebuild'),
    'ghast':          ('minecraft:ghast', ['geometry.ghast'], ['ghast.png', 'ghast_shooting.tga'], None, 'json'),
    'blaze':          ('minecraft:blaze', ['geometry.blaze'], ['blaze.tga'], None, 'rebuild'),
    # players and non-mob entities (models still built in C++)
    'player':         ('minecraft:player', [], ['steve.png', 'alex.png'], None, 'native'),
    'boat':           ('minecraft:boat', ['geometry.boat'], ['images/entity/boat/boat_%s.png' % w for w in ('oak', 'spruce', 'birch', 'jungle', 'acacia', 'darkoak')], None, 'rebuild'),
    'minecart':       ('minecraft:minecart', ['geometry.minecart'], ['images/entity/minecart.png'], None, 'rebuild'),
    'arrow':          ('minecraft:arrow', [], ['images/item/arrows.png'], None, 'native'),
    'xp_orb':         ('minecraft:xp_orb', [], ['images/entity/experience_orb.png'], None, 'native'),
    'fireball':       ('minecraft:fireball', [], ['images/entity/fireball.png'], None, 'native'),
    'fishing_hook':   ('minecraft:fishing_hook', [], ['images/entity/fishhook.png'], None, 'native'),
    # shared templates (armor layers, the generic quadruped)
    '_shared':        (None, ['geometry.quadruped', 'geometry.humanoid.armor1', 'geometry.humanoid.armor2'], [], None, 'json'),
}
# which geometry a texture-only entity draws with
USES = {'mooshroom': 'geometry.cow', 'wither_skeleton': 'geometry.skeleton',
        'cave_spider': 'geometry.spider', 'rabbit': None, 'silverfish': None,
        'slime': None, 'magma_cube': None, 'blaze': None}


def dump(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write('\n')


root = os.path.join(OUT, 'entities')
if os.path.isdir(root):
    shutil.rmtree(root)
owned = set()
renamed = []
for eid, (ident, geos, texs, anim, src) in E.items():
    d = os.path.join(root, eid)
    os.makedirs(d, exist_ok=True)
    tex_map = {}
    textures = {}
    for t in texs:
        fname = os.path.basename(t)
        legacy_path = t if t.startswith('images/') else 'images/mob/' + t
        shutil.copyfile(os.path.join(SRC, legacy_path), os.path.join(d, fname))
        tex_map[legacy_path] = fname
        renamed.append(legacy_path)
        textures[os.path.splitext(fname)[0] if textures else 'default'] = fname
    if geos:
        arr = []
        for g in geos:
            owned.add(g)
            tsize = None
            if texs:
                from PIL import Image
                lp = texs[0] if texs[0].startswith('images/') else 'images/mob/' + texs[0]
                tsize = Image.open(os.path.join(SRC, lp)).size
            elif g.startswith('geometry.humanoid.armor'):
                tsize = (64, 32)
            elif g == 'geometry.quadruped':
                tsize = (64, 32)
            arr.append(HAND[g] if g in HAND else to_modern(g, flatten(g), tsize))
        dump(os.path.join(d, eid + '.geo.json'), {'format_version': '1.12.0', 'minecraft:geometry': arr})
    if anim:
        dump(os.path.join(d, eid + '.animation.json'),
             {'format_version': '1.8.0', 'animations': ANIM[anim]})
    if ident:
        geo_ref = geos[0] if geos else USES.get(eid)
        desc = {
            'identifier': ident,
            'textures': textures,
            'geometry': {'default': geo_ref} if geo_ref else {},
            'animations': {k.split('.')[-1]: k for k in ANIM[anim]} if anim else {},
            'scripts': {'animate': [k.split('.')[-1] for k in ANIM[anim]]} if anim else {},
            'nova:legacy': {
                # which game path each file in this folder replaces
                'textures': tex_map,
                # 'json'   = engine already builds this mob from the geometry file
                # 'native' = still built in C++; geometry here is the reference
                #            for the NOVA native model loader (next stage)
                'model': src,
                # 'rebuild' = C++ model whose parts libnova reloads from the geometry
                'rebuild': [{'class': REBUILD_BY_GEO[g][1], 'geometry': g,
                             'when': list(REBUILD_BY_GEO[g][2]) if REBUILD_BY_GEO[g][2] else None,
                             'parts': {n: o for o, n in REBUILD_BY_GEO[g][3].items()}}
                            for g in geos if g in REBUILD_BY_GEO],
            },
        }
        dump(os.path.join(d, eid + '.entity.json'),
             {'format_version': '1.10.0', 'minecraft:client_entity': {'description': desc}})

missing = [k for k in legacy if k not in owned and not k.startswith('geometry.humanoid')
           and k.count('.') == 1 or (k in ('geometry.zombie.villager',) and k not in owned)]
missing = [k for k in missing if k in [n.partition(':')[0] for n in load_legacy(os.path.join(BASE, 'Mobs.json'))] and k not in owned]
assert not missing, 'Mobs.json geometry without an owner: %r' % missing
# bone names the C++ models look up by name: these keep their plain name in the
# generated Mobs.json; every other child bone is attached by libnova
dump(os.path.join(root, '_shared', 'native_bones.json'),
     dict([(g, [b['name'] for b in flatten(g)['bones']]) for g in sorted(owned) if g in legacy] +
          [(g, sorted(v[3].values())) for g, v in REBUILD_BY_GEO.items()]))
# legacy copies are renamed old_* in the APK, so nothing reads them by accident
with open(os.path.join(OUT, '..', 'entity_renames.txt'), 'w') as f:
    for p in renamed:
        dd, bb = os.path.split(p)
        f.write('assets/%s>assets/%s/old_%s\n' % (p, dd, bb))
print('entities:', len(E), 'geometries:', len(owned))
