#!/usr/bin/env python3
"""Генерирует таблицы перевода MCPE 0.14 -> Java 1.20.1 из minecraft-data."""
import json, struct, os, sys
D = '/home/claude/jbridge/ref/md/data/pc/'
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
leg = json.load(open(D + 'common/legacy.json'))
blocks = json.load(open(D + '1.20/blocks.json'))
items = json.load(open(D + '1.20/items.json'))
ents = json.load(open(D + '1.20/entities.json'))
byname = {b['name']: b for b in blocks}
items_by = {i['name']: i for i in items}
RENAME = {'sign': 'oak_sign', 'wall_sign': 'oak_wall_sign', 'stone_slab': 'smooth_stone_slab', 'grass_path': 'dirt_path', 'portal': 'nether_portal'}

def prop_vals(st):
    if st['type'] == 'bool': return ['true', 'false']
    if st['type'] == 'int':
        return st['values'] if 'values' in st else [str(i) for i in range(st['num_values'])]
    return st['values']

def decode_default(b):
    idx = b['defaultState'] - b['minStateId']
    res = {}
    for st in reversed(b['states']):
        vals = prop_vals(st)
        res[st['name']] = vals[idx % len(vals)]
        idx //= len(vals)
    return res

def state_id(name, props=None):
    name = name.replace('minecraft:', '')
    name = RENAME.get(name, name)
    b = byname[name]
    if not b['states']: return b['defaultState']
    cur = decode_default(b)
    for k, v in (props or {}).items():
        if k in cur and v in prop_vals(next(s for s in b['states'] if s['name'] == k)): cur[k] = v
    idx = 0
    for st in b['states']:
        vals = prop_vals(st)
        idx = idx * len(vals) + vals.index(cur[st['name']])
    return b['minStateId'] + idx

def parse(s):
    if '[' not in s: return s, {}
    n, r = s.split('[', 1)
    props = dict(p.split('=') for p in r.rstrip(']').split(','))
    return n, props

def leg_state(key):
    v = leg['blocks'].get(key)
    if v is None: return None
    n, p = parse(v)
    return state_id(n, p)

