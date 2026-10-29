"""MCPE 0.14 atlas tiles -> Java 1.19.4 texture files (new textures option).
key: 't:<meta name>:<index>' (terrain) or 'i:<meta name>:<index>' (items)
value: 'block/x' | 'item/x' | ('tint', 'block/x') -> java gray tile tinted with the
old tile's average colour | ('grass_side', i) | ('opaque', 'block/x') | ('potion', 'item/x')
Missing keys keep the old tile."""

M = {}
def T(name, *files):
    for i, f in enumerate(files):
        if f: M['t:%s:%d' % (name, i)] = f
def I(name, *files):
    for i, f in enumerate(files):
        if f: M['i:%s:%d' % (name, i)] = f

COLORS = ['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray', 'light_gray',
          'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black']
WOOD = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak']
MAT = ['wooden', 'stone', 'iron', 'golden', 'diamond']
ARM = ['leather', 'chainmail', 'iron', 'golden', 'diamond']

# ---------------- terrain ----------------
T('grass', 'block/grass_block_top', 'block/grass_block_snow', ('tint', 'block/grass_block_top'),
  *[('grass_side', 0)] * 16)
T('stone', 'block/stone', 'block/granite', 'block/polished_granite', 'block/diorite', 'block/polished_diorite',
  'block/andesite', 'block/polished_andesite')
T('cobblestone', 'block/cobblestone')
T('cobblestone_mossy', 'block/mossy_cobblestone')
T('stonebrick', 'block/stone_bricks', 'block/mossy_stone_bricks', 'block/cracked_stone_bricks', 'block/chiseled_stone_bricks')
T('bedrock', 'block/bedrock')
T('obsidian', 'block/obsidian')
T('clay', 'block/clay')
T('sand', 'block/sand', 'block/red_sand')
T('sandstone', 'block/sandstone_top', 'block/chiseled_sandstone', 'block/cut_sandstone', 'block/sandstone', 'block/sandstone_bottom')
T('redsandstone', 'block/red_sandstone_top', 'block/chiseled_red_sandstone', 'block/cut_red_sandstone', 'block/red_sandstone', 'block/red_sandstone_bottom')
T('gravel', 'block/gravel')
T('dirt', 'block/dirt', 'block/podzol_top', 'block/podzol_side')
T('planks', *['block/%s_planks' % w for w in WOOD])
T('stone_slab', 'block/smooth_stone', 'block/smooth_stone_slab_side')
T('brick', 'block/bricks')
T('tnt', 'block/tnt_side', 'block/tnt_top', 'block/tnt_bottom')
T('bookshelf', 'block/bookshelf')
T('web', 'block/cobweb')
T('flower1', 'block/dandelion')
T('flower2', 'block/poppy', 'block/blue_orchid', 'block/allium', 'block/azure_bluet', 'block/red_tulip',
  'block/orange_tulip', 'block/white_tulip', 'block/pink_tulip', 'block/oxeye_daisy')
T('double_plant_bottom', 'block/sunflower_bottom', 'block/lilac_bottom', 'block/tall_grass_bottom',
  'block/large_fern_bottom', 'block/rose_bush_bottom', 'block/peony_bottom')
T('double_plant_top', 'block/sunflower_top', 'block/lilac_top', 'block/tall_grass_top',
  'block/large_fern_top', 'block/rose_bush_top', 'block/peony_top')
T('sunflower_additional', 'block/sunflower_front', 'block/sunflower_back')
T('sapling', *['block/%s_sapling' % w for w in WOOD])
T('log', 'block/oak_log', 'block/oak_log_top', 'block/spruce_log', 'block/spruce_log_top',
  'block/birch_log', 'block/birch_log_top', 'block/jungle_log', 'block/jungle_log_top')
