# Smart Clipboard — مدير الحافظة الذكي

تطبيق أندرويد لإدارة الحافظة وتنظيم النصوص والروابط والأكواد، يعمل محليًا أولًا مع تشفير **AES-256-GCM** عبر Android Keystore، ودعم **العربية (RTL)** والإنجليزية (LTR)، والمزامنة مع **Telegram** و**Discord** و**Google Drive**.

---

## تثبيت التطبيق عبر GitHub (خطوة بخطوة)

تم تجهيز المشروع بملف **GitHub Actions** (`.github/workflows/android-apk.yml`) لبناء ملف التثبيت `APK` تلقائيًا على خوادم GitHub دون الحاجة لتثبيت Android Studio على جهازك:

### الطريقة 1: التحميل المباشر من GitHub Actions (بعد الرفع مباشرة)
1. ارفع المشروع إلى مستودعك على GitHub (عبر **Push to GitHub** في AI Studio أو عبر `git push`).
2. افتح صفحة المستودع على GitHub ثم انتقل إلى تبويب **Actions**.
3. اختر تشغيل سير العمل **Build & Release Android APK** (يعمل تلقائيًا عند الرفع على `main`/`master`، ويمكنك تشغيله يدويًا عبر زر **Run workflow**).
4. بعد انتهاء البناء بنجاح، انزل إلى قسم **Artifacts** وحمّل الملف **`smart-clipboard-debug-apk`**.
5. فك ضغط الملف على هاتفك الأندرويد، ثم افتح **`SmartClipboard-debug.apk`** وثبّته (مع السماح بخيار «تثبيت التطبيقات غير المعروفة» لمدير الملفات أو المتصفح).

### الطريقة 2: النشر في GitHub Releases (رابط APK مباشر + دعم Obtainium)
1. أنشئ وسم إصدار (Tag) مثل `v1.0.0` وارفعه إلى GitHub:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
   *(أو أنشئ Tag/Release جديدًا من واجهة GitHub -> Releases -> Draft a new release)*.
2. سيقوم GitHub Actions ببناء التطبيق ونشر ملف **`SmartClipboard-debug.apk`** (و**`SmartClipboard-release.apk`** إذا أضفت مفاتيح التوقيع) تلقائيًا داخل صفحة **Releases**، لتتمكن من تثبيته مباشرة على الهاتف أو ربطه بتطبيق **Obtainium** للتحديث التلقائي من GitHub.

### توقيع نسخة Release رسمية (اختياري)
إذا أردت بناء نسخة **Release** موقّعة بمفتاحك الخاص داخل GitHub Actions، أضف الأسرار التالية في المستودع من:
**Settings → Secrets and variables → Actions → New repository secret**:
- `RELEASE_KEYSTORE_BASE64`: محتوى ملف `.jks` (باسم alias = `upload`) محولًا إلى Base64.
- `STORE_PASSWORD`: كلمة مرور مخزن المفاتيح (Keystore).
- `KEY_PASSWORD`: كلمة مرور المفتاح (`upload`).

---

## البناء والاختبار محليًا (للمطورين)

```bash
# تشغيل اختبارات الوحدة وRobolectric
gradle :app:testDebugUnitTest

# بناء Debug APK
gradle :app:assembleDebug
```
> ملاحظة: إذا قمت بتنزيل المستودع محليًا قبل وجود `debug.keystore`، فك تشفير ملف `debug.keystore.base64` أولًا:
> `base64 -d debug.keystore.base64 > debug.keystore`

راجع ملف [`BUILD_AND_RELEASE.md`](BUILD_AND_RELEASE.md) لتفاصيل إعداد تكاملات Telegram وDiscord وGoogle Drive OAuth 2.0.
