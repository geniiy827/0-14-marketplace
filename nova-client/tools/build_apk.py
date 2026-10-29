"""Repack the original APK with replaced files (same entry order and
compression method per entry), dropping the old signature.

usage: build_apk.py ORIG.apk OUT_unsigned.apk  arc_path=local_file ...
"""
import sys, zipfile

orig, out = sys.argv[1], sys.argv[2]
repl = dict(a.split('=', 1) for a in sys.argv[3:] if '=' in a)
# 'old>new' renames an entry (keeps its data)
ren = dict(a.split('>', 1) for a in sys.argv[3:] if '=' not in a and '>' in a)
used = set()
with zipfile.ZipFile(orig) as zi, zipfile.ZipFile(out, 'w') as zo:
    for info in zi.infolist():
        name = info.filename
        if name.startswith('META-INF/'):
            continue  # old signature is invalid after any change
        data = open(repl[name], 'rb').read() if name in repl else zi.read(name)
        if name in repl:
            used.add(name)
        if name in ren:
            name = ren.pop(info.filename)
        ni = zipfile.ZipInfo(name, date_time=info.date_time)
        ni.compress_type = info.compress_type
        ni.external_attr = info.external_attr
        ni.create_system = info.create_system
        zo.writestr(ni, data)
    assert not ren, 'rename sources missing: %r' % ren
    # new files (not present in the original APK) are appended
    for name in sorted(set(repl) - used):
        ni = zipfile.ZipInfo(name, date_time=(2016, 6, 1, 0, 0, 0))
        ni.compress_type = zipfile.ZIP_STORED if name.endswith(('.png', '.ogg')) else zipfile.ZIP_DEFLATED
        ni.external_attr = 0o644 << 16
        zo.writestr(ni, open(repl[name], 'rb').read())
        print('added', name)
print('replaced %d entries' % len(used))