T('log2', 'block/acacia_log', 'block/acacia_log_top', 'block/dark_oak_log', 'block/dark_oak_log_top')
for b in ['iron_block', 'gold_block', 'diamond_block', 'coal_block', 'lapis_block', 'emerald_block',
          'redstone_block', 'gold_ore', 'iron_ore', 'coal_ore', 'lapis_ore', 'diamond_ore', 'redstone_ore',
          'emerald_ore', 'sponge', 'glass', 'snow', 'ice', 'ladder', 'iron_trapdoor', 'iron_bars', 'lever',
          'netherrack', 'soul_sand', 'glowstone', 'end_stone', 'dragon_egg', 'command_block', 'flower_pot',
          'slime_block', 'hopper_inside', 'hopper_outside', 'hopper_top', 'jukebox_side', 'jukebox_top',
          'cauldron_top', 'cauldron_inner', 'cauldron_side', 'cauldron_bottom', 'brewing_stand_base',
          'brewing_stand', 'cake_bottom', 'cake_inner', 'cake_top', 'cake_side', 'daylight_detector_side',
          'daylight_detector_top', 'daylight_detector_inverted_top', 'enchanting_table_bottom',
          'enchanting_table_side', 'enchanting_table_top', 'glass_pane_top', 'obsidian']:
    T(b, 'block/' + b)
T('quartz_block', 'block/quartz_block_side', 'block/quartz_block_top', 'block/quartz_block_bottom',
  'block/chiseled_quartz_block', 'block/chiseled_quartz_block_top', 'block/quartz_pillar', 'block/quartz_pillar_top')
T('command_block', 'block/command_block_front')
T('mushroom_red', 'block/red_mushroom')
T('mushroom_brown', 'block/brown_mushroom')
T('quartz_ore', 'block/nether_quartz_ore')
T('tallgrass', 'block/grass', 'block/dead_bush', 'block/fern', ('tint', 'block/grass'), ('tint', 'block/fern'))
T('crafting_table', 'block/crafting_table_top', 'block/crafting_table_side', 'block/crafting_table_front')
T('furnace', 'block/furnace_front', 'block/furnace_front_on', 'block/furnace_side', 'block/furnace_top')
T('leaves', 'block/oak_leaves', 'block/spruce_leaves', 'block/birch_leaves', 'block/jungle_leaves',
  ('tint', 'block/oak_leaves'), ('tint', 'block/spruce_leaves'), ('tint', 'block/birch_leaves'), ('tint', 'block/jungle_leaves'))
T('leaves_opaque', ('opaque', 'block/oak_leaves'), ('opaque', 'block/spruce_leaves'),
  ('opaque', 'block/birch_leaves'), ('opaque', 'block/jungle_leaves'))
T('leaves2', 'block/acacia_leaves', 'block/dark_oak_leaves', ('tint', 'block/acacia_leaves'), ('tint', 'block/dark_oak_leaves'))
T('leaves_opaque2', ('opaque', 'block/acacia_leaves'), ('opaque', 'block/dark_oak_leaves'))
T('mob_spawner', 'block/spawner')
T('ice_packed', 'block/packed_ice')
T('cactus', 'block/cactus_top', 'block/cactus_side', 'block/cactus_bottom')
T('reeds', 'block/sugar_cane')
T('waterlily', 'block/lily_pad', ('tint', 'block/lily_pad'))
T('mycelium', 'block/mycelium_side', 'block/mycelium_top')
T('torch_on', 'block/torch')
T('redstone_torch_on', 'block/redstone_torch')
T('redstone_torch_off', 'block/redstone_torch_off')
T('door', 'block/oak_door_bottom', 'block/oak_door_top', 'block/spruce_door_bottom', 'block/spruce_door_top',
  'block/birch_door_bottom', 'block/birch_door_top', 'block/jungle_door_bottom', 'block/jungle_door_top',
  'block/acacia_door_bottom', 'block/acacia_door_top', 'block/dark_oak_door_bottom', 'block/dark_oak_door_top',
  'block/iron_door_bottom', 'block/iron_door_top')
