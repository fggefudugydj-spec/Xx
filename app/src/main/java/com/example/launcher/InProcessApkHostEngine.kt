package com.example.launcher

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import com.example.core.LeviLogger
import com.example.engine.AxmlManifestParser
import com.example.engine.Elf64Parser
import com.example.engine.NativeLibraryManager
import com.example.model.ElfInspectionResult
import com.example.model.InstalledMinecraftPackage
import com.example.model.LibraryLoadSource
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

class ApkHostContextWrapper(
    base: Context,
    private val hostActivity: Activity?,
    private val hostAssets: AssetManager,
    private val hostResources: Resources,
    private val hostClassLoader: ClassLoader,
    private val targetPkg: InstalledMinecraftPackage,
    private val nativeLibDirPath: String,
    private val sandboxDataRoot: File,
    private val sandboxFilesDir: File,
    private val sandboxCacheDir: File,
    private val sandboxExternalFilesDir: File,
    private val targetThemeResId: Int = 0,
    private val activityMetaData: Bundle = Bundle().apply {
        putString("android.app.lib_name", "minecraftpe")
    },
    private val hostedActivityClassName: String = targetPkg.launchActivityName ?: "com.mojang.minecraftpe.MainActivity"
) : ContextThemeWrapper(base, 0) {

    @Volatile
    private var attachedApplicationContext: Context? = null

    private val hostTheme: Resources.Theme by lazy {
        hostResources.newTheme().apply {
            val resolvedRes = if (targetThemeResId != 0) {
                targetThemeResId
            } else {
                android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen
            }
            val applied = runCatching { applyStyle(resolvedRes, true) }.isSuccess
            if (!applied && resolvedRes != android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {
                runCatching { applyStyle(android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen, true) }
            }
        }
    }

    private val patchedAppInfo: ApplicationInfo by lazy {
        ApplicationInfo(base.applicationInfo).apply {
            packageName = targetPkg.packageName
            sourceDir = targetPkg.sourceDir
            publicSourceDir = targetPkg.sourceDir
            splitSourceDirs = targetPkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
            splitPublicSourceDirs = targetPkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
            nativeLibraryDir = nativeLibDirPath
            dataDir = sandboxDataRoot.absolutePath
            metaData = Bundle(activityMetaData)
            if (targetThemeResId != 0) {
                theme = targetThemeResId
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                deviceProtectedDataDir = sandboxDataRoot.absolutePath
            }
        }
    }

    private val sandboxPm: PackageManager by lazy {
        SandboxPackageManager(
            basePm = base.packageManager,
            targetPkg = targetPkg,
            patchedAppInfo = patchedAppInfo,
            hostResources = hostResources,
            activityMetaData = activityMetaData,
            hostedActivityClassName = hostedActivityClassName
        )
    }

    internal fun attachApplicationContext(app: Context) {
        attachedApplicationContext = app
    }

    override fun getAssets(): AssetManager = hostAssets

    override fun getResources(): Resources = hostResources

    override fun getTheme(): Resources.Theme = hostTheme

    override fun getClassLoader(): ClassLoader = hostClassLoader

    override fun getPackageName(): String = targetPkg.packageName

    override fun getOpPackageName(): String = baseContext.packageName

    override fun getPackageManager(): PackageManager = sandboxPm

    override fun getPackageCodePath(): String = targetPkg.sourceDir

    override fun getPackageResourcePath(): String = targetPkg.sourceDir

    override fun getFilesDir(): File = sandboxFilesDir.apply { mkdirs() }

    override fun getNoBackupFilesDir(): File =
        File(sandboxDataRoot, "no_backup").apply { mkdirs() }

    override fun getCacheDir(): File = sandboxCacheDir.apply { mkdirs() }

    override fun getCodeCacheDir(): File =
        File(sandboxDataRoot, "code_cache").apply { mkdirs() }

    override fun getExternalFilesDir(type: String?): File =
        if (type.isNullOrBlank()) {
            sandboxExternalFilesDir.apply { mkdirs() }
        } else {
            File(sandboxExternalFilesDir, type).apply { mkdirs() }
        }

    override fun getExternalFilesDirs(type: String?): Array<File> =
        arrayOf(getExternalFilesDir(type))

    override fun getExternalCacheDir(): File =
        File(sandboxCacheDir, "external").apply { mkdirs() }

    override fun getDatabasePath(name: String): File {
        val dbDir = File(sandboxDataRoot, "databases").apply { mkdirs() }
        return File(dbDir, name)
    }

    override fun getApplicationInfo(): ApplicationInfo = patchedAppInfo

    override fun getApplicationContext(): Context = attachedApplicationContext ?: this

    override fun createPackageContext(packageName: String?, flags: Int): Context {
        if (packageName == targetPkg.packageName) return this
        return super.createPackageContext(packageName, flags)
    }

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            return super.getSystemService(name)
        }
        return hostActivity?.getSystemService(name) ?: super.getSystemService(name)
    }
}

