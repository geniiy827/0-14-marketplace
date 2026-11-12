"""1.1.5 interface for NUI (native/nui.inc): copies the 1.1.5 UI json and GUI pictures into assets_new and adds the
1.1.5 strings the 0.14 .lang files lack.
  ui json  -> assets_new/ui115/ui/*.json                (libnova reads them: readAssetFile("ui115/ui/..."))
  pictures -> assets_new/images/ui115/textures/gui/...  (TextureGroup path "ui115/textures/gui/....png")
  strings  -> assets_new/loc/<lang>-pocket.lang         (only keys 0.14 does not have; 0.14 text wins)
Usage: ui115_assets.py <1.1.5 vanilla resource pack> <assets_new>"""
import os, sys, re, shutil, json

PREFIX = ('menu.', 'options.', 'selectWorld.', 'createWorld.', 'gui.', 'skins.', 'playscreen.', 'settings.',
          'pauseScreen.', 'disconnectionScreen.', 'store.', 'gameMode.', 'difficulty.', 'generator.', 'controller.',
          'accessibility.', 'resourcePack.', 'permissions.', 'deathScreen.', 'progressScreen.', 'connect.',
          'networkWorld.', 'worldConversion.', 'keyboard.', 'chat.', 'tile.', 'item.', 'container.', 'stat.')
SKIP_PICS = ('background', 'titleEdu.png', 'titleFireTV.png', 'titleVR.png', 'titleOriginal.png')


def copy_tree(src, dst, skip=()):
    n = 0
    for root, dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        if rel != '.' and rel.split(os.sep)[0] in skip:
            continue
        for f in files:
            if rel == '.' and f in skip:
                continue
            s = os.path.join(root, f)
            d = os.path.join(dst, rel, f)
            os.makedirs(os.path.dirname(d), exist_ok=True)
            if not os.path.exists(d) or os.path.getmtime(d) < os.path.getmtime(s) or os.path.getsize(d) != os.path.getsize(s):
                shutil.copyfile(s, d)
            n += 1
    return n


def read_lang(p):
    out = {}
    for l in open(p, encoding='utf-8-sig', errors='replace').read().replace('\r', '').split('\n'):
        if '=' not in l or l.startswith('#'):
            continue
        k, v = l.split('=', 1)
        v = v.split('\t')[0].split('##')[0].rstrip() if '\t' in v or '##' in v else v.rstrip()
        out[k.strip()] = v
    return out


def ui_keys(ui):
    keys = set()
    for f in os.listdir(ui):
        if f.endswith('.json'):
            keys.update(re.findall(r'"([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"', open(os.path.join(ui, f), encoding='utf-8', errors='replace').read()))
    return keys


def main(pack, out):
    n = copy_tree(os.path.join(pack, 'ui'), os.path.join(out, 'ui115', 'ui'))
    m = copy_tree(os.path.join(pack, 'textures', 'gui'), os.path.join(out, 'images', 'ui115', 'textures', 'gui'), SKIP_PICS)
    used = ui_keys(os.path.join(pack, 'ui'))
    loc = os.path.join(out, 'loc')
    added = 0
    for f in sorted(os.listdir(loc)):
        mm = re.match(r'(.+)-pocket\.lang$', f)
        if not mm:
            continue
        src = os.path.join(pack, 'texts', mm.group(1) + '.lang')
        if not os.path.exists(src):
            src = os.path.join(pack, 'texts', 'en_US.lang')
        new = read_lang(src)
        p = os.path.join(loc, f)
        text = open(p, encoding='utf-8').read()
        have = set(l.split('=', 1)[0] for l in text.split('\n') if '=' in l)
        lines = [l for l in text.split('\n')]
        while lines and lines[-1] == '':
            lines.pop()
        for k in sorted(new):
            if k in have or not new[k]:
                continue
            if k in used or k.startswith(PREFIX):
                lines.append('%s=%s\t#' % (k, new[k].replace('\t', ' ')))
                added += 1
        open(p, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    print('ui115: %d json, %d pictures, %d strings added' % (n, m, added))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
