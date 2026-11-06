#!/usr/bin/env python3
"""
Port structures of Repurposed Structures (TelepathicGrunt, LGPL-3.0,
https://github.com/telepathicgrunt/repurposedstructures) to Minecraft PE 0.14 for the NOVA structure engine
(native/structs.inc). Reads the mod's data folder, writes one structures.bin for a .mcmod.

  python3 tools/port_rs.py <repurposedstructures>/common/src/main/resources/data/repurposed_structures <out.bin>

What is converted: the .nbt templates (blocks mapped to 0.14 ids + data, for the 4 rotations), rule and pillar
processors (random replacements, pillars down to the ground), chests with their loot tables (items mapped to 0.14,
things 0.14 does not have are dropped), spawners (mob), jigsaw pairs of pyramids (body + pit joined at the jigsaw).

structures.bin (little endian):
  'NSB1' u16 nloot u16 nmobs u16 nstruct
  mobs:   u8 len + name (EntityTypeFromString)
  loot:   u8 rollsMin u8 rollsMax u16 n, n x (u16 id, u16 data, u8 min, u8 max, u16 weight)
  struct: u8 len + name, u8 kind (0 surface, 1 underground, 2 surface or under water), u16 chance (1 in N chunks), i8 yoff, u8 ymin, u8 ymax,
          u8 maxSlope, u8 nbiomes + biomes, u16 sx sy sz, u16 npal, palette, u32 nblocks, blocks,
          u8 nRandomChests, u16 randomChestLoot group, u16 ncand + cand (u8 x y z)
          u8 ngroups, groups: u8 n + u16 loot ids
  palette entry: u8 flags (1 pillar, 2 chest, 4 spawner, 8 keep-world-if-air), u8 id, u8 data[4],
                 u8 nalt, nalt x (u16 prob/65535 cumulative, u8 id, u8 data[4])
  block: u8 x y z, u16 pal, u8 extra (chest: loot group + 1; spawner: mob + 1)
"""
import json, os, struct, sys, random
import nbtlib

ROOT = sys.argv[1] if len(sys.argv) > 1 else ''
OUT = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith('--') else 'structures.bin'
EXT = os.path.abspath(os.path.join(ROOT, *(['..'] * 7))) if ROOT else ''
# data folders of each mod (namespace -> folder with structure(s)/, loot_table(s)/, worldgen/)
ROOTS = {'repurposed_structures': ROOT,
         'yungsextras': os.path.join(EXT, 'YUNGs-Extras/Common/src/main/resources/data/yungsextras'),
         'towers_of_the_wild': os.path.join(EXT, 'Towers_Of_The_Wild-1.16.3/src/main/resources/data/towers_of_the_wild')}

def data_file(ns, kind, path, ext):
    """kind: structure / loot_table / processor_list; 1.16 mods use the plural folder names"""
    base = ROOTS.get(ns)
    if not base: return None
    for k in (kind, kind + 's'):
        sub = 'worldgen/' + k if kind == 'processor_list' else k
        f = os.path.join(base, sub, path + ext)
        if os.path.exists(f): return f
    return None

# ------------------------------------------------------------------ biomes of 0.14 (ids as in Java 1.8)
B = dict(plains=[1], desert=[2, 17, 130], hills=[3, 20, 34, 131, 162], forest=[4, 18], flower=[132], taiga=[5, 19, 133],
         swamp=[6, 134], ice=[12, 13, 140], mushroom=[14, 15], jungle=[21, 22, 23, 149, 151], birch=[27, 28, 155, 156],
         roofed=[29, 157], coldtaiga=[30, 31, 158], megataiga=[32, 33, 160, 161], savanna=[35, 36, 163, 164],
         mesa=[37, 38, 39, 165, 166, 167], beach=[16, 25, 26])
def biomes(*names):
    out = []
    for n in names: out += B[n]
    return out
ALL_LAND = biomes('plains', 'hills', 'forest', 'flower', 'taiga', 'birch', 'savanna', 'beach')

# ------------------------------------------------------------------ blocks
COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray', 'cyan', 'purple',
          'blue', 'brown', 'green', 'red', 'black']
WOODS = {'oak': 0, 'spruce': 1, 'birch': 2, 'jungle': 3, 'acacia': 4, 'dark_oak': 5, 'mangrove': 5, 'cherry': 2,
         'poplar': 2, 'dappled': 0, 'pale_oak': 2, 'crimson': 5, 'warped': 3, 'bamboo': 4}
STAIRS_ID = {'oak': 53, 'spruce': 134, 'birch': 135, 'jungle': 136, 'acacia': 163, 'dark_oak': 164}
GATE_ID = {0: 107, 1: 183, 2: 184, 3: 185, 4: 187, 5: 186}
FACE4 = ['north', 'east', 'south', 'west']

def rot_face(f, r):
    if f in FACE4: return FACE4[(FACE4.index(f) + r) % 4]
    return f

def wood_of(name):
    for w in sorted(WOODS, key=len, reverse=True):
        if name.startswith(w + '_') or name.startswith('stripped_' + w + '_'): return w
    return None

def color_of(name):
    for c in sorted(COLORS, key=len, reverse=True):
        if name.startswith(c + '_'): return COLORS.index(c)
    return None

STAIR_DATA = {'east': 0, 'west': 1, 'south': 2, 'north': 3}
CHEST_DATA = {'north': 2, 'south': 3, 'west': 4, 'east': 5}
TORCH_DATA = {'east': 1, 'west': 2, 'south': 3, 'north': 4}
GATE_DATA = {'south': 0, 'west': 1, 'north': 2, 'east': 3}
PUMPKIN_DATA = GATE_DATA
DOOR_DATA = {'east': 0, 'south': 1, 'west': 2, 'north': 3}
TRAP_DATA = {'south': 0, 'north': 1, 'east': 2, 'west': 3}
HOOK_DATA = {'south': 0, 'west': 1, 'north': 2, 'east': 3}
REPEATER_DATA = {'north': 0, 'east': 1, 'south': 2, 'west': 3}
BUTTON_DATA = {'east': 1, 'west': 2, 'south': 3, 'north': 4}