class HostedActivityHandle(
    val hostedActivity: Activity,
    val hostContext: ApkHostContextWrapper,
    val classLoader: ClassLoader,
    val activityClassName: String,
    val mountedAssetCount: Int,
    val loadedNativeLibraries: List<String>,
    val reflectionWarnings: List<String>
) {
    fun dispatchStart() {
        invokeLifecycleMethod("onStart")
    }

    fun dispatchResume() {
        invokeLifecycleMethod("onResume")
        invokeLifecycleMethod("onPostResume")
    }

    fun dispatchPause() {
        invokeLifecycleMethod("onPause")
    }

    fun dispatchStop() {
        invokeLifecycleMethod("onStop")
    }

    fun dispatchDestroy() {
        invokeLifecycleMethod("onDestroy")
    }

    fun dispatchLowMemory() {
        runCatching { hostedActivity.onLowMemory() }
    }

    fun dispatchTrimMemory(level: Int) {
        runCatching { hostedActivity.onTrimMemory(level) }
    }

    fun dispatchWindowFocusChanged(hasFocus: Boolean) {
        runCatching { hostedActivity.onWindowFocusChanged(hasFocus) }
    }

    fun dispatchConfigurationChanged(newConfig: Configuration) {
        runCatching { hostedActivity.onConfigurationChanged(newConfig) }
    }

    fun dispatchNewIntent(intent: Intent) {
        runCatching {
            val m = findMethodInHierarchy(hostedActivity.javaClass, "onNewIntent", Intent::class.java)
            m?.apply { isAccessible = true }?.invoke(hostedActivity, intent)
        }
    }

    fun dispatchBackPressed(): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            hostedActivity.onBackPressed()
            true
        }.getOrDefault(false)
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return runCatching { hostedActivity.dispatchKeyEvent(event) }.getOrDefault(false)
    }

    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return runCatching { hostedActivity.dispatchTouchEvent(event) }.getOrDefault(false)
    }

    private fun invokeLifecycleMethod(methodName: String) {
        runCatching {
            val m = findMethodInHierarchy(hostedActivity.javaClass, methodName)
            m?.apply { isAccessible = true }?.invoke(hostedActivity)
        }
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var cur: Class<*>? = cls
        while (cur != null) {
            runCatching { cur.getDeclaredMethod(name, *paramTypes) }.getOrNull()?.let { return it }
            cur = cur.superclass
        }
        return null
    }
}

data class HostPreparedResources(
    val assets: AssetManager,
    val resources: Resources,
    val mountedApkCount: Int,
    val topAssetEntries: List<String>,
    val methodUsed: String
)

object InProcessApkHostEngine {

    @Volatile
    private var hiddenApiUnlocked = false

    fun isolatedFrameworkParentClassLoader(): ClassLoader {
        val sys = ClassLoader.getSystemClassLoader()
        return sys.parent ?: sys
    }