T('trapdoor', 'block/oak_trapdoor')
T('farmland', 'block/farmland_moist', 'block/farmland')
T('wheat', *['block/wheat_stage%d' % k for k in range(8)])
T('grass_path', 'block/dirt_path_top', 'block/dirt_path_side')
T('pumpkin', 'block/pumpkin_top', 'block/pumpkin_side', 'block/carved_pumpkin', 'block/jack_o_lantern')
T('nether_brick', 'block/nether_bricks')
T('nether_wart', 'block/nether_wart_stage0', 'block/nether_wart_stage1', 'block/nether_wart_stage2')
T('piston_top_sticky', 'block/piston_top_sticky')
T('piston_top_normal', 'block/piston_top')
T('piston_side', 'block/piston_side')
T('piston_inner', 'block/piston_inner')
T('piston_bottom', 'block/piston_bottom')
T('melon', 'block/melon_side', 'block/melon_top')
T('melon_stem', 'block/melon_stem', 'block/attached_melon_stem')
T('pumpkin_stem', 'block/pumpkin_stem', 'block/attached_pumpkin_stem')
T('rail_normal', 'block/rail')
T('rail_normal_turned', 'block/rail_corner')
T('rail_golden', 'block/powered_rail')
T('rail_golden_powered', 'block/powered_rail_on')
T('rail_detector', 'block/detector_rail')
T('rail_detector_powered', 'block/detector_rail_on')
T('rail_activator', 'block/activator_rail')
T('rail_activator_powered', 'block/activator_rail_on')
T('mushroom_block', 'block/brown_mushroom_block', 'block/red_mushroom_block', 'block/mushroom_block_inside', 'block/mushroom_stem')
T('vine', 'block/vine', ('tint', 'block/vine'))
T('repeater_off', 'block/repeater')
T('repeater_on', 'block/repeater_on')
T('endframee', 'block/end_portal_frame_eye', 'block/end_portal_frame_side', 'block/end_portal_frame_top')
T('redstone_dust_cross', 'block/redstone_dust_dot')
T('redstone_dust_line', ('rot90', 'block/redstone_dust_line0'))
T('redstone_lamp_off', 'block/redstone_lamp')
T('redstone_lamp_on', 'block/redstone_lamp_on')
T('hayblock', 'block/hay_block_top', 'block/hay_block_side')
T('cocoa', 'block/cocoa_stage0', 'block/cocoa_stage1', 'block/cocoa_stage2')
T('trip_wire', 'block/tripwire')
T('trip_wire_source', 'block/tripwire_hook')
T('itemframe_background', 'block/item_frame')
T('carrots', *['block/carrots_stage%d' % k for k in range(4)])
T('potatoes', *['block/potatoes_stage%d' % k for k in range(4)])
T('beetroot', *['block/beetroots_stage%d' % k for k in range(4)])
T('anvil_base', 'block/anvil')
T('anvil_top_damaged_x', 'block/anvil_top', 'block/chipped_anvil_top', 'block/damaged_anvil_top')
T('destroy', *['block/destroy_stage_%d' % k for k in range(10)])
T('wool', *['block/%s_wool' % c for c in COLORS])
T('hardened_clay', 'block/terracotta')
T('stained_clay', *['block/%s_terracotta' % c for c in COLORS])
T('noteblock', 'block/note_block')
T('comparator_on', 'block/comparator_on')
T('comparator_off', 'block/comparator')
T('dispenser_front_horizontal', 'block/dispenser_front')
T('dispenser_front_vertical', 'block/dispenser_front_vertical')
T('dropper_front_horizontal', 'block/dropper_front')
T('dropper_front_vertical', 'block/dropper_front_vertical')

# ---------------- items ----------------
for n in ['apple', 'arrow', 'blaze_powder', 'blaze_rod', 'bone', 'bowl', 'bread', 'brewing_stand', 'brick',
          'cake', 'carrot', 'carrot_on_a_stick', 'beetroot', 'cauldron', 'charcoal', 'clay_ball', 'coal',
          'comparator', 'cookie', 'diamond', 'diamond_horse_armor', 'egg', 'emerald', 'ender_eye', 'ender_pearl',
          'experience_bottle', 'feather', 'flint', 'flint_and_steel', 'flower_pot', 'ghast_tear',
          'glowstone_dust', 'gold_horse_armor', 'gold_ingot', 'gold_nugget', 'gunpowder', 'hopper',
          'iron_horse_armor', 'iron_ingot', 'item_frame', 'lead', 'leather', 'magma_cream', 'melon_slice',
          'nether_star', 'nether_wart', 'painting', 'paper', 'pumpkin_pie', 'quartz', 'redstone', 'repeater',
          'rotten_flesh', 'saddle', 'shears', 'slime_ball', 'snowball', 'spider_eye', 'stick', 'string',
          'sugar', 'wheat', 'rabbit_foot', 'rabbit_hide', 'rabbit_stew', 'name_tag', 'mushroom_stew',
          'beetroot_soup', 'potato', 'baked_potato', 'poisonous_potato', 'bucket', 'minecart', 'lever']:
    pass