SIMPLE = {
    'smooth_stone': (43, 8), 'air': (0, 0), 'cave_air': (0, 0), 'void_air': (0, 0), 'stone': (1, 0), 'granite': (1, 1), 'polished_granite': (1, 2),
    'diorite': (1, 3), 'polished_diorite': (1, 4), 'andesite': (1, 5), 'polished_andesite': (1, 6),
    'deepslate': (1, 0), 'cobbled_deepslate': (4, 0), 'polished_deepslate': (1, 6), 'deepslate_bricks': (98, 0),
    'deepslate_tiles': (98, 0), 'tuff': (1, 5), 'calcite': (1, 3), 'blackstone': (1, 5), 'polished_blackstone': (1, 6),
    'polished_blackstone_bricks': (98, 0), 'chiseled_polished_blackstone': (98, 3), 'gilded_blackstone': (14, 0),
    'basalt': (1, 5), 'smooth_basalt': (1, 5), 'grass_block': (2, 0), 'dirt': (3, 0), 'coarse_dirt': (3, 1),
    'rooted_dirt': (3, 1), 'podzol': (243, 0), 'mycelium': (110, 0), 'dirt_path': (198, 0), 'farmland': (60, 0),
    'cobblestone': (4, 0), 'mossy_cobblestone': (48, 0), 'bedrock': (7, 0), 'sand': (12, 0), 'red_sand': (12, 1),
    'gravel': (13, 0), 'suspicious_gravel': (13, 0), 'suspicious_sand': (12, 0), 'clay': (82, 0),
    'gold_ore': (14, 0), 'iron_ore': (15, 0), 'coal_ore': (16, 0), 'lapis_ore': (21, 0), 'diamond_ore': (56, 0),
    'redstone_ore': (73, 0), 'emerald_ore': (129, 0), 'nether_quartz_ore': (153, 0), 'copper_ore': (15, 0),
    'deepslate_gold_ore': (14, 0), 'deepslate_iron_ore': (15, 0), 'deepslate_diamond_ore': (56, 0),
    'sponge': (19, 0), 'wet_sponge': (19, 0), 'glass': (20, 0), 'tinted_glass': (20, 0), 'glass_pane': (102, 0),
    'lapis_block': (22, 0), 'sandstone': (24, 0), 'chiseled_sandstone': (24, 1), 'cut_sandstone': (24, 2),
    'smooth_sandstone': (24, 2), 'red_sandstone': (179, 0), 'chiseled_red_sandstone': (179, 1),
    'cut_red_sandstone': (179, 2), 'smooth_red_sandstone': (179, 2), 'cobweb': (30, 0), 'gold_block': (41, 0),
    'iron_block': (42, 0), 'bricks': (45, 0), 'tnt': (46, 0), 'bookshelf': (47, 0), 'chiseled_bookshelf': (47, 0),
    'obsidian': (49, 0), 'crying_obsidian': (49, 0), 'spawner': (52, 0), 'diamond_block': (57, 0),
    'crafting_table': (58, 0), 'fletching_table': (58, 0), 'smithing_table': (58, 0), 'cartography_table': (58, 0),
    'ice': (79, 0), 'snow_block': (80, 0), 'powder_snow': (80, 0), 'packed_ice': (174, 0), 'blue_ice': (174, 0),
    'clay_block': (82, 0), 'netherrack': (87, 0), 'soul_sand': (88, 0), 'soul_soil': (88, 0), 'glowstone': (89, 0),
    'shroomlight': (89, 0), 'sea_lantern': (89, 0), 'stone_bricks': (98, 0), 'mossy_stone_bricks': (98, 1),
    'cracked_stone_bricks': (98, 2), 'chiseled_stone_bricks': (98, 3), 'infested_stone': (97, 0),
    'infested_cobblestone': (97, 1), 'infested_stone_bricks': (97, 2), 'infested_mossy_stone_bricks': (97, 3),
    'infested_cracked_stone_bricks': (97, 4), 'infested_chiseled_stone_bricks': (97, 5), 'iron_bars': (101, 0),
    'iron_chain': (101, 0), 'chain': (101, 0), 'melon': (103, 0), 'nether_bricks': (112, 0),
    'chiseled_nether_bricks': (112, 0), 'cracked_nether_bricks': (112, 0), 'red_nether_bricks': (112, 0),
    'nether_brick_fence': (113, 0), 'enchanting_table': (116, 0), 'end_stone': (121, 0), 'end_stone_bricks': (121, 0),
    'redstone_lamp': (123, 0), 'emerald_block': (133, 0), 'beacon': (138, 0), 'redstone_block': (152, 0),
    'quartz_block': (155, 0), 'chiseled_quartz_block': (155, 1), 'quartz_pillar': (155, 2), 'smooth_quartz': (155, 0),
    'quartz_bricks': (155, 0), 'purpur_block': (155, 0), 'purpur_pillar': (155, 2), 'terracotta': (172, 0),
    'hay_block': (170, 0), 'coal_block': (173, 0), 'slime_block': (165, 0), 'magma_block': (246, 0),
    'bone_block': (155, 2), 'prismarine': (98, 1), 'prismarine_bricks': (98, 0), 'dark_prismarine': (159, 9),
    'moss_block': (48, 0), 'moss_carpet': (171, 13), 'mushroom_stem': (99, 10), 'brown_mushroom_block': (99, 14),
    'red_mushroom_block': (100, 14), 'water': (9, 0), 'lava': (11, 0), 'bubble_column': (9, 0), 'cactus': (81, 0),
    'sugar_cane': (83, 0), 'pumpkin': (86, 0), 'lily_pad': (111, 0), 'dead_bush': (32, 0), 'short_grass': (31, 1),
    'grass': (31, 1), 'fern': (31, 2), 'poppy': (38, 0), 'blue_orchid': (38, 1), 'allium': (38, 2),
    'azure_bluet': (38, 3), 'red_tulip': (38, 4), 'orange_tulip': (38, 5), 'white_tulip': (38, 6),
    'pink_tulip': (38, 7), 'oxeye_daisy': (38, 8), 'cornflower': (38, 3), 'lily_of_the_valley': (38, 6),
    'dandelion': (37, 0), 'brown_mushroom': (39, 0), 'red_mushroom': (40, 0), 'note_block': (25, 0),
    'jukebox': (25, 0), 'anvil': (145, 0), 'chipped_anvil': (145, 4), 'damaged_anvil': (145, 8),
    'brewing_stand': (117, 0), 'cauldron': (118, 0), 'water_cauldron': (118, 6), 'redstone_wire': (55, 0),
    'tripwire': (132, 0), 'flower_pot': (140, 0), 'daylight_detector': (151, 0), 'hopper': (154, 0),
    'cobblestone_wall': (139, 0), 'mossy_cobblestone_wall': (139, 1), 'piston': (4, 0), 'sticky_piston': (4, 0),
    'piston_head': (0, 0), 'stone_pressure_plate': (70, 0), 'polished_blackstone_pressure_plate': (70, 0),
    'light_weighted_pressure_plate': (147, 0), 'heavy_weighted_pressure_plate': (148, 0), 'barrel': (54, 2),
    'target': (152, 0), 'composter': (0, 0), 'lectern': (47, 0), 'stonecutter': (245, 0), 'grindstone': (0, 0),
    'smoker': (61, 2), 'blast_furnace': (61, 2), 'loom': (58, 0), 'iron_door': (71, 0), 'sea_pickle': (0, 0),
    'dried_kelp_block': (173, 0), 'honeycomb_block': (24, 2), 'amethyst_block': (159, 10), 'copper_block': (159, 1),
}
# not in 0.14 and nothing close: leave the world as it is there (decorations)
SKIP = ('banner', 'candle', 'bell', 'spore_blossom', 'dripleaf', 'shelf_mushroom', 'pointed_dripstone', 'glow_lichen',
        'weeping_vines', 'twisting_vines', 'sculk', 'head', 'skull', 'sign', 'bed', 'kelp', 'seagrass', 'coral',
        'lantern', 'campfire', 'item_frame', 'painting', 'azalea', 'hanging_roots', 'amethyst_cluster', 'bud',
        'structure_void', 'cobweb_ignore', 'pale_moss', 'leaf_litter', 'firefly', 'resin', 'decorated_pot', 'big_dripleaf')

