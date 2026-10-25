"""Uniformly scale pixel layout values of container screens (text-preserving edit).

Scales numbers in "size", "offset" and layout variables. Percent parts are kept,
"px" parts inside expressions ("50% - 12px") are scaled too. uv/uv_size untouched.
usage: scale_ui.py FACTOR file.json [file.json ...] [--blocks common.json name1,name2,...]
"""
import re, sys

KEYS = r'(?:size|offset|\$cell_image_size|\$screen_dimensions|\$grid_offset|\$bar_offset|\$bar_size)'
ARR = re.compile(r'("' + KEYS + r'"\s*:\s*)\[([^\]\n]*)\]')

def scale_num(v, f):
    x = float(v)
    r = round(x * f)
    if x != 0 and r == 0:
        r = 1 if x > 0 else -1
    return str(int(r))

def scale_item(item, f):
    item = item.strip()
    if item.startswith('"'):
        inner = item[1:-1]
        if inner.startswith('$') or inner.startswith('@'):
            return item
        inner = re.sub(r'(\d+(?:\.\d+)?)px', lambda m: scale_num(m.group(1), f) + 'px', inner)
        return '"' + inner + '"'
    if re.fullmatch(r'-?\d+(?:\.\d+)?', item):
        return scale_num(item, f)
    return item

def scale_text(s, f):
    def rep(m):
        items = [scale_item(x, f) for x in m.group(2).split(',')]
        return m.group(1) + '[ ' + ', '.join(items) + ' ]'
    return ARR.sub(rep, s)

def block_span(s, name):
    m = re.search(r'\n  "' + re.escape(name) + r'(?:@[^"]*)?"\s*:\s*\{', s)
    assert m, name
    i = m.end() - 1; depth = 0
    for j in range(i, len(s)):
        if s[j] == '{': depth += 1
        elif s[j] == '}':
            depth -= 1
            if depth == 0: return m.start(), j + 1
    raise ValueError(name)

def main():
    a = sys.argv[1:]
    f = eval(a.pop(0), {}, {})
    if '--blocks' in a:
        i = a.index('--blocks'); bfile, names = a[i + 1], a[i + 2].split(','); del a[i:i + 3]
        s = open(bfile, encoding='utf-8').read()
        for n in names:
            st, en = block_span(s, n)
            s = s[:st] + scale_text(s[st:en], f) + s[en:]
        open(bfile, 'w', encoding='utf-8').write(s)
        print('scaled blocks in', bfile, len(names))
    for p in a:
        s = open(p, encoding='utf-8').read()
        n = len(ARR.findall(s))
        open(p, 'w', encoding='utf-8').write(scale_text(s, f))
        print('scaled', p, n, 'arrays')

main()