    fun enableHiddenApiExemptions(logger: LeviLogger? = null): Boolean {
        if (hiddenApiUnlocked) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            hiddenApiUnlocked = true
            return true
        }
        val ok = runCatching {
            val forNameMethod = Class::class.java.getDeclaredMethod(
                "forName",
                String::class.java
            )
            val getDeclaredMethodMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                emptyArray<Class<*>>().javaClass
            )
            val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethodMethod.invoke(
                vmRuntimeClass,
                "getRuntime",
                emptyArray<Class<*>>()
            ) as Method
            val setHiddenApiExemptions = getDeclaredMethodMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf<Class<*>>(arrayOf<String>().javaClass)
            ) as Method
            val runtime = getRuntime.invoke(null)
            setHiddenApiExemptions.invoke(runtime, arrayOf("L"))
            true
        }.onFailure { err ->
            logger?.warn("HOST_HIDDEN_API", "VMRuntime exemption skipped: ${err.javaClass.simpleName}: ${err.message}")
        }.getOrDefault(false)
        if (ok) {
            hiddenApiUnlocked = true
            logger?.info("HOST_HIDDEN_API", "Hidden API reflection exemptions active for Android ${Build.VERSION.SDK_INT}")
        }
        return ok
    }

    fun prepareAssetsAndResources(
        context: Context,
        pkg: InstalledMinecraftPackage,
        logger: LeviLogger? = null
    ): HostPreparedResources {
        enableHiddenApiExemptions(logger)
        val apkPaths = collectReadableApkPaths(pkg)
        require(apkPaths.isNotEmpty()) {
            "No readable Base/Split APK found for ${pkg.packageName}"
        }

        // For Vault APKs, mount an isolated AssetManager directly from the Vault APK + splits first
        // so ResourcesManager cannot mix in LeviLab overlays or phone-installed Minecraft packages.
        if (pkg.isVaultApk) {
            val isolated = tryMountIsolatedAssetManager(context, apkPaths, logger)
            if (isolated != null) {
                return isolated
            }
        }

        val officialRes = runCatching {
            val syntheticApp = ApplicationInfo(context.applicationInfo).apply {
                packageName = pkg.packageName
                sourceDir = pkg.sourceDir
                publicSourceDir = pkg.sourceDir
                splitSourceDirs = pkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
                splitPublicSourceDirs = pkg.splitSourceDirs.takeIf { it.isNotEmpty() }?.toTypedArray()
            }
            val r = context.packageManager.getResourcesForApplication(syntheticApp)
            val rootEntries = r.assets.list("")?.toList().orEmpty()
            HostPreparedResources(
                assets = r.assets,
                resources = r,
                mountedApkCount = apkPaths.size,
                topAssetEntries = rootEntries,
                methodUsed = "PackageManager.getResourcesForApplication"
            )
        }.getOrNull()

        if (officialRes != null && officialRes.topAssetEntries.isNotEmpty()) {
            logger?.info(
                "HOST_ASSETS",
                "Mounted ${officialRes.mountedApkCount} APK(s) via ${officialRes.methodUsed} (roots=${officialRes.topAssetEntries.size})"
            )
            return officialRes
        }

        return tryMountIsolatedAssetManager(context, apkPaths, logger)
            ?: throw IllegalStateException("AssetManager.addAssetPath could not mount any APK paths for ${pkg.packageName}")
    }

    private fun tryMountIsolatedAssetManager(
        context: Context,
        apkPaths: List<String>,
        logger: LeviLogger?
    ): HostPreparedResources? {
        return runCatching {
            val am = AssetManager::class.java.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance()
            val addAssetPathMethod = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
            }

            var mountedCount = 0
            for (path in apkPaths) {
                val cookie = (addAssetPathMethod.invoke(am, path) as? Int) ?: 0
                if (cookie > 0) {
                    mountedCount++
                } else {
                    logger?.warn("HOST_ASSETS", "addAssetPath returned cookie=$cookie for $path")
                }
            }
            if (mountedCount <= 0) return@runCatching null

            @Suppress("DEPRECATION")
            val res = Resources(am, context.resources.displayMetrics, context.resources.configuration)
            val rootEntries = runCatching { am.list("")?.toList().orEmpty() }.getOrDefault(emptyList())
            logger?.info(
                "HOST_ASSETS",
                "Mounted $mountedCount/${apkPaths.size} APK(s) via AssetManager.addAssetPath (roots=${rootEntries.size})"
            )
            HostPreparedResources(
                assets = am,
                resources = res,
                mountedApkCount = mountedCount,
                topAssetEntries = rootEntries,
                methodUsed = "AssetManager.addAssetPath"
            )
        }.getOrNull()
    }

    fun buildSandboxContext(
        baseContext: Context,
        hostActivity: Activity?,
        pkg: InstalledMinecraftPackage,
        preparedResources: HostPreparedResources,
        classLoader: ClassLoader,
        nativeLibDirPath: String,
        targetThemeResId: Int = 0,
        activityMetaData: Bundle = Bundle().apply {
            putString("android.app.lib_name", "minecraftpe")
        },
        hostedActivityClassName: String = pkg.launchActivityName ?: "com.mojang.minecraftpe.MainActivity"
    ): ApkHostContextWrapper {
        val profileKey = buildProfileKey(pkg)
        val sandboxRoot = File(baseContext.filesDir, "mc_sandbox/$profileKey").apply { mkdirs() }
        val sandboxFiles = File(sandboxRoot, "files").apply { mkdirs() }
        val sandboxCache = File(baseContext.cacheDir, "mc_sandbox/$profileKey").apply { mkdirs() }
        val sandboxExtBase = baseContext.getExternalFilesDir(null) ?: sandboxFiles
        val sandboxExternalFiles = File(sandboxExtBase, "mc_sandbox/$profileKey/files").apply { mkdirs() }

        return ApkHostContextWrapper(
            base = baseContext,
            hostActivity = hostActivity,
            hostAssets = preparedResources.assets,
            hostResources = preparedResources.resources,
            hostClassLoader = classLoader,
            targetPkg = pkg,
            nativeLibDirPath = nativeLibDirPath,
            sandboxDataRoot = sandboxRoot,
            sandboxFilesDir = sandboxFiles,
            sandboxCacheDir = sandboxCache,
            sandboxExternalFilesDir = sandboxExternalFiles,
            targetThemeResId = targetThemeResId,
            activityMetaData = activityMetaData,
            hostedActivityClassName = hostedActivityClassName
        )
    }

    fun buildApkClassLoader(
        context: Context,
        pkg: InstalledMinecraftPackage,
        libraries: List<ElfInspectionResult>,
        parentClassLoader: ClassLoader = isolatedFrameworkParentClassLoader()
    ): Pair<DexClassLoader, String> {
        val apkPaths = collectReadableApkPaths(pkg)
        require(apkPaths.isNotEmpty()) {
            "Cannot create DexClassLoader: no readable APK files"
        }

        // Enforce read-only APK files when stored under LeviLab's private filesDir (Android 14+ W^X rule)
        val filesRoot = context.filesDir.canonicalPath
        for (path in apkPaths) {
            val f = File(path)
            if (runCatching { f.canonicalPath.startsWith(filesRoot) }.getOrDefault(false)) {
                f.setReadOnly()
            }
        }

        val profileKey = buildProfileKey(pkg)
        val dexOptDir = File(context.codeCacheDir, "mc_dex/$profileKey").apply { mkdirs() }

        val activeCacheDir = NativeLibraryManager.targetLibDir(context, pkg).apply { mkdirs() }.absolutePath
        // Ensure extracted .so files are readable by the dynamic linker
        File(activeCacheDir).listFiles { f -> f.isFile && f.name.endsWith(".so") }?.forEach { libFile ->
            runCatching { libFile.setReadable(true, false) }
        }

        val directZipDirs = libraries
            .filter { it.loadSource == LibraryLoadSource.APK_DIRECT_ZIP && it.loadablePath.contains("!/") }
            .map { it.loadablePath.substringBeforeLast('/') }
            .distinct()

        val searchPaths = buildList {
            if (pkg.nativeLibraryDir.isNotBlank() && File(pkg.nativeLibraryDir).isDirectory) {
                add(pkg.nativeLibraryDir)
            }
            addAll(directZipDirs)
            add(activeCacheDir)
        }.distinct().joinToString(File.pathSeparator)

        val classLoader = DexClassLoader(
            apkPaths.joinToString(File.pathSeparator),
            dexOptDir.absolutePath,
            searchPaths,
            parentClassLoader
        )
        return classLoader to searchPaths
    }

    fun resolveNativeLoadOrder(libraries: List<ElfInspectionResult>): List<String> {
        val validByName = libraries
            .filter { it.isValid && File(it.filePath).isFile }
            .associateBy { it.fileName }
        require(validByName.containsKey("libminecraftpe.so")) {
            "Cannot resolve native libs: valid ELF64 libminecraftpe.so is missing"
        }
        val fileMap = validByName.mapValues { File(it.value.filePath) }
        val depResolution = Elf64Parser.resolveLoadOrder("libminecraftpe.so", fileMap)
        if (depResolution.missingDependencies.isNotEmpty()) {
            throw UnsatisfiedLinkError(
                "Missing non-system native dependencies: ${depResolution.missingDependencies.joinToString(", ")}"
            )
        }
        return depResolution.bundledLoadOrder
    }

    /**
     * Loads native libraries bound strictly to [targetClass]'s ClassLoader (DexClassLoader) so
     * JNI_OnLoad FindClass resolves Minecraft classes instead of LeviLab classes.
     * Never calls System.load() from LeviLab's ClassLoader; if Runtime.load0 is blocked on a ROM,
     * lets MainActivity's own System.loadLibrary() resolve via DexClassLoader librarySearchPath.
     */
    fun preloadNativeLibrariesForClass(
        targetClass: Class<*>,
        libraries: List<ElfInspectionResult>,
        logger: LeviLogger? = null
    ): List<String> {
        val validByName = libraries
            .filter { it.isValid && File(it.filePath).isFile }
            .associateBy { it.fileName }
        val loadOrder = resolveNativeLoadOrder(libraries)

        val load0Method = runCatching {
            findMethodInHierarchy(
                Runtime::class.java,
                "load0",
                Class::class.java,
                String::class.java
            )
        }.getOrNull()

        if (load0Method == null) {
            logger?.info(
                "HOST_NATIVE",
                "Runtime.load0 unavailable; delegating ${loadOrder.size} ARM64 libs to ${targetClass.simpleName} via DexClassLoader librarySearchPath"
            )
            return loadOrder
        }

        val runtime = Runtime.getRuntime()
        val loaded = mutableListOf<String>()
        for (libName in loadOrder) {
            val info = validByName[libName] ?: continue
            val candidatePath = when (info.loadSource) {
                LibraryLoadSource.NATIVE_DIR -> {
                    val nativeFile = File(info.loadablePath)
                    if (nativeFile.isFile) nativeFile.absolutePath else info.filePath
                }
                else -> info.filePath
            }
            logger?.info("HOST_NATIVE", "Runtime.load0(${targetClass.simpleName}, $libName) <- $candidatePath")
            val loadAttempt = runCatching {
                load0Method.invoke(runtime, targetClass, candidatePath)
            }
            if (loadAttempt.isSuccess) {
                loaded.add(libName)
            } else {
                val err = loadAttempt.exceptionOrNull()
                val root = generateSequence(err) { it.cause }.lastOrNull() ?: err
                if (root is SecurityException || root is IllegalAccessException || root is NoSuchMethodException) {
                    logger?.warn(
                        "HOST_NATIVE",
                        "Runtime.load0 blocked (${root.javaClass.simpleName}); remaining libs will load via ${targetClass.simpleName}"
                    )
                    return if (loaded.isEmpty()) loadOrder else (loaded + loadOrder).distinct()
                }
                if (root != null) throw root
            }
        }
        return loaded
    }

    fun launchHostedMainActivity(
        hostActivity: Activity,
        pkg: InstalledMinecraftPackage,
        libraries: List<ElfInspectionResult>,
        logger: LeviLogger? = null,
        onBeforeActivityOnCreate: (mountedAssetCount: Int, loadedLibsCount: Int, activityClassName: String) -> Unit = { _, _, _ -> }
    ): HostedActivityHandle {
        enableHiddenApiExemptions(logger)

        val preparedAssets = prepareAssetsAndResources(hostActivity, pkg, logger)
        val (dexClassLoader, nativeSearchPath) = buildApkClassLoader(
            context = hostActivity,
            pkg = pkg,
            libraries = libraries,
            parentClassLoader = isolatedFrameworkParentClassLoader()
        )

        val axml = if (pkg.sourceDir.isNotBlank()) {
            AxmlManifestParser.parseFromApk(File(pkg.sourceDir))
        } else {
            null
        }
        val archiveInfo = if (pkg.sourceDir.isNotBlank()) {
            runCatching {
                val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hostActivity.packageManager.getPackageArchiveInfo(
                        pkg.sourceDir,
                        PackageManager.PackageInfoFlags.of(flags.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    hostActivity.packageManager.getPackageArchiveInfo(pkg.sourceDir, flags)
                }
            }.getOrNull()
        } else {
            null
        }

        val activityMetaData = Bundle().apply {
            putString("android.app.lib_name", "minecraftpe")
            axml?.metaData?.forEach { (k, v) ->
                putString(k, v)
            }
        }

        val candidateActivities = buildList {
            pkg.launchActivityName?.takeIf { it.isNotBlank() }?.let { add(it) }
            axml?.activities?.let { addAll(it) }
            archiveInfo?.activities?.mapNotNull { it.name }?.let { addAll(it) }
            add("com.mojang.minecraftpe.MainActivity")
            add("com.mojang.minecraftpe.preview.MainActivity")
            add("com.mojang.minecraftpe.GameActivity")
        }.distinct()

        // Load Activity class WITHOUT running <clinit> yet
        var loadedClass: Class<*>? = null
        var lastClassError: Throwable? = null
        for (className in candidateActivities) {
            try {
                val cls = Class.forName(className, false, dexClassLoader)
                if (Activity::class.java.isAssignableFrom(cls)) {
                    loadedClass = cls
                    break
                }
            } catch (t: Throwable) {
                lastClassError = t
            }
        }
        val activityClass = loadedClass
            ?: throw (lastClassError ?: ClassNotFoundException(
                "No launchable Activity class resolved among ${candidateActivities.joinToString(", ")}"
            ))

        val matchedArchiveActivity = archiveInfo?.activities?.firstOrNull {
            it.name == activityClass.name || it.name.endsWith(".${activityClass.simpleName}")
        }
        matchedArchiveActivity?.metaData?.let { meta ->
            activityMetaData.putAll(meta)
        }
        val resolvedAppClassName = axml?.applicationClassName?.takeIf { it.isNotBlank() }
            ?: archiveInfo?.applicationInfo?.className?.takeIf { it.isNotBlank() }
        val resolvedThemeRes = matchedArchiveActivity?.theme?.takeIf { it != 0 }
            ?: archiveInfo?.applicationInfo?.theme?.takeIf { it != 0 }
            ?: (axml?.applicationThemeRes ?: 0)

        val nativeLibPrimaryDir = nativeSearchPath
            .split(File.pathSeparatorChar)
            .firstOrNull { !it.contains("!/") && File(it).isDirectory }
            ?: NativeLibraryManager.targetLibDir(hostActivity, pkg).absolutePath

        // 1. Build isolated SandboxContext FIRST (before Application & Activity init)
        val sandboxContext = buildSandboxContext(
            baseContext = hostActivity,
            hostActivity = hostActivity,
            pkg = pkg,
            preparedResources = preparedAssets,
            classLoader = dexClassLoader,
            nativeLibDirPath = nativeLibPrimaryDir,
            targetThemeResId = resolvedThemeRes,
            activityMetaData = activityMetaData,
            hostedActivityClassName = activityClass.name
        )

        val warnings = mutableListOf<String>()
        val previousTccl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = dexClassLoader
        try {
            // 2. Instantiate & attach custom <application> BEFORE Activity construction/native preload
            val targetApp: Application = resolveOrCreateApplication(
                hostActivity = hostActivity,
                sandboxContext = sandboxContext,
                dexClassLoader = dexClassLoader,
                appClassName = resolvedAppClassName,
                warnings = warnings
            )
            if (targetApp !== hostActivity.application) {
                sandboxContext.attachApplicationContext(targetApp)
            }

            // 3. Preload bundled ARM64 ELF64 libs bound strictly to dexClassLoader
            val loadedLibs = preloadNativeLibrariesForClass(activityClass, libraries, logger)

            // 4. Construct Activity instance (triggers MainActivity.<clinit> after Application & libs exist)
            val hostedActivity = activityClass.getDeclaredConstructor().apply {
                isAccessible = true
            }.newInstance() as Activity

            // Attach isolated ApkHostContextWrapper as base context of hosted Activity
            invokeMethodInHierarchy(
                target = hostedActivity,
                name = "attachBaseContext",
                paramTypes = arrayOf(Context::class.java),
                args = arrayOf(sandboxContext)
            ).onFailure { warnings.add("attachBaseContext: ${it.message}") }

            val hostedIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(pkg.packageName, activityClass.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Mirror Activity.attach(...) fields from hostActivity
            val fieldsToMirror = listOf(
                "mWindow",
                "mWindowManager",
                "mMainThread",
                "mInstrumentation",
                "mToken",
                "mEmbeddedID",
                "mUiThread",
                "mHandler",
                "mCurrentConfig"
            )
            for (fieldName in fieldsToMirror) {
                mirrorFieldFromHost(
                    host = hostActivity,
                    target = hostedActivity,
                    fieldName = fieldName,
                    warnings = warnings
                )
            }

            // Keep mParent null so hostedActivity.isChild() == false like a normal top-level Activity
            setFieldInHierarchy(hostedActivity, "mParent", null)
                .onFailure { warnings.add("mParent: ${it.message}") }
            setFieldInHierarchy(hostedActivity, "mApplication", targetApp)
                .onFailure { warnings.add("mApplication: ${it.message}") }
            setFieldInHierarchy(hostedActivity, "mResources", preparedAssets.resources)
                .onFailure { warnings.add("mResources: ${it.message}") }
            if (resolvedThemeRes != 0) {
                setFieldInHierarchy(hostedActivity, "mThemeResource", resolvedThemeRes)
            }
            setFieldInHierarchy(hostedActivity, "mTheme", sandboxContext.theme)
                .onFailure { warnings.add("mTheme: ${it.message}") }
            setFieldInHierarchy(hostedActivity, "mIntent", hostedIntent)
                .onFailure { warnings.add("mIntent: ${it.message}") }
            setFieldInHierarchy(hostedActivity, "mTitle", pkg.appLabel)
                .onFailure { warnings.add("mTitle: ${it.message}") }
            setFieldInHierarchy(
                hostedActivity,
                "mComponent",
                ComponentName(pkg.packageName, activityClass.name)
            ).onFailure { warnings.add("mComponent: ${it.message}") }

            val populatedActivityInfo = if (matchedArchiveActivity != null) {
                ActivityInfo(matchedArchiveActivity).apply {
                    packageName = pkg.packageName
                    name = activityClass.name
                    applicationInfo = sandboxContext.applicationInfo
                    val mergedMeta = Bundle(matchedArchiveActivity.metaData ?: Bundle())
                    mergedMeta.putAll(activityMetaData)
                    metaData = mergedMeta
                    theme = resolvedThemeRes
                    exported = true
                }
            } else {
                ActivityInfo().apply {
                    packageName = pkg.packageName
                    name = activityClass.name
                    applicationInfo = sandboxContext.applicationInfo
                    metaData = Bundle(activityMetaData)
                    theme = resolvedThemeRes
                    exported = true
                }
            }
            setFieldInHierarchy(hostedActivity, "mActivityInfo", populatedActivityInfo)
                .onFailure { warnings.add("mActivityInfo: ${it.message}") }

            if (warnings.isNotEmpty()) {
                logger?.warn("HOST_ACTIVITY", "Pre-onCreate host warnings: ${warnings.joinToString("; ")}")
            }
            onBeforeActivityOnCreate(preparedAssets.mountedApkCount, loadedLibs.size, activityClass.name)

            val instrumentation = runCatching {
                findFieldInHierarchy(Activity::class.java, "mInstrumentation")?.get(hostActivity) as? Instrumentation
            }.getOrNull()

            if (instrumentation != null) {
                instrumentation.callActivityOnCreate(hostedActivity, null)
            } else {
                val onCreate = findMethodInHierarchy(activityClass, "onCreate", Bundle::class.java)
                    ?: throw NoSuchMethodException("onCreate(Bundle) not found on ${activityClass.name}")
                onCreate.isAccessible = true
                onCreate.invoke(hostedActivity, null)
            }

            val handle = HostedActivityHandle(
                hostedActivity = hostedActivity,
                hostContext = sandboxContext,
                classLoader = dexClassLoader,
                activityClassName = activityClass.name,
                mountedAssetCount = preparedAssets.mountedApkCount,
                loadedNativeLibraries = loadedLibs,
                reflectionWarnings = warnings
            )
            handle.dispatchStart()
            handle.dispatchResume()
            if (warnings.isNotEmpty()) {
                logger?.warn("HOST_ACTIVITY", "Reflection warnings: ${warnings.joinToString("; ")}")
            }
            logger?.success(
                "HOST_ACTIVITY",
                "Started ${activityClass.name} in-process (${loadedLibs.size} .so libs, ${preparedAssets.mountedApkCount} APK assets)"
            )
            return handle
        } finally {
            Thread.currentThread().contextClassLoader = previousTccl
        }
    }

    private fun resolveOrCreateApplication(
        hostActivity: Activity,
        sandboxContext: ApkHostContextWrapper,
        dexClassLoader: ClassLoader,
        appClassName: String?,
        warnings: MutableList<String>
    ): Application {
        if (!appClassName.isNullOrBlank()) {
            val customApp = runCatching {
                val cls = Class.forName(appClassName, true, dexClassLoader)
                if (Application::class.java.isAssignableFrom(cls)) {
                    val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Application
                    invokeMethodInHierarchy(
                        target = instance,
                        name = "attach",
                        paramTypes = arrayOf(Context::class.java),
                        args = arrayOf(sandboxContext)
                    ).recoverCatching {
                        invokeMethodInHierarchy(
                            target = instance,
                            name = "attachBaseContext",
                            paramTypes = arrayOf(Context::class.java),
                            args = arrayOf(sandboxContext)
                        ).getOrThrow()
                    }
                    // Bind getApplicationContext() to instance BEFORE instance.onCreate() executes
                    sandboxContext.attachApplicationContext(instance)
                    instance.onCreate()
                    instance
                } else null
            }.onFailure {
                warnings.add("Custom Application ($appClassName) skipped: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrNull()
            if (customApp != null) return customApp
        }
        return hostActivity.application
    }

    fun collectReadableApkPaths(pkg: InstalledMinecraftPackage): List<String> {
        return buildList {
            if (pkg.sourceDir.isNotBlank()) add(pkg.sourceDir)
            addAll(pkg.splitSourceDirs.filter { it.isNotBlank() })
        }.distinct().filter { path ->
            val f = File(path)
            f.isFile && f.canRead()
        }
    }

    private fun buildProfileKey(pkg: InstalledMinecraftPackage): String {
        val raw = pkg.vaultId?.takeIf { it.isNotBlank() }
            ?: "${pkg.packageName}_${pkg.versionName}_${pkg.versionCode}"
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun findFieldInHierarchy(cls: Class<*>, fieldName: String): Field? {
        var cur: Class<*>? = cls
        while (cur != null) {
            val f = runCatching {
                cur.getDeclaredField(fieldName).apply { isAccessible = true }
            }.getOrNull()
            if (f != null) return f
            cur = cur.superclass
        }
        return null
    }

    private fun findMethodInHierarchy(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        var cur: Class<*>? = cls
        while (cur != null) {
            val m = runCatching {
                cur.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }
            }.getOrNull()
            if (m != null) return m
            cur = cur.superclass
        }
        return null
    }

    private fun invokeMethodInHierarchy(
        target: Any,
        name: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Result<Any?> = runCatching {
        val m = findMethodInHierarchy(target.javaClass, name, *paramTypes)
            ?: throw NoSuchMethodException("$name not found on ${target.javaClass.name}")
        m.invoke(target, *args)
    }

    private fun mirrorFieldFromHost(
        host: Activity,
        target: Activity,
        fieldName: String,
        warnings: MutableList<String>
    ) {
        runCatching {
            val f = findFieldInHierarchy(Activity::class.java, fieldName)
                ?: findFieldInHierarchy(host.javaClass, fieldName)
                ?: return@runCatching
            val v = f.get(host)
            setFieldInHierarchy(target, fieldName, v).getOrThrow()
        }.onFailure { err ->
            warnings.add("$fieldName: ${err.javaClass.simpleName}: ${err.message}")
        }
    }

    private fun setFieldInHierarchy(target: Any, fieldName: String, value: Any?): Result<Unit> = runCatching {
        val f = findFieldInHierarchy(target.javaClass, fieldName)
            ?: throw NoSuchFieldException("$fieldName not found on ${target.javaClass.name}")
        f.set(target, value)
    }
}
