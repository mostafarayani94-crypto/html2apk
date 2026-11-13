const assert = require('assert'), fs = require('fs'), os = require('os'), path = require('path');
const B = require('../scripts/builder.js');
const ok = (o, n) => B.validate(o, n), bad = (o, re) => assert.throws(() => B.validate(o, 1), re || Error);

const c = ok({}, 7);
assert.strictEqual(c.versionCode, 7); assert.deepStrictEqual(c.outputs, { apk: true, aab: false, project: false });
assert.strictEqual(ok({ versionCode: 12 }, 7).versionCode, 12);
bad({ appName: '' }); bad({ appName: 'x'.repeat(41) });
bad({ appId: 'My.App' }); bad({ appId: 'app' }); bad({ appId: 'com.new.app' }, /محجوزة/); bad({ appId: 'com.1x.app' });
bad({ versionName: 'a b' }); bad({ versionCode: 0 }); bad({ versionCode: 'x' });
bad({ source: 'url', url: 'http://x.com' }); bad({ source: 'url', url: 'nope' }); bad({ source: 'ftp' });
bad({ orientation: 'diagonal' }); bad({ backgroundColor: 'red' }); bad({ splashColor: '#12' });
bad({ deepLinkScheme: 'https' }); bad({ deepLinkScheme: 'A b' }); bad({ permissions: ['root'] });
bad({ outputs: { apk: false, aab: false, project: false } });
assert.strictEqual(ok({ splashSeconds: 99 }).splashSeconds, 5);
assert.deepStrictEqual(ok({ permissions: ['camera', 'camera'] }).permissions, ['camera']);

// capacitor config
let cc = B.capacitorConfig(ok({ source: 'url', url: 'https://example.com/app', desktopMode: true, splashColor: '#112233' }));
assert.strictEqual(cc.server.url, 'https://example.com/app'); assert.deepStrictEqual(cc.server.allowNavigation, ['example.com']);
assert.ok(cc.android.overrideUserAgent.includes('X11')); assert.strictEqual(cc.plugins.SplashScreen.backgroundColor, '#112233');
cc = B.capacitorConfig(ok({ appName: 'حاسبتي "x"' })); assert.ok(!cc.server); assert.strictEqual(JSON.parse(JSON.stringify(cc)).appName, 'حاسبتي "x"');

// manifest (شبه الـ template بتاع Capacitor 7)
const MF = `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/app_name">
        <activity
            android:configChanges="orientation"
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
            </intent-filter>
        </activity>
    </application>
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
`;
const same = B.patchManifest(MF, ok({ bridge: false }));
assert.strictEqual(same, MF, 'من غير خيارات: ما يتغيرش');
const p = B.patchManifest(MF, ok({ orientation: 'landscape', deepLinkScheme: 'myapp', permissions: ['camera', 'microphone', 'location', 'vibration'] }));
assert.ok(p.includes('android:screenOrientation="landscape"')); assert.ok(p.includes('<data android:scheme="myapp" />'));
for (const x of ['CAMERA', 'RECORD_AUDIO', 'ACCESS_FINE_LOCATION', 'VIBRATE']) assert.ok(p.includes('android.permission.' + x), x);
assert.ok(p.includes('android.hardware.camera') && p.indexOf('<data') < p.indexOf('</activity>') && p.indexOf('uses-permission android:name="android.permission.CAMERA') < p.indexOf('</manifest>'));
assert.strictEqual((p.match(/android.permission.INTERNET/g) || []).length, 1);
// idempotent على الصلاحيات الموجودة
assert.strictEqual((B.patchManifest(p, ok({ permissions: ['camera'] })).match(/permission.CAMERA/g) || []).length, 1);
assert.strictEqual(B.patchManifest(MF, ok({ orientation: 'auto', bridge: false })), MF);
assert.ok(B.patchManifest(MF, ok({})).includes('permission.VIBRATE'), 'الجسر يضيف VIBRATE');
assert.throws(() => B.patchManifest('<manifest></manifest>', ok({ orientation: 'portrait' })), /MainActivity/);
assert.throws(() => B.patchManifest(MF.replace('</activity>', ''), ok({ deepLinkScheme: 'x1' })), /activity/);
assert.ok(/^<\?xml/.test(p));

// MainActivity
let j = B.mainActivity(ok({ appId: 'com.me.calc', keepScreenOn: true, fullscreen: true, desktopMode: true }));
assert.ok(j.startsWith('package com.me.calc;') && j.includes('FLAG_KEEP_SCREEN_ON') && j.includes('hideBars();') && j.includes('setUseWideViewPort') && j.includes('if (hasFocus) hideBars();'));
j = B.mainActivity(ok({})); assert.ok(!j.includes('FLAG_KEEP_SCREEN_ON') && !j.includes('        hideBars();\n') && !j.includes('AdsHelper') && !j.includes('SwipeRefresh'));
assert.strictEqual((j.match(/\{/g) || []).length, (j.match(/\}/g) || []).length);

