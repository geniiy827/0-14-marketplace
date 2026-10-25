"""Validate MCPE 0.14 JSON UI files.

Checks:
  1. every file parses as JSON after stripping // and /* */ comments
  2. every "@ref" / "name@ref" resolves to an element in some namespace
  3. every literal texture path exists in assets/images
  4. functional strings from the original (bindings, button ids, collections,
     renderers, factories, tab content names) are all still present
Usage: validate.py <new_ui_dir> <orig_ui_dir> <images_dir>
"""
import json, os, re, sys, glob

def strip_comments(s):
    out, i, n, in_str = [], 0, len(s), False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if c == '\\':
                out.append(s[i + 1]); i += 2; continue
            if c == '"':
                in_str = False
            i += 1; continue
        if c == '"':
            in_str = True; out.append(c); i += 1; continue
        if s.startswith('//', i):
            j = s.find('\n', i); i = n if j < 0 else j; continue
        if s.startswith('/*', i):
            j = s.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return ''.join(out)

def load_dir(d):
    data, errors = {}, []
    for f in sorted(glob.glob(os.path.join(d, '*.json'))):
        raw = open(f, encoding='utf-8').read()
        try:
            # trailing commas are not valid in the engine's parser either
            data[os.path.basename(f)] = json.loads(strip_comments(raw))
        except Exception as e:
            errors.append('%s: JSON error %s' % (os.path.basename(f), e))
    return data, errors

def walk(o, fn, path=()):
    fn(o, path)
    if isinstance(o, dict):
        for k, v in o.items(): walk(v, fn, path + (k,))
    elif isinstance(o, list):
        for i, v in enumerate(o): walk(v, fn, path + (i,))

def collect_defs(data):
    defs = set()
    for fname, doc in data.items():
        if not isinstance(doc, dict) or 'namespace' not in doc: continue
        ns = doc['namespace']
        for k in doc:
            if k == 'namespace': continue
            defs.add(ns + '.' + k.split('@')[0])
    return defs

FUNC_KEYS = {'binding_name', 'binding_name_override', 'binding_collection_name',
             'collection_name', 'from_button_id', 'to_button_id', 'renderer',
             'control_name', 'tab_content', 'grid_item_template', 'scroll_report',
             'content_panel', 'scrollbar_box', 'touch_scroll_box', 'text'}

def functional_strings(doc):
    found = set()
    def fn(o, path):
        if isinstance(o, dict):
            for k, v in o.items():
                if k.startswith('$') and isinstance(v, str) and '/' not in v:
                    found.add(('text', v))  # text passed through a variable
                if k in FUNC_KEYS:
                    for x in (v if isinstance(v, list) else [v]):
                        if isinstance(x, str): found.add((k, x))
    walk(doc, fn)
    return found

def check(new_dir, orig_dir, img_dir):
    new, errs = load_dir(new_dir)
    orig, _ = load_dir(orig_dir)
    defs = collect_defs(new)
    # refs
    for fname, doc in new.items():
        if not isinstance(doc, dict) or 'namespace' not in doc: continue
        ns = doc['namespace']
        def check_ref(ref, where):
            if ref.startswith('$') or ref == '': return
            full = ref if '.' in ref else ns + '.' + ref
            if full not in defs:
                errs.append('%s: unresolved ref "%s" at %s' % (fname, ref, where))
        def fn(o, path):
            if isinstance(o, dict):
                for k in o:
                    if '@' in k and not k.startswith('$'):
                        base = k.split('@', 1)[1]
                        check_ref(base, '/'.join(map(str, path + (k,))))
            if isinstance(o, str) and o.startswith('@') and len(path) and path[-1] not in ('text',):
                check_ref(o[1:], '/'.join(map(str, path)))
            if isinstance(o, list) and path and path[-1] == 'anims':
                for x in o:
                    if isinstance(x, str) and x.startswith('@'): check_ref(x[1:], '/'.join(map(str, path)))
        walk(doc, fn)
        # textures
        def tex(o, path):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k.startswith('$') and isinstance(v, str) and v.startswith('gui/'):
                        if not any(os.path.exists(os.path.join(d_, v)) for d_ in img_dir.split(':')):
                            errs.append('%s: missing texture %s' % (fname, v))
                    if k == 'texture' and isinstance(v, str) and not v[:1] in '$#':
                        if not any(os.path.exists(os.path.join(d_, v)) for d_ in img_dir.split(':')):
                            # engine lookup is case-insensitive on some paths; flag anyway
                            errs.append('%s: missing texture %s' % (fname, v))
        walk(doc, tex)
    # functional preservation
    for fname, odoc in orig.items():
        if fname not in new:
            errs.append('%s: file missing' % fname); continue
        lost = functional_strings(odoc) - functional_strings(new[fname])
        for k, v in sorted(lost):
            errs.append('%s: lost functional %s=%r' % (fname, k, v))
    return errs, len(new), len(defs)

def main(new_dir, orig_dir, img_dir):
    base, _, _ = check(orig_dir, orig_dir, img_dir)
    errs, nf, nd = check(new_dir, orig_dir, img_dir)
    import os
    allow_f = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'validate_allow.txt')
    allow = [l.strip() for l in open(allow_f, encoding='utf-8')] if os.path.exists(allow_f) else []
    errs = [e for e in errs if e not in set(base) and not any(a and a in e for a in allow)]
    for e in errs: print('ERR', e)
    print('files:', nf, 'defs:', nd, 'new errors:', len(errs), '(pre-existing in original: %d)' % len(base))
    return 1 if errs else 0

if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:4]))
