package com.example.core

enum class AppLanguage {
    AR,
    EN
}

interface LeviStrings {
    val appTitle: String
    val targetVersionTag: String
    val tabLaunch: String
    val tabProbe: String
    val tabTools: String
    val tabLogs: String
    val toggleLanguageLabel: String
    val shareDiagnosticsLabel: String

    val deviceSummaryTitle: String
    val abiLabel: String
    val androidOsLabel: String
    val hardwareRamLabel: String
    val hardwareStorageLabel: String
    val hardwareGlLabel: String
    val hardwareRecommendedPresetLabel: String
    val readinessChecklistTitle: String
    val discoveredPackagesTitle: String
    val noMinecraftInstalledTitle: String
    val noMinecraftInstalledDesc: String
    val rescanPackagesButton: String
    val packageVersionLabel: String
    val versionCodeLabel: String
    val targetMatchExact: String
    val targetMatchMismatch: String
    val mismatchGuardTitle: String
    val mismatchGuardBody: String
    val launchBlockedLabel: String
    val splitApkBadge: String
    val singleApkBadge: String
    val previewBadge: String
    val releaseBadge: String
    val betaBadge: String
    val trialBadge: String
    val arm64ReadyBadge: String
    val arm64MissingBadge: String
    val launchPrimaryButton: String
    val launchQuickOfficialButton: String
    val launchAdjacentSplitButton: String
    val launchFallbackButton: String
    val extractAndVerifyButton: String
    val inspectExternalApkButton: String
    val externalApkCardTitle: String
    val installExternalApkButton: String
    val signingCertLabel: String
    val candidateScanTitle: String
    val candidateFoundLabel: String
    val candidateMissingLabel: String
    val splitBundleBadge: String
    val splitBundleInstallHint: String
    val mismatchPickApkButton: String
    val importApkToVaultButton: String
    val importingVaultStatus: String
    val vaultApkBadge: String
    val removeVaultApkDesc: String
    val apkVaultHeroTitle: String
    val apkVaultHeroSubtitle: String
    val systemPackagesOptionalTitle: String

    val gameHealthTitle: String
    val repairReextractButton: String
    val clearCacheButton: String
    val repairingStatus: String
    val noLibrariesExtracted: String
    val elfValidBadge: String
    val elfInvalidBadge: String
    val sizeOverOneMbBadge: String
    val sizeUnderOneMbBadge: String
    val architectureLabel: String
    val sourceOriginLabel: String
    val loadSourceLabel: String
    val cacheSummaryTitle: String
    val cacheSizeLabel: String
    val cacheDirectLoadLabel: String
    val cacheCrcVerifiedLabel: String
    val staleCachePrunedLabel: String

    val targetComparisonTitle: String
    val targetRowPackage: String
    val targetRowEdition: String
    val targetRowVersion: String
    val targetRowVersionCode: String
    val targetRowAbi: String
    val targetRowMainLib: String
    val requiredLabel: String
    val installedLabel: String
    val importedApkLabel: String
    val vaultComparisonTitle: String
    val vaultPackageIsolatedBadge: String
    val vaultMismatchGuardTitle: String
    val vaultMismatchGuardBody: String
    val toggleSystemPackagesLabel: String
    val officialLaunchScopeTitle: String
    val officialLaunchScopeBody: String

    val lastFailureBannerTitle: String
    val fallbackExplanationPrefix: String
    val dismissBanner: String

    val probeTitle: String
    val probeSubtitle: String
    val runProbeButton: String
    val probingInProgress: String
    val probeAutoNotice: String
    val probeSelectPackage: String
    val probeContextStatusTitle: String
    val probeContextSuccess: String
    val probeContextFailed: String
    val probeInheritanceTitle: String
    val probeNativeMethodsTitle: String
    val probeNoNativeMethods: String
    val probeDtNeededTitle: String
    val probeElfEntrypointsTitle: String
    val probeJniCountLabel: String
    val probeDexSourceLabel: String
    val probeAxmlTitle: String
    val probeAssetsTitle: String
    val probeLoadOrderTitle: String
    val probeSystemDepsLabel: String
    val probeMissingDepsLabel: String
    val probeApkPathsTitle: String

    val toolsTitle: String
    val graphicsTitle: String
    val graphicsDesc: String
    val loadOptionsTxtButton: String
    val saveOptionsTxtButton: String
    val exportOptionsTxtButton: String
    val presetBattery: String
    val presetBalanced: String
    val presetCompetitive: String
    val presetUltra: String
    val renderDistanceLabel: String
    val maxFramerateLabel: String
    val msaaLabel: String
    val fovLabel: String
    val fancyGraphicsLabel: String
    val smoothLightingLabel: String
    val fancySkiesLabel: String
    val vsyncLabel: String
    val deferredRenderingLabel: String
    val uiProfileClassicLabel: String
    val launcherCompatTitle: String
    val preferredLaunchModeLabel: String
    val launchModeVerifiedShort: String
    val launchModeQuickShort: String
    val launchModeSplitShort: String
    val autoVerifyCacheLabel: String
    val preferDirectApkLoadLabel: String

