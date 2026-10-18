# نظام المزامنة والبناء والتحديث التلقائي (RikkaHub Arabic)

هذا المستند يوثّق تصميم سير العمل `.github/workflows/sync-and-release.yml` وقواعد حلّ التعارض،
حتى يكون السلوك قابلًا للصيانة ولا يُدمج أي تغيير رسمي بطريقة عشوائية. يشغّل workflow اختبارات
التهيئة والسلوك التي تنفذ أوامر shell الفعلية على Git repositories مؤقتة قبل أي fetch أو merge.

## المراحل (كل مرحلة خطوة مستقلة واضحة)
| المرحلة | الخطوة | ماذا تفعل | عند الفشل |
| --- | --- | --- | --- |
| A | `A) Fetch upstream` | جلب `rikkahub/rikkahub` (قراءة فقط) وحساب هل هناك جديد؛ تُتخطى في `build-only` | تفشل المرحلة فقط |
| B | `B) Decide phase` | تحديد الوضع (auto/sync-only/build-only)، تجاهل SHA محجوب سابقًا | - |
| C | `C) Attempt upstream merge` | الدمج على **فرع معزول**؛ في `sync-only` يُجهض المرشح بعد الفحص بلا commit؛ وإلا تُحلّ التعارضات الموثّقة أو يصدر **تقرير + توقف** | لا تفشل، لا تلمس master |
| C1 | `C1) Record blocked upstream state` | خطوة منفصلة تسجل SHA المحجوب في الوضع `auto` على `master` فقط، بعد نجاح الخطوات السابقة | تمنع البوابة أي خطوة لاحقة عند الفشل |
| D | `D) Verify upstream contained` | التأكد أن الـcommit المبني يحتوي فعلاً على upstream | فشل واضح |
| E | `E) Release version` | `<base>.<versionCode>`؛ `versionCode` أكبر من رقم أحدث Release منشور | يفشل إذا تعذّر إثبات التزايد |
| F | `F) Arabic resource completeness` | كل مفتاح في `values/` له ترجمة في `values-ar/` | فشل |
| G | `G) Protected changes` | بقاء العربية/RTL/appId/DB/updater/conversation-scoped | فشل |
| H–I3 | toolchains/tests/lint/Room instrumentation | JVM Release tests و`lintRelease` وRoom instrumentation على API 35 x86_64 مع KVM | فشل ⇒ لا نشر، وتُرفع تقارير test/lint دائمًا |
| J–L | signing/build/verify APK | توقيع ← بناء ← تحقق `apksigner` من الشهادة و`aapt` من appId والإصدار | فشل ⇒ لا نشر |
| P3 | `P3) Record validation failure (state only)` | عند فشل تحقق مرشح نظيف مطلوب بناؤه، يسجل SHA محظورًا في worktree منفصل من `origin/master` | دفع state فقط؛ لا يدفع مرشح الدمج |
| M | `M) Push validated upstream merge` | دفع الدمج إلى master بعد الفحوص والبناء والتحقق، وبحراس صريحة `auto` و`master` ونجاح المهمة | - |
| N | `N) Publish GitHub Release` | نشر يدوي مصرح به فقط، دون clobber أو إعادة استخدام tag | افتراضيًا لا نشر |

## قواعد حلّ التعارض
1. **قاعدة موثّقة واحدة فقط (تُطبَّق آليًا):**
   - `.github/workflows/**` ⇒ **نُبقي نسختنا**. لأن CI الخاص بـupstream يستهدف المستودع
     الرسمي و`applicationId` وتوقيع مختلفين، واستبداله يُعطّل خط النشر العربي.
