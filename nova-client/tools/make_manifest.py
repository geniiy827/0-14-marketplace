"""Build the NOVA client manifest from the original binary AndroidManifest.xml.
usage: make_manifest.py ORIG.xml OUT.xml
"""
import sys, os, copy
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml
from axml import Element, Attr, a_str, a_int, a_bool, a_ref, ANDROID_NS, T_INT_HEX

# android.R.attr ids (android-23 android.jar)
NAME, LABEL, THEME, EXPORTED = 0x01010003, 0x01010001, 0x01010000, 0x01010010
SCHEME, MIME, HOST, PATHPAT = 0x01010027, 0x01010026, 0x01010028, 0x0101002c
SCREEN_ORIENT, CONFIG_CHANGES, EXCLUDE_RECENTS = 0x0101001e, 0x0101001f, 0x01010017
PROCESS, NO_HISTORY, TASK_AFFINITY, MIN_SDK, TARGET_SDK = 0x01010011, 0x0101022d, 0x01010012, 0x0101020c, 0x01010270
THEME_FULLSCREEN = 0x01030007          # Theme.Black.NoTitleBar.Fullscreen (same as the game)
THEME_TRANSLUCENT = 0x01030010         # Theme.Translucent.NoTitleBar

PKG_OLD, PKG_NEW = 'com.mojang.minecraftpe', 'com.mojang.minecraftpn'
NOVA = 'com.nova.client.'


def el(tag, *attrs, children=None):
    return Element(tag, list(attrs), children or [])

def action(n): return el('action', a_str('name', n, NAME))
def category(n): return el('category', a_str('name', n, NAME))
def data(**kw):
    m = {'scheme': SCHEME, 'mimeType': MIME, 'host': HOST, 'pathPattern': PATHPAT}
    return el('data', *[a_str(k, v, m[k]) for k, v in kw.items()])


def main(inp, out):
    root = axml.parse(open(inp, 'rb').read())

    root.get('package').raw = PKG_NEW
    sdk = root.find_all('uses-sdk')[0]
    sdk.get('targetSdkVersion').data = 24

    perms = {p.get('name').raw for p in root.find_all('uses-permission')}
    insert_at = max(i for i, c in enumerate(root.children) if c.tag == 'uses-permission') + 1
    for p in ['android.permission.ACCESS_WIFI_STATE', 'android.permission.CHANGE_WIFI_MULTICAST_STATE']:
        if p not in perms:
            root.children.insert(insert_at, el('uses-permission', a_str('name', p, NAME)))
            insert_at += 1

    app = root.find_all('application')[0]
    main_act = [a for a in app.children if a.tag == 'activity' and a.get('name').raw == PKG_OLD + '.MainActivity'][0]

    # NovaActivity: exact copy of the game activity (same flags, meta-data, filters), new class
    nova = copy.deepcopy(main_act)
    nova.get('name').raw = NOVA + 'NovaActivity'
    # original MainActivity stays declared (in case something starts it explicitly) but is no launcher
    main_act.children = [c for c in main_act.children if c.tag != 'intent-filter']
    app.children.insert(app.children.index(main_act), nova)

    orient = copy.deepcopy(main_act.get('screenOrientation'))
    cfg = copy.deepcopy(main_act.get('configChanges'))

    # PacksActivity: in-game "Наборы ресурсов" screen
    app.children.append(el('activity',
        a_str('name', NOVA + 'PacksActivity', NAME),
        a_ref('theme', THEME_FULLSCREEN, THEME),
        a_bool('exported', False, EXPORTED),
        orient, cfg))

    # CreateWorldActivity: NOVA world creation with fine settings
    app.children.append(el('activity',
        a_str('name', NOVA + 'CreateWorldActivity', NAME),
        a_ref('theme', THEME_FULLSCREEN, THEME),
        a_bool('exported', False, EXPORTED),
        copy.deepcopy(orient), copy.deepcopy(cfg)))

    # ImportActivity: opens .mcres files from file managers / share sheet
    view_cats = [action('android.intent.action.VIEW'), category('android.intent.category.DEFAULT'),
                 category('android.intent.category.BROWSABLE')]
    f_mime = el('intent-filter', children=copy.deepcopy(view_cats) + [
        data(scheme='content'), data(scheme='file'),
        data(mimeType='application/octet-stream'), data(mimeType='application/zip'),
        data(mimeType='application/x-zip-compressed'), data(mimeType='application/x-mcres')])
    path_datas = [data(scheme='file'), data(scheme='content'), data(host='*'), data(mimeType='*/*')]
    for pp in [r'.*\.mcres', r'.*\..*\.mcres', r'.*\..*\..*\.mcres', r'.*\..*\..*\..*\.mcres']:
        path_datas.append(data(pathPattern=pp))
    f_path = el('intent-filter', children=copy.deepcopy(view_cats) + path_datas)
    f_send = el('intent-filter', children=[action('android.intent.action.SEND'), category('android.intent.category.DEFAULT'),
                                           data(mimeType='application/octet-stream'), data(mimeType='application/zip'),
                                           data(mimeType='application/x-mcres')])
    app.children.append(el('activity',
        a_str('name', NOVA + 'ImportActivity', NAME),
        a_ref('theme', THEME_TRANSLUCENT, THEME),
        a_bool('exported', True, EXPORTED),
        a_bool('excludeFromRecents', True, EXCLUDE_RECENTS),
        a_str('taskAffinity', 'com.nova.client.import', TASK_AFFINITY),
        children=[f_mime, f_path, f_send]))

    # RestartActivity: separate process that relaunches the game after a pack change
    app.children.append(el('activity',
        a_str('name', NOVA + 'RestartActivity', NAME),
        a_ref('theme', THEME_FULLSCREEN, THEME),
        a_bool('exported', False, EXPORTED),
        a_str('process', ':nova_restart', PROCESS),
        a_bool('excludeFromRecents', True, EXCLUDE_RECENTS),
        a_bool('noHistory', True, NO_HISTORY),
        copy.deepcopy(orient)))

    open(out, 'wb').write(axml.write(root))
    print(axml.dump(axml.parse(open(out, 'rb').read())))


if __name__ == '__main__':
    main(*sys.argv[1:3])