    val worldBackupTitle: String
    val worldBackupDesc: String
    val worldProtectionBadge: String
    val worldProtectionHistoryTitle: String
    val overwriteGuardTitle: String
    val overwriteGuardMessage: String
    val confirmOverwriteButton: String
    val cancelButton: String
    val pickWorldFolderButton: String
    val exportMcWorldButton: String
    val pickRestoreMcWorldButton: String
    val restoreToFolderButton: String
    val openWorldInMinecraftButton: String
    val worldNameLabel: String
    val worldModeLabel: String
    val worldVersionLabel: String
    val worldFilesLabel: String
    val levelDatFound: String
    val levelDatMissing: String
    val dbFolderFound: String
    val mcPackImportTitle: String
    val mcPackImportDesc: String
    val pickMcPackButton: String
    val openInMinecraftButton: String
    val packNameLabel: String
    val packTypeLabel: String
    val packVersionLabel: String

    val fileManagerTitle: String
    val fileManagerDesc: String
    val fileTabAppCache: String
    val fileTabSafBrowser: String
    val pickSafFolderButton: String
    val folderUpButton: String
    val newFolderButton: String
    val newFolderHint: String
    val createButton: String
    val noFilesFound: String
    val previewTitle: String
    val closeButton: String

    val serverPingerTitle: String
    val serverPingerDesc: String
    val serverNameHint: String
    val serverHostHint: String
    val serverPortHint: String
    val addServerButton: String
    val pingAllButton: String
    val pingButton: String
    val deleteServerDesc: String
    val serverOnline: String
    val serverOffline: String
    val serverPlayersLabel: String
    val serverLatencyLabel: String
    val openServerInMcButton: String
    val noServersSaved: String

    val logsTitle: String
    val subTabHistory: String
    val subTabSessionLogs: String
    val noLaunchHistoryYet: String
    val clearHistoryButton: String
    val logsFilePathLabel: String
    val filterAll: String
    val filterErrors: String
    val copyLogsButton: String
    val shareReportButton: String
    val clearLogsButton: String
    val noLogsYet: String
    val copiedToast: String

    val embeddedDirectHostTitle: String
    val embeddedRunningStages: String
    val embeddedDirectSuccessHint: String
    val embeddedFailureTitle: String
    val embeddedFallbackMessage: String
    val embeddedOpenExternalButton: String
    val embeddedBackToLabButton: String
    val embeddedShareReportButton: String
    val stageALabel: String
    val stageBLabel: String
    val stageCLabel: String
    val stageDLabel: String
    val stageELabel: String
}

object ArStrings : LeviStrings {
    override val appTitle = "LeviLab"
    override val targetVersionTag = "الهدف: Preview 1.26.70.24"
    override val tabLaunch = "التشغيل"
    override val tabProbe = "الفحص والمكتبات"
    override val tabTools = "الأدوات والملفات"
    override val tabLogs = "السجل"
    override val toggleLanguageLabel = "EN"
    override val shareDiagnosticsLabel = "مشاركة التقرير"

    override val deviceSummaryTitle = "بيئة الجهاز والهدف"
    override val abiLabel = "المعمارية"
    override val androidOsLabel = "نظام أندرويد"
    override val hardwareRamLabel = "الذاكرة RAM"
    override val hardwareStorageLabel = "المساحة المتاحة"
    override val hardwareGlLabel = "OpenGL ES"
    override val hardwareRecommendedPresetLabel = "الوضع المقترح لجهازك"
    override val readinessChecklistTitle = "قائمة الجاهزية والتحقق (Pre-Flight)"
    override val discoveredPackagesTitle = "ملفات APK المستوردة وحزم النظام"
    override val noMinecraftInstalledTitle = "لم يتم استيراد ملف Preview بعد"
    override val noMinecraftInstalledDesc =
        "اضغط على زر استيراد ملف APK / APKS أعلاه لاختيار ملف Minecraft Bedrock Preview 1.26.70.24 وتشغيله داخل LeviLab."
    override val rescanPackagesButton = "إعادة الفحص"
    override val packageVersionLabel = "الإصدار"
    override val versionCodeLabel = "رقم البناء"
    override val targetMatchExact = "يطابق Preview 1.26.70.24"
    override val targetMatchMismatch = "إصدار/نسخة مختلفة عن الهدف"
    override val mismatchGuardTitle = "تحذير: النسخة المثبّتة لا تطابق الهدف المطلوب"
    override val mismatchGuardBody =
        "الإصدار المطلوب هو Minecraft Bedrock Preview 1.26.70.24 (ARM64). تم إيقاف التشغيل لمنع تشغيل نسخة مختلفة."
    override val launchBlockedLabel = "التشغيل متوقف — يلزم Preview 1.26.70.24"
    override val splitApkBadge = "Split APK"
    override val singleApkBadge = "Single APK"
    override val previewBadge = "Preview"
    override val releaseBadge = "النسخة العادية"
    override val betaBadge = "Beta"
    override val trialBadge = "تجريبية"
    override val arm64ReadyBadge = "ARM64 جاهز"
    override val arm64MissingBadge = "ARM64 مفقود"
    override val launchPrimaryButton = "تشغيل داخل LeviLab (Preview 1.26.70.24)"
    override val launchQuickOfficialButton = "تشغيل رسمي سريع"
    override val launchAdjacentSplitButton = "تشغيل جانبي Split-Screen"
    override val launchFallbackButton = "فتح من النظام"
    override val extractAndVerifyButton = "فحص وإصلاح المكتبات"
    override val inspectExternalApkButton = "فحص APK / APKS"
    override val externalApkCardTitle = "نتيجة فحص ملف التثبيت الخارجي"
    override val installExternalApkButton = "فتح لتثبيت APK"
    override val signingCertLabel = "توقيع SHA-256"
    override val candidateScanTitle = "نتيجة البحث عن الحزم على الجهاز (Android 13)"
    override val candidateFoundLabel = "مكتشف"
    override val candidateMissingLabel = "غير مثبّت"
    override val splitBundleBadge = "Split Bundle (.apks/.xapk)"
    override val splitBundleInstallHint = "ملف Split APK مجمّع؛ يمكنك استيراده مباشرة إلى مخزن APK في LeviLab لتشغيله داخله، أو تثبيته عبر مثبت Split APK."
    override val mismatchPickApkButton = "اختيار ملف Preview (.apk / .apks)"
    override val importApkToVaultButton = "اختيار ملف APK / APKS لتشغيله داخل LeviLab"
    override val importingVaultStatus = "جاري استيراد ملف APK وتجهيز المكتبات..."
    override val vaultApkBadge = "ملف APK داخل LeviLab"
    override val removeVaultApkDesc = "إزالة APK المستورد"
    override val apkVaultHeroTitle = "تشغيل ماينكرافت من ملف APK داخل LeviLab"
    override val apkVaultHeroSubtitle =
        "مستقل تماماً عن النسخة المثبّتة على الهاتف — اختر ملف Preview 1.26.70.24 (.apk / .apks / .xapk) لتشغيله مباشرة داخل التطبيق."
    override val systemPackagesOptionalTitle = "حزم النظام المثبّتة على الهاتف (اختياري)"

