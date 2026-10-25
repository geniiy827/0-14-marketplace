import re, sys, glob, os
d = sys.argv[1]
subs = [
  (r'\[\s*1\.0,\s*1\.0,\s*0\.2\s*\]', '[ 0.4, 0.95, 0.88 ]'),
  (r'\[\s*1\.0,\s*1\.0,\s*0\.0,\s*1\.0\s*\]', '[ 0.4, 0.95, 0.88, 1.0 ]'),
  (r'\[\s*0\.8,\s*0\.8,\s*0\.16\s*\]', '[ 0.32, 0.76, 0.7 ]'),
  (r'\[\s*0\.25,\s*0\.25,\s*0\.25,\s*1\.0\s*\]', '[ 0.82, 0.86, 0.95, 1.0 ]'),
  (r'\[\s*0\.6,\s*0\.6,\s*0\.6\s*\]', '[ 0.62, 0.67, 0.78 ]'),
]
tot = {}
for f in sorted(glob.glob(os.path.join(d, '*.json'))):
    s = open(f, encoding='utf-8').read()
    for a, b in subs:
        s, n = re.subn(a, b, s)
        if n: tot[os.path.basename(f)] = tot.get(os.path.basename(f), 0) + n
    open(f, 'w', encoding='utf-8').write(s)
for k, v in tot.items(): print(k, v)