2. **كل تعارض آخر = مراجعة بشرية.** لا يُطبَّق أي حلّ آلي، ويُسجَّل تقرير يحتوي:
   - الملفات المتعارضة كاملة.
   - الملفات المطابقة لقائمة `PROTECTED_PATTERNS` (العربية/RTL/الهوية/قاعدة البيانات/المحدّث/الميزات المحمية).
   - يُرفَق `conflict-details.diff` كـartifact.
   ثم **يُوقف دمج upstream** ولا يُدفع merge؛ قد تدفع خطوة C1 المستقلة ملف الحالة فقط في تشغيل
   `auto` على `master`. تبقى تغييرات التطبيق ودمج upstream دون تغيير على `master`.

لماذا لا نُحلّ آليًا ملفات Kotlin الأساسية (PreferencesStore/ChatService/ChatVM/Conversation/…)?
لأنها تحمل تعديلاتنا الوظيفية (نموذج/مساحة عمل لكل محادثة، المحدّث، RTL)، وأي دمج آلي قد
يُسقط وظيفة أو يُفسد البيانات. القرار الصحيح هو تقرير + إيقاف.

## منع تكرار المحاولات الفاشلة
عند تعارض في تشغيل إنتاجي `auto` على `master`، تكتب خطوة C1 المستقلة `blockedUpstream` في
`.github/upstream-sync.json` (commit واحد فقط عندما تتغيّر الحالة، مع `[skip ci]`). وإذا كان الدمج
نظيفًا لكن فشل التحقق/الاختبار/التوقيع/البناء/الترقية مع `do_build=true`، تكتب P3 الحالة نفسها
مع `blockedStage=validation` و`blockedFiles=[]` وسبب ثابت لا ينسخ سجلات أو أسرارًا. تبدأ P3
worktree مؤقتًا من `origin/master` بعد fetch، وتدفع commit يحتوي ملف الحالة فقط؛ لا تدفع مطلقًا
مرشح الدمج الذي فشل. إذا أصبح upstream ancestor لـ`origin/master` قبل تسجيل الحالة، تتخطى P3
الكتابة كي لا تحظر SHA قديمًا. لا تسجل `sync-only` أو `build-only` أو فروع المراجعة أو التشغيل
الناجح أي حالة failure. وبالتالي:
- التشغيل المجدول كل 6 ساعات **لا يعيد** محاولة نفس الـSHA.
- عندما يتقدّم upstream إلى SHA جديد ⇒ محاولة جديدة تلقائيًا، سواء كان الحظر بسبب تعارض أو تحقق.
- لإجبار إعادة محاولة نفس الـSHA بعد إصلاح القواعد/الحل اليدوي: شغّل الـworkflow مع
  `force_sync=true` أو أفرغ `blockedUpstream` في ملف الحالة.

## البناء اليدوي/النشر عند تعذّر المزامنة
`workflow_dispatch` يدعم، ويعمل كل وضع على ref المختار في dispatch (`github.ref_name`):
- `mode=build-only` ⇒ بناء APK من ذلك ref دون جلب upstream أو دمجه أو الاعتماد على نسخته؛ فرع المراجعة لا يُدفع إلى master.
- `mode=sync-only` ⇒ يحاول الدمج ويُبلغ هل ينجح بلا تعارض؛ لا ينشئ commit محليًا، ولا يكتب ملف الحالة، ولا يدفع، ولا يبني، ولا ينشر، سواء نجح المرشح أو تعارض.
- `mode=auto` (افتراضي) ⇒ مزامنة ثم بناء عند وجود جديد.
- `publish_release` ⇒ Boolean افتراضيه `false`. لا يسمح النشر إلا إذا كان التشغيل `workflow_dispatch`
  من `refs/heads/master` وكانت القيمة `true`، وبعد اكتمال الفحوص والتحقق ورفع APK كـartifact.
  يظل الخيار **غير مفعّل** إلى أن يوافق كِنان صراحةً لاحقًا؛ لا يوجد نشر علني تلقائي.

