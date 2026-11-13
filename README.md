# html2apk (v2): حوّل HTML / ZIP / رابط موقع إلى APK وAAB

بيبني GitHub التطبيق (Capacitor) من غير علامة مائية، وصفحة `html2apk.html` هي الواجهة.

## إعداد لمرة واحدة

1. مستودع جديد على GitHub (خاص لو ملفاتك سرية) وارفع عليه محتوى المجلد ده.
2. **Settings → Secrets and variables → Actions**: `KEYSTORE_BASE64` و `KEYSTORE_PASSWORD` و `KEY_ALIAS` و `KEY_PASSWORD`. نفس المفتاح ينفع لكل تطبيقاتك. لو مفيش secrets بيتبني بمفتاح مؤقت (مش هيتثبّت كتحديث ومش لـ Google Play).
3. **Settings → Developer settings → Fine-grained tokens**: توكن للمستودع ده بصلاحيات **Contents** و**Actions** و**Workflows** (Read and write).

## الاستخدام

افتح `html2apk.html`، الصق المستودع والتوكن، اختار المصدر، املا الإعدادات، واضغط **ابنِ**. بعد 5–10 دقايق تظهر روابط التنزيل.

## المميزات

- **المصدر:** ملف HTML، محرر مع معاينة وقوالب (لعبة، واجهة الجهاز، AdMob تجريبي)، ZIP (لازم index.html)، أو رابط https.
- **البيانات:** الاسم، معرّف الحزمة، رقم الإصدار، versionCode (تلقائي أو يدوي)، أيقونة بتتقص مربعة.
- **الشكل:** اتجاه الشاشة، لون الخلفية، لون ومدة شاشة البداية، ملء الشاشة (مع الـnotch)، إبقاء الشاشة شغالة، وضع سطح المكتب، محتوى مختلط، وضع الكاش.
- **السلوك:** سحب للتحديث، مدير تنزيلات (روابط عادية وblob وdata)، زرار الرجوع بيتنقل في السجل.
- **الصلاحيات:** كاميرا، ميكروفون، GPS، اهتزاز.
- **روابط عميقة:** `myapp://`.
- **جسر الجهاز** `window.AndroidAPI` (واسم بديل `AndoridAPI`): `vibrate(ms)` و`flashlight(on)` و`toast(msg)` و`alert(title,msg)` و`share(text)` و`openUrl(url)` و`copy(text)` و`deviceInfo()` و`batteryLevel()` و`keepScreenOn(on)` و`saveFile(name,mime,base64)` و`startSensor()/stopSensor()` (النتيجة في `window.onAndroidSensor({x,y,z})`) و`exitApp()`. الجسر بيتقفل في وضع "رابط موقع".
- **AdMob:** بانر ثابت (فوق/تحت)، إعلان بيني بمؤقت أو بعدد صفحات، فيديو بمكافأة (`AndroidAPI.showRewarded()` ← `window.onRewarded(amount)`). الوضع التجريبي بيستخدم معرّفات Google التجريبية.
- **المخرجات:** APK، AAB (Google Play)، ومشروع Android Studio ZIP.
- **مفتاح التوقيع:** شغّل workflow **Create keystore** من تبويب Actions (في مستودع خاص بس). بيطلع Artifact فيه `release.jks` والقيم لـ Secrets، بيتمسح بعد يوم. امسح الـ run بعد ما تنسخ القيم.

## مش موجود

أداة PEPK (محتاجة ملف Java من Google ومش بتتنزل هنا)، وضع "Works like Chrome" الكامل (نوافذ منبثقة وإشعارات)، بناء على الموبايل نفسه، ووضع البناء السريع (10 ثواني).

## ملاحظات

- معرّف الحزمة والمفتاح لازم يفضلوا ثابتين للتطبيق الواحد عشان كل بناء يتثبّت فوق القديم.
- المستودع بيبني تطبيق واحد في المرة (الملفات في `app/`). لتطبيقات متعددة استخدم مستودع لكل تطبيق.
- `npm test` بيختبر منطق الإعدادات.
