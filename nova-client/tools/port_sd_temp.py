"""SimpleDifficulty (Charles445, MIT) temperature -> Adventure Craft mod files.

  python3 tools/port_sd_temp.py <SimpleDifficulty checkout> <Java textures dir> <mod dir>

  HUD pictures (temphud.png cut into frames), item / armor / effect textures, and the Java block models of the
  campfire and the heater / chiller turned into Blockbench .bbmodel (java_block, textures inside as data: URIs).
  Faces with a 90/270 degree UV rotation get their own pre-rotated picture (NOVA's loader turns only 180).
"""
import base64, io, json, os, sys
from PIL import Image

SD, JT, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
A = os.path.join(SD, 'src/main/resources/assets/simpledifficulty')
T = os.path.join(OUT, 'textures/temperature')


def save(im, *p):
    path = os.path.join(T, *p)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


def tex(rel):
    return Image.open(os.path.join(A, 'textures', rel)).convert('RGBA')


# ---------------------------------------------------------------- HUD (TemperatureGui, modern + alternate)
hud = tex('gui/temphud.png')
for i in range(16):                                     # core orb: 0-4 freezing frames, 5-11 cold..hot, 12-15 burning
    save(hud.crop((i * 16, 0, i * 16 + 16, 16)), 'hud', 'core_%02d.png' % i)
for i in range(8):                                      # outside feel ring (32x32)
    save(hud.crop((i * 32, 16, i * 32 + 32, 48)), 'hud', 'ring_%d.png' % i)
save(hud.crop((0, 48, 32, 80)), 'hud', 'ring_hot.png')
for i in range(15):                                     # change animation: y 144 falling, 160 rising
    save(hud.crop((i * 16, 144, i * 16 + 16, 160)), 'hud', 'arrow_down_%02d.png' % i)
    save(hud.crop((i * 16, 160, i * 16 + 16, 176)), 'hud', 'arrow_up_%02d.png' % i)
for t in range(26):                                     # HUD thermometer: 8 per row from y 192
    x, y = (t % 8) * 16, 192 + (t // 8) * 16
    save(hud.crop((x, y, x + 16, y + 16)), 'hud', 'therm_%02d.png' % t)
for e in ('hypothermia', 'hyperthermia', 'resist_cold', 'resist_heat'):   # 18x18 effect icons on a 32x32 canvas
    c = Image.new('RGBA', (32, 32))
    c.paste(tex('potions/%s.png' % e), (0, 0))
    save(c, 'effects', e + '.png')

# ---------------------------------------------------------------- items and armor
strip = Image.new('RGBA', (16, 16 * 26))           # like the clock: one picture, 26 frames top to bottom
for t in range(26):
    strip.paste(tex('items/thermometer_%02d.png' % t), (0, 16 * t))
save(strip, 'items', 'thermometer.png')
for n in ('ice_chunk', 'magma_chunk', 'wool_helmet', 'wool_chestplate', 'wool_leggings', 'wool_boots',
          'ice_helmet', 'ice_chestplate', 'ice_leggings', 'ice_boots'):
    save(tex('items/%s.png' % n), 'items', n + '.png')
for n in ('wool_layer_1', 'wool_layer_2', 'ice_layer_1', 'ice_layer_2'):
    save(tex('models/armor/%s.png' % n), 'armor', n + '.png')


def potion(color):                                       # vanilla bottle + liquid overlay tinted by the effect color
    bottle = Image.open(os.path.join(JT, 'item/potion.png')).convert('RGBA')
    ov = Image.open(os.path.join(JT, 'item/potion_overlay.png')).convert('RGBA')
    r, g, b = (color >> 16) & 255, (color >> 8) & 255, color & 255
    px = ov.load()
    for y in range(ov.height):
        for x in range(ov.width):
            pr, pg, pb, pa = px[x, y]
            px[x, y] = (pr * r // 255, pg * g // 255, pb * b // 255, pa)
    out = Image.new('RGBA', bottle.size)
    out.alpha_composite(ov)
    out.alpha_composite(bottle)
    return out


save(potion(0x8EF1FF), 'items', 'potion_cold_resist.png')    # PotionResistCold liquid color
save(potion(0xFFCD72), 'items', 'potion_heat_resist.png')    # PotionResistHeat
mag = Image.open(os.path.join(JT, 'block/magma.png')).convert('RGBA')
save(mag, 'blocks', 'magma.png')       # 1.10 magma block (not in 0.14): the whole animation strip (frametime 8)

# ---------------------------------------------------------------- Java block models -> .bbmodel


def data_uri(im):
    b = io.BytesIO()
    im.save(b, 'PNG')
    return 'data:image/png;base64,' + base64.b64encode(b.getvalue()).decode()


def convert(name, out_name):
    j = json.load(open(os.path.join(A, 'models/block', name + '.json')))
    texmap = {}                                          # '#1' -> index
    textures = []

    def add(im, nm):
        textures.append({'name': nm, 'id': str(len(textures)), 'source': data_uri(im),
                         'uv_width': im.width, 'uv_height': im.height, 'width': im.width, 'height': im.height})
        return len(textures) - 1

    for k, v in j['textures'].items():
        if k == 'particle':
            continue
        texmap['#' + k] = add(tex(v.split(':')[1] + '.png'), v.split('/')[-1] + '.png')
    els = []
    for e in j['elements']:
        el = {'type': 'cube', 'from': e['from'], 'to': e['to'], 'faces': {}}
        r = e.get('rotation')
        if r:
            ax = {'x': 0, 'y': 1, 'z': 2}[r['axis']]
            rot = [0, 0, 0]
            rot[ax] = r['angle']
            el['rotation'] = rot
            el['origin'] = r['origin']
        for f, fc in e['faces'].items():
            ti = texmap[fc['texture']]
            uv = fc.get('uv', [0, 0, 16, 16])
            fr = fc.get('rotation', 0)
            if fr in (90, 270):                         # bake the rotated region into its own picture
                src = Image.open(io.BytesIO(base64.b64decode(textures[ti]['source'].split(',')[1])))
                u0, v0, u1, v1 = uv
                reg = src.crop((int(min(u0, u1)), int(min(v0, v1)), int(max(u0, u1)), int(max(v0, v1))))
                if u0 > u1:
                    reg = reg.transpose(Image.FLIP_LEFT_RIGHT)
                if v0 > v1:
                    reg = reg.transpose(Image.FLIP_TOP_BOTTOM)
                reg = reg.rotate(-fr, expand=True)      # Minecraft turns the texture clockwise
                ti = add(reg, '%s_%d_%s.png' % (out_name, len(els), f))
                uv = [0, 0, reg.width, reg.height]
            el['faces'][f] = {'uv': uv, 'texture': ti}
        els.append(el)
    bb = {'meta': {'format_version': '4.0', 'model_format': 'java_block', 'box_uv': False},
          'name': out_name, 'resolution': {'width': 16, 'height': 16}, 'elements': els, 'textures': textures}
    os.makedirs(os.path.join(OUT, 'models'), exist_ok=True)
    json.dump(bb, open(os.path.join(OUT, 'models', out_name + '.bbmodel'), 'w'), separators=(',', ':'))


for src, dst in (('campfire', 'campfire'), ('campfire_half', 'campfire_half'), ('campfire_burnt', 'campfire_burnt'),
                 ('temper_heat_off', 'heater'), ('temper_heat_on', 'heater_on'),
                 ('temper_chill_off', 'chiller'), ('temper_chill_on', 'chiller_on')):
    convert(src, dst)
print('ok')