## بوابة البناء (C2) — لماذا وُجدت
`do_build` تُحسب بعد مرحلة الدمج وتمنع حالتين خطيرتين:
- دمج نظيف في تشغيل مجدول ⇒ يُبنى ويُتحقق منه ثم يُدفع إلى master ويُرفع APK كـartifact؛ **لا ينشر علنيًا تلقائيًا**.
- SHA ما زال محجوبًا أو دمج `auto` محجوب ⇒ **يُمنع** البناء والنشر حتى مع `force_build=true`؛ يمكن طلب بناء النسخة الحالية منفصلًا بوضع `build-only`؛ فشل أي خطوة سابقة يمنع الوصول إلى بوابة البناء.
- push غيّر ملفات التطبيق ⇒ يمكن بناء المحتوى الحالي؛ push مقتصر على CI/docs لا يسبب مزامنة أو بناءً أو نشرًا حتى لو وُجد upstream جديد.
- `build-only` يبني ref المحدد في dispatch، مستقلًا عن upstream.
- `sync-only` ⇒ لا يبني أبدًا.

## البناء مقابل النشر العلني
- البناء لا يعني النشر: بعد نجاح التحقق يُرفع APK كـworkflow artifact ويُذكر outcome بدقة: **built and staged, not released** إذا كانت بوابة النشر مغلقة.
- لا ينشر push أو schedule أو دمج upstream تلقائيًا. لا ينشر GitHub Release إلا عند `workflow_dispatch` صريح من `master` مع `publish_release=true` وبعد موافقة كِنان المحددة لاحقًا.
- لا يُعاد استخدام tag موجود ولا يوجد fallback إلى `gh release upload --clobber`؛ وجود tag مكرر يوقف خطوة النشر.
- `versionCode` أكبر من `versionCode` المستنتج من tag أحدث Release منشور، ولا يُنشأ tag إصدار مكرر.
- `mode=sync-only` لا ينشئ commit محليًا ولا يكتب الحالة ولا يدفع ولا يبني ولا ينشر أبدًا.
- تسجيل فشل التحقق لا يحدث إلا بعد merge نظيف و`do_build=true` وفشل المهمة على `auto`/`master`؛
  commitه مبني على `origin/master` ويحتوي ملف الحالة وحده، لا ملفات التطبيق ولا merge المرشح.

## ضمانات غير قابلة للتفاوض
- لا `reset --hard`، ولا `push --force`، ولا إعادة كتابة التاريخ.
- لا يُستبدل أي ملف عربي بنسخة upstream دون مراجعة.
- لا حذف لإصلاحات قاعدة البيانات/Room ولا لملفات التوقيع.
- لا نشر علني تلقائي؛ `publish_release` افتراضيًا `false`.
- لا نشر إذا لم تكن الدعوة manual من `master` مع بوابة صريحة، أو فشلت الاختبارات أو `lintRelease` أو Room instrumentation أو التوقيع أو التحقق من APK.
- التحقق يستخدم `apksigner verify --print-certs` وبصمة الشهادة المرجعية، و`aapt dump badging` لمطابقة `applicationId` و`versionName` و`versionCode` المتوقع.
- فشل بعد خطوة البناء لا يُوصف بأنه «لم يُبنَ APK»؛ يذكر outcome حالة خطوة البناء ووجود الملف ورفع artifact والنشر كلٌّ على حدة.

## إثبات الترقية قبل النشر
يشمل التحقق اختبارات JVM للتطبيق واختبارات الوحدات للمكتبات التابعة، و`lintRelease`،
واختبارات Room الفعلية على محاكي API 35. وبعد بناء APK بالمفتاح الدائم والتحقق من الشهادة،
يُثبّت المحاكي الإصدار العربي المرجعي `v3.0.0.178` ثم يثبّت الجديد باستخدام `adb install -r`،
ويتحقق من تشغيل التطبيق بعد الترقية. تُحفظ نتائج التثبيت وسجل التشغيل ضمن Artifact التقارير.
هذا إثبات على المحاكي، وليس ادعاء باختبار هاتف المستخدم أو بياناته الشخصية.