// ---- ميزات جديدة
let d = ok({});
assert.ok(d.bridge && d.downloads && !d.pullToRefresh && !d.ads.enabled && d.cacheMode === 'default');
assert.strictEqual(ok({ source: 'url', url: 'https://a.com' }).bridge, false, 'مفيش جسر لموقع خارجي');
bad({ cacheMode: 'x' });
// إعلانات
const AD = { enabled: true };
d = ok({ ads: AD }); assert.strictEqual(d.ads.appId, 'ca-app-pub-3940256099942544~3347511713');
bad({ ads: { ...AD, testMode: false, appId: 'x' } }); 
bad({ ads: { ...AD, testMode: false, appId: 'ca-app-pub-1234567890123456~1234567890', bannerId: 'bad' } });
d = ok({ ads: { ...AD, testMode: false, appId: 'ca-app-pub-1234567890123456~1234567890', bannerId: 'ca-app-pub-1234567890123456/1234567890', interstitialMode: 'pages', interstitialEvery: 2, interstitialId: 'ca-app-pub-1234567890123456/0987654321' } });
assert.strictEqual(d.ads.bannerId, 'ca-app-pub-1234567890123456/1234567890');
bad({ ads: { ...AD, testMode: false, appId: 'ca-app-pub-1234567890123456~1234567890', bannerPosition: 'none', interstitialMode: 'timer', interstitialEvery: 5, interstitialId: 'ca-app-pub-1234567890123456/0987654321' } }, /30/);
bad({ ads: { ...AD, bannerPosition: 'left' } }); bad({ ads: { ...AD, interstitialMode: 'x' } });
assert.ok(B.patchManifest(MF, ok({ ads: AD })).includes('com.google.android.gms.ads.APPLICATION_ID') && B.patchManifest(MF, ok({ ads: AD })).includes('ACCESS_NETWORK_STATE'));
assert.strictEqual(B.patchManifest(MF, ok({ bridge: false })).includes('ads.APPLICATION_ID'), false);
const GR = 'apply plugin: "com.android.application"\nandroid { }\ndependencies {\n    implementation "x:y:1"\n}\n';
assert.strictEqual(B.patchGradle(GR, ok({})), GR);
const g = B.patchGradle(GR, ok({ pullToRefresh: true, ads: AD })); assert.ok(g.includes('swiperefreshlayout') && g.includes('play-services-ads') && g.indexOf('swiperefreshlayout') > g.indexOf('dependencies {'));
assert.throws(() => B.patchGradle('android {}', ok({ pullToRefresh: true })), /dependencies/);
// Java المولَّد
j = B.mainActivity(ok({ appId: 'com.me.calc', pullToRefresh: true, ads: AD, cacheMode: 'noCache' }));
assert.ok(j.includes('installPullToRefresh(web)') && j.includes('new AdsHelper') && j.includes('LOAD_NO_CACHE') && j.includes('addJavascriptInterface(appBridge, "AndroidAPI")') && j.includes('AndoridAPI') && j.includes('installDownloads'));
assert.ok(j.includes('onShowRewarded'));
j = B.mainActivity(ok({ source: 'url', url: 'https://a.com' })); assert.ok(!j.includes('addJavascriptInterface') && j.includes('new AppBridge(this, web, false)') && j.includes('installDownloads'));
j = B.mainActivity(ok({ downloads: false, bridge: false })); assert.ok(!j.includes('AppBridge'));
const br = B.appBridge(ok({ appId: 'com.me.calc' })); assert.ok(br.startsWith('package com.me.calc;') && br.includes('@JavascriptInterface') && br.includes('saveDataUrl') && br.includes('MediaStore.Downloads'));
const ah = B.adsHelper(ok({ appId: 'com.me.calc', ads: { ...AD, interstitialMode: 'pages', interstitialEvery: 3, rewardedId: 'ca-app-pub-1/2' } }));
assert.ok(ah.includes('ca-app-pub-3940256099942544/6300978111') && ah.includes('Gravity.BOTTOM') && ah.includes('INTERSTITIAL_MODE = "pages"'));
for (const src of [br, ah, B.mainActivity(ok({ pullToRefresh: true, ads: AD, fullscreen: true }))]) { assert.strictEqual((src.match(/\{/g) || []).length, (src.match(/\}/g) || []).length, 'أقواس غير متوازنة'); assert.strictEqual((src.match(/\(/g) || []).length, (src.match(/\)/g) || []).length); }

// ZIP flatten
const t = fs.mkdtempSync(path.join(os.tmpdir(), 'zip-'));
fs.mkdirSync(path.join(t, 'site')); fs.writeFileSync(path.join(t, 'site/index.html'), 'x'); fs.mkdirSync(path.join(t, 'site/js')); fs.writeFileSync(path.join(t, 'site/js/a.js'), '1');
assert.strictEqual(B.flattenWww(t), 'flattened'); assert.ok(fs.existsSync(path.join(t, 'index.html')) && fs.existsSync(path.join(t, 'js/a.js')) && !fs.existsSync(path.join(t, 'site')));
assert.strictEqual(B.flattenWww(t), 'root');
const t2 = fs.mkdtempSync(path.join(os.tmpdir(), 'zip-')); fs.writeFileSync(path.join(t2, 'a.txt'), '1');
assert.throws(() => B.flattenWww(t2), /index\.html/);
console.log('كل اختبارات منطق البناء نجحت ✓');