D = {'apple': 'apple', 'apple_golden': 'golden_apple', 'arrow': 'arrow', 'beef_cooked': 'cooked_beef',
     'beef_raw': 'beef', 'blaze_powder': 'blaze_powder', 'blaze_rod': 'blaze_rod', 'bone': 'bone',
     'book_enchanted': 'enchanted_book', 'book_normal': 'book', 'book_writable': 'writable_book',
     'book_written': 'written_book', 'bowl': 'bowl', 'bow_standby': 'bow', 'bread': 'bread',
     'brewing_stand': 'brewing_stand', 'brick': 'brick', 'cake': 'cake', 'carrot': 'carrot',
     'carrot_golden': 'golden_carrot', 'carrot_on_a_stick': 'carrot_on_a_stick', 'beetroot': 'beetroot',
     'cauldron': 'cauldron', 'charcoal': 'charcoal', 'chicken_cooked': 'cooked_chicken', 'chicken_raw': 'chicken',
     'clay_ball': 'clay_ball', 'coal': 'coal', 'comparator': 'comparator', 'cookie': 'cookie', 'diamond': 'diamond',
     'diamond_horse_armor': 'diamond_horse_armor', 'door_iron': 'iron_door', 'door_wood': 'oak_door',
     'door_spruce': 'spruce_door', 'door_birch': 'birch_door', 'door_jungle': 'jungle_door',
     'door_acacia': 'acacia_door', 'door_darkoak': 'dark_oak_door', 'egg': 'egg', 'emerald': 'emerald',
     'ender_eye': 'ender_eye', 'ender_pearl': 'ender_pearl', 'experience_bottle': 'experience_bottle',
     'feather': 'feather', 'fireball': 'fire_charge', 'fireworks': 'firework_rocket', 'fireworks_charge': 'firework_star',
     'fish_raw_cod': 'cod', 'fish_raw_salmon': 'salmon', 'fish_raw_clown_fish': 'tropical_fish',
     'fish_raw_puffer_fish': 'pufferfish', 'fish_cooked_cod': 'cooked_cod', 'fish_cooked_salmon': 'cooked_salmon',
     'flint': 'flint', 'flint_and_steel': 'flint_and_steel', 'flower_pot': 'flower_pot', 'ghast_tear': 'ghast_tear',
     'glowstone_dust': 'glowstone_dust', 'gold_horse_armor': 'golden_horse_armor', 'gold_ingot': 'gold_ingot',
     'gold_nugget': 'gold_nugget', 'gunpowder': 'gunpowder', 'hopper': 'hopper', 'iron_horse_armor': 'iron_horse_armor',
     'iron_ingot': 'iron_ingot', 'item_frame': 'item_frame', 'lead': 'lead', 'leather': 'leather',
     'magma_cream': 'magma_cream', 'map_empty': 'map', 'map_filled': 'filled_map', 'melon': 'melon_slice',
     'melon_speckled': 'glistering_melon_slice', 'minecart_chest': 'chest_minecart', 'minecart_furnace': 'furnace_minecart',
     'minecart_hopper': 'hopper_minecart', 'minecart_normal': 'minecart', 'minecart_tnt': 'tnt_minecart',
     'mushroom_stew': 'mushroom_stew', 'beetroot_soup': 'beetroot_soup', 'name_tag': 'name_tag',
     'netherbrick': 'nether_brick', 'nether_star': 'nether_star', 'nether_wart': 'nether_wart', 'painting': 'painting',
     'paper': 'paper', 'porkchop_cooked': 'cooked_porkchop', 'porkchop_raw': 'porkchop', 'potato': 'potato',
     'potato_baked': 'baked_potato', 'potato_poisonous': 'poisonous_potato', 'potion_bottle_empty': 'glass_bottle',
     'pumpkin_pie': 'pumpkin_pie', 'quartz': 'quartz', 'record_11': 'music_disc_11', 'record_13': 'music_disc_13',
     'record_blocks': 'music_disc_blocks', 'record_cat': 'music_disc_cat', 'record_chirp': 'music_disc_chirp',
     'record_far': 'music_disc_far', 'record_mall': 'music_disc_mall', 'record_mellohi': 'music_disc_mellohi',
     'record_stal': 'music_disc_stal', 'record_strad': 'music_disc_strad', 'record_wait': 'music_disc_wait',
     'record_ward': 'music_disc_ward', 'redstone_dust': 'redstone', 'reeds': 'sugar_cane', 'repeater': 'repeater',
     'rotten_flesh': 'rotten_flesh', 'saddle': 'saddle', 'seeds_melon': 'melon_seeds', 'seeds_pumpkin': 'pumpkin_seeds',
     'seeds_wheat': 'wheat_seeds', 'seeds_beetroot': 'beetroot_seeds', 'shears': 'shears', 'sign': 'oak_sign',
     'slimeball': 'slime_ball', 'snowball': 'snowball', 'spider_eye': 'spider_eye',
     'spider_eye_fermented': 'fermented_spider_eye', 'stick': 'stick', 'string': 'string', 'sugar': 'sugar',
     'wheat': 'wheat', 'clock_item': 'clock_00', 'compass_item': 'compass_00', 'rabbit_cooked': 'cooked_rabbit',
     'rabbit_foot': 'rabbit_foot', 'rabbit_hide': 'rabbit_hide', 'rabbit': 'rabbit', 'rabbit_stew': 'rabbit_stew',
     'empty_armor_slot_boots': 'empty_armor_slot_boots',
     'empty_armor_slot_chestplate': 'empty_armor_slot_chestplate', 'empty_armor_slot_helmet': 'empty_armor_slot_helmet',
     'empty_armor_slot_leggings': 'empty_armor_slot_leggings'}