def block(name, p, r):
    """modern block -> (id, data) of 0.14 for rotation r; None = do not touch the world there"""
    n = name.split(':', 1)[1]
    if n.startswith('potted_'): return (140, 0)
    for s in SKIP:
        if s in n: return None
    f = rot_face(p.get('facing', ''), r)
    half_top = p.get('half') == 'top' or p.get('type') == 'top'
    w = wood_of(n)
    col = color_of(n)
    if n in SIMPLE:
        idd = SIMPLE[n]
        if n in ('barrel', 'smoker', 'blast_furnace'): return (idd[0], CHEST_DATA.get(f, 2))
        return idd
    if n.endswith('_wool'): return (35, col if col is not None else 0)
    if n.endswith('_carpet'): return (171, col if col is not None else 0)
    if n.endswith('_terracotta') or n.endswith('_concrete') or n.endswith('_concrete_powder') or n.endswith('_glazed_terracotta'):
        return (159, col if col is not None else 0)
    if n.endswith('_stained_glass'): return (20, 0)
    if n.endswith('_stained_glass_pane'): return (102, 0)
    if n.endswith('_planks'): return (5, WOODS.get(w, 0))
    if n.endswith('_log') or n.endswith('_wood') or n.endswith('_stem') or n.endswith('_hyphae'):
        wt = WOODS.get(w, 0)
        axis = p.get('axis', 'y')
        if r % 2 and axis in 'xz': axis = 'z' if axis == 'x' else 'x'
        ad = {'y': 0, 'x': 4, 'z': 8}[axis]
        return (17, wt + ad) if wt < 4 else (162, wt - 4 + ad)
    if n.endswith('_leaves'):
        wt = WOODS.get(w, 0)
        return (18, wt) if wt < 4 else (161, wt - 4)
    if n.endswith('_sapling'): return (6, WOODS.get(w, 0))
    if n.endswith('_stairs'):
        base = n[:-7]
        sid = STAIRS_ID.get(w) if w else None
        if sid is None:
            sid = {'cobblestone': 67, 'stone': 67, 'mossy_cobblestone': 67, 'brick': 108, 'stone_brick': 109,
                   'mossy_stone_brick': 109, 'nether_brick': 114, 'sandstone': 128, 'smooth_sandstone': 128,
                   'cut_sandstone': 128, 'quartz': 156, 'smooth_quartz': 156, 'purpur': 156, 'red_sandstone': 180,
                   'smooth_red_sandstone': 180, 'end_stone_brick': 109, 'prismarine': 109, 'prismarine_brick': 109,
                   'dark_prismarine': 109, 'blackstone': 67, 'polished_blackstone': 109, 'deepslate_brick': 109,
                   'cobbled_deepslate': 67, 'andesite': 67, 'polished_andesite': 67, 'granite': 67, 'diorite': 67,
                   'red_nether_brick': 114}.get(base, 67)
        return (sid, STAIR_DATA.get(f, 0) + (4 if half_top else 0))
    if n.endswith('_slab'):
        base = n[:-5]
        if p.get('type') == 'double':
            full = block('minecraft:' + (base + '_planks' if w and base in WOODS else base), {}, r)
            return full or (43, 0)
        top = 8 if half_top else 0
        if w and base in WOODS: return (158, WOODS[base] + top)
        st = {'stone': 0, 'smooth_stone': 0, 'andesite': 0, 'polished_andesite': 0, 'diorite': 0, 'granite': 0,
              'sandstone': 1, 'smooth_sandstone': 1, 'cut_sandstone': 1, 'cobblestone': 3, 'mossy_cobblestone': 3,
              'cobbled_deepslate': 3, 'blackstone': 3, 'brick': 4, 'stone_brick': 5, 'mossy_stone_brick': 5,
              'deepslate_brick': 5, 'polished_blackstone_brick': 5, 'end_stone_brick': 5, 'prismarine': 5,
              'prismarine_brick': 5, 'dark_prismarine': 5, 'quartz': 6, 'smooth_quartz': 6, 'purpur': 6,
              'nether_brick': 7, 'red_nether_brick': 7}
        if base in ('red_sandstone', 'smooth_red_sandstone', 'cut_red_sandstone'): return (182, 0 + top)
        return (44, st.get(base, 0) + top)
    if n.endswith('_wall'):
        return (139, 1 if 'mossy' in n else 0)
    if n.endswith('_fence'):
        if 'nether' in n: return (113, 0)
        return (85, WOODS.get(w, 0))
    if n.endswith('_fence_gate'):
        return (GATE_ID[WOODS.get(w, 0)], GATE_DATA.get(f, 0) + (4 if p.get('open') == 'true' else 0))
    if n.endswith('_door'):
        did = 71 if n == 'iron_door' else {0: 64, 1: 193, 2: 194, 3: 195, 4: 196, 5: 197}[WOODS.get(w, 0)]
        if p.get('half') == 'upper': return (did, 8 + (1 if p.get('hinge') == 'right' else 0))
        return (did, DOOR_DATA.get(f, 0) + (4 if p.get('open') == 'true' else 0))
    if n.endswith('_trapdoor'):
        tid = 167 if n == 'iron_trapdoor' else 96
        return (tid, TRAP_DATA.get(f, 0) + (4 if p.get('open') == 'true' else 0) + (8 if half_top else 0))
    if n.endswith('_button'):
        bid = 77 if 'stone' in n or 'blackstone' in n else 143
        if p.get('face') == 'floor': return (bid, 5)
        if p.get('face') == 'ceiling': return (bid, 0)
        return (bid, BUTTON_DATA.get(f, 5))
    if n.endswith('_pressure_plate'): return (72 if w else 70, 0)
    if n in ('torch', 'soul_torch', 'redstone_torch'): return (76 if 'redstone' in n else 50, 5)
    if n in ('wall_torch', 'soul_wall_torch', 'redstone_wall_torch'):
        return (76 if 'redstone' in n else 50, TORCH_DATA.get(f, 5))
    if n in ('chest', 'trapped_chest'): return (54 if n == 'chest' else 146, CHEST_DATA.get(f, 2))
    if n in ('furnace',): return (61, CHEST_DATA.get(f, 2))
    if n in ('dispenser', 'dropper'):
        d = {'down': 0, 'up': 1}.get(p.get('facing', ''), CHEST_DATA.get(f, 2))
        return (23 if n == 'dispenser' else 125, d)
    if n == 'ladder': return (65, CHEST_DATA.get(f, 2))
    if n in ('carved_pumpkin', 'jack_o_lantern'): return (86 if n == 'carved_pumpkin' else 91, PUMPKIN_DATA.get(f, 0))
    if n == 'tripwire_hook': return (131, HOOK_DATA.get(f, 0) + (4 if p.get('attached') == 'true' else 0))
    if n in ('repeater', 'comparator'):
        d = REPEATER_DATA.get(f, 0)
        if n == 'repeater': d += (int(p.get('delay', '1')) - 1) * 4
        return (93 if n == 'repeater' else 149, d)
    if n == 'lever':
        face = p.get('face', 'wall')
        d = 5 if face == 'floor' else 7 if face == 'ceiling' else BUTTON_DATA.get(f, 5)
        return (69, d + (8 if p.get('powered') == 'true' else 0))
    if n in ('rail', 'powered_rail', 'detector_rail', 'activator_rail'):
        shape = p.get('shape', 'north_south')
        if r % 2:
            shape = {'north_south': 'east_west', 'east_west': 'north_south'}.get(shape, shape)
        d = {'north_south': 0, 'east_west': 1, 'ascending_east': 2, 'ascending_west': 3, 'ascending_north': 4,
             'ascending_south': 5, 'south_east': 6, 'south_west': 7, 'north_west': 8, 'north_east': 9}.get(shape, 0)
        rid = {'rail': 66, 'powered_rail': 27, 'detector_rail': 28, 'activator_rail': 126}[n]
        return (rid, d if rid == 66 else min(d, 5))
    if n == 'snow': return (78, int(p.get('layers', '1')) - 1)
    if n == 'vine':
        d = 0
        for k, bit in (('south', 1), ('west', 2), ('north', 4), ('east', 8)):
            if p.get(k) == 'true': d |= {'south': 1, 'west': 2, 'north': 4, 'east': 8}[rot_face(k, r)]
        return (106, d)
    if n in ('tall_grass', 'large_fern', 'sunflower', 'lilac', 'rose_bush', 'peony'):
        d = {'sunflower': 0, 'lilac': 1, 'tall_grass': 2, 'large_fern': 3, 'rose_bush': 4, 'peony': 5}[n]
        return (175, d + (8 if p.get('half') == 'upper' else 0))
    if n in ('wheat', 'carrots', 'potatoes', 'beetroots'):
        return ({'wheat': 59, 'carrots': 141, 'potatoes': 142, 'beetroots': 244}[n], min(7, int(p.get('age', '7'))))
    if n == 'jigsaw': return None
    return 'unknown'

