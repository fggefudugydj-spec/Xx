package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.pm.PackageInfoCompat
import com.example.core.LeviLogger
import com.example.model.CandidateProbeResult
import com.example.model.ExternalApkInspection
import com.example.model.InstalledMinecraftPackage
import com.example.model.MinecraftEdition
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object MinecraftPackageDiscovery {

    const val TARGET_VERSION_NAME = "1.26.70.24"
    const val PREVIEW_PACKAGE_NAME = "com.mojang.minecraftpe.preview"
    const val RELEASE_PACKAGE_NAME = "com.mojang.minecraftpe"
    const val TRIAL_PACKAGE_NAME = "com.mojang.minecrafttrialpe"
    const val BETA_PACKAGE_NAME = "com.mojang.minecraftpe.beta"

    private val TARGET_VERSION_REGEX = Regex("""(?:^|[^0-9])1\.26\.70\.24(?:$|[^0-9])""")

    val CANDIDATE_PACKAGES = listOf(
        PREVIEW_PACKAGE_NAME,
        RELEASE_PACKAGE_NAME,
        TRIAL_PACKAGE_NAME,
        BETA_PACKAGE_NAME
    )

    data class DiscoveryOutcome(
        val packages: List<InstalledMinecraftPackage>,
        val candidateProbes: List<CandidateProbeResult>
    )

    fun discoverInstalledPackages(
        context: Context,
        logger: LeviLogger? = null
    ): List<InstalledMinecraftPackage> {
        return discoverWithDiagnostics(context, logger).packages
    }

    fun discoverWithDiagnostics(
        context: Context,
        logger: LeviLogger? = null
    ): DiscoveryOutcome {
        val pm = context.packageManager
        val found = mutableListOf<InstalledMinecraftPackage>()
        val probeResults = mutableListOf<CandidateProbeResult>()

        val discoveredNames = linkedSetOf<String>().apply {
            addAll(CANDIDATE_PACKAGES)
            addAll(queryMinecraftLauncherPackages(pm))
            addAll(queryInstalledMinecraftPackages(pm))
        }

        logger?.info(
            "STAGE_A",
            "Scanning installed Minecraft packages (${discoveredNames.size} candidate(s)): ${discoveredNames.joinToString(", ")}"
        )

        val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val deviceHasArm64 = "arm64-v8a" in deviceAbis

        for (pkg in discoveredNames) {
            val lookup = getPackageInfoCompatWithStatus(pm, pkg, logger)
            val info = lookup.packageInfo
            if (info != null) {
                val appInfo = info.applicationInfo
                val sourceDir = appInfo?.sourceDir ?: ""
                val splits = appInfo?.splitSourceDirs?.toList() ?: emptyList()
                val splitNames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appInfo?.splitNames?.toList() ?: emptyList()
                } else {
                    emptyList()
                }
                val axmlSummary = if (sourceDir.isNotBlank()) {
                    AxmlManifestParser.parseFromApk(File(sourceDir))
                } else {
                    null
                }
                val pmVersionName = info.versionName?.trim().orEmpty()
                val versionName = pmVersionName.ifBlank {
                    axmlSummary?.versionName?.trim().orEmpty()
                }.ifBlank { "unknown" }
                val pmVersionCode = PackageInfoCompat.getLongVersionCode(info)
                val versionCode = if (pmVersionCode > 0L) {
                    pmVersionCode
                } else {
                    axmlSummary?.versionCode ?: 0L
                }
                val appLabel = appInfo?.loadLabel(pm)?.toString() ?: pkg
                val nativeDir = appInfo?.nativeLibraryDir ?: ""
                val edition = determineEdition(pkg, versionName)
                val isPreview = edition == MinecraftEdition.PREVIEW
                val matchesTarget = isTargetVersionMatch(versionName)
                val versionCodeValid = isValidVersionCode(versionCode)
                val extractNative = appInfo != null &&
                    (appInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0
                val launchActivity = resolveLaunchActivityName(pm, pkg)
                val targetSdk = appInfo?.targetSdkVersion ?: (axmlSummary?.targetSdkVersion ?: 0)
                val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appInfo?.minSdkVersion ?: (axmlSummary?.minSdkVersion ?: 0)
                } else {
                    axmlSummary?.minSdkVersion ?: 0
                }

                val apkQuickScan = quickScanApkNativeSupport(sourceDir, splits, nativeDir)
                val apkHasArm64 = "arm64-v8a" in apkQuickScan.abis ||
                    (apkQuickScan.hasArm64MainLib && deviceHasArm64)
                val hasArm64 = (deviceAbis.isEmpty() || deviceHasArm64) && apkHasArm64

                val mismatchReason = buildMismatchReason(
                    packageName = pkg,
                    edition = edition,
                    versionName = versionName,
                    versionCode = versionCode,
                    matchesTarget = matchesTarget,
                    versionCodeValid = versionCodeValid,
                    hasArm64 = hasArm64,
                    apkAbis = apkQuickScan.abis,
                    hasMainNativeLib = apkQuickScan.hasArm64MainLib
                )

                val signingSha = computeSigningCertSha256(info)

                val item = InstalledMinecraftPackage(
                    packageName = pkg,
                    appLabel = appLabel,
                    versionName = versionName,
                    versionCode = versionCode,
                    lastUpdateTime = info.lastUpdateTime,
                    sourceDir = sourceDir,
                    splitSourceDirs = splits,
                    splitNames = splitNames,
                    nativeLibraryDir = nativeDir,
                    supportedAbis = deviceAbis,
                    matchesTargetVersion = matchesTarget,
                    isPreviewPackage = isPreview,
                    extractNativeLibs = extractNative,
                    launchActivityName = launchActivity,
                    targetSdkVersion = targetSdk,
                    minSdkVersion = minSdk,
                    edition = edition,
                    apkAbis = apkQuickScan.abis,
                    hasArm64Support = hasArm64,
                    hasMainNativeLib = apkQuickScan.hasArm64MainLib,
                    versionCodeValid = versionCodeValid,
                    axmlVersionName = axmlSummary?.versionName,
                    axmlVersionCode = axmlSummary?.versionCode,
                    signingCertSha256 = signingSha,
                    targetMismatchReason = mismatchReason
                )
                found.add(item)
                probeResults.add(
                    CandidateProbeResult(
                        packageName = pkg,
                        found = true,
                        statusNote = "${lookup.note} • v$versionName (code=$versionCode)"
                    )
                )

                val levelMsg = "Found $pkg [${edition.name}] | label='$appLabel' | v$versionName (code=$versionCode) | targetMatch=$matchesTarget | arm64=$hasArm64 (mainLib=${apkQuickScan.hasArm64MainLib}) | splits=${splits.size} | activity=${launchActivity ?: "none"}"
                if (item.isExactTargetReady) {
                    logger?.success("STAGE_A", levelMsg)
                } else {
                    logger?.warn("STAGE_A", "$levelMsg | MISMATCH: ${mismatchReason ?: "not target"}")
                }
            } else {
                probeResults.add(
                    CandidateProbeResult(
                        packageName = pkg,
                        found = false,
                        statusNote = lookup.note
                    )
                )
                logger?.info("STAGE_A", "Package $pkg: ${lookup.note}")
            }
        }

        val sorted = found.sortedWith(
            compareByDescending<InstalledMinecraftPackage> { it.isExactTargetReady }
                .thenByDescending { it.isPreviewPackage && it.matchesTargetVersion }
                .thenByDescending { it.isPreviewPackage }
                .thenByDescending { it.matchesTargetVersion }
                .thenBy { it.packageName }
        )
        return DiscoveryOutcome(packages = sorted, candidateProbes = probeResults)
    }

    fun selectBestPackage(packages: List<InstalledMinecraftPackage>): InstalledMinecraftPackage? {
        if (packages.isEmpty()) return null
        return packages.firstOrNull { it.isExactTargetReady }
            ?: packages.firstOrNull { it.isPreviewPackage && it.matchesTargetVersion }
            ?: packages.firstOrNull { it.matchesTargetVersion && it.versionCodeValid }
            ?: packages.firstOrNull { it.isPreviewPackage }
            ?: packages.first()
    }

    fun determineEdition(packageName: String, versionName: String = ""): MinecraftEdition {
        val pkgLower = packageName.trim().lowercase()
        val verLower = versionName.trim().lowercase()
        return when {
            pkgLower == PREVIEW_PACKAGE_NAME || pkgLower.endsWith(".preview") || pkgLower.contains(".preview.") ->
                MinecraftEdition.PREVIEW
            pkgLower == BETA_PACKAGE_NAME || pkgLower.endsWith(".beta") ->
                MinecraftEdition.BETA
            pkgLower == TRIAL_PACKAGE_NAME || pkgLower.contains("trial") ->
                MinecraftEdition.TRIAL
            pkgLower == RELEASE_PACKAGE_NAME ->
                if ("preview" in verLower) MinecraftEdition.PREVIEW else MinecraftEdition.RELEASE
            "preview" in pkgLower || "preview" in verLower ->
                MinecraftEdition.PREVIEW
            "minecraft" in pkgLower ->
                MinecraftEdition.RELEASE
            else ->
                MinecraftEdition.UNKNOWN
        }
    }

    fun isTargetVersionMatch(versionName: String): Boolean {
        val normalized = versionName.trim()
        if (normalized.isEmpty()) return false
        return TARGET_VERSION_REGEX.containsMatchIn(normalized)
    }

    fun isValidVersionCode(versionCode: Long): Boolean {
        return versionCode > 0L
    }

    fun buildMismatchReason(
        packageName: String,
        edition: MinecraftEdition,
        versionName: String,
        versionCode: Long,
        matchesTarget: Boolean,
        versionCodeValid: Boolean,
        hasArm64: Boolean,
        apkAbis: List<String>,
        hasMainNativeLib: Boolean,
        isVaultApk: Boolean = false
    ): String? {
        val issues = mutableListOf<String>()
        if (!isVaultApk && edition != MinecraftEdition.PREVIEW) {
            issues.add("Edition is ${edition.name} ($packageName), expected Minecraft Preview ($PREVIEW_PACKAGE_NAME)")
        }
        if (!matchesTarget) {
            issues.add("Version $versionName != target $TARGET_VERSION_NAME")
        }
        if (!versionCodeValid) {
            issues.add("Invalid versionCode ($versionCode)")
        }
        if (!hasArm64) {
            issues.add("Missing arm64-v8a support (APKs: ${apkAbis.ifEmpty { listOf("none") }.joinToString(", ")})")
        }
        if (!hasMainNativeLib) {
            issues.add("Valid ARM64 ELF64 libminecraftpe.so (>1MB) not detected in Base/Split APK or nativeLibraryDir")
        }
        return if (issues.isEmpty()) null else issues.joinToString(" • ")
    }

    internal data class ApkNativeQuickScan(
        val abis: List<String>,
        val hasArm64MainLib: Boolean
    )

    internal fun quickScanApkNativeSupport(
        sourceDir: String,
        splitSourceDirs: List<String>,
        nativeLibraryDir: String
    ): ApkNativeQuickScan {
        val abis = linkedSetOf<String>()
        var hasArm64Main = false

        if (nativeLibraryDir.isNotBlank()) {
            val nativeMain = File(nativeLibraryDir, "libminecraftpe.so")
            if (nativeMain.exists() && nativeMain.length() > ElfValidator.MIN_MAIN_LIBRARY_BYTES) {
                val check = ElfValidator.validateLibrary(nativeMain, "nativeLibraryDir", requireMinOneMb = true, requireArm64 = true)
                if (check.isValid && check.isAArch64) {
                    abis.add("arm64-v8a")
                    hasArm64Main = true
                }
            }
        }

        val apkPaths = buildList {
            if (sourceDir.isNotBlank()) add(sourceDir)
            addAll(splitSourceDirs.filter { it.isNotBlank() })
        }.distinct()

        for (path in apkPaths) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) continue
            runCatching {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (name.startsWith("lib/") && name.endsWith(".so")) {
                            val parts = name.split('/')
                            if (parts.size >= 3) {
                                abis.add(parts[1])
                            }
                            if (name == "lib/arm64-v8a/libminecraftpe.so" &&
                                !hasArm64Main &&
                                entry.size > ElfValidator.MIN_MAIN_LIBRARY_BYTES
                            ) {
                                val header = ByteArray(20)
                                val read = zip.getInputStream(entry).use { input ->
                                    var total = 0
                                    while (total < header.size) {
                                        val n = input.read(header, total, header.size - total)
                                        if (n <= 0) break
                                        total += n
                                    }
                                    total
                                }
                                val check = ElfValidator.validateHeaderBytes(
                                    fileName = "libminecraftpe.so",
                                    filePath = entry.name,
                                    sourceOrigin = file.name,
                                    sizeBytes = entry.size,
                                    header = header,
                                    bytesRead = read,
                                    requireMinOneMb = true,
                                    requireArm64 = true
                                )
                                if (check.isValid && check.isAArch64) {
                                    hasArm64Main = true
                                }
                            }
                        }
                    }
                }
            }
        }
        return ApkNativeQuickScan(abis = abis.toList(), hasArm64MainLib = hasArm64Main)
    }

    private fun queryMinecraftLauncherPackages(pm: PackageManager): List<String> {
        val queryIntents = listOf(
            Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            Intent(Intent.ACTION_VIEW, Uri.parse("minecraft://"))
        )
        val matched = linkedSetOf<String>()
        for (intent in queryIntents) {
            val resolved = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                }
            }.getOrNull().orEmpty()

            for (info in resolved) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName ?: continue
                val pkgLower = pkg.lowercase()
                val actLower = activity.name?.lowercase().orEmpty()
                val labelLower = runCatching { info.loadLabel(pm).toString().lowercase() }.getOrDefault("")
                if (
                    pkgLower.contains("minecraft") ||
                    pkgLower.contains("mojang") ||
                    pkgLower.contains("mcpe") ||
                    pkgLower.contains("levimc") ||
                    pkgLower.contains("levilauncher") ||
                    actLower.contains("com.mojang.minecraftpe") ||
                    labelLower.contains("minecraft")
                ) {
                    matched.add(pkg)
                }
            }
        }
        return matched.toList()
    }

    private fun queryInstalledMinecraftPackages(pm: PackageManager): List<String> {
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrNull().orEmpty()

        return installed.mapNotNull { info ->
            val pkg = info.packageName ?: return@mapNotNull null
            val pkgLower = pkg.lowercase()
            val labelLower = runCatching {
                info.applicationInfo?.loadLabel(pm)?.toString()?.lowercase().orEmpty()
            }.getOrDefault("")
            if (
                pkgLower.contains("minecraft") ||
                pkgLower.contains("mojang") ||
                pkgLower.contains("mcpe") ||
                pkgLower.contains("levimc") ||
                labelLower.contains("minecraft")
            ) {
                pkg
            } else {
                null
            }
        }.distinct()
    }

    fun resolveLaunchActivityName(pm: PackageManager, packageName: String): String? {
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        val fromLaunchIntent = launchIntent?.component?.className
        if (!fromLaunchIntent.isNullOrBlank()) return fromLaunchIntent

        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    mainIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull().orEmpty()
        return resolved.firstOrNull { it.activityInfo?.exported != false }?.activityInfo?.name
            ?: resolved.firstOrNull()?.activityInfo?.name
    }

    fun inspectExternalApk(
        context: Context,
        uri: Uri,
        logger: LeviLogger? = null
    ): ExternalApkInspection {
        val fileName = queryDisplayName(context, uri) ?: "selected.apk"
        val workDir = File(context.cacheDir, "inspect_external_${System.currentTimeMillis()}").apply { mkdirs() }
        val pickedArchive = File(workDir, "picked_archive.bin")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(pickedArchive).use { out ->
                    input.copyTo(out, 64 * 1024)
                    out.flush()
                }
            } ?: error("Cannot open APK/APKS stream")

            val fileSize = pickedArchive.length()
            val pm = context.packageManager
            val abis = linkedSetOf<String>()
            var hasArm64Main = false
            var mainLibSize = 0L
            var mainLibValidElf64 = false
            var isSplitBundle = false

            val apksToScan = mutableListOf<File>()
            ZipFile(pickedArchive).use { rootZip ->
                if (rootZip.getEntry("AndroidManifest.xml") != null) {
                    apksToScan.add(pickedArchive)
                } else {
                    // Support .apks / .apkm / .xapk split bundles containing inner *.apk files
                    val entries = rootZip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if (entry.name.lowercase().endsWith(".apk")) {
                            isSplitBundle = true
                            val safeName = entry.name.substringAfterLast('/').ifBlank { "inner_${apksToScan.size}.apk" }
                            val innerApk = File(workDir, "${apksToScan.size}_$safeName")
                            rootZip.getInputStream(entry).use { zin ->
                                FileOutputStream(innerApk).use { zout ->
                                    zin.copyTo(zout, 64 * 1024)
                                }
                            }
                            apksToScan.add(innerApk)
                        }
                    }
                    if (apksToScan.isEmpty()) {
                        error("No AndroidManifest.xml or inner .apk files found inside '$fileName'")
                    }
                }
            }

            // Prefer base.apk (or first inner APK with manifest package) for manifest metadata
            var pkgName: String? = null
            var verName: String? = null
            var verCode: Long? = null
            var axmlPermCount = 0

            val orderedForManifest = apksToScan.sortedWith(
                compareBy<File> { !it.name.contains("base", ignoreCase = true) }
                    .thenBy { it.name.contains("split", ignoreCase = true) }
            )
            for (apkFile in orderedForManifest) {
                val archiveInfo = getPackageArchiveInfoCompat(pm, apkFile.absolutePath)
                val axml = AxmlManifestParser.parseFromApk(apkFile)
                if (pkgName == null) pkgName = archiveInfo?.packageName ?: axml?.packageName
                if (verName == null) verName = archiveInfo?.versionName ?: axml?.versionName
                if (verCode == null) verCode = archiveInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: axml?.versionCode
                if (axmlPermCount == 0 && axml != null) axmlPermCount = axml.permissions.size
                if (pkgName != null && verName != null) break
            }

            for (apkFile in apksToScan) {
                runCatching {
                    ZipFile(apkFile).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) continue
                            if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                                val parts = entry.name.split('/')
                                if (parts.size >= 3) {
                                    abis.add(parts[1])
                                }
                                if (entry.name == "lib/arm64-v8a/libminecraftpe.so") {
                                    hasArm64Main = true
                                    mainLibSize = entry.size
                                    val header = ByteArray(20)
                                    val read = zip.getInputStream(entry).use { input ->
                                        var total = 0
                                        while (total < header.size) {
                                            val n = input.read(header, total, header.size - total)
                                            if (n <= 0) break
                                            total += n
                                        }
                                        total
                                    }
                                    val check = ElfValidator.validateHeaderBytes(
                                        fileName = "libminecraftpe.so",
                                        filePath = entry.name,
                                        sourceOrigin = apkFile.name,
                                        sizeBytes = entry.size,
                                        header = header,
                                        bytesRead = read,
                                        requireMinOneMb = true,
                                        requireArm64 = true
                                    )
                                    if (check.isValid && check.isAArch64) {
                                        mainLibValidElf64 = true
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val edition = if (pkgName != null) {
                determineEdition(pkgName, verName.orEmpty())
            } else {
                MinecraftEdition.UNKNOWN
            }
            val matches = verName != null && isTargetVersionMatch(verName)
            val isTargetPreview = edition == MinecraftEdition.PREVIEW &&
                matches &&
                (verCode ?: 0L) > 0L &&
                hasArm64Main &&
                mainLibValidElf64

            logger?.info(
                "VERSION_MGR",
                "Inspected external ${if (isSplitBundle) "APKS/Split Bundle (${apksToScan.size} APKs)" else "APK"} $fileName: pkg=$pkgName [${edition.name}], v=$verName ($verCode), targetMatch=$matches, abis=$abis, arm64Main=$hasArm64Main, validElf64=$mainLibValidElf64"
            )

            ExternalApkInspection(
                uriString = uri.toString(),
                fileName = fileName,
                fileSizeBytes = fileSize,
                packageName = pkgName,
                versionName = verName,
                versionCode = verCode,
                edition = edition,
                matchesTargetVersion = matches,
                isTargetPreviewApk = isTargetPreview,
                availableAbis = abis.toList(),
                hasArm64MainLib = hasArm64Main,
                mainLibSizeBytes = mainLibSize,
                mainLibIsValidElf64 = mainLibValidElf64,
                manifestPermissionsCount = axmlPermCount,
                isSplitBundleContainer = isSplitBundle
            )
        } catch (t: Throwable) {
            logger?.error("VERSION_MGR", "External APK inspection failed: ${t.message}", t)
            ExternalApkInspection(
                uriString = uri.toString(),
                fileName = fileName,
                fileSizeBytes = 0L,
                packageName = null,
                versionName = null,
                versionCode = null,
                edition = MinecraftEdition.UNKNOWN,
                matchesTargetVersion = false,
                isTargetPreviewApk = false,
                availableAbis = emptyList(),
                hasArm64MainLib = false,
                mainLibSizeBytes = 0L,
                mainLibIsValidElf64 = false,
                errorMessage = "${t.javaClass.simpleName}: ${t.message}"
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun computeSigningCertSha256(info: PackageInfo): String {
        return runCatching {
            val sigBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                val signers = signingInfo?.apkContentsSigners ?: signingInfo?.signingCertificateHistory
                signers?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (sigBytes == null || sigBytes.isEmpty()) return@runCatching ""
            val digest = MessageDigest.getInstance("SHA-256").digest(sigBytes)
            digest.take(8).joinToString(":") { "%02X".format(it) }
        }.getOrDefault("")
    }

    private data class PackageLookupOutcome(
        val packageInfo: PackageInfo?,
        val note: String
    )

    private fun getPackageInfoCompatWithStatus(
        pm: PackageManager,
        pkg: String,
        logger: LeviLogger? = null
    ): PackageLookupOutcome {
        val fullFlags = PackageManager.GET_META_DATA or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SIGNING_CERTIFICATES
        val flagAttempts = listOf(
            "fullFlags" to fullFlags,
            "meta+activities" to (PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES),
            "basicFlags" to 0,
            "matchDisabledOrUninstalled" to (PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
        )

        var lastError: String? = null
        for ((label, intFlags) in flagAttempts) {
            try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(intFlags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    val compatFlags = if (intFlags == fullFlags && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                        PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES or PackageManager.GET_SIGNATURES
                    } else {
                        intFlags
                    }
                    pm.getPackageInfo(pkg, compatFlags)
                }
                return PackageLookupOutcome(info, "Detected ($label)")
            } catch (_: PackageManager.NameNotFoundException) {
                lastError = "Not installed (NameNotFound)"
                if (label == "fullFlags") {
                    // Continue to matchDisabledOrUninstalled / getApplicationInfo in case disabled/archived
                    continue
                }
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                logger?.warn("STAGE_A", "getPackageInfo($pkg, $label) fallback after: $lastError")
            }
        }

        // Final fallback: try getApplicationInfo directly in case Binder PackageInfo failed
        val appInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
        }.getOrNull()

        if (appInfo != null) {
            val synthesized = PackageInfo().apply {
                packageName = pkg
                applicationInfo = appInfo
            }
            return PackageLookupOutcome(synthesized, "Detected (ApplicationInfo fallback)")
        }

        return PackageLookupOutcome(null, lastError ?: "Not installed")
    }

    private fun getPackageArchiveInfoCompat(pm: PackageManager, archivePath: String): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    archivePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(archivePath, PackageManager.GET_META_DATA)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
