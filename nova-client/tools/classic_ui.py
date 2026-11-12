"""NOVA UI back to the classic Minecraft look (one-time edit of ui_new/*.json, like recolor.py was).

* Buttons (images of NormalButtonStroke/NoStroke tinted slate/teal) -> the 1.1.5 classic button pictures
  gui/nova/btn*.png (6x6, nineslice 2; made by tools/classic_gui.py from textures/gui/newgui/ButtonWithBorder*.png),
  untinted: default = grey with black outline, hover = lavender with white outline, pressed = pressed lavender.
* Panels/cards drawn with the same pictures -> the grey classic panel of dialog-background-atlas (uv 0,80 16x16).
* Colours: the NOVA mint/violet/slate palette -> the classic one (yellow highlights, neutral greys); reds, greens,
  yellows and browns stay.
usage: classic_ui.py ui_new"""
import os, re, sys

BTN = {'default': 'gui/nova/btn.png', 'hover': 'gui/nova/btn_hover.png', 'pressed': 'gui/nova/btn_pressed.png',
       'locked': 'gui/nova/btn_locked.png'}
OLD = ('gui/newgui/NormalButtonStroke.png', 'gui/newgui/NormalButtonNoStroke.png')
BUTTONISH = ('button', 'btn', 'state', 'hover', 'pressed', 'toggle', 'tab')
NUM = r'-?\d+(?:\.\d+)?'
COLOR_RE = re.compile(r'("[$a-z_]*color"\s*:\s*)\[\s*(%s)\s*,\s*(%s)\s*,\s*(%s)\s*(?:,\s*(%s)\s*)?\]' % (NUM, NUM, NUM, NUM))


def obj_span(s, pos):
    """start ('{') and end ('}') of the innermost object around pos, and the key in front of it"""
    depth = 0
    i = pos
    while i >= 0:
        c = s[i]
        if c == '}': depth += 1
        elif c == '{':
            if depth == 0: break
            depth -= 1
        i -= 1
    start = i
    depth = 0
    j = start
    while j < len(s):
        c = s[j]
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: break
        j += 1
    m = re.search(r'"([^"]+)"\s*:\s*$', s[max(0, start - 200):start])
    return start, j, (m.group(1) if m else '')


def own_part(s, a, b):
    """the object's own properties: up to its "controls" array (nested objects are not its properties)"""
    m = re.search(r'"controls"\s*:', s[a:b])
    return a, (a + m.start() if m else b)


PALETTE = {'default': [(0.47, 0.53, 0.68), (0.36, 0.41, 0.54), (0.45, 0.52, 0.7), (0.45, 0.5, 0.68)],
           'hover': [(0.34, 0.8, 0.82), (0.36, 0.86, 0.8), (0.31, 0.72, 0.74), (0.3, 0.78, 0.8), (0.36, 0.86, 0.86)],
           'pressed': [(0.24, 0.62, 0.66), (0.22, 0.55, 0.58), (0.24, 0.6, 0.62), (0.2, 0.55, 0.6)]}


def kind_of(rgb):
    """which button state a NOVA button tint stood for (None: not a button tint)"""
    for k, cols in PALETTE.items():
        for c in cols:
            if all(abs(a - b) < .015 for a, b in zip(rgb, c)): return k
    return None


def fmt(v):
    return ('%.2f' % v).rstrip('0').rstrip('.') if abs(v - round(v)) > 1e-9 else '%.1f' % v


def colour(vals, has_alpha):
    return '[ ' + ', '.join(fmt(v) for v in vals) + ' ]'


def map_colour(r, g, b, a):
    """the classic colour for a NOVA palette colour; None keeps it"""
    near = lambda x, y, z: abs(r - x) < .02 and abs(g - y) < .02 and abs(b - z) < .02
    if near(0.4, 0.95, 0.88):                          # mint accent -> classic yellow highlight
        return (1.0, 1.0, 0.2) if a is None else (1.0, 1.0, 0.63, a)
    if near(0.32, 0.76, 0.7): return (0.8, 0.8, 0.16)
    if near(0.68, 0.52, 1.0): return (1.0, 1.0, 0.0) + (() if a is None else (a,))    # violet focus -> yellow
    mx, mn = max(r, g, b), min(r, g, b)
    if mx - mn < 0.04: return None                     # grey already
    bluish = (b >= g - 0.02 and b > r + 0.04) or (g > r + 0.15 and b > r + 0.15)
    if not bluish: return None                         # reds, greens, yellows, browns
    L = 0.3 * r + 0.59 * g + 0.11 * b
    L = min(1.0, L * 1.08)
    return (L, L, L) + (() if a is None else (a,))


def set_prop(seg, key, value):
    pat = re.compile(r'("%s"\s*:\s*)(\[[^\]]*\]|"[^"]*"|%s)' % (key, NUM))
    if pat.search(seg):
        return pat.sub(lambda m: m.group(1) + value, seg, count=1)
    # insert after the opening brace
    nl = seg.index('{') + 1
    ind = re.search(r'\n(\s*)"', seg)
    ind = ind.group(1) if ind else '    '
    return seg[:nl] + '\n%s"%s": %s,' % (ind, key, value) + seg[nl:]