def unrot(x, z, sx, sz, r):
    """position of (x, z) after r clockwise turns; size swaps on odd turns (native/structs.inc does the same)"""
    for _ in range(r):
        x, z = sz - 1 - z, x
        sx, sz = sz, sx
    return x, z

# ------------------------------------------------------------------ items for loot
ITEMS = {
    'iron_ingot': 265, 'gold_ingot': 266, 'diamond': 264, 'emerald': 388, 'coal': 263, 'charcoal': (263, 1),
    'redstone': 331, 'lapis_lazuli': (351, 4), 'bread': 297, 'apple': 260, 'golden_apple': 322,
    'enchanted_golden_apple': (322, 1), 'saddle': 329, 'name_tag': 421, 'lead': 420, 'bone': 352,
    'rotten_flesh': 367, 'string': 287, 'gunpowder': 289, 'bucket': 325, 'book': 340, 'writable_book': 340,
    'enchanted_book': 340, 'arrow': 262, 'tipped_arrow': 262, 'spectral_arrow': 262, 'bow': 261, 'wheat': 296,
    'melon_seeds': 362, 'pumpkin_seeds': 361, 'beetroot_seeds': 458, 'beetroot': 457, 'carrot': 391, 'potato': 392,
    'poisonous_potato': 394, 'spider_eye': 375, 'fermented_spider_eye': 376, 'ender_pearl': 368, 'ender_eye': 381,
    'slime_ball': 341, 'glowstone_dust': 348, 'clay_ball': 337, 'paper': 339, 'feather': 288, 'leather': 334,
    'rabbit_hide': 415, 'flint': 318, 'compass': 345, 'clock': 347, 'map': 395, 'golden_carrot': 396,
    'glistering_melon_slice': 382, 'cookie': 357, 'sugar': 353, 'egg': 344, 'snowball': 332, 'cocoa_beans': (351, 3),
    'bone_meal': (351, 15), 'ink_sac': (351, 0), 'red_dye': (351, 1), 'green_dye': (351, 2), 'brown_dye': (351, 3),
    'blue_dye': (351, 4), 'purple_dye': (351, 5), 'cyan_dye': (351, 6), 'light_gray_dye': (351, 7),
    'gray_dye': (351, 8), 'pink_dye': (351, 9), 'lime_dye': (351, 10), 'yellow_dye': (351, 11),
    'light_blue_dye': (351, 12), 'magenta_dye': (351, 13), 'orange_dye': (351, 14), 'white_dye': (351, 15),
    'black_dye': (351, 0), 'gold_nugget': 371, 'experience_bottle': 384, 'potion': 373, 'stick': 280,
    'bowl': 281, 'mushroom_stew': 282, 'suspicious_stew': 282, 'beetroot_soup': 459, 'melon_slice': 360,
    'quartz': 406, 'nether_wart': 372, 'fire_charge': 385, 'shears': 359, 'fishing_rod': 346, 'torch': 50,
    'iron_sword': 267, 'iron_shovel': 256, 'iron_pickaxe': 257, 'iron_axe': 258, 'iron_hoe': 292,
    'wooden_sword': 268, 'stone_sword': 272, 'stone_shovel': 273, 'stone_pickaxe': 274, 'stone_axe': 275,
    'diamond_sword': 276, 'diamond_shovel': 277, 'diamond_pickaxe': 278, 'diamond_axe': 279, 'diamond_hoe': 293,
    'golden_sword': 283, 'golden_shovel': 284, 'golden_pickaxe': 285, 'golden_axe': 286, 'golden_hoe': 294,
    'leather_helmet': 298, 'leather_chestplate': 299, 'leather_leggings': 300, 'leather_boots': 301,
    'chainmail_helmet': 302, 'chainmail_chestplate': 303, 'chainmail_leggings': 304, 'chainmail_boots': 305,
    'iron_helmet': 306, 'iron_chestplate': 307, 'iron_leggings': 308, 'iron_boots': 309, 'diamond_helmet': 310,
    'diamond_chestplate': 311, 'diamond_leggings': 312, 'diamond_boots': 313, 'golden_helmet': 314,
    'golden_chestplate': 315, 'golden_leggings': 316, 'golden_boots': 317, 'raw_iron': 15, 'raw_gold': 14,
    'raw_copper': (159, 1), 'copper_ingot': 265, 'obsidian': 49, 'cobweb': 30, 'vine': 106, 'lily_pad': 111,
    'dead_bush': 32, 'sand': 12, 'red_sand': (12, 1), 'snow_block': 80, 'ice': 79, 'packed_ice': 174,
    'blue_ice': 174, 'pumpkin': 86, 'carved_pumpkin': 86, 'melon': 103, 'podzol': 243, 'mycelium': 110,
    'brown_mushroom': 39, 'red_mushroom': 40, 'brown_mushroom_block': 99, 'red_mushroom_block': 100,
    'mushroom_stem': 99, 'gold_block': 41, 'iron_block': 42, 'diamond_block': 57, 'emerald_block': 133,
    'quartz_block': 155, 'purpur_block': 155, 'slime_block': 165, 'sea_lantern': 89, 'dark_oak_log': (162, 1),
    'oak_log': 17, 'cracked_stone_bricks': (98, 2), 'blackstone': 4, 'prismarine_shard': 406,
    'prismarine_crystals': 348, 'poppy': 38, 'allium': (38, 2), 'red_tulip': (38, 4), 'orange_tulip': (38, 5),
    'white_tulip': (38, 6), 'pink_tulip': (38, 7), 'cornflower': (38, 3), 'lilac': (175, 1), 'rose_bush': (175, 4),
    'peony': (175, 5), 'comparator': 404, 'repeater': 356, 'oak_boat': 333, 'dark_oak_boat': (333, 5),
    'chorus_fruit': 260, 'sweet_berries': 260, 'glow_berries': 260, 'bamboo': 280, 'heart_of_the_sea': 264,
    'nautilus_shell': 352, 'totem_of_undying': 322, 'cooked_beef': 364, 'beef': 363, 'porkchop': 319,
    'cooked_porkchop': 320, 'chicken': 365, 'cooked_chicken': 366, 'mutton': 423, 'cooked_mutton': 424,
    'cod': 349, 'cooked_cod': 350, 'salmon': (349, 1), 'cooked_salmon': (350, 1), 'pufferfish': (349, 3),
    'tropical_fish': (349, 2), 'rabbit': 411, 'cooked_rabbit': 412, 'rabbit_stew': 413, 'cake': 354,
    'pumpkin_pie': 400, 'baked_potato': 393, 'dark_prismarine': (159, 9), 'iron_nugget': 371,
}