    override val gameHealthTitle = "مدير المكتبات وذاكرة الكاش"
    override val repairReextractButton = "إصلاح وإعادة بناء الكاش"
    override val clearCacheButton = "مسح الكاش"
    override val repairingStatus = "جاري فحص المكتبات وإعادة بناء الكاش..."
    override val noLibrariesExtracted = "لم تُفحص المكتبات بعد. اضغط إعادة الفحص أو إصلاح."
    override val elfValidBadge = "ELF64 صالحة"
    override val elfInvalidBadge = "ELF غير صالحة"
    override val sizeOverOneMbBadge = "الحجم > 1MB"
    override val sizeUnderOneMbBadge = "الحجم < 1MB"
    override val architectureLabel = "البنية"
    override val sourceOriginLabel = "المصدر"
    override val loadSourceLabel = "مسار التحميل"
    override val cacheSummaryTitle = "حالة Library Cache"
    override val cacheSizeLabel = "حجم الكاش"
    override val cacheDirectLoadLabel = "تحميل مباشر بدون نسخ"
    override val cacheCrcVerifiedLabel = "ملفات محققة CRC32"
    override val staleCachePrunedLabel = "تم تنظيف كاش قديم"

    override val targetComparisonTitle = "مقارنة التحقق من الهدف (المثبّت مقابل المطلوب)"
    override val targetRowPackage = "الحزمة (Package)"
    override val targetRowEdition = "النسخة (Edition)"
    override val targetRowVersion = "الإصدار (versionName)"
    override val targetRowVersionCode = "رقم البناء (versionCode)"
    override val targetRowAbi = "معمارية ARM64"
    override val targetRowMainLib = "libminecraftpe.so"
    override val requiredLabel = "المطلوب"
    override val installedLabel = "المثبّت"
    override val importedApkLabel = "ملف APK"
    override val vaultComparisonTitle = "فحص ملف APK المستورد (مقارنة الهدف)"
    override val vaultPackageIsolatedBadge = "معزول داخل LeviLab"
    override val vaultMismatchGuardTitle = "تحذير: ملف APK المختار لا يطابق الهدف المطلوب"
    override val vaultMismatchGuardBody =
        "الملف المستورد داخل LeviLab لا يطابق Minecraft Bedrock 1.26.70.24 (ARM64) أو يفتقر إلى libminecraftpe.so صالحة."
    override val toggleSystemPackagesLabel = "عرض حزم الهاتف المثبّتة (اختياري)"
    override val officialLaunchScopeTitle = "محرك تشغيل APK الداخلي + التوافق مع Android 13"
    override val officialLaunchScopeBody =
        "يدعم LeviLab استيراد ملفات (.apk / .apks / .xapk) إلى مخزن داخلي معزول أو قراءة APK الحزمة المثبّتة، ثم تركيب AssetManager و DexClassLoader وتحميل مكتبات ARM64 ELF64 بترتيب DT_NEEDED لتشغيل MainActivity داخل عملية LeviLab مع خيار التشغيل الرسمي كبديل."

    override val lastFailureBannerTitle = "تشخيص توقف التشغيل"
    override val fallbackExplanationPrefix = "توقف التشغيل عند:"
    override val dismissBanner = "إخفاء"

