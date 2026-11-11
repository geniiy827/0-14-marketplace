#!/usr/bin/env python3
"""Wall Kickers Ultimate builder.

  python3 build.py                 # full build -> out/WallKickersUltimate.apk
  python3 build.py --data-only     # only regenerate WJScenarios.dat (fast iteration)
  python3 build.py --preview       # also render chunk previews to out/preview/

Inputs : original/WallKickers.apk   (universal APK of the original game, see README)
Config : mod/hardcore.py (difficulty), BUILD dict below
"""
import argparse, os, shutil, subprocess, sys, urllib.request, json
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from tools.levels import Data
from mod import hardcore
from patches import java_manifest

BUILD = dict(
    version='2.0-hardcore',
    package='com.kumobius.android.walljumpes',
    label='Wall Kickers Ultimate',
    difficulty='hardcore',          # 'hardcore' | 'vanilla'
    native_trampoline=False,        # experimental native patch (arm64 only)
)
TOOLS = {
    'apktool.jar': 'https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar',
    'uber-apk-signer.jar': 'https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar',
}
ORIG = os.path.join(HERE, 'original', 'WallKickers.apk')
WORK, OUT, CACHE = (os.path.join(HERE, d) for d in ('work', 'out', '.tools'))
KS = dict(path=os.path.join(HERE, 'keystore', 'wku.jks'), alias='wku',
          password=os.environ.get('WKU_KS_PASS', 'wkultimate'))

def sh(*cmd, **kw):
    print('$', ' '.join(map(str, cmd)), flush=True)
    subprocess.run(list(map(str, cmd)), check=True, **kw)

def tool(name):
    os.makedirs(CACHE, exist_ok=True)
    p = os.path.join(CACHE, name)
    if not os.path.exists(p):
        print('downloading', name); urllib.request.urlretrieve(TOOLS[name], p)
    return p

def build_data(src_dat, dst_dat):
    d = Data.load(src_dat)
    if BUILD['difficulty'] == 'hardcore':
        hardcore.apply(d)
    d.save(dst_dat)
    return d

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data-only', action='store_true')
    ap.add_argument('--preview', action='store_true')
    a = ap.parse_args()
    if not os.path.exists(ORIG):
        sys.exit(f'missing {ORIG} (put the original universal APK there, see README)')
    os.makedirs(OUT, exist_ok=True)
    dec = os.path.join(WORK, 'dec')
    if not os.path.isdir(dec) or not a.data_only:
        shutil.rmtree(WORK, ignore_errors=True); os.makedirs(WORK)
        sh('java', '-jar', tool('apktool.jar'), 'd', '-f', '-o', dec, ORIG, stdout=subprocess.DEVNULL)
        java_manifest.apply(dec, BUILD['package'], BUILD['label'])
        if BUILD['native_trampoline']:
            so = os.path.join(dec, 'lib/arm64-v8a/libKumoGame.so')
            sh(sys.executable, os.path.join(HERE, 'patches/native_trampoline.py'), so, so + '.new')
            os.replace(so + '.new', so)
            for abi in ('armeabi-v7a', 'x86', 'x86_64'):
                shutil.rmtree(os.path.join(dec, 'lib', abi), ignore_errors=True)
        shutil.copy(os.path.join(dec, 'assets/Media/WJScenarios.dat'), os.path.join(WORK, 'WJScenarios.orig.dat'))
    d = build_data(os.path.join(WORK, 'WJScenarios.orig.dat'), os.path.join(dec, 'assets/Media/WJScenarios.dat'))
    shutil.copy(os.path.join(dec, 'assets/Media/WJScenarios.dat'), os.path.join(OUT, 'WJScenarios.dat'))
    if a.preview:
        from tools import render
        render.sheet(d, os.path.join(OUT, 'preview.png'))
    if a.data_only:
        print('data-only: out/WJScenarios.dat'); return
    unsigned = os.path.join(WORK, 'unsigned.apk')
    sh('java', '-jar', tool('apktool.jar'), 'b', dec, '-o', unsigned)
    sd = os.path.join(WORK, 'signed'); shutil.rmtree(sd, ignore_errors=True)
    sh('java', '-jar', tool('uber-apk-signer.jar'), '-a', unsigned, '--ks', KS['path'], '--ksAlias', KS['alias'],
       '--ksPass', KS['password'], '--ksKeyPass', KS['password'], '-o', sd)
    apk = [f for f in os.listdir(sd) if f.endswith('.apk')][0]
    final = os.path.join(OUT, f"WallKickersUltimate-{BUILD['version']}.apk")
    shutil.copy(os.path.join(sd, apk), final)
    json.dump(BUILD, open(os.path.join(OUT, 'build.json'), 'w'), indent=1)
    print('OK ->', final)

if __name__ == '__main__':
    main()
