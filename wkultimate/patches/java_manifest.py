"""Patches applied to the apktool-decoded APK:
 - Google Play Games / Play billing / licence check disabled (game uses its Dummy* features)
 - package renamed, app label changed
 - native libs extracted on install (we may replace .so files)
"""
import re, glob, os

def apply(dec, package, label, log=print):
    p = os.path.join(dec, 'smali_classes2/com/kumobius/android/KumoApp.smali')
    s = open(p).read()
    s, n1 = re.subn(r'(const-string v0, "KumoJava"\s+)if-eqz p0, :cond_0', r'\1goto :cond_0', s, count=1)
    s, n2 = re.subn(r'\s+\.line 65\s+new-instance p0, Lcom/kumobius/android/KumoApp\$\$ExternalSyntheticLambda2;\s+'
                    r'invoke-direct \{p0\}, [^\n]+\s+invoke-static \{p0\}, Lcom/kumobius/android/KumoApp;->setIapFeatureFactory[^\n]+', '', s)
    assert n1 == 1 and n2 == 1, ('KumoApp.smali layout changed', n1, n2)
    open(p, 'w').write(s)

    m = open(os.path.join(dec, 'AndroidManifest.xml')).read()
    old = re.search(r'package="([^"]+)"', m).group(1)
    for r in [r'\s*<uses-permission android:name="com.google.android.gms.permission.AD_ID"/>',
              r'\s*<uses-permission android:name="com.android.vending.BILLING"/>',
              r'\s*<uses-permission android:name="com.android.vending.CHECK_LICENSE"/>',
              r'\s*<uses-permission android:name="com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE"/>',
              r'\s*<meta-data android:name="com.google.android.gms.games.APP_ID"[^>]*/>',
              r'\s*<meta-data android:name="com.google.android.gms.appstate.APP_ID"[^>]*/>',
              r'\s*<provider android:authorities="[^"]*playgamesinitprovider"[^>]*/>',
              r'\s*<meta-data android:name="com.android.dynamic.apk.fused.modules"[^>]*/>',
              r'\s*<meta-data android:name="com.android.vending.splits"[^>]*/>']:
        m = re.sub(r, '', m)
    m = re.sub(r' android:(requiredSplitTypes|splitTypes)="[^"]*"', '', m)
    m = m.replace('android:extractNativeLibs="false"', 'android:extractNativeLibs="true"')
    m = m.replace(f'package="{old}"', f'package="{package}"').replace(old + '.', package + '.')
    open(os.path.join(dec, 'AndroidManifest.xml'), 'w').write(m)

    for f in glob.glob(os.path.join(dec, 'res/values*/strings.xml')):
        s = open(f).read()
        s = re.sub(r'<string name="appName">[^<]*</string>', f'<string name="appName">{label}</string>', s)
        open(f, 'w').write(s)
    log(f'java/manifest: Google Play Games+billing off, package {old} -> {package}, label "{label}"')
