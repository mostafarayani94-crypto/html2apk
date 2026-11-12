/* منطق بناء التطبيق من app/config.json. دوال نقية (بتتختبر من غير أندرويد) + CLI للـ workflow. */
'use strict';
const fs = require('fs');
const path = require('path');
const J = require('./javagen.js');

const JAVA_KW = new Set(('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null _').split(' '));
const PERMS = {
  camera: ['android.permission.CAMERA'],
  microphone: ['android.permission.RECORD_AUDIO', 'android.permission.MODIFY_AUDIO_SETTINGS'],
  location: ['android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION'],
  vibration: ['android.permission.VIBRATE']
};
const ORIENT = { portrait: 'portrait', landscape: 'landscape', auto: 'unspecified' };
const BAD_SCHEMES = new Set(['http', 'https', 'file', 'content', 'javascript', 'data', 'intent', 'market']);

const DEFAULTS = {
  appName: 'My App', appId: 'com.example.myapp', versionName: '1.0', versionCode: 'auto',
  source: 'html', url: '', orientation: 'auto', fullscreen: false, keepScreenOn: false, desktopMode: false,
  backgroundColor: '#ffffff', splashColor: '', splashSeconds: 1, deepLinkScheme: '', allowMixedContent: false,
  permissions: [], outputs: { apk: true, aab: false, project: false },
  cacheMode: 'default', bridge: true, downloads: true, pullToRefresh: false,
  ads: { enabled: false, testMode: true, appId: '', bannerId: '', bannerPosition: 'bottom', interstitialId: '', interstitialMode: 'none', interstitialEvery: 3, rewardedId: '' }
};
const TEST_ADS = { appId: 'ca-app-pub-3940256099942544~3347511713', bannerId: 'ca-app-pub-3940256099942544/6300978111', interstitialId: 'ca-app-pub-3940256099942544/1033173712', rewardedId: 'ca-app-pub-3940256099942544/5224354917' };
const CACHE = { default: 'LOAD_DEFAULT', noCache: 'LOAD_NO_CACHE', cacheElseNetwork: 'LOAD_CACHE_ELSE_NETWORK' };