# ---- блоки: PE id:meta -> java state ----
OV = {}  # особенности PE 0.14
woods = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak']
for m in range(16):
    OV[(85, m)] = ('%s_fence' % woods[m % 6], {})
    OV[(95, m)] = ('barrier', {})                                      # невидимый бедрок
    OV[(157, m)] = ('%s_slab' % woods[m % 6], {'type': 'double'})      # двойная деревянная плита
    OV[(158, m)] = ('%s_slab' % woods[(m & 7) % 6], {'type': 'top' if m & 8 else 'bottom'})
    OV[(198, m)] = ('dirt_path', {})
    OV[(243, m)] = ('podzol', {})
    OV[(244, m)] = ('beetroots', {'age': str(min(3, m // 2))})
    OV[(245, m)] = ('stonecutter', {})
    OV[(246, m)] = ('crying_obsidian', {})
    OV[(247, m)] = ('iron_block', {})
    OV[(199, m)] = ('air', {})                                         # рамка (в Java это сущность)
    OV[(126, m)] = None                                                # активирующие рельсы = PC 157
    OV[(125, m)] = None                                                # выбрасыватель = PC 158
OV[(50, 0)] = ('torch', {}); OV[(75, 0)] = ('redstone_torch', {'lit': 'false'}); OV[(76, 0)] = ('redstone_torch', {'lit': 'true'})
# плиты: у PE 6 — кварц, 7 — незер-кирпич (у PC наоборот)
slab_pe = ['smooth_stone', 'sandstone', 'oak', 'cobblestone', 'brick', 'stone_brick', 'quartz', 'nether_brick']
for m in range(16):
    OV[(44, m)] = ('%s_slab' % slab_pe[m & 7], {'type': 'top' if m & 8 else 'bottom'})
    OV[(43, m)] = ('%s_slab' % slab_pe[m & 7], {'type': 'double'})
OV_PC = {126: 157, 125: 158}

btab = []
unresolved = []
for pid in range(256):
    for m in range(16):
        sid = None
        o = OV.get((pid, m), 'none')
        if o is None:
            sid = leg_state('%d:%d' % (OV_PC[pid], m)) or leg_state('%d:0' % OV_PC[pid])
        elif o != 'none':
            sid = state_id(o[0], o[1])
        else:
            sid = leg_state('%d:%d' % (pid, m))
            if sid is None: sid = leg_state('%d:0' % pid)
            if sid is None:
                for mm in range(16):
                    sid = leg_state('%d:%d' % (pid, mm))
                    if sid is not None: break
        if sid is None:
            sid = state_id('stone') if pid > 0 else 0
            if m == 0 and pid > 0: unresolved.append(pid)
        btab.append(sid)
open(OUT + '/blocks.bin', 'wb').write(struct.pack('>4096i', *btab))
print('blocks: unresolved ids ->', unresolved)

# ---- предметы: PE id:meta -> java item id ----
IOV = {}
for m in range(16):
    IOV[(85, m)] = '%s_fence' % woods[m % 6]
    IOV[(158, m)] = '%s_slab' % woods[m % 6]
    IOV[(44, m)] = '%s_slab' % slab_pe[m & 7]
    IOV[(198, m)] = 'dirt_path'; IOV[(243, m)] = 'podzol'; IOV[(245, m)] = 'stonecutter'
    IOV[(246, m)] = 'crying_obsidian'; IOV[(247, m)] = 'iron_block'; IOV[(95, m)] = 'barrier'
IOV[(325, 0)] = 'bucket'; IOV[(325, 1)] = 'milk_bucket'; IOV[(325, 8)] = 'water_bucket'; IOV[(325, 10)] = 'lava_bucket'
IOV[(457, 0)] = 'beetroot'; IOV[(458, 0)] = 'beetroot_seeds'; IOV[(459, 0)] = 'beetroot_soup'
IOV[(126, 0)] = 'activator_rail'; IOV[(125, 0)] = 'dropper'; IOV[(438, 0)] = 'splash_potion'
boats = ['oak_boat', 'spruce_boat', 'birch_boat', 'jungle_boat', 'acacia_boat', 'dark_oak_boat']
for m in range(6): IOV[(333, m)] = boats[m]
PE_EGG = {10: 'chicken', 11: 'cow', 12: 'pig', 13: 'sheep', 14: 'wolf', 15: 'villager', 16: 'mooshroom', 17: 'squid', 18: 'rabbit',
          19: 'bat', 20: 'iron_golem', 21: 'snow_golem', 22: 'ocelot', 32: 'zombie', 33: 'creeper', 34: 'skeleton', 35: 'spider',
          36: 'zombified_piglin', 37: 'slime', 38: 'enderman', 39: 'silverfish', 40: 'cave_spider', 41: 'ghast', 42: 'magma_cube',
          43: 'blaze', 44: 'zombie_villager', 45: 'witch'}
for e, n in PE_EGG.items():
    if n + '_spawn_egg' in items_by: IOV[(383, e)] = n + '_spawn_egg'

ITEM_RENAME = {'grass_path': 'dirt_path', 'sign': 'oak_sign', 'stone_slab': 'smooth_stone_slab', 'zombie_pigman_spawn_egg': 'zombified_piglin_spawn_egg',
               'grass': 'grass', 'cactus_green': 'green_dye', 'rose_red': 'red_dye', 'dandelion_yellow': 'yellow_dye', 'turtle_shell': 'turtle_helmet'}
def item_id(n):
    n = n.replace('minecraft:', '').split('[')[0]
    n = ITEM_RENAME.get(n, n)
    i = items_by.get(n)
    return i['id'] if i else None

fwd = {}
for pid in range(1, 512):
    for m in range(16):
        n = IOV.get((pid, m))
        jid = item_id(n) if n else None
        if jid is None:
            v = leg['items'].get('%d:%d' % (pid, m))
            if v: jid = item_id(v)
        if jid is None and m == 0:
            v = leg['blocks'].get('%d:0' % pid)
            if v: jid = item_id(parse(v)[0])
        if jid is not None: fwd[(pid, m)] = jid
# запись: count, затем (pe_id u16, meta u8, java i32)
with open(OUT + '/items.bin', 'wb') as f:
    f.write(struct.pack('>i', len(fwd)))
    for (pid, m), j in sorted(fwd.items()): f.write(struct.pack('>HBi', pid, m, j))
print('items mapped:', len(fwd))

# ---- сущности: PE тип -> java тип ----
ent_by = {e['name']: e['id'] for e in ents}
PE_ENT = dict(PE_EGG)
PE_ENT.update({63: 'player', 64: 'item', 65: 'tnt', 66: 'falling_block', 69: 'experience_orb', 80: 'arrow', 81: 'snowball', 82: 'egg',
               83: 'painting', 84: 'minecart', 85: 'small_fireball', 86: 'potion', 87: 'ender_pearl', 90: 'boat', 93: 'lightning_bolt',
               94: 'small_fireball', 95: 'area_effect_cloud'})
with open(OUT + '/entities.bin', 'wb') as f:
    pairs = [(k, ent_by[v]) for k, v in PE_ENT.items() if v in ent_by]
    f.write(struct.pack('>i', len(pairs)))
    for k, v in pairs: f.write(struct.pack('>ii', k, v))
print('entities:', len(pairs))

# ---- кодек реестров (NBT) ----
lp = json.load(open(D + '1.20/loginPacket.json'))
codec = lp['dimensionCodec']
dims = codec['value']['minecraft:dimension_type']['value']['value']['value']['value']
for d in dims:
    if d['name']['value'] == 'minecraft:overworld':
        el = d['element']['value']
        el['min_y']['value'] = 0; el['height']['value'] = 128; el['logical_height']['value'] = 128
biomes = codec['value']['minecraft:worldgen/biome']['value']['value']['value']['value']
plains = next(b['id']['value'] for b in biomes if b['name']['value'] == 'minecraft:plains')

TYPES = {'end': 0, 'byte': 1, 'short': 2, 'int': 3, 'long': 4, 'float': 5, 'double': 6, 'byteArray': 7, 'string': 8, 'list': 9,
         'compound': 10, 'intArray': 11, 'longArray': 12}
def wstr(s): b = s.encode('utf8'); return struct.pack('>H', len(b)) + b
def payload(t, v):
    if t == 'byte': return struct.pack('>b', v)
    if t == 'short': return struct.pack('>h', v)
    if t == 'int': return struct.pack('>i', v)
    if t == 'long': return struct.pack('>q', (v[0] << 32) | (v[1] & 0xffffffff)) if isinstance(v, list) else struct.pack('>q', v)
    if t == 'float': return struct.pack('>f', v)
    if t == 'double': return struct.pack('>d', v)
    if t == 'string': return wstr(v)
    if t == 'compound':
        out = b''
        for k, e in v.items(): out += bytes([TYPES[e['type']]]) + wstr(k) + payload(e['type'], e['value'])
        return out + b'\x00'
    if t == 'list':
        et = v['type']; vals = v['value']
        return bytes([TYPES[et] if vals else 0]) + struct.pack('>i', len(vals)) + b''.join(payload(et, x) for x in vals)
    if t == 'intArray': return struct.pack('>i', len(v)) + b''.join(struct.pack('>i', x) for x in v)
    if t == 'longArray': return struct.pack('>i', len(v)) + b''.join(payload('long', x) for x in v)
    if t == 'byteArray': return struct.pack('>i', len(v)) + bytes((x & 0xff) for x in v)
    raise Exception(t)
nbt = b'\x0a' + wstr('') + payload('compound', codec['value'])
open(OUT + '/codec.nbt', 'wb').write(nbt)
open(OUT + '/meta.txt', 'w').write('plains=%d\nair=0\n' % plains)
print('codec bytes', len(nbt), 'plains id', plains)