    override val probeTitle = "فحص الحزمة والواجهة البرمجية (Probe)"
    override val probeSubtitle = "تحليل Base/Split APK و ELF64 و DT_NEEDED"
    override val runProbeButton = "تشغيل Probe الآن"
    override val probingInProgress = "جاري فحص الـ APK و JNI و ELF64..."
    override val probeAutoNotice = "تم الفحص التلقائي اعتماداً على الحزمة المكتشفة وإصدارها."
    override val probeSelectPackage = "الحزم المثبّتة"
    override val probeContextStatusTitle = "سياق الحزمة على Android 13"
    override val probeContextSuccess = "تم تحميل Package Context بنجاح"
    override val probeContextFailed = "عزل Android 13 مفعل (تم استخدام بيانات PackageManager)"
    override val probeInheritanceTitle = "سلسلة وراثة MainActivity"
    override val probeNativeMethodsTitle = "دوال native المكتشفة"
    override val probeNoNativeMethods = "لم يتم العثور على دوال native معلنة أو الحزمة معزولة على Android 13."
    override val probeDtNeededTitle = "قسم DT_NEEDED من libminecraftpe.so"
    override val probeElfEntrypointsTitle = "نقاط الدخول الأصلية المصدّرة (ELF64 dynsym)"
    override val probeJniCountLabel = "دوال JNI مسجّلة في الجدول"
    override val probeDexSourceLabel = "مصدر تحليل DEX"
    override val probeAxmlTitle = "تحليل البيان الثنائي (AndroidManifest AXML)"
    override val probeAssetsTitle = "موارد APK ومحرك RenderDragon"
    override val probeLoadOrderTitle = "ترتيب التحميل الطوبولوجي للمكتبات المجمّعة"
    override val probeSystemDepsLabel = "مكتبات النظام"
    override val probeMissingDepsLabel = "مكتبات مفقودة"
    override val probeApkPathsTitle = "مسارات Base APK و Split APKs"

    override val toolsTitle = "الرسوم والأداء والعوالم والملفات"
    override val graphicsTitle = "إعدادات الرسوم والأداء (options.txt)"
    override val graphicsDesc =
        "اقرأ وعدّل ملف options.txt الحقيقي للعبة عبر SAF أو جهّز ملف إعدادات بأوضاع أداء جاهزة دون المساس بالعوالم."
    override val loadOptionsTxtButton = "استيراد options.txt"
    override val saveOptionsTxtButton = "حفظ التعديلات في الملف"
    override val exportOptionsTxtButton = "تصدير options.txt"
    override val presetBattery = "توفير الطاقة"
    override val presetBalanced = "متوازن"
    override val presetCompetitive = "أداء 120 FPS"
    override val presetUltra = "جودة فائقة"
    override val renderDistanceLabel = "مسافة الرؤية (Chunks)"
    override val maxFramerateLabel = "الحد الأقصى للإطارات (FPS)"
    override val msaaLabel = "تنعيم الحواف (MSAA)"
    override val fovLabel = "زاوية الرؤية (FOV)"
    override val fancyGraphicsLabel = "رسوم متقدمة (Fancy Graphics)"
    override val smoothLightingLabel = "إضاءة ناعمة (Smooth Lighting)"
    override val fancySkiesLabel = "سماء عالية الدقة (Fancy Skies)"
    override val vsyncLabel = "مزامنة عمودية (VSync)"
    override val deferredRenderingLabel = "تظليل متقدم (Deferred / Vibrant)"
    override val uiProfileClassicLabel = "واجهة كلاسيكية (Classic UI)"
    override val launcherCompatTitle = "خيارات توافق المشغّل"
    override val preferredLaunchModeLabel = "وضع التشغيل الافتراضي"
    override val launchModeVerifiedShort = "داخلي APK"
    override val launchModeQuickShort = "رسمي سريع"
    override val launchModeSplitShort = "شاشة مقسمة"
    override val autoVerifyCacheLabel = "التحقق من libminecraftpe.so والكاش قبل التشغيل"
    override val preferDirectApkLoadLabel = "تفضيل التحميل المباشر من APK!/lib لتجاوز قيود Android 13"

    override val worldBackupTitle = "حماية ونسخ احتياطي واستعادة العوالم (.mcworld)"
    override val worldBackupDesc =
        "نظام حماية بيانات العوالم: فحص level.dat و db/، إنشاء نسخ .mcworld موثقة ببصمة SHA-256، ومنع الاستبدال غير المقصود عند الاستعادة."
    override val worldProtectionBadge = "حماية بيانات العوالم مفعّلة"
    override val worldProtectionHistoryTitle = "سجل النسخ الاحتياطية المحمية (SHA-256)"
    override val overwriteGuardTitle = "تحذير حماية العالم"
    override val overwriteGuardMessage =
        "المجلد المختار يحتوي بالفعل على عالم حالي (level.dat / db). هل تريد استبدال ملفاته؟ يُنصح بأخذ نسخة احتياطية أولاً."
    override val confirmOverwriteButton = "نعم، استعد واستبدل"
    override val cancelButton = "إلغاء"
    override val pickWorldFolderButton = "اختر مجلد العالم (نسخ)"
    override val exportMcWorldButton = "حفظ كـ .mcworld محمي"
    override val pickRestoreMcWorldButton = "اختر .mcworld للاستعادة"
    override val restoreToFolderButton = "استعادة إلى مجلد (SAF)"
    override val openWorldInMinecraftButton = "فتح العالم في ماينكرافت"
    override val worldNameLabel = "اسم العالم"
    override val worldModeLabel = "الوضع (NBT)"
    override val worldVersionLabel = "إصدار Bedrock (NBT)"
    override val worldFilesLabel = "عدد الملفات"
    override val levelDatFound = "level.dat سليم وموجود"
    override val levelDatMissing = "تحذير: level.dat غير موجود"
    override val dbFolderFound = "مجلد LevelDB (db/) موجود"
    override val mcPackImportTitle = "استيراد حزمة موارد (.mcpack / .mcaddon)"
    override val mcPackImportDesc =
        "افحص manifest.json داخل الحزمة ثم افتحها مباشرة داخل ماينكرافت عبر Intent."
    override val pickMcPackButton = "اختر ملف .mcpack"
    override val openInMinecraftButton = "فتح في ماينكرافت"
    override val packNameLabel = "اسم الحزمة"
    override val packTypeLabel = "النوع"
    override val packVersionLabel = "الإصدار"