function validate(input, runNumber) {
  const c = Object.assign({}, DEFAULTS, input || {});
  c.outputs = Object.assign({}, DEFAULTS.outputs, (input && input.outputs) || {});
  const err = m => { throw new Error(m); };
  if (typeof c.appName !== 'string' || !c.appName.trim() || c.appName.length > 40) err('اسم التطبيق لازم يكون من 1 إلى 40 حرف');
  c.appName = c.appName.trim();
  if (!/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(c.appId || '')) err('معرّف الحزمة غلط: ' + c.appId);
  const kw = c.appId.split('.').find(s => JAVA_KW.has(s)); if (kw) err('معرّف الحزمة فيه كلمة محجوزة: ' + kw);
  if (!/^[0-9A-Za-z._-]{1,20}$/.test(String(c.versionName))) err('رقم الإصدار غلط');
  if (c.versionCode !== 'auto') { const n = Number(c.versionCode); if (!Number.isInteger(n) || n < 1 || n > 2100000000) err('versionCode غلط'); c.versionCode = n; } else c.versionCode = Number(runNumber) || 1;
  if (!['html', 'zip', 'url'].includes(c.source)) err('مصدر التطبيق غلط');
  if (c.source === 'url') { let u; try { u = new URL(c.url); } catch (e) { err('الرابط غلط'); } if (u.protocol !== 'https:') err('الرابط لازم يبدأ بـ https://'); c.url = u.href; }
  if (!ORIENT[c.orientation]) err('اتجاه الشاشة غلط');
  for (const k of ['fullscreen', 'keepScreenOn', 'desktopMode', 'allowMixedContent']) c[k] = !!c[k];
  if (!/^#[0-9a-fA-F]{6}$/.test(c.backgroundColor)) err('لون الخلفية غلط');
  if (c.splashColor && !/^#[0-9a-fA-F]{6}$/.test(c.splashColor)) err('لون شاشة البداية غلط');
  c.splashSeconds = Math.min(Math.max(Number(c.splashSeconds) || 0, 0), 5);
  if (c.deepLinkScheme) { if (!/^[a-z][a-z0-9+.-]{1,30}$/.test(c.deepLinkScheme) || BAD_SCHEMES.has(c.deepLinkScheme)) err('Deep link scheme غلط'); }
  if (!Array.isArray(c.permissions) || c.permissions.some(p => !PERMS[p])) err('صلاحية غير معروفة');
  c.permissions = [...new Set(c.permissions)];
  if (!CACHE[c.cacheMode]) err('وضع الكاش غلط');
  for (const k of ['bridge', 'downloads', 'pullToRefresh']) c[k] = !!c[k];
  if (c.source === 'url') c.bridge = false; // أمان: مفيش جسر للجهاز لموقع خارجي
  const ad = c.ads = Object.assign({}, DEFAULTS.ads, (input && input.ads) || {});
  ad.enabled = !!ad.enabled; ad.testMode = ad.testMode !== false;
  if (ad.enabled) {
    if (!['top', 'bottom', 'none'].includes(ad.bannerPosition)) err('مكان البانر غلط');
    if (!['none', 'timer', 'pages'].includes(ad.interstitialMode)) err('نوع الإعلان البيني غلط');
    ad.interstitialEvery = Math.floor(Number(ad.interstitialEvery));
    if (ad.interstitialMode === 'timer' && !(ad.interstitialEvery >= 30 && ad.interstitialEvery <= 3600)) err('الإعلان البيني بالمؤقت: من 30 إلى 3600 ثانية');
    if (ad.interstitialMode === 'pages' && !(ad.interstitialEvery >= 1 && ad.interstitialEvery <= 100)) err('الإعلان البيني بالصفحات: من 1 إلى 100');
    if (ad.testMode) Object.assign(ad, TEST_ADS);
    else {
      if (!/^ca-app-pub-\d{16}~\d{10}$/.test(ad.appId)) err('AdMob App ID غلط');
      const u = /^ca-app-pub-\d{16}\/\d{10}$/;
      if (ad.bannerPosition !== 'none' && !u.test(ad.bannerId)) err('Banner ID غلط');
      if (ad.interstitialMode !== 'none' && !u.test(ad.interstitialId)) err('Interstitial ID غلط');
      if (ad.rewardedId && !u.test(ad.rewardedId)) err('Rewarded ID غلط');
    }
  }
  if (!c.outputs.apk && !c.outputs.aab && !c.outputs.project) err('اختار مخرج واحد على الأقل');
  return c;
}

function capacitorConfig(c) {
  const cfg = { appId: c.appId, appName: c.appName, webDir: 'www', backgroundColor: c.backgroundColor,
    android: { allowMixedContent: c.allowMixedContent } };
  if (c.desktopMode) cfg.android.overrideUserAgent = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
  if (c.source === 'url') cfg.server = { url: c.url, cleartext: false, allowNavigation: [new URL(c.url).host] };
  cfg.plugins = { SplashScreen: { launchShowDuration: Math.round(c.splashSeconds * 1000), launchAutoHide: true, backgroundColor: c.splashColor || c.backgroundColor, androidScaleType: 'CENTER', showSpinner: false } };
  return cfg;
}

const xmlAttr = s => String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

function patchManifest(xml, c) {
  let out = xml;
  const must = (re, what) => { if (!re.test(out)) throw new Error('AndroidManifest: مش لاقي ' + what); };
  if (c.orientation !== 'auto') {
    must(/android:name="\.MainActivity"/, '.MainActivity');
    out = out.replace(/android:name="\.MainActivity"/, m => m + '\n            android:screenOrientation="' + ORIENT[c.orientation] + '"');
  }
  if (c.deepLinkScheme) {
    must(/<\/activity>/, '</activity>');
    out = out.replace(/<\/activity>/, `    <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="${xmlAttr(c.deepLinkScheme)}" />
            </intent-filter>
        </activity>`);
  }
  const lines = [];
  const perms = [...c.permissions];
  if (c.bridge && !perms.includes('vibration')) perms.push('vibration');
  if (c.ads.enabled) {
    must(/<\/application>/, '</application>');
    out = out.replace(/<\/application>/, `    <meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="${xmlAttr(c.ads.appId)}" />\n    </application>`);
    if (!out.includes('"android.permission.ACCESS_NETWORK_STATE"')) lines.push('    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />');
  }
  for (const p of perms) for (const perm of PERMS[p]) if (!out.includes('"' + perm + '"')) lines.push(`    <uses-permission android:name="${perm}" />`);
  if (perms.includes('camera')) lines.push('    <uses-feature android:name="android.hardware.camera" android:required="false" />');
  if (perms.includes('location')) lines.push('    <uses-feature android:name="android.hardware.location.gps" android:required="false" />');
  if (lines.length) { must(/<\/manifest>/, '</manifest>'); out = out.replace(/<\/manifest>/, lines.join('\n') + '\n</manifest>'); }
  return out;
}

function patchGradle(gradle, c) {
  const deps = [];
  if (c.pullToRefresh) deps.push('    implementation "androidx.swiperefreshlayout:swiperefreshlayout:1.1.0"');
  if (c.ads.enabled) deps.push('    implementation "com.google.android.gms:play-services-ads:23.6.0"');
  if (!deps.length) return gradle;
  if (!/^dependencies\s*\{/m.test(gradle)) throw new Error('build.gradle: مش لاقي dependencies');
  return gradle.replace(/^dependencies\s*\{/m, m => m + '\n' + deps.join('\n'));
}

/* flatten: لو الـ ZIP اتفك جوه فولدر واحد، نطلّع محتواه لجذر www */
function flattenWww(dir) {
  if (fs.existsSync(path.join(dir, 'index.html'))) return 'root';
  const ents = fs.readdirSync(dir).filter(n => n !== '__MACOSX' && !n.startsWith('.'));
  if (ents.length === 1 && fs.statSync(path.join(dir, ents[0])).isDirectory() && fs.existsSync(path.join(dir, ents[0], 'index.html'))) {
    const inner = path.join(dir, ents[0]);
    for (const n of fs.readdirSync(inner)) fs.renameSync(path.join(inner, n), path.join(dir, n));
    fs.rmdirSync(inner);
    return 'flattened';
  }
  throw new Error('الـ ZIP لازم يحتوي index.html في الجذر (أو جوه فولدر واحد)');
}

module.exports = { validate, capacitorConfig, patchManifest, patchGradle, mainActivity: J.mainActivity, appBridge: J.appBridge, adsHelper: J.adsHelper, flattenWww, PERMS, DEFAULTS };

/* ---------------- CLI ---------------- */
if (require.main === module) {
  const cmd = process.argv[2];
  const readCfg = () => validate(JSON.parse(fs.readFileSync('app/config.json', 'utf8')), process.env.RUN_NUMBER);
  if (cmd === 'prepare') {
    const c = readCfg();
    fs.rmSync('www', { recursive: true, force: true }); fs.mkdirSync('www');
    if (c.source === 'html') fs.copyFileSync('app/index.html', 'www/index.html');
    else if (c.source === 'zip') flattenWww('www-zip') && fs.cpSync('www-zip', 'www', { recursive: true });
    else fs.writeFileSync('www/index.html', '<!doctype html><meta charset="utf-8"><title>' + c.appName.replace(/[<&]/g, '') + '</title>');
    fs.writeFileSync('capacitor.config.json', JSON.stringify(capacitorConfig(c), null, 2));
    const env = { APP_ID: c.appId, VERSION_NAME: c.versionName, VERSION_CODE: c.versionCode, OUT_APK: c.outputs.apk, OUT_AAB: c.outputs.aab, OUT_PROJECT: c.outputs.project, HAS_ICON: fs.existsSync('app/icon.png') };
    fs.appendFileSync(process.env.GITHUB_ENV || '/dev/null', Object.entries(env).map(([k, v]) => k + '=' + v).join('\n') + '\n');
    console.log('prepared', c.appId, 'v' + c.versionName, '#' + c.versionCode);
  } else if (cmd === 'patch') {
    const c = readCfg();
    const mf = 'android/app/src/main/AndroidManifest.xml';
    fs.writeFileSync(mf, patchManifest(fs.readFileSync(mf, 'utf8'), c));
    const dir = path.join('android/app/src/main/java', ...c.appId.split('.'));
    fs.mkdirSync(dir, { recursive: true });
    // لو الـ template حط MainActivity في مسار تاني، نحذفه عشان ما يتكررش
    const base = 'android/app/src/main/java';
    (function rm(d) { for (const e of fs.readdirSync(d, { withFileTypes: true })) { const p = path.join(d, e.name); if (e.isDirectory()) rm(p); else if (e.name === 'MainActivity.java' && path.dirname(p) !== dir) fs.unlinkSync(p); } })(base);
    fs.writeFileSync(path.join(dir, 'MainActivity.java'), J.mainActivity(c));
    if (c.bridge || c.downloads) fs.writeFileSync(path.join(dir, 'AppBridge.java'), J.appBridge(c));
    if (c.ads.enabled) fs.writeFileSync(path.join(dir, 'AdsHelper.java'), J.adsHelper(c));
    const gr = 'android/app/build.gradle';
    fs.writeFileSync(gr, patchGradle(fs.readFileSync(gr, 'utf8'), c));
    console.log('patched', mf, 'and MainActivity');
  } else { console.error('usage: builder.js prepare|patch'); process.exit(2); }
}