def item(name):
    n = name.split(':', 1)[1]
    v = ITEMS.get(n)
    if v is None: return None
    return (v, 0) if isinstance(v, int) else v

# ------------------------------------------------------------------ loot tables
class Loot:
    def __init__(self):
        self.tables = []           # (rmin, rmax, [(id, data, min, max, weight)])
        self.index = {}
    def table_ids(self, ref):
        """a loot table -> ids of 0.14 tables (one per pool)"""
        if ref in self.index: return self.index[ref]
        ns, path = ref.split(':', 1)
        f = data_file(ns, 'loot_table', path, '.json')
        ids = []
        self.index[ref] = ids
        if not f or not os.path.exists(f): return ids
        d = json.load(open(f))
        for pool in d.get('pools', []):
            rolls = pool.get('rolls', 1)
            if isinstance(rolls, dict): rmin, rmax = int(rolls.get('min', 1)), int(rolls.get('max', 1))
            else: rmin = rmax = int(rolls)
            entries = []
            for e in pool.get('entries', []):
                if e.get('type') == 'minecraft:loot_table':
                    for sub in self.table_ids(e.get('value', e.get('name', ''))):
                        if self.tables[sub][2]: entries += [(i, dd, a, b, max(1, wgt * int(e.get('weight', 1)) // 4)) for (i, dd, a, b, wgt) in self.tables[sub][2]]
                    continue
                if e.get('type') != 'minecraft:item': continue
                it = item(e['name'])
                if not it: continue
                cmin = cmax = 1
                for fn in e.get('functions', []) + e.get('modifier', []):
                    if fn.get('function', fn.get('type')) == 'minecraft:set_count':
                        c = fn.get('count', 1)
                        if isinstance(c, dict): cmin, cmax = int(c.get('min', 1)), int(c.get('max', 1))
                        else: cmin = cmax = int(c)
                entries.append((it[0], it[1], max(1, min(64, cmin)), max(1, min(64, cmax)), int(e.get('weight', 1))))
            if entries:
                self.tables.append((max(0, min(255, rmin)), max(0, min(255, rmax)), entries))
                ids.append(len(self.tables) - 1)
        return ids

# ------------------------------------------------------------------ processors
def processors(ref):
    """rule processors -> {input name: [(probability, output name, props)]}, pillar -> {trigger: (out, props)}"""
    rules, pillars = {}, {}
    if not ref: return rules, pillars
    ns, path = ref.split(':', 1)
    f = data_file(ns, 'processor_list', path, '.json')
    if not f: return rules, pillars
    for pr in json.load(open(f)).get('processors', []):
        t = pr.get('processor_type')
        if t == 'minecraft:capped': continue                      # archaeology (suspicious gravel) - not in 0.14
        if t == 'minecraft:rule':
            for ru in pr.get('rules', []):
                ip = ru.get('input_predicate', {})
                if ru.get('location_predicate', {}).get('predicate_type', 'minecraft:always_true') != 'minecraft:always_true': continue
                blk = ip.get('block')
                if not blk: continue
                prob = float(ip.get('probability', 1.0)) if ip.get('predicate_type') == 'minecraft:random_block_match' else 1.0
                out = ru['output_state']
                rules.setdefault(blk, []).append((prob, out.get('id', out.get('Name')), dict(out.get('properties', out.get('Properties', {})) or {})))
        elif t == 'repurposed_structures:pillar_processor':
            for pt in pr.get('pillar_trigger_and_replacements', []):
                rep = pt['replacement']
                pillars[pt['trigger'].get('id', pt['trigger'].get('Name'))] = (rep.get('id', rep.get('Name')), dict(rep.get('properties', {}) or {}))
    return rules, pillars

# ------------------------------------------------------------------ templates
def load_nbt(path):
    ns, p = path.split(':', 1) if ':' in path else ('repurposed_structures', path)
    n = nbtlib.load(data_file(ns, 'structure', p, '.nbt'))
    pal = n['palette'] if 'palette' in n else n['palettes'][0]
    states = []
    for st in pal:
        name = str(st.get('Name', st.get('id')))
        props = {str(k): str(v) for k, v in (st.get('Properties') or st.get('properties') or {}).items()}
        states.append((name, props))
    blocks = []
    for b in n['blocks']:
        pos = [int(v) for v in b['pos']]
        nbt = b.get('nbt')
        blocks.append((pos, int(b['state']), nbt))
    return [int(v) for v in n['size']], states, blocks

unknown = {}

class Struct:
    def __init__(self, name, kind, chance, yoff, ymin, ymax, slope, bio):
        self.name, self.kind, self.chance, self.yoff, self.ymin, self.ymax, self.slope, self.bio = name, kind, chance, yoff, ymin, ymax, slope, bio
        self.pal, self.palidx, self.blocks, self.groups, self.size = [], {}, [], [], [0, 0, 0]
        self.rand_chests, self.rand_group, self.cands = 0, 0, []

    def group(self, ids):
        key = tuple(ids)
        if key not in self.groups: self.groups.append(key)
        return self.groups.index(key)

    def palette(self, name, props, rules, pillars, flags):
        key = (name, tuple(sorted(props.items())), flags)
        if key in self.palidx: return self.palidx[key]
        pillar = 0
        if name in pillars:
            name, props = pillars[name]
            pillar = 1
        def four(nm, pr):
            out = []
            for r in range(4):
                v = block(nm, pr, r)
                if v == 'unknown':
                    unknown[nm] = unknown.get(nm, 0) + 1
                    v = None
                out.append(v)
            return out
        main = four(name, props)
        alts, acc = [], 0.0
        left = 1.0
        for prob, oname, oprops in rules.get(name, []):
            p = left * prob
            acc += p
            left -= p
            alts.append((min(65535, int(acc * 65535)), four(oname, oprops)))
        if main[0] is None and not alts: entry = None
        else:
            keep = 8 if main[0] is None else 0
            entry = (flags | pillar | keep, [m if m else (0, 0) for m in main], [(pr, [a if a else (0, 0) for a in al]) for pr, al in alts])
        if entry is None:
            self.palidx[key] = -1
            return -1
        self.pal.append(entry)
        self.palidx[key] = len(self.pal) - 1
        return self.palidx[key]

    def add_template(self, path, procs, loot, mobs, offset=(0, 0, 0), default_spawner_mob=None):
        size, states, blocks = load_nbt(path)
        rules, pillars = processors(procs)
        for i in range(3): self.size[i] = max(self.size[i], size[i] + offset[i])
        for pos, st, nbt in blocks:
            name, props = states[st]
            n = name.split(':', 1)[1]
            flags, extra = 0, 0
            if n == 'jigsaw':
                fin = str(nbt.get('final_state', 'minecraft:air')) if nbt else 'minecraft:air'
                name, props = fin.split('[')[0], {}
            if n in ('chest', 'trapped_chest', 'barrel', 'dispenser') and nbt is not None and 'LootTable' in nbt:
                ids = loot.table_ids(str(nbt['LootTable']))
                if ids:
                    flags = 2
                    extra = self.group(ids) + 1
            if n == 'spawner':
                mob = default_spawner_mob
                try:
                    mob = str(nbt['SpawnData']['entity']['id']).split(':')[1]
                except Exception:
                    pass
                mob = MOBS.get(mob, 'zombie')
                if mob not in mobs: mobs.append(mob)
                flags, extra = 4, mobs.index(mob) + 1
            pi = self.palette(name, props, rules, pillars, flags)
            if pi < 0: continue
            self.blocks.append((pos[0] + offset[0], pos[1] + offset[1], pos[2] + offset[2], pi, extra))
        return size, states, blocks

MOBS = {'zombie': 'zombie', 'husk': 'zombie', 'drowned': 'zombie', 'zombie_villager': 'zombie', 'skeleton': 'skeleton',
        'stray': 'skeleton', 'wither_skeleton': 'skeleton', 'spider': 'spider', 'cave_spider': 'cavespider',
        'witch': 'witch', 'silverfish': 'silverfish', 'creeper': 'creeper', 'slime': 'slime', 'blaze': 'blaze',
        'magma_cube': 'lavaslime', 'enderman': 'enderman', 'endermite': 'silverfish', 'bogged': 'skeleton',
        'pillager': 'skeleton', 'vindicator': 'zombie', 'guardian': 'silverfish', 'phantom': 'blaze'}

# ------------------------------------------------------------------ what to port
def build():
    loot, mobs, out = Loot(), [], []
    WELLS = [('forest', biomes('forest', 'plains', 'birch', 'flower'), 'repurposed_structures:wells/forest', -6),
             ('snow', biomes('ice', 'coldtaiga'), 'repurposed_structures:wells/snow', -3),
             ('badlands', biomes('mesa', 'desert'), 'repurposed_structures:wells/badlands', -2),
             ('mossy_stone', biomes('hills', 'roofed', 'swamp', 'taiga'), 'repurposed_structures:wells/mossy_stone', -2),
             ('mushroom', biomes('mushroom'), 'repurposed_structures:wells/mushroom', -3)]
    for n, bio, procs, yoff in WELLS:
        s = Struct('well_' + n, 0, 90, yoff, 0, 0, 2, bio)
        s.add_template('wells/' + n, procs, loot, mobs)
        out.append(s)
    HUTS = [('oak', biomes('forest', 'swamp', 'flower')), ('birch', biomes('birch')), ('taiga', biomes('taiga', 'coldtaiga')),
            ('dark_forest', biomes('roofed')), ('giant_tree_taiga', biomes('megataiga'))]
    for n, bio in HUTS:
        s = Struct('witch_hut_' + n, 0, 110, 0, 0, 0, 3, bio)
        s.add_template('witch_huts/' + n, 'repurposed_structures:witch_huts/' + n, loot, mobs)
        out.append(s)
    DUNGEONS = [('desert', biomes('desert')), ('icy', biomes('ice')), ('badlands', biomes('mesa')),
                ('dark_forest', biomes('roofed')), ('jungle', biomes('jungle')), ('mushroom', biomes('mushroom')),
                ('snow', biomes('coldtaiga')), ('swamp', biomes('swamp')), ('deep', ALL_LAND + biomes('megataiga'))]
    for n, bio in DUNGEONS:
        feat = json.load(open(os.path.join(ROOT, 'worldgen/feature/dungeons', n + '.json')))
        ids = loot.table_ids(feat['loot_block_loottable_identifier'])
        spawn_mob = {'desert': 'husk', 'icy': 'stray', 'snow': 'stray', 'swamp': 'slime', 'mushroom': 'zombie',
                     'jungle': 'cave_spider', 'badlands': 'husk', 'dark_forest': 'spider', 'deep': 'skeleton'}[n]
        for v in (1, 2, 3):
            s = Struct('dungeon_%s_%d' % (n, v), 1, 6, 0, 10, 50 if n != 'deep' else 28, 0, bio)
            s.add_template('dungeons/%s_%d' % (n, v), feat.get('processors'), loot, mobs, default_spawner_mob=spawn_mob)
            s.rand_chests = int(feat.get('max_num_of_loot_blocks', 2))
            s.rand_group = s.group(ids) if ids else 0
            # chest places: air on the floor (y = 1) next to a wall
            solid = {(x, y, z) for (x, y, z, pi, e) in s.blocks if s.pal[pi][1][0][0] != 0}
            for (x, y, z, pi, e) in s.blocks:
                if y == 1 and s.pal[pi][1][0][0] == 0 and (x, 0, z) in solid:
                    if any((x + dx, 1, z + dz) in solid for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                        s.cands.append((x, 1, z))
            out.append(s)
    # taiga temple
    s = Struct('temple_taiga', 0, 220, -3, 0, 0, 4, biomes('taiga', 'coldtaiga', 'megataiga'))
    s.add_template('temples/taiga', 'repurposed_structures:temples/taiga', loot, mobs)
    out.append(s)
    # pyramids: body + pit joined at their jigsaws
    PYR = [('badlands', biomes('mesa')), ('dark_forest', biomes('roofed')), ('giant_tree_taiga', biomes('megataiga')),
           ('icy', biomes('ice')), ('jungle', biomes('jungle')), ('mushroom', biomes('mushroom')),
           ('snowy', biomes('coldtaiga')), ('flower_forest', biomes('flower'))]
    for n, bio in PYR:
        procs = 'repurposed_structures:pyramids/' + n
        body = load_nbt('pyramids/%s_body' % n)
        pit = load_nbt('pyramids/%s_pit' % n)
        def jig(t):
            for pos, st, nbt in t[2]:
                if t[1][st][0].endswith('jigsaw'): return pos, t[1][st][1].get('orientation', 'down_south')
        jb, jp = jig(body), jig(pit)
        if not jb or not jp: continue
        d = {'down': (0, -1, 0), 'up': (0, 1, 0), 'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0)}[jb[1].split('_')[0]]
        pitpos = (jb[0][0] + d[0] - jp[0][0], jb[0][1] + d[1] - jp[0][1], jb[0][2] + d[2] - jp[0][2])
        lift = -min(0, pitpos[1])
        s = Struct('pyramid_' + n, 0, 260, -3 - lift, 0, 0, 4, bio)
        s.add_template('pyramids/%s_body' % n, procs, loot, mobs, offset=(0, lift, 0), default_spawner_mob='husk')
        s.add_template('pyramids/%s_pit' % n, procs, loot, mobs, offset=(pitpos[0], pitpos[1] + lift, pitpos[2]))
        out.append(s)
    return loot, mobs, out

DIRS = {'down': (0, -1, 0), 'up': (0, 1, 0), 'north': (0, 0, -1), 'south': (0, 0, 1), 'west': (-1, 0, 0), 'east': (1, 0, 0)}

def jigsaws(t):
    return [(pos, t[1][st][1].get('orientation', 'down_south'), nbt) for pos, st, nbt in t[2] if t[1][st][0].endswith('jigsaw')]

def joined(name, kind, chance, base_yoff, slope, bio, a_path, b_path, procs, loot, mobs):
    """two templates joined at their jigsaws (tower bottom + top, pyramid body + pit), shifted to start at 0"""
    a, b = load_nbt(a_path), load_nbt(b_path)
    ja, jb = jigsaws(a), jigsaws(b)
    if not ja or not jb: return None
    (pa, oa, _), (pb, ob, _) = ja[0], jb[0]
    d = DIRS[oa.split('_')[0]]
    off = (pa[0] + d[0] - pb[0], pa[1] + d[1] - pb[1], pa[2] + d[2] - pb[2])
    sh = (-min(0, off[0]), -min(0, off[1]), -min(0, off[2]))
    s = Struct(name, kind, chance, base_yoff - sh[1], 0, 0, slope, bio)
    s.add_template(a_path, procs, loot, mobs, offset=sh)
    s.add_template(b_path, procs, loot, mobs, offset=(off[0] + sh[0], off[1] + sh[1], off[2] + sh[2]))
    return s

def build_adventure():
    """Adventure Craft: Repurposed Structures + YUNG's Extras + Towers of the Wild"""
    loot, mobs, out = build()
    Y = 'yungsextras:'
    desert = biomes('desert')
    singles = [  # (template, chance, yoff, slope, biomes, kind)
        ('desert/wells/well_sm', 260, -7, 2, desert, 0), ('desert/wells/well_md', 300, -7, 2, desert, 0),
        ('desert/wells/well_lg', 360, -7, 2, desert, 0), ('desert/wells/dry_well_sm', 260, -7, 2, desert, 0),
        ('desert/wells/dry_well_md', 300, -7, 2, desert, 0), ('desert/wells/dry_well_lg', 360, -7, 2, desert, 0),
        ('desert/wells/wishing_well_sm', 400, -7, 2, desert, 0), ('desert/wells/wishing_well_md', 450, -7, 2, desert, 0),
        ('desert/wells/wishing_well_lg', 500, -7, 2, desert, 0), ('desert/obelisk/obelisk', 300, 0, 2, desert, 0),
        ('desert/obelisk/obelisk_creeper', 450, 0, 2, desert, 0), ('desert/obelisk/obelisk_rare', 700, 0, 2, desert, 0),
        ('desert/obelisk/obelisk_ruined', 300, 0, 2, desert, 0), ('desert/misc/chillzone', 350, 0, 2, desert, 0),
        ('desert/misc/giant_torch', 350, 0, 2, desert, 0), ('desert/misc/ruins_0', 250, -1, 2, desert, 0)]
    swamp = biomes('swamp')
    for i in range(11): singles.append(('swamp/arch/swamp_arch_%d' % i, 330, -1, 4, swamp, 2))
    for i in range(22): singles.append(('swamp/double_arch/swamp_double_arch_%d' % i, 600, -1, 4, swamp, 2))
    for i in range(8): singles.append(('swamp/cubby/swamp_cubby_%d' % i, 330, -1, 3, swamp, 2))
    for i in range(3): singles.append(('swamp/pillar/swamp_pillar_%d' % i, 250, -1, 3, swamp, 2))
    singles += [('swamp/misc/swamp_church', 700, -1, 4, swamp, 2), ('swamp/misc/swamp_ogre_0', 500, -1, 3, swamp, 2)]
    for path, chance, yoff, slope, bio, kind in singles:
        st = Struct('yung_' + path.split('/')[-1], kind, chance, yoff, 0, 0, slope, bio)
        st.add_template(Y + path, None, loot, mobs)
        out.append(st)
    T = 'towers_of_the_wild:'
    towers = [('regular', ALL_LAND, 'tower_bottom', 'tower_top'),
              ('derelict', biomes('savanna', 'desert', 'mesa'), 'derelict_tower_bottom', 'derelict_tower_top'),
              ('derelict_grass', biomes('plains', 'forest', 'birch', 'roofed'), 'derelict_grass_tower_bottom', 'derelict_grass_tower_top'),
              ('ice', biomes('ice', 'coldtaiga'), 'ice_tower_bottom', 'ice_tower_top'),
              ('jungle', biomes('jungle'), 'jungle_tower_bottom', 'jungle_tower_top')]
    for n, bio, bot, top in towers:
        st = joined('tower_' + n, 0, 300, 0, 3, bio, T + n + '/' + bot, T + n + '/' + top, None, loot, mobs)
        if st: out.append(st)
    return loot, mobs, out

def write(loot, mobs, structs):
    b = bytearray(b'NSB1')
    b += struct.pack('<HHH', len(loot.tables), len(mobs), len(structs))
    for m in mobs:
        e = m.encode(); b += struct.pack('<B', len(e)) + e
    for rmin, rmax, entries in loot.tables:
        b += struct.pack('<BBH', rmin, rmax, len(entries))
        for i, d, a, c, w in entries: b += struct.pack('<HHBBH', i, d, a, c, min(65535, w))
    for s in structs:
        e = s.name.encode(); b += struct.pack('<B', len(e)) + e
        b += struct.pack('<BHbBBB', s.kind, s.chance, s.yoff, s.ymin, s.ymax, s.slope)
        b += struct.pack('<B', len(s.bio)) + bytes(s.bio)
        b += struct.pack('<HHHH', s.size[0], s.size[1], s.size[2], len(s.pal))
        for flags, main, alts in s.pal:
            b += struct.pack('<BB', flags, main[0][0]) + bytes(m[1] & 0xff for m in main)
            b += struct.pack('<B', len(alts))
            for pr, al in alts:
                b += struct.pack('<HB', pr, al[0][0]) + bytes(a[1] & 0xff for a in al)
        b += struct.pack('<I', len(s.blocks))
        for x, y, z, pi, e in s.blocks: b += struct.pack('<BBBHB', x, y, z, pi, e)
        b += struct.pack('<BHH', s.rand_chests, s.rand_group, len(s.cands))
        for x, y, z in s.cands: b += struct.pack('<BBB', x, y, z)
        b += struct.pack('<B', len(s.groups))
        for g in s.groups: b += struct.pack('<B', len(g)) + b''.join(struct.pack('<H', i) for i in g)
    open(OUT, 'wb').write(b)
    return len(b)

if __name__ == '__main__':
    loot, mobs, structs = build_adventure() if '--adventure' in sys.argv else build()
    n = write(loot, mobs, structs)
    print('structures', len(structs), 'loot tables', len(loot.tables), 'mobs', mobs, 'bytes', n)
    for s in structs: print('  %-28s %s blocks %5d pal %3d groups %d cands %d' % (s.name, s.size, len(s.blocks), len(s.pal), len(s.groups), len(s.cands)))
    if unknown: print('unmapped (left as the world):', sorted(unknown.items(), key=lambda kv: -kv[1]))