    override val fileManagerTitle = "مدير الملفات والكاش (File Manager)"
    override val fileManagerDesc =
        "تصفّح ملفات ذاكرة الكاش والسجلات الداخلية أو افتح أي مجلد خارجي عبر SAF لمعاينة الملفات النصية وإنشاء المجلدات."
    override val fileTabAppCache = "كاش وسجلات LeviLab"
    override val fileTabSafBrowser = "متصفح مجلدات SAF"
    override val pickSafFolderButton = "فتح مجلد (SAF)"
    override val folderUpButton = "للأعلى"
    override val newFolderButton = "مجلد جديد"
    override val newFolderHint = "اسم المجلد الجديد"
    override val createButton = "إنشاء"
    override val noFilesFound = "المجلد فارغ أو لم يُفتح مجلد SAF بعد."
    override val previewTitle = "معاينة الملف"
    override val closeButton = "إغلاق"

    override val serverPingerTitle = "فاحص سيرفرات Bedrock (UDP 19132)"
    override val serverPingerDesc = "فحص حقيقي بحزم RakNet Unconnected Ping على المنفذ 19132."
    override val serverNameHint = "اسم السيرفر (اختياري)"
    override val serverHostHint = "العنوان (مثال: play.cubecraft.net)"
    override val serverPortHint = "المنفذ"
    override val addServerButton = "إضافة"
    override val pingAllButton = "فحص الكل"
    override val pingButton = "فحص"
    override val deleteServerDesc = "حذف السيرفر"
    override val serverOnline = "متصل"
    override val serverOffline = "غير متصل"
    override val serverPlayersLabel = "اللاعبون"
    override val serverLatencyLabel = "التأخير"
    override val openServerInMcButton = "دخول في Preview"
    override val noServersSaved = "لا توجد سيرفرات مضافة بعد. أضف عنوان سيرفر Bedrock لفحصه."

    override val logsTitle = "سجل التشغيل والتشخيص"
    override val subTabHistory = "سجل عمليات التشغيل"
    override val subTabSessionLogs = "السجل المباشر"
    override val noLaunchHistoryYet = "لم يتم تسجيل أي عملية تشغيل بعد."
    override val clearHistoryButton = "مسح سجل التشغيل"
    override val logsFilePathLabel = "ملف السجل الدائم (filesDir)"
    override val filterAll = "الكل"
    override val filterErrors = "الأخطاء فقط"
    override val copyLogsButton = "نسخ السجل"
    override val shareReportButton = "مشاركة تقرير التشخيص"
    override val clearLogsButton = "مسح"
    override val noLogsYet = "لا توجد سجلات مسجلة حالياً."
    override val copiedToast = "تم النسخ إلى الحافظة"

    override val embeddedDirectHostTitle = "مضيف التشغيل الداخلي للـ APK — LeviLab"
    override val embeddedRunningStages = "جاري تجهيز الموارد والمكتبات وتشغيل Minecraft Preview داخل LeviLab..."
    override val embeddedDirectSuccessHint =
        "تم التحقق من Preview 1.26.70.24 و ARM64 و libminecraftpe.so وتجهيز المضيف بنجاح."
    override val embeddedFailureTitle = "توقف التشغيل — فحص الحماية أو المضيف الداخلي"
    override val embeddedFallbackMessage =
        "راجع رسالة الخطأ وتفاصيل المرحلة أعلاه. إذا كانت الحزمة مثبّتة على النظام فيمكنك استخدام زر التشغيل الرسمي كبديل."
    override val embeddedOpenExternalButton = "تشغيل عبر نشاط النظام الرسمي"
    override val embeddedBackToLabButton = "العودة إلى LeviLab"
    override val embeddedShareReportButton = "مشاركة التقرير"
    override val stageALabel = "أ. التحقق من APK / Preview 1.26.70.24 و versionCode و ARM64"
    override val stageBLabel = "ب. فحص libminecraftpe.so و Split APK و Library Cache"
    override val stageCLabel = "ج. تحليل DT_NEEDED واعتماديات المكتبات"
    override val stageDLabel = "د. تركيب AssetManager و DexClassLoader وتحميل المكتبات"
    override val stageELabel = "هـ. تشغيل MainActivity داخل نافذة LeviLab"
}

