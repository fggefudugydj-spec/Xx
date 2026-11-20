package com.example.engine

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.pm.PackageInfoCompat
import com.example.core.LeviLogger
import com.example.model.InstalledMinecraftPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

object LeviApkVaultManager {

    private const val VAULT_META_FILE = "vault_meta.json"
    private const val BUFFER_SIZE = 64 * 1024

    fun vaultRootDir(context: Context): File = File(context.filesDir, "mc_vault").apply { mkdirs() }

    data class ImportOutcome(
        val importedPackage: InstalledMinecraftPackage?,
        val errorMessage: String? = null
    )

    fun importApkOrBundle(
        context: Context,
        uri: Uri,
        logger: LeviLogger? = null
    ): ImportOutcome {
        val displayName = queryDisplayName(context, uri) ?: "imported_minecraft.apk"
        val stageId = "stage_${System.currentTimeMillis()}"
        val stageDir = File(vaultRootDir(context), stageId).apply { mkdirs() }
        val pickedFile = File(stageDir, "source_archive.bin")

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(pickedFile).use { out ->
                    input.copyTo(out, BUFFER_SIZE)
                    out.flush()
                }
            } ?: return ImportOutcome(null, "Cannot open input stream for '$displayName'")

            val extractedApks = mutableListOf<File>()
            ZipFile(pickedFile).use { rootZip ->
                if (rootZip.getEntry("AndroidManifest.xml") != null) {
                    val baseApk = File(stageDir, "base.apk")
                    if (!pickedFile.renameTo(baseApk)) {
                        pickedFile.copyTo(baseApk, overwrite = true)
                        pickedFile.delete()
                    }
                    extractedApks.add(baseApk)
                } else {
                    val entries = rootZip.entries()
                    var splitCounter = 0
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val lowerName = entry.name.lowercase(Locale.US)
                        if (!lowerName.endsWith(".apk")) continue
                        val rawLeaf = entry.name.substringAfterLast('/').ifBlank { "split_${splitCounter++}.apk" }
                        val safeLeaf = rawLeaf.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                        val outName = if (safeLeaf == "base.apk" && !File(stageDir, "base.apk").exists()) {
                            "base.apk"
                        } else {
                            "${splitCounter++}_$safeLeaf"
                        }
                        val innerFile = File(stageDir, outName)
                        rootZip.getInputStream(entry).use { zin ->
                            FileOutputStream(innerFile).use { zout ->
                                zin.copyTo(zout, BUFFER_SIZE)
                                zout.flush()
                            }
                        }
                        extractedApks.add(innerFile)
                    }
                    pickedFile.delete()
                }
            }

            if (extractedApks.isEmpty()) {
                stageDir.deleteRecursively()
                return ImportOutcome(null, "No AndroidManifest.xml or inner .apk files found in '$displayName'")
            }

            val baseApk = chooseBaseApk(extractedApks)
                ?: run {
                    stageDir.deleteRecursively()
                    return ImportOutcome(null, "No base APK with AndroidManifest.xml found in '$displayName'")
                }
            val splitApks = extractedApks.filter { it.absolutePath != baseApk.absolutePath }
                .sortedBy { it.name }

            val axml = AxmlManifestParser.parseFromApk(baseApk)
            val archiveInfo = getPackageArchiveInfoCompat(context.packageManager, baseApk.absolutePath)

            val pkgName = (archiveInfo?.packageName ?: axml?.packageName ?: "com.mojang.minecraftpe.preview").trim()
            val versionName = (archiveInfo?.versionName ?: axml?.versionName ?: "unknown").trim()
            val archiveCode = archiveInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
            val versionCode = if (archiveCode > 0L) archiveCode else (axml?.versionCode ?: 0L)
            val appLabel = runCatching {
                val appInfo = archiveInfo?.applicationInfo?.apply {
                    sourceDir = baseApk.absolutePath
                    publicSourceDir = baseApk.absolutePath
                }
                appInfo?.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: displayName.substringBeforeLast('.')

            val vaultId = buildVaultId(pkgName, versionName, versionCode)
            val finalDir = File(vaultRootDir(context), vaultId)
            if (finalDir.exists()) {
                makeTreeWritable(finalDir)
                finalDir.deleteRecursively()
            }
            if (!stageDir.renameTo(finalDir)) {
                stageDir.copyRecursively(finalDir, overwrite = true)
                stageDir.deleteRecursively()
            }

            val finalBase = File(finalDir, baseApk.name)
            val finalSplits = splitApks.map { File(finalDir, it.name) }

            // Mark APK artifacts read-only for Android 13/14+ DexClassLoader W^X safety
            finalBase.setReadOnly()
            finalSplits.forEach { it.setReadOnly() }

            val allApkPaths = listOf(finalBase.absolutePath) + finalSplits.map { it.absolutePath }
            val dexActivity = DexInspector.inspectApks(
                apkPaths = allApkPaths,
                launchActivityClass = null
            ).inheritanceChain.firstOrNull()
            val launchActivity = axml?.activities?.firstOrNull {
                it.endsWith(".MainActivity") || it.endsWith(".GameActivity")
            } ?: axml?.activities?.firstOrNull()
                ?: dexActivity
                ?: "com.mojang.minecraftpe.MainActivity"

            val importedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val meta = JSONObject().apply {
                put("vaultId", vaultId)
                put("sourceFileName", displayName)
                put("packageName", pkgName)
                put("appLabel", appLabel)
                put("versionName", versionName)
                put("versionCode", versionCode)
                put("baseApkName", finalBase.name)
                put("splitApkNames", JSONArray(finalSplits.map { it.name }))
                put("launchActivityName", launchActivity)
                put("importedAt", importedAt)
                put("targetSdkVersion", archiveInfo?.applicationInfo?.targetSdkVersion ?: (axml?.targetSdkVersion ?: 0))
                put(
                    "minSdkVersion",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        archiveInfo?.applicationInfo?.minSdkVersion ?: (axml?.minSdkVersion ?: 0)
                    } else {
                        axml?.minSdkVersion ?: 0
                    }
                )
                put("extractNativeLibs", axml?.extractNativeLibs ?: true)
            }
            File(finalDir, VAULT_META_FILE).writeText(meta.toString(2), Charsets.UTF_8)

            val model = buildPackageModelFromMeta(finalDir, meta, axml)
            // Prepare Library Cache immediately upon import so libminecraftpe.so & splits are ready
            NativeLibraryManager.inspectAndPrepareCache(
                context = context,
                pkg = model,
                logger = logger,
                forceReextract = true
            )

            logger?.success(
                "VAULT",
                "Imported APK '$displayName' into Vault [$vaultId]: ${model.packageName} [${model.edition.name}] v${model.versionName} (code=${model.versionCode}, arm64=${model.hasArm64Support}, mainLib=${model.hasMainNativeLib})"
            )
            ImportOutcome(model, null)
        } catch (t: Throwable) {
            stageDir.deleteRecursively()
            logger?.error("VAULT", "Failed importing APK '$displayName': ${t.message}", t)
            ImportOutcome(null, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun loadAllVaultPackages(
        context: Context,
        logger: LeviLogger? = null
    ): List<InstalledMinecraftPackage> {
        val root = vaultRootDir(context)
        if (!root.exists() || !root.isDirectory) return emptyList()

        val result = mutableListOf<InstalledMinecraftPackage>()
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            val metaFile = File(dir, VAULT_META_FILE)
            if (!metaFile.exists()) return@forEach
            runCatching {
                val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
                val baseApk = File(dir, meta.optString("baseApkName", "base.apk"))
                if (!baseApk.exists()) return@forEach
                val axml = AxmlManifestParser.parseFromApk(baseApk)
                result.add(buildPackageModelFromMeta(dir, meta, axml))
            }.onFailure { err ->
                logger?.warn("VAULT", "Skipping unreadable vault entry ${dir.name}: ${err.message}")
            }
        }
        return result.sortedWith(
            compareByDescending<InstalledMinecraftPackage> { it.isExactTargetReady }
                .thenByDescending { it.isPreviewPackage }
                .thenByDescending { it.matchesTargetVersion }
                .thenBy { it.vaultId ?: it.packageName }
        )
    }

    fun deleteVaultPackage(
        context: Context,
        vaultId: String,
        logger: LeviLogger? = null
    ): Boolean {
        val dir = File(vaultRootDir(context), vaultId)
        if (!dir.exists()) return false
        makeTreeWritable(dir)
        val deleted = dir.deleteRecursively()
        if (deleted) {
            logger?.info("VAULT", "Removed imported APK vault entry '$vaultId'")
        }
        return deleted
    }

    private fun buildPackageModelFromMeta(
        dir: File,
        meta: JSONObject,
        axml: com.example.model.AxmlManifestSummary?
    ): InstalledMinecraftPackage {
        val vaultId = meta.optString("vaultId", dir.name)
        val pkgName = meta.optString("packageName", "com.mojang.minecraftpe.preview")
        val versionName = meta.optString("versionName", "unknown")
        val versionCode = meta.optLong("versionCode", 0L)
        val baseApk = File(dir, meta.optString("baseApkName", "base.apk"))
        val splitsJson = meta.optJSONArray("splitApkNames") ?: JSONArray()
        val splitPaths = buildList {
            for (i in 0 until splitsJson.length()) {
                val splitFile = File(dir, splitsJson.optString(i))
                if (splitFile.exists()) add(splitFile.absolutePath)
            }
        }

        val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val deviceHasArm64 = "arm64-v8a" in deviceAbis
        val quickScan = MinecraftPackageDiscovery.quickScanApkNativeSupport(
            sourceDir = baseApk.absolutePath,
            splitSourceDirs = splitPaths,
            nativeLibraryDir = ""
        )
        val edition = MinecraftPackageDiscovery.determineEdition(pkgName, versionName)
        val matchesTarget = MinecraftPackageDiscovery.isTargetVersionMatch(versionName)
        val versionCodeValid = MinecraftPackageDiscovery.isValidVersionCode(versionCode)
        val apkHasArm64 = "arm64-v8a" in quickScan.abis ||
            (quickScan.hasArm64MainLib && deviceHasArm64)
        val hasArm64 = (deviceAbis.isEmpty() || deviceHasArm64) && apkHasArm64

        val mismatchReason = MinecraftPackageDiscovery.buildMismatchReason(
            packageName = pkgName,
            edition = edition,
            versionName = versionName,
            versionCode = versionCode,
            matchesTarget = matchesTarget,
            versionCodeValid = versionCodeValid,
            hasArm64 = hasArm64,
            apkAbis = quickScan.abis,
            hasMainNativeLib = quickScan.hasArm64MainLib,
            isVaultApk = true
        )

        return InstalledMinecraftPackage(
            packageName = pkgName,
            appLabel = "${meta.optString("appLabel", "Minecraft APK")} (APK Vault)",
            versionName = versionName,
            versionCode = versionCode,
            lastUpdateTime = baseApk.lastModified(),
            sourceDir = baseApk.absolutePath,
            splitSourceDirs = splitPaths,
            splitNames = splitPaths.map { File(it).nameWithoutExtension },
            nativeLibraryDir = "",
            supportedAbis = deviceAbis,
            matchesTargetVersion = matchesTarget,
            isPreviewPackage = edition == MinecraftEditionAlias.PREVIEW,
            extractNativeLibs = meta.optBoolean("extractNativeLibs", true),
            launchActivityName = meta.optString("launchActivityName", "com.mojang.minecraftpe.MainActivity"),
            targetSdkVersion = meta.optInt("targetSdkVersion", 0),
            minSdkVersion = meta.optInt("minSdkVersion", 0),
            edition = edition,
            apkAbis = quickScan.abis,
            hasArm64Support = hasArm64,
            hasMainNativeLib = quickScan.hasArm64MainLib,
            versionCodeValid = versionCodeValid,
            axmlVersionName = axml?.versionName,
            axmlVersionCode = axml?.versionCode,
            signingCertSha256 = "",
            targetMismatchReason = mismatchReason,
            isVaultApk = true,
            vaultId = vaultId,
            vaultImportedAt = meta.optString("importedAt", "")
        )
    }

    private fun chooseBaseApk(apks: List<File>): File? {
        val exactBase = apks.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
        if (exactBase != null && AxmlManifestParser.parseFromApk(exactBase) != null) {
            return exactBase
        }
        fun hasDex(file: File): Boolean = runCatching {
            ZipFile(file).use { it.getEntry("classes.dex") != null }
        }.getOrDefault(false)

        return apks.firstOrNull { file ->
            val lower = file.name.lowercase(Locale.US)
            val isSplitName = "split" in lower || "config." in lower || lower.startsWith("arm64") || lower.startsWith("armeabi")
            val axml = AxmlManifestParser.parseFromApk(file)
            axml?.packageName != null && !isSplitName && (hasDex(file) || axml.activities.isNotEmpty())
        } ?: apks.firstOrNull { file ->
            val axml = AxmlManifestParser.parseFromApk(file)
            axml?.packageName != null && (hasDex(file) || axml.activities.isNotEmpty())
        } ?: apks.firstOrNull {
            AxmlManifestParser.parseFromApk(it) != null
        } ?: apks.firstOrNull()
    }

    private fun buildVaultId(pkg: String, ver: String, code: Long): String {
        val safePkg = pkg.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val safeVer = ver.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "${safePkg}_${safeVer}_${code}"
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
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun getPackageArchiveInfoCompat(pm: PackageManager, archivePath: String) = try {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_ACTIVITIES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                archivePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(archivePath, flags)
        }
    } catch (_: Throwable) {
        null
    }

    private fun makeTreeWritable(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { makeTreeWritable(it) }
        }
        file.setWritable(true)
    }
}

private typealias MinecraftEditionAlias = com.example.model.MinecraftEdition