for k, v in D.items():
    I(k, 'item/' + v)
I('axe', *['item/%s_axe' % m for m in MAT])
I('hoe', *['item/%s_hoe' % m for m in MAT])
I('pickaxe', *['item/%s_pickaxe' % m for m in MAT])
I('shovel', *['item/%s_shovel' % m for m in MAT])
I('sword', *['item/%s_sword' % m for m in MAT])
I('helmet', *['item/%s_helmet' % m for m in ARM])
I('chestplate', *['item/%s_chestplate' % m for m in ARM])
I('leggings', *['item/%s_leggings' % m for m in ARM])
I('boots', *['item/%s_boots' % m for m in ARM])
I('boat', *['item/%s_boat' % w for w in WOOD])
I('bow_pulling', 'item/bow_pulling_0', 'item/bow_pulling_1', 'item/bow_pulling_2')
I('bucket', 'item/bucket', 'item/milk_bucket', 'item/water_bucket', 'item/lava_bucket')
I('dye_powder', 'item/ink_sac', 'item/red_dye', 'item/green_dye', 'item/cocoa_beans', 'item/lapis_lazuli',
  'item/purple_dye', 'item/cyan_dye', 'item/light_gray_dye', 'item/gray_dye', 'item/pink_dye', 'item/lime_dye',
  'item/yellow_dye', 'item/light_blue_dye', 'item/magenta_dye', 'item/orange_dye', 'item/bone_meal')
I('fishing_rod', 'item/fishing_rod', 'item/fishing_rod_cast')
I('potion_bottle_drinkable', *[('potion', 'item/potion')] * 24)
I('potion_bottle_splash', *[('potion', 'item/splash_potion')] * 24)
I('potion_overlay', 'item/potion_overlay')
I('fireworks_charge_overlay', 'item/firework_star_overlay')