object EnStrings : LeviStrings {
    override val appTitle = "LeviLab"
    override val targetVersionTag = "Target: Preview 1.26.70.24"
    override val tabLaunch = "Launch"
    override val tabProbe = "Probe & Libs"
    override val tabTools = "Tools & Files"
    override val tabLogs = "Logs"
    override val toggleLanguageLabel = "AR"
    override val shareDiagnosticsLabel = "Share Report"

    override val deviceSummaryTitle = "Device & Target Environment"
    override val abiLabel = "ABI"
    override val androidOsLabel = "Android OS"
    override val hardwareRamLabel = "RAM"
    override val hardwareStorageLabel = "Free Storage"
    override val hardwareGlLabel = "OpenGL ES"
    override val hardwareRecommendedPresetLabel = "Recommended Preset"
    override val readinessChecklistTitle = "Pre-Flight Readiness Checks"
    override val discoveredPackagesTitle = "Imported APK Vault & Packages"
    override val noMinecraftInstalledTitle = "No Preview APK Imported Yet"
    override val noMinecraftInstalledDesc =
        "Tap Import APK / APKS above to pick a Minecraft Bedrock Preview 1.26.70.24 file and run it directly inside LeviLab."
    override val rescanPackagesButton = "Rescan"
    override val packageVersionLabel = "Version"
    override val versionCodeLabel = "Version Code"
    override val targetMatchExact = "Matches Preview 1.26.70.24"
    override val targetMatchMismatch = "Differs from target 1.26.70.24"
    override val mismatchGuardTitle = "Warning: Installed Package Does Not Match Target"
    override val mismatchGuardBody =
        "Required target is Minecraft Bedrock Preview 1.26.70.24 (ARM64). Launch is blocked to avoid running the wrong edition or version."
    override val launchBlockedLabel = "Launch Blocked — Need Preview 1.26.70.24"
    override val splitApkBadge = "Split APK"
    override val singleApkBadge = "Single APK"
    override val previewBadge = "Preview"
    override val releaseBadge = "Release"
    override val betaBadge = "Beta"
    override val trialBadge = "Trial"
    override val arm64ReadyBadge = "ARM64 Ready"
    override val arm64MissingBadge = "ARM64 Missing"
    override val launchPrimaryButton = "Launch Inside LeviLab (Preview 1.26.70.24)"
    override val launchQuickOfficialButton = "Quick Official"
    override val launchAdjacentSplitButton = "Split-Screen Launch"
    override val launchFallbackButton = "Direct Launch"
    override val extractAndVerifyButton = "Verify & Repair Libraries"
    override val inspectExternalApkButton = "Inspect APK / APKS"
    override val externalApkCardTitle = "External Installer Inspection"
    override val installExternalApkButton = "Open APK Installer"
    override val signingCertLabel = "Signing SHA-256"
    override val candidateScanTitle = "Device Package Lookup Status (Android 13)"
    override val candidateFoundLabel = "Found"
    override val candidateMissingLabel = "Not installed"
    override val splitBundleBadge = "Split Bundle (.apks/.xapk)"
    override val splitBundleInstallHint = "Split APK bundle detected. You can import it straight into LeviLab's APK Vault to run in-process, or install via SAI."
    override val mismatchPickApkButton = "Import Preview (.apk / .apks)"
    override val importApkToVaultButton = "Pick APK / APKS to Run Inside LeviLab"
    override val importingVaultStatus = "Importing APK & preparing ARM64 libraries..."
    override val vaultApkBadge = "APK Vault (In-Process)"
    override val removeVaultApkDesc = "Remove imported APK"
    override val apkVaultHeroTitle = "Run Minecraft APK Inside LeviLab"
    override val apkVaultHeroSubtitle =
        "Completely independent of any Minecraft app installed on your phone — import a Preview 1.26.70.24 (.apk / .apks / .xapk) file to run directly inside LeviLab."
    override val systemPackagesOptionalTitle = "Installed System Packages (Optional)"

    override val gameHealthTitle = "Native Libraries & Cache Manager"
    override val repairReextractButton = "Repair & Rebuild Cache"
    override val clearCacheButton = "Clear Cache"
    override val repairingStatus = "Verifying libraries and rebuilding cache..."
    override val noLibrariesExtracted = "No libraries verified yet. Tap Rescan or Repair."
    override val elfValidBadge = "ELF64 Valid"
    override val elfInvalidBadge = "Invalid ELF"
    override val sizeOverOneMbBadge = "Size > 1MB"
    override val sizeUnderOneMbBadge = "Size < 1MB"
    override val architectureLabel = "Arch"
    override val sourceOriginLabel = "Origin"
    override val loadSourceLabel = "Load Path"
    override val cacheSummaryTitle = "Library Cache Status"
    override val cacheSizeLabel = "Cache Size"
    override val cacheDirectLoadLabel = "Zero-Copy Direct Load"
    override val cacheCrcVerifiedLabel = "CRC32 Verified"
    override val staleCachePrunedLabel = "Stale Cache Pruned"