def process(s):
    edits = 0
    # 1) images that use the old button pictures
    while True:
        m = re.search(r'"texture"\s*:\s*"(%s)"' % '|'.join(re.escape(o) for o in OLD), s)
        if not m: break
        a, b, name = obj_span(s, m.start())
        a, e = own_part(s, a, b)
        seg = s[a:e]
        cm = COLOR_RE.search(seg)
        rgb = tuple(float(cm.group(i)) for i in (2, 3, 4)) if cm else (1, 1, 1)
        alpha = re.search(r'"alpha"\s*:\s*(%s)' % NUM, seg)
        alpha = float(alpha.group(1)) if alpha else 1.0
        low = name.lower()
        state = 'hover' if 'hover' in low else 'pressed' if 'press' in low else \
            'locked' if ('lock' in low or 'disabl' in low) else 'default' if 'default' in low else None
        if any(w in low for w in BUTTONISH) and 'card' not in low and 'background' not in low:
            st = state or kind_of(rgb) or 'default'
            seg = re.sub(r'"texture"\s*:\s*"[^"]*"', '"texture": "%s"' % BTN[st], seg, count=1)
            seg = set_prop(seg, 'uv_size', '[ 6, 6 ]')
            seg = set_prop(seg, 'nineslice_size', '2.0')
            seg = set_prop(seg, 'color', '[ 1.0, 1.0, 1.0, 1.0 ]')
            if alpha < 1: seg = set_prop(seg, 'alpha', '1.0')
        elif alpha < 0.6:                                   # translucent glass card -> classic dark shade
            seg = re.sub(r'"texture"\s*:\s*"[^"]*"', '"texture": "gui/newgui/newTouchScrollBox.png"', seg, count=1)
            seg = set_prop(seg, 'uv_size', '[ 1, 1 ]')
            seg = re.sub(r'\s*"nineslice_size"\s*:\s*%s\s*,?' % NUM, '', seg)
            seg = set_prop(seg, 'color', '[ 0.0, 0.0, 0.0, 1.0 ]')
            seg = set_prop(seg, 'alpha', '0.35')
        else:                                               # panel / window / badge -> classic grey panel
            seg = re.sub(r'"texture"\s*:\s*"[^"]*"', '"texture": "gui/newgui/dialog-background-atlas.png"', seg, count=1)
            seg = set_prop(seg, 'uv', '[ 0, 80 ]')
            seg = set_prop(seg, 'uv_size', '[ 16, 16 ]')
            seg = set_prop(seg, 'nineslice_size', '4.0')
            seg = set_prop(seg, 'color', '[ 1.0, 1.0, 1.0, 1.0 ]')
            if alpha < 1: seg = set_prop(seg, 'alpha', '1.0')
        s = s[:a] + seg + s[e:]
        edits += 1
    # 2) states that only change the colour of an inherited button picture
    pos = 0
    while True:
        m = re.compile(r'"([^"]*@[^"]*button[^"]*|[^"]*@[^"]*state[^"]*)"\s*:\s*\{').search(s, pos)
        if not m: break
        a, b, name = obj_span(s, m.end() - 1)
        a, e = own_part(s, a, b)
        seg = s[a:e]
        pos = m.end()
        if '"texture"' in seg or '"label"' in seg or '"text"' in seg or 'font' in seg: continue
        cm = COLOR_RE.search(seg)
        if not cm: continue
        rgb = tuple(float(cm.group(i)) for i in (2, 3, 4))
        low = name.lower()
        st = 'hover' if 'hover' in low else 'pressed' if 'press' in low else \
            'default' if 'default' in low else kind_of(rgb)
        if not st or not kind_of(rgb): continue
        seg = set_prop(seg, 'texture', '"%s"' % BTN[st])
        seg = set_prop(seg, 'color', '[ 1.0, 1.0, 1.0, 1.0 ]')
        s = s[:a] + seg + s[e:]
        edits += 1
    # 3) the palette
    def sub(m):
        r, g, b = (float(m.group(i)) for i in (2, 3, 4))
        a = float(m.group(5)) if m.group(5) else None
        new = map_colour(r, g, b, a)
        if new is None: return m.group(0)
        return m.group(1) + colour(new, a is not None)
    s, n = COLOR_RE.subn(sub, s)
    return s, edits


def main(d):
    for f in sorted(os.listdir(d)):
        if not f.endswith('.json'): continue
        p = os.path.join(d, f)
        s = open(p, encoding='utf-8').read()
        t, n = process(s)
        if t != s:
            open(p, 'w', encoding='utf-8').write(t)
            print('%-28s %d pictures' % (f, n))


if __name__ == '__main__':
    main(sys.argv[1])