    override val targetComparisonTitle = "Target Verification Matrix (Installed vs Required)"
    override val targetRowPackage = "Package"
    override val targetRowEdition = "Edition"
    override val targetRowVersion = "Version (versionName)"
    override val targetRowVersionCode = "Build (versionCode)"
    override val targetRowAbi = "ARM64 ABI"
    override val targetRowMainLib = "libminecraftpe.so"
    override val requiredLabel = "Required"
    override val installedLabel = "Installed"
    override val importedApkLabel = "APK File"
    override val vaultComparisonTitle = "Imported APK Verification Matrix"
    override val vaultPackageIsolatedBadge = "Isolated In-Process"
    override val vaultMismatchGuardTitle = "Warning: Selected APK Does Not Match Target"
    override val vaultMismatchGuardBody =
        "The imported APK inside LeviLab does not match Minecraft Bedrock 1.26.70.24 (ARM64) or lacks a valid libminecraftpe.so. Launch is blocked."
    override val toggleSystemPackagesLabel = "Show Phone-Installed Packages (Optional)"
    override val officialLaunchScopeTitle = "In-Process APK Host & Android 13 Architecture"
    override val officialLaunchScopeBody =
        "LeviLab can import local (.apk/.apks/.xapk) files into a private read-only APK Vault or use an installed APK, mount merged AssetManager/Resources, build a DexClassLoader, preload DT_NEEDED ARM64 libs, and host MainActivity in-process—with official Activity Intent launch available as an explicit fallback."

    override val lastFailureBannerTitle = "Launch Guard & Diagnostics"
    override val fallbackExplanationPrefix = "Stopped at:"
    override val dismissBanner = "Dismiss"

    override val probeTitle = "APK, Split & JNI Probe"
    override val probeSubtitle = "Analyze Base/Split APKs, ELF64 AArch64, and DT_NEEDED"
    override val runProbeButton = "Run Probe"
    override val probingInProgress = "Probing APK, JNI classes, and ELF64..."
    override val probeAutoNotice = "Auto-probed based on discovered package & versionName."
    override val probeSelectPackage = "Installed Packages"
    override val probeContextStatusTitle = "Android 13 Package Context"
    override val probeContextSuccess = "Loaded Package Context"
    override val probeContextFailed = "Android 13 code isolation active (using PackageManager metadata)"
    override val probeInheritanceTitle = "MainActivity Inheritance Chain"
    override val probeNativeMethodsTitle = "Discovered Native Methods"
    override val probeNoNativeMethods = "No declared native methods found or isolated by Android 13."
    override val probeDtNeededTitle = "libminecraftpe.so DT_NEEDED"
    override val probeElfEntrypointsTitle = "Exported Native Entrypoints (ELF64 dynsym)"
    override val probeJniCountLabel = "JNI Symbols Exported"
    override val probeDexSourceLabel = "DEX Analysis Source"
    override val probeAxmlTitle = "Binary Manifest Analysis (AndroidManifest AXML)"
    override val probeAssetsTitle = "APK Assets & RenderDragon Shaders"
    override val probeLoadOrderTitle = "Topological Bundled Load Order"
    override val probeSystemDepsLabel = "System Libs"
    override val probeMissingDepsLabel = "Missing Libs"
    override val probeApkPathsTitle = "Base APK & Split APK Paths"

    override val toolsTitle = "Graphics, Worlds & File Manager"
    override val graphicsTitle = "Graphics & Performance (options.txt)"
    override val graphicsDesc =
        "Load and edit Minecraft's real options.txt via SAF or export a tuned preset without touching your worlds."
    override val loadOptionsTxtButton = "Load options.txt"
    override val saveOptionsTxtButton = "Save to Loaded File"
    override val exportOptionsTxtButton = "Export options.txt"
    override val presetBattery = "Battery"
    override val presetBalanced = "Balanced"
    override val presetCompetitive = "120 FPS"
    override val presetUltra = "Ultra"
    override val renderDistanceLabel = "Render Distance (Chunks)"
    override val maxFramerateLabel = "Max Framerate (FPS)"
    override val msaaLabel = "Anti-Aliasing (MSAA)"
    override val fovLabel = "Field of View (FOV)"
    override val fancyGraphicsLabel = "Fancy Graphics"
    override val smoothLightingLabel = "Smooth Lighting"
    override val fancySkiesLabel = "Fancy Skies"
    override val vsyncLabel = "VSync"
    override val deferredRenderingLabel = "Deferred / Vibrant Visuals"
    override val uiProfileClassicLabel = "Classic UI Profile"
    override val launcherCompatTitle = "Launcher Compatibility Options"
    override val preferredLaunchModeLabel = "Preferred Launch Mode"
    override val launchModeVerifiedShort = "In-Process APK"
    override val launchModeQuickShort = "Quick Official"
    override val launchModeSplitShort = "Split-Screen"
    override val autoVerifyCacheLabel = "Verify libminecraftpe.so & cache before launch"
    override val preferDirectApkLoadLabel = "Prefer direct APK!/lib load (Android 13 safe)"

    override val worldBackupTitle = "World Protection, Backup & Restore (.mcworld)"
    override val worldBackupDesc =
        "Verify level.dat & db/, export SHA-256 verified .mcworld archives, and prevent accidental overwrites during restore."
    override val worldProtectionBadge = "World Data Protection Active"
    override val worldProtectionHistoryTitle = "Protected Backups (SHA-256)"
    override val overwriteGuardTitle = "World Overwrite Protection"
    override val overwriteGuardMessage =
        "Target folder already contains an existing world (level.dat / db). Overwrite its files? Backing up first is recommended."
    override val confirmOverwriteButton = "Overwrite & Restore"
    override val cancelButton = "Cancel"
    override val pickWorldFolderButton = "Pick World Folder (Backup)"
    override val exportMcWorldButton = "Export Protected .mcworld"
    override val pickRestoreMcWorldButton = "Pick .mcworld to Restore"
    override val restoreToFolderButton = "Restore to Folder (SAF)"
    override val openWorldInMinecraftButton = "Open World in Minecraft"
    override val worldNameLabel = "World Name"
    override val worldModeLabel = "Mode (NBT)"
    override val worldVersionLabel = "Bedrock Ver (NBT)"
    override val worldFilesLabel = "Files"
    override val levelDatFound = "level.dat present & valid"
    override val levelDatMissing = "Warning: level.dat missing"
    override val dbFolderFound = "LevelDB (db/) folder present"
    override val mcPackImportTitle = "Import Resource Pack (.mcpack / .mcaddon)"
    override val mcPackImportDesc =
        "Inspect manifest.json inside the pack and launch it into Minecraft via Intent."
    override val pickMcPackButton = "Pick .mcpack File"
    override val openInMinecraftButton = "Open in Minecraft"
    override val packNameLabel = "Pack Name"
    override val packTypeLabel = "Type"
    override val packVersionLabel = "Version"

    override val fileManagerTitle = "File & Cache Manager"
    override val fileManagerDesc =
        "Browse LeviLab's internal Library Cache & logs, or mount any external folder via SAF to inspect files and create folders."
    override val fileTabAppCache = "LeviLab Cache & Logs"
    override val fileTabSafBrowser = "SAF Folder Browser"
    override val pickSafFolderButton = "Open Folder (SAF)"
    override val folderUpButton = "Up"
    override val newFolderButton = "New Folder"
    override val newFolderHint = "New folder name"
    override val createButton = "Create"
    override val noFilesFound = "Directory is empty or no SAF folder opened yet."
    override val previewTitle = "File Preview"
    override val closeButton = "Close"

    override val serverPingerTitle = "Bedrock Server Pinger (UDP 19132)"
    override val serverPingerDesc = "Live RakNet Unconnected Ping packets over UDP port 19132."
    override val serverNameHint = "Label (optional)"
    override val serverHostHint = "Host (e.g. play.cubecraft.net)"
    override val serverPortHint = "Port"
    override val addServerButton = "Add"
    override val pingAllButton = "Ping All"
    override val pingButton = "Ping"
    override val deleteServerDesc = "Delete server"
    override val serverOnline = "Online"
    override val serverOffline = "Offline"
    override val serverPlayersLabel = "Players"
    override val serverLatencyLabel = "Latency"
    override val openServerInMcButton = "Join in Preview"
    override val noServersSaved = "No servers saved yet. Add a Bedrock server host to ping."

    override val logsTitle = "Launch History & Diagnostics"
    override val subTabHistory = "Launch History"
    override val subTabSessionLogs = "Live Logs"
    override val noLaunchHistoryYet = "No launches recorded yet."
    override val clearHistoryButton = "Clear History"
    override val logsFilePathLabel = "Persistent Log File (filesDir)"
    override val filterAll = "All"
    override val filterErrors = "Errors Only"
    override val copyLogsButton = "Copy Logs"
    override val shareReportButton = "Share Diagnostic Report"
    override val clearLogsButton = "Clear"
    override val noLogsYet = "No log entries recorded yet."
    override val copiedToast = "Copied to clipboard"

    override val embeddedDirectHostTitle = "In-Process APK Runtime Host"
    override val embeddedRunningStages = "Mounting APK assets, loading ARM64 libs & starting MainActivity..."
    override val embeddedDirectSuccessHint =
        "Preview 1.26.70.24, ARM64, and libminecraftpe.so verified and hosted."
    override val embeddedFailureTitle = "Host Halted — Guard or Runtime Diagnostics"
    override val embeddedFallbackMessage =
        "Review the stage failure and stack trace above. If the target package is installed on the device, you can launch its official Activity instead."
    override val embeddedOpenExternalButton = "Launch Official System Activity"
    override val embeddedBackToLabButton = "Return to LeviLab"
    override val embeddedShareReportButton = "Share Diagnostic Report"
    override val stageALabel = "A. APK / Preview 1.26.70.24, versionCode & ARM64 Check"
    override val stageBLabel = "B. libminecraftpe.so, Split APK & Library Cache"
    override val stageCLabel = "C. DT_NEEDED Dependency Graph Verification"
    override val stageDLabel = "D. AssetManager, DexClassLoader & Native Preload"
    override val stageELabel = "E. Host MainActivity Inside LeviLab"
}

fun stringsFor(language: AppLanguage): LeviStrings = when (language) {
    AppLanguage.AR -> ArStrings
    AppLanguage.EN -> EnStrings
}
