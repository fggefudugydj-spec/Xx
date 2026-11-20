package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import com.example.core.AppLanguage
import com.example.core.LeviLogger
import com.example.core.LeviStrings
import com.example.core.stringsFor
import com.example.engine.ElfValidator
import com.example.engine.MinecraftPackageDiscovery
import com.example.engine.NativeLibraryManager
import com.example.launcher.InProcessLaunchCoordinator
import com.example.model.InstalledMinecraftPackage
import com.example.model.LaunchMode
import com.example.model.LaunchStepState
import com.example.model.LeviUiState
import com.example.model.LibraryLoadSource
import com.example.model.MinecraftEdition
import com.example.ui.LeviApp
import com.example.ui.screens.LaunchHealthScreen
import com.example.ui.theme.LeviLabTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LeviLab", appName)
    }

    @Test
    fun `arabic and english strings are both populated`() {
        val ar: LeviStrings = stringsFor(AppLanguage.AR)
        val en: LeviStrings = stringsFor(AppLanguage.EN)
        assertTrue(ar.launchPrimaryButton.isNotBlank())
        assertTrue(en.launchPrimaryButton.isNotBlank())
        assertTrue(ar.mismatchGuardTitle.isNotBlank())
        assertTrue(en.fileManagerTitle.isNotBlank())
        assertEquals("LeviLab", ar.appTitle)
        assertEquals("LeviLab", en.appTitle)
    }

    @Test
    fun `split apk extraction resolves stored libminecraftpe_so and prunes 1_26_62_1 stale cache`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logger = LeviLogger.getInstance(context)

        val baseApk = File(tempFolder.root, "base.apk")
        ZipOutputStream(FileOutputStream(baseApk)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
        }

        // Create split_config.arm64_v8a.apk with STORED uncompressed ELF64 > 1MB libminecraftpe.so
        val splitApk = File(tempFolder.root, "split_config.arm64_v8a.apk")
        val libBytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 4096)
        libBytes[0] = 0x7F.toByte()
        libBytes[1] = 'E'.code.toByte()
        libBytes[2] = 'L'.code.toByte()
        libBytes[3] = 'F'.code.toByte()
        libBytes[4] = 2.toByte()
        libBytes[5] = 1.toByte()
        libBytes[6] = 1.toByte()
        libBytes[18] = 0xB7.toByte()
        val crc = CRC32().apply { update(libBytes) }.value

        ZipOutputStream(FileOutputStream(splitApk)).use { zos ->
            val entry = ZipEntry("lib/arm64-v8a/libminecraftpe.so").apply {
                method = ZipEntry.STORED
                size = libBytes.size.toLong()
                compressedSize = libBytes.size.toLong()
                this.crc = crc
            }
            zos.putNextEntry(entry)
            zos.write(libBytes)
            zos.closeEntry()
        }

        // Create a stale old 1.26.62.1 cache dir that should get pruned when 1.26.70.24 runs
        val staleOldVersionDir = File(
            NativeLibraryManager.cacheRootDir(context),
            "com.mojang.minecraftpe.preview/1.26.62.1_952606201/arm64-v8a"
        )
        staleOldVersionDir.mkdirs()
        File(staleOldVersionDir, "libminecraftpe.so").writeText("stale 1.26.62.1")

        val pkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe.preview",
            appLabel = "Minecraft Preview",
            versionName = "1.26.70.24",
            versionCode = 952607024L,
            lastUpdateTime = 1700000000L,
            sourceDir = baseApk.absolutePath,
            splitSourceDirs = listOf(splitApk.absolutePath),
            splitNames = listOf("config.arm64_v8a"),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = true,
            edition = MinecraftEdition.PREVIEW,
            hasArm64Support = true,
            extractNativeLibs = false
        )

        val cachePath = NativeLibraryManager.targetLibDir(context, pkg, "arm64-v8a").absolutePath
        assertTrue(cachePath.contains("1.26.70.24"))

        val results = NativeLibraryManager.inspectExistingOrExtract(
            context = context,
            pkg = pkg,
            logger = logger,
            forceReextract = true
        )

        assertEquals(1, results.size)
        val mainLib = results.first()
        assertEquals("libminecraftpe.so", mainLib.fileName)
        assertTrue(mainLib.isValid)
        assertTrue(mainLib.isAArch64)
        assertEquals(LibraryLoadSource.APK_DIRECT_ZIP, mainLib.loadSource)
        assertTrue(mainLib.loadablePath.endsWith("split_config.arm64_v8a.apk!/lib/arm64-v8a/libminecraftpe.so"))
        assertFalse(staleOldVersionDir.exists())
    }

    @Test
    fun `launch screen blocks wrong version 1_26_62_1 and shows mismatch guard banner`() {
        val wrongPkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe.preview",
            appLabel = "Minecraft Preview",
            versionName = "1.26.62.1",
            versionCode = 952606201L,
            sourceDir = "/data/app/base.apk",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = false,
            isPreviewPackage = true,
            edition = MinecraftEdition.PREVIEW,
            hasArm64Support = true,
            targetMismatchReason = "Version 1.26.62.1 != target 1.26.70.24"
        )
        val uiState = LeviUiState(
            language = AppLanguage.EN,
            installedPackages = listOf(wrongPkg),
            selectedPackage = wrongPkg,
            deviceAbiList = listOf("arm64-v8a"),
            androidVersionLabel = "Android 13 (API 33)"
        )

        composeTestRule.setContent {
            LeviLabTheme(language = AppLanguage.EN) {
                LaunchHealthScreen(
                    state = uiState,
                    strings = stringsFor(AppLanguage.EN),
                    onRescan = {},
                    onSelectPackage = {},
                    onLaunchVerified = {},
                    onLaunchQuickOfficial = {},
                    onLaunchDirectSystem = {},
                    onRepairLibraries = {},
                    onClearCache = {},
                    onInspectExternalApk = {},
                    onInstallExternalApk = {},
                    onDismissFailure = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_health_list").performScrollToKey("packages_section")
        composeTestRule.onNodeWithTag("target_comparison_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("target_mismatch_guard_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("launch_primary_button").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("launch_direct_fallback_button").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("launch_quick_official_button").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("launch_adjacent_split_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `launch screen enables launch buttons only when preview 1_26_70_24 arm64 is ready`() {
        val exactPkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe.preview",
            appLabel = "Minecraft Preview",
            versionName = "1.26.70.24",
            versionCode = 952607024L,
            sourceDir = "/data/app/base.apk",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = true,
            edition = MinecraftEdition.PREVIEW,
            hasArm64Support = true,
            hasMainNativeLib = true
        )
        val uiState = LeviUiState(
            language = AppLanguage.EN,
            installedPackages = listOf(exactPkg),
            selectedPackage = exactPkg,
            deviceAbiList = listOf("arm64-v8a"),
            androidVersionLabel = "Android 13 (API 33)"
        )

        composeTestRule.setContent {
            LeviLabTheme(language = AppLanguage.EN) {
                LaunchHealthScreen(
                    state = uiState,
                    strings = stringsFor(AppLanguage.EN),
                    onRescan = {},
                    onSelectPackage = {},
                    onLaunchVerified = {},
                    onLaunchQuickOfficial = {},
                    onLaunchDirectSystem = {},
                    onRepairLibraries = {},
                    onClearCache = {},
                    onInspectExternalApk = {},
                    onInstallExternalApk = {},
                    onDismissFailure = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_health_list").performScrollToKey("packages_section")
        composeTestRule.onNodeWithTag("target_comparison_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("target_mismatch_guard_banner").assertDoesNotExist()
        composeTestRule.onNodeWithTag("launch_primary_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("launch_direct_fallback_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("launch_quick_official_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("launch_adjacent_split_button").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `discovery selects preview 1_26_70_24 over release 1_26_62_1 and fires official launch intent`() {
        val originalAbis = Build.SUPPORTED_ABIS
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a"))
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val logger = LeviLogger.getInstance(context)
            val shadowPm = shadowOf(context.packageManager)

            val baseApk = File(tempFolder.root, "preview_base.apk").apply {
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(byteArrayOf(1, 2, 3, 4))
                    zos.closeEntry()
                }
            }
            val splitApk = File(tempFolder.root, "preview_split_arm64.apk").apply {
                val libBytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 4096).also { b ->
                    b[0] = 0x7F.toByte()
                    b[1] = 'E'.code.toByte()
                    b[2] = 'L'.code.toByte()
                    b[3] = 'F'.code.toByte()
                    b[4] = 2.toByte()
                    b[5] = 1.toByte()
                    b[6] = 1.toByte()
                    b[18] = 0xB7.toByte()
                }
                val crc = CRC32().apply { update(libBytes) }.value
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    val entry = ZipEntry("lib/arm64-v8a/libminecraftpe.so").apply {
                        method = ZipEntry.STORED
                        size = libBytes.size.toLong()
                        compressedSize = libBytes.size.toLong()
                        this.crc = crc
                    }
                    zos.putNextEntry(entry)
                    zos.write(libBytes)
                    zos.closeEntry()
                }
            }

            // Stale cache from 1.26.62.1 should be pruned cleanly when Preview 1.26.70.24 runs
            val staleOldVersionDir = File(
                NativeLibraryManager.cacheRootDir(context),
                "com.mojang.minecraftpe.preview/1.26.62.1_952606201/arm64-v8a"
            ).apply {
                mkdirs()
                File(this, "libminecraftpe.so").writeText("stale 1.26.62.1")
            }

            val releasePkgInfo = PackageInfo().apply {
                packageName = "com.mojang.minecraftpe"
                versionName = "1.26.62.1"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    longVersionCode = 952606201L
                } else {
                    @Suppress("DEPRECATION")
                    versionCode = 952606201
                }
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.mojang.minecraftpe"
                    name = "Minecraft"
                    sourceDir = baseApk.absolutePath
                }
            }
            val previewPkgInfo = PackageInfo().apply {
                packageName = "com.mojang.minecraftpe.preview"
                versionName = "1.26.70.24"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    longVersionCode = 952607024L
                } else {
                    @Suppress("DEPRECATION")
                    versionCode = 952607024
                }
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.mojang.minecraftpe.preview"
                    name = "Minecraft Preview"
                    sourceDir = baseApk.absolutePath
                    splitSourceDirs = arrayOf(splitApk.absolutePath)
                    splitNames = arrayOf("config.arm64_v8a")
                }
                activities = arrayOf(
                    ActivityInfo().apply {
                        packageName = "com.mojang.minecraftpe.preview"
                        name = "com.mojang.minecraftpe.MainActivity"
                        exported = true
                    }
                )
            }

            shadowPm.installPackage(releasePkgInfo)
            shadowPm.installPackage(previewPkgInfo)

            val previewComponent = android.content.ComponentName(
                "com.mojang.minecraftpe.preview",
                "com.mojang.minecraftpe.MainActivity"
            )
            val previewMainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("com.mojang.minecraftpe.preview")
            }
            @Suppress("DEPRECATION")
            shadowPm.addResolveInfoForIntent(
                previewMainIntent,
                android.content.pm.ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply {
                        packageName = previewComponent.packageName
                        name = previewComponent.className
                        exported = true
                        applicationInfo = previewPkgInfo.applicationInfo
                    }
                }
            )

            val discovered = MinecraftPackageDiscovery.discoverInstalledPackages(context, logger)
            assertTrue(discovered.any { it.packageName == "com.mojang.minecraftpe" && !it.isExactTargetReady })
            val best = MinecraftPackageDiscovery.selectBestPackage(discovered)
            assertNotNull(best)
            assertEquals("com.mojang.minecraftpe.preview", best?.packageName)
            assertEquals("1.26.70.24", best?.versionName)
            assertTrue("Expected exactTargetReady, got: ${best?.targetMismatchReason}", best?.isExactTargetReady == true)
            assertTrue(best?.hasMainNativeLib == true)

            val hostActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val outcome = runBlocking {
                InProcessLaunchCoordinator.executeVerifiedLaunch(
                    activityContext = hostActivity,
                    preferredPackageName = "com.mojang.minecraftpe.preview",
                    verifyCacheBeforeLaunch = true,
                    launchMode = LaunchMode.QUICK_OFFICIAL,
                    strings = stringsFor(AppLanguage.EN),
                    logger = logger,
                    onStageUpdate = {}
                )
            }

            assertTrue("Expected verified launch to succeed: ${outcome.failure?.reason}", outcome.gameLaunchedExternally)
            assertEquals(null, outcome.failure)
            assertTrue(outcome.historyEntry?.succeeded == true)
            assertEquals(5, outcome.stages.size)
            assertTrue(outcome.stages.all { it.state == LaunchStepState.PASSED })
            assertFalse("Expected stale 1.26.62.1 cache directory to be pruned", staleOldVersionDir.exists())

            val launchedIntent = shadowOf(hostActivity).nextStartedActivity
            assertNotNull("Expected official Android Activity Intent to be fired", launchedIntent)
            assertEquals("com.mojang.minecraftpe.preview", launchedIntent.`package` ?: launchedIntent.component?.packageName)
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", originalAbis)
        }
    }

    @Test
    fun `levi app renders navigation tabs, language toggle, and tools file manager`() {
        composeTestRule.setContent {
            LeviApp()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_launch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_probe").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_tools").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_logs").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toggle_language_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tools").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tools_list").performScrollToKey("world_backup_card")
        composeTestRule.onNodeWithTag("world_protection_badge").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_list").performScrollToKey("file_manager_card")
        composeTestRule.onNodeWithTag("file_manager_card").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `discoverWithDiagnostics reports candidate probes and detects renamed levimc preview launcher package`() {
        val originalAbis = Build.SUPPORTED_ABIS
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a"))
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val logger = LeviLogger.getInstance(context)
            val shadowPm = shadowOf(context.packageManager)

            val baseApk = File(tempFolder.root, "cloned_base.apk").apply {
                val libBytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 2048).also { b ->
                    b[0] = 0x7F.toByte()
                    b[1] = 'E'.code.toByte()
                    b[2] = 'L'.code.toByte()
                    b[3] = 'F'.code.toByte()
                    b[4] = 2.toByte()
                    b[5] = 1.toByte()
                    b[6] = 1.toByte()
                    b[18] = 0xB7.toByte()
                }
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(byteArrayOf(1, 2, 3, 4))
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
                    zos.write(libBytes)
                    zos.closeEntry()
                }
            }

            // Simulate user having ONLY a cloned/renamed Preview package (not com.mojang.minecraftpe.preview)
            val clonedPkgName = "org.levimc.preview"
            val clonedPkgInfo = PackageInfo().apply {
                packageName = clonedPkgName
                versionName = "1.26.70.24"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    longVersionCode = 952607024L
                } else {
                    @Suppress("DEPRECATION")
                    versionCode = 952607024
                }
                applicationInfo = ApplicationInfo().apply {
                    packageName = clonedPkgName
                    name = "Minecraft Preview"
                    sourceDir = baseApk.absolutePath
                }
            }
            shadowPm.installPackage(clonedPkgInfo)

            val launcherQuery = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            @Suppress("DEPRECATION")
            shadowPm.addResolveInfoForIntent(
                launcherQuery,
                android.content.pm.ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply {
                        packageName = clonedPkgName
                        name = "com.mojang.minecraftpe.MainActivity"
                        exported = true
                        applicationInfo = clonedPkgInfo.applicationInfo
                    }
                }
            )

            val outcome = MinecraftPackageDiscovery.discoverWithDiagnostics(context, logger)
            val officialPreviewProbe = outcome.candidateProbes.firstOrNull {
                it.packageName == MinecraftPackageDiscovery.PREVIEW_PACKAGE_NAME
            }
            assertNotNull(officialPreviewProbe)
            assertFalse(officialPreviewProbe?.found == true)

            val clonedProbe = outcome.candidateProbes.firstOrNull { it.packageName == clonedPkgName }
            assertNotNull(clonedProbe)
            assertTrue(clonedProbe?.found == true)

            val clonedPkg = outcome.packages.firstOrNull { it.packageName == clonedPkgName }
            assertNotNull(clonedPkg)
            assertEquals(MinecraftEdition.PREVIEW, clonedPkg?.edition)
            assertTrue(clonedPkg?.matchesTargetVersion == true)
            assertTrue(clonedPkg?.isExactTargetReady == true)
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", originalAbis)
        }
    }

    @Test
    fun `inspectExternalApk supports apks split bundle archives for preview 1_26_70_24`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logger = LeviLogger.getInstance(context)

        val baseApk = File(tempFolder.root, "base.apk").apply {
            val manifestBytes = buildMinimalBinaryManifest(
                packageName = "com.mojang.minecraftpe.preview",
                versionName = "1.26.70.24",
                versionCode = 952607024
            )
            ZipOutputStream(FileOutputStream(this)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(manifestBytes)
                zos.closeEntry()
            }
        }

        val splitArm64Apk = File(tempFolder.root, "split_config.arm64_v8a.apk").apply {
            val libBytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 2048).also { b ->
                b[0] = 0x7F.toByte()
                b[1] = 'E'.code.toByte()
                b[2] = 'L'.code.toByte()
                b[3] = 'F'.code.toByte()
                b[4] = 2.toByte()
                b[5] = 1.toByte()
                b[6] = 1.toByte()
                b[18] = 0xB7.toByte()
            }
            ZipOutputStream(FileOutputStream(this)).use { zos ->
                zos.putNextEntry(ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
                zos.write(libBytes)
                zos.closeEntry()
            }
        }

        val apksBundle = File(tempFolder.root, "minecraft_preview_1_26_70_24.apks").apply {
            ZipOutputStream(FileOutputStream(this)).use { zos ->
                zos.putNextEntry(ZipEntry("base.apk"))
                zos.write(baseApk.readBytes())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("split_config.arm64_v8a.apk"))
                zos.write(splitArm64Apk.readBytes())
                zos.closeEntry()
            }
        }

        val inspection = MinecraftPackageDiscovery.inspectExternalApk(
            context = context,
            uri = android.net.Uri.fromFile(apksBundle),
            logger = logger
        )
        assertEquals(null, inspection.errorMessage)
        assertTrue(inspection.isSplitBundleContainer)
        assertEquals("com.mojang.minecraftpe.preview", inspection.packageName)
        assertEquals("1.26.70.24", inspection.versionName)
        assertEquals(952607024L, inspection.versionCode)
        assertEquals(MinecraftEdition.PREVIEW, inspection.edition)
        assertTrue(inspection.matchesTargetVersion)
        assertTrue(inspection.hasArm64MainLib)
        assertTrue(inspection.mainLibIsValidElf64)
        assertTrue(inspection.isTargetPreviewApk)
    }

    @Test
    fun `launch screen renders candidate probe card and mismatch pick apk button in arabic rtl`() {
        val releasePkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe",
            appLabel = "Minecraft",
            versionName = "1.26.62.1",
            versionCode = 952606201L,
            sourceDir = "/data/app/base.apk",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = false,
            isPreviewPackage = false,
            edition = MinecraftEdition.RELEASE,
            hasArm64Support = true,
            hasMainNativeLib = true,
            targetMismatchReason = "Edition is RELEASE (com.mojang.minecraftpe), expected Minecraft Preview (com.mojang.minecraftpe.preview)"
        )
        val uiState = LeviUiState(
            language = AppLanguage.AR,
            installedPackages = listOf(releasePkg),
            selectedPackage = releasePkg,
            candidateProbeResults = listOf(
                com.example.model.CandidateProbeResult(
                    packageName = "com.mojang.minecraftpe.preview",
                    found = false,
                    statusNote = "Not installed (NameNotFound)"
                ),
                com.example.model.CandidateProbeResult(
                    packageName = "com.mojang.minecraftpe",
                    found = true,
                    statusNote = "Detected (fullFlags)"
                )
            ),
            deviceAbiList = listOf("arm64-v8a"),
            androidVersionLabel = "Android 13 (API 33)"
        )

        composeTestRule.setContent {
            LeviLabTheme(language = AppLanguage.AR) {
                LaunchHealthScreen(
                    state = uiState,
                    strings = stringsFor(AppLanguage.AR),
                    onRescan = {},
                    onSelectPackage = {},
                    onLaunchVerified = {},
                    onLaunchQuickOfficial = {},
                    onLaunchDirectSystem = {},
                    onRepairLibraries = {},
                    onClearCache = {},
                    onInspectExternalApk = {},
                    onInstallExternalApk = {},
                    onDismissFailure = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_health_list").performScrollToKey("packages_section")
        composeTestRule.onNodeWithTag("candidate_probe_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("target_mismatch_guard_banner").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("mismatch_pick_apk_button").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("launch_primary_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `launch intent resolver falls back to exported package activity and collects hardware snapshot`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val hw = com.example.engine.DeviceHardwareProfiler.collect(context)
        assertTrue(hw.cpuCores >= 1)
        assertTrue(hw.glEsVersion.isNotBlank())

        val shadowPm = shadowOf(context.packageManager)
        val previewPkg = "com.mojang.minecraftpe.preview"
        val customActivityClass = "com.mojang.minecraftpe.preview.MainActivity"
        val pkgInfo = PackageInfo().apply {
            packageName = previewPkg
            versionName = "1.26.70.24"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode = 952607024L
            }
            applicationInfo = ApplicationInfo().apply {
                packageName = previewPkg
                name = "Minecraft Preview"
            }
            activities = arrayOf(
                ActivityInfo().apply {
                    packageName = previewPkg
                    name = customActivityClass
                    exported = true
                }
            )
        }
        shadowPm.installPackage(pkgInfo)

        // Register resolveActivity for explicit component even without launcher MAIN category registration
        val explicitIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = android.content.ComponentName(previewPkg, customActivityClass)
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            explicitIntent,
            android.content.pm.ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = previewPkg
                    name = customActivityClass
                    exported = true
                    applicationInfo = pkgInfo.applicationInfo
                }
            }
        )

        val modelPkg = InstalledMinecraftPackage(
            packageName = previewPkg,
            appLabel = "Minecraft Preview",
            versionName = "1.26.70.24",
            versionCode = 952607024L,
            sourceDir = "",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = true,
            edition = MinecraftEdition.PREVIEW,
            hasArm64Support = true,
            hasMainNativeLib = true
        )

        val resolvedIntent = InProcessLaunchCoordinator.buildLaunchIntentForPackage(
            context = context,
            pkg = modelPkg,
            launchMode = LaunchMode.DIRECT_NATIVE
        )
        assertNotNull(resolvedIntent)
        assertEquals(customActivityClass, resolvedIntent?.component?.className)
    }

    @Test
    fun `vault manager imports apks bundle and in-process host mounts assets and sandbox context`() {
        val originalAbis = Build.SUPPORTED_ABIS
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a"))
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val logger = LeviLogger.getInstance(context)

            val baseApk = File(tempFolder.root, "base.apk").apply {
                val manifestBytes = buildMinimalBinaryManifest(
                    packageName = "com.mojang.minecraftpe.preview",
                    versionName = "1.26.70.24",
                    versionCode = 952607024
                )
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(manifestBytes)
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("assets/resource_packs/vanilla/manifest.json"))
                    zos.write("{\"format_version\":2}".toByteArray())
                    zos.closeEntry()
                }
            }
            val splitArm64Apk = File(tempFolder.root, "split_config.arm64_v8a.apk").apply {
                val libBytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 2048).also { b ->
                    b[0] = 0x7F.toByte()
                    b[1] = 'E'.code.toByte()
                    b[2] = 'L'.code.toByte()
                    b[3] = 'F'.code.toByte()
                    b[4] = 2.toByte()
                    b[5] = 1.toByte()
                    b[6] = 1.toByte()
                    b[18] = 0xB7.toByte()
                }
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
                    zos.write(libBytes)
                    zos.closeEntry()
                }
            }
            val apksBundle = File(tempFolder.root, "preview_1_26_70_24.apks").apply {
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("base.apk"))
                    zos.write(baseApk.readBytes())
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("split_config.arm64_v8a.apk"))
                    zos.write(splitArm64Apk.readBytes())
                    zos.closeEntry()
                }
            }

            val importedOutcome = com.example.engine.LeviApkVaultManager.importApkOrBundle(
                context = context,
                uri = android.net.Uri.fromFile(apksBundle),
                logger = logger
            )
            assertEquals(null, importedOutcome.errorMessage)
            val vaultPkg = importedOutcome.importedPackage
            assertNotNull(vaultPkg)
            assertTrue(vaultPkg?.isVaultApk == true)
            assertTrue(vaultPkg?.isExactTargetReady == true)
            assertEquals("com.mojang.minecraftpe.preview", vaultPkg?.packageName)
            assertEquals("1.26.70.24", vaultPkg?.versionName)

            val loadedList = com.example.engine.LeviApkVaultManager.loadAllVaultPackages(context, logger)
            assertTrue(loadedList.any { it.vaultId == vaultPkg?.vaultId && it.isExactTargetReady })

            val libs = NativeLibraryManager.inspectExistingOrExtract(context, vaultPkg!!, logger, forceReextract = false)
            assertTrue(libs.any { it.fileName == "libminecraftpe.so" && it.isValid && it.isAArch64 })

            val preparedAssets = com.example.launcher.InProcessApkHostEngine.prepareAssetsAndResources(
                context = context,
                pkg = vaultPkg,
                logger = logger
            )
            assertTrue(preparedAssets.mountedApkCount >= 1)

            val (dexCl, libSearchPath) = com.example.launcher.InProcessApkHostEngine.buildApkClassLoader(
                context = context,
                pkg = vaultPkg,
                libraries = libs,
                parentClassLoader = context.classLoader
            )
            assertNotNull(dexCl)
            assertTrue(libSearchPath.contains("arm64-v8a"))

            val sandboxCtx = com.example.launcher.InProcessApkHostEngine.buildSandboxContext(
                baseContext = context,
                hostActivity = null,
                pkg = vaultPkg,
                preparedResources = preparedAssets,
                classLoader = dexCl,
                nativeLibDirPath = libSearchPath.substringBefore(File.pathSeparator)
            )
            assertEquals("com.mojang.minecraftpe.preview", sandboxCtx.packageName)
            assertEquals(context.packageName, sandboxCtx.opPackageName)
            assertEquals(vaultPkg.sourceDir, sandboxCtx.packageCodePath)
            assertTrue(sandboxCtx.filesDir.absolutePath.contains("mc_sandbox"))
            assertNotNull(sandboxCtx.theme)

            // Verify BootClassLoader isolation prevents LeviLab's androidx classes from shadowing APK classes
            assertTrue(
                com.example.launcher.InProcessApkHostEngine.isolatedFrameworkParentClassLoader() !== context.classLoader
            )

            // Verify SandboxPackageManager resolves Vault APK ActivityInfo & PackageInfo without NameNotFoundException
            val hostedComponent = android.content.ComponentName(
                "com.mojang.minecraftpe.preview",
                "com.mojang.minecraftpe.MainActivity"
            )
            @Suppress("DEPRECATION")
            val actInfo = sandboxCtx.packageManager.getActivityInfo(
                hostedComponent,
                android.content.pm.PackageManager.GET_META_DATA
            )
            assertEquals("minecraftpe", actInfo.metaData?.getString("android.app.lib_name"))
            @Suppress("DEPRECATION")
            val synthPkgInfo = sandboxCtx.packageManager.getPackageInfo("com.mojang.minecraftpe.preview", 0)
            assertEquals("1.26.70.24", synthPkgInfo.versionName)

            // Clean up vault entry
            assertTrue(com.example.engine.LeviApkVaultManager.deleteVaultPackage(context, vaultPkg.vaultId!!, logger))
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", originalAbis)
        }
    }

    @Test
    fun `launch screen renders vault apk with primary in-process launch enabled and external buttons hidden`() {
        val vaultPkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe.preview",
            appLabel = "Minecraft Preview (APK Vault)",
            versionName = "1.26.70.24",
            versionCode = 952607024L,
            sourceDir = "/data/user/0/app/files/mc_vault/preview/base.apk",
            splitSourceDirs = listOf("/data/user/0/app/files/mc_vault/preview/split_arm64.apk"),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = true,
            edition = MinecraftEdition.PREVIEW,
            hasArm64Support = true,
            hasMainNativeLib = true,
            isVaultApk = true,
            vaultId = "vault_preview_1_26_70_24"
        )
        val uiState = LeviUiState(
            language = AppLanguage.EN,
            installedPackages = listOf(vaultPkg),
            selectedPackage = vaultPkg,
            vaultApkCount = 1,
            deviceAbiList = listOf("arm64-v8a"),
            androidVersionLabel = "Android 13 (API 33)"
        )

        composeTestRule.setContent {
            LeviLabTheme(language = AppLanguage.EN) {
                LaunchHealthScreen(
                    state = uiState,
                    strings = stringsFor(AppLanguage.EN),
                    onRescan = {},
                    onSelectPackage = {},
                    onLaunchVerified = {},
                    onLaunchQuickOfficial = {},
                    onLaunchDirectSystem = {},
                    onRepairLibraries = {},
                    onClearCache = {},
                    onInspectExternalApk = {},
                    onImportApkToVault = {},
                    onRemoveVaultApk = {},
                    onInstallExternalApk = {},
                    onDismissFailure = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_health_list").performScrollToKey("packages_section")
        composeTestRule.onNodeWithTag("apk_vault_hero_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_apk_vault_button").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("delete_vault_vault_preview_1_26_70_24").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("launch_primary_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("launch_direct_fallback_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("launch_quick_official_button").assertDoesNotExist()
        composeTestRule.onNodeWithTag("launch_adjacent_split_button").assertDoesNotExist()
    }

    @Test
    fun `launch screen does not auto-show mismatch banner for un-selected phone release app when vault is empty`() {
        val phoneReleasePkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe",
            appLabel = "Minecraft",
            versionName = "1.26.62.1",
            versionCode = 952606201L,
            sourceDir = "/data/app/base.apk",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = false,
            isPreviewPackage = false,
            edition = MinecraftEdition.RELEASE,
            hasArm64Support = true,
            hasMainNativeLib = true,
            targetMismatchReason = "Edition is RELEASE (com.mojang.minecraftpe), expected Minecraft Preview (com.mojang.minecraftpe.preview)"
        )
        val uiState = LeviUiState(
            language = AppLanguage.AR,
            installedPackages = listOf(phoneReleasePkg),
            selectedPackage = null,
            vaultApkCount = 0,
            deviceAbiList = listOf("arm64-v8a"),
            androidVersionLabel = "Android 13 (API 33)"
        )

        composeTestRule.setContent {
            LeviLabTheme(language = AppLanguage.AR) {
                LaunchHealthScreen(
                    state = uiState,
                    strings = stringsFor(AppLanguage.AR),
                    onRescan = {},
                    onSelectPackage = {},
                    onLaunchVerified = {},
                    onLaunchQuickOfficial = {},
                    onLaunchDirectSystem = {},
                    onRepairLibraries = {},
                    onClearCache = {},
                    onInspectExternalApk = {},
                    onImportApkToVault = {},
                    onRemoveVaultApk = {},
                    onInstallExternalApk = {},
                    onDismissFailure = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_health_list").performScrollToKey("packages_section")
        composeTestRule.onNodeWithTag("apk_vault_hero_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("import_apk_vault_button").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithTag("empty_vault_prompt_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("target_mismatch_guard_banner").assertDoesNotExist()
        composeTestRule.onNodeWithTag("launch_primary_button").assertDoesNotExist()
    }

    @Test
    fun `vault manager imports xapk with config arm64 split naming and selects base apk by manifest and dex`() {
        val originalAbis = Build.SUPPORTED_ABIS
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a"))
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val logger = LeviLogger.getInstance(context)

            val mainAppApk = File(tempFolder.root, "com.mojang.minecraftpe.preview.apk").apply {
                val manifestBytes = buildMinimalBinaryManifest(
                    packageName = "com.mojang.minecraftpe.preview",
                    versionName = "1.26.70.24",
                    versionCode = 952607024
                )
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(manifestBytes)
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("classes.dex"))
                    zos.write("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
                    zos.closeEntry()
                }
            }
            val configArm64Apk = File(tempFolder.root, "config.arm64_v8a.apk").apply {
                val manifestBytes = buildMinimalBinaryManifest(
                    packageName = "com.mojang.minecraftpe.preview",
                    versionName = "1.26.70.24",
                    versionCode = 952607024
                )
                val libBytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 2048).also { b ->
                    b[0] = 0x7F.toByte()
                    b[1] = 'E'.code.toByte()
                    b[2] = 'L'.code.toByte()
                    b[3] = 'F'.code.toByte()
                    b[4] = 2.toByte()
                    b[5] = 1.toByte()
                    b[6] = 1.toByte()
                    b[18] = 0xB7.toByte()
                }
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(manifestBytes)
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
                    zos.write(libBytes)
                    zos.closeEntry()
                }
            }
            val xapkBundle = File(tempFolder.root, "preview_1_26_70_24.xapk").apply {
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("config.arm64_v8a.apk"))
                    zos.write(configArm64Apk.readBytes())
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("com.mojang.minecraftpe.preview.apk"))
                    zos.write(mainAppApk.readBytes())
                    zos.closeEntry()
                }
            }

            val outcome = com.example.engine.LeviApkVaultManager.importApkOrBundle(
                context = context,
                uri = android.net.Uri.fromFile(xapkBundle),
                logger = logger
            )
            assertEquals(null, outcome.errorMessage)
            val pkg = outcome.importedPackage
            assertNotNull(pkg)
            assertTrue(pkg?.sourceDir?.endsWith("com.mojang.minecraftpe.preview.apk") == true)
            assertTrue(pkg?.splitSourceDirs?.any { it.endsWith("config.arm64_v8a.apk") } == true)
            assertTrue(pkg?.isExactTargetReady == true)

            assertTrue(com.example.engine.LeviApkVaultManager.deleteVaultPackage(context, pkg!!.vaultId!!, logger))
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", originalAbis)
        }
    }

    @Test
    fun `vault manager imports 1_26_70_24 single apk with com_mojang_minecraftpe manifest and isolates native cache`() {
        val originalAbis = Build.SUPPORTED_ABIS
        ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a", "armeabi-v7a"))
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val logger = LeviLogger.getInstance(context)

            val singleUniversalApk = File(tempFolder.root, "mc_1_26_70_24_universal.apk").apply {
                val manifestBytes = buildMinimalBinaryManifest(
                    packageName = "com.mojang.minecraftpe",
                    versionName = "1.26.70.24",
                    versionCode = 982607024
                )
                val libBytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 2048).also { b ->
                    b[0] = 0x7F.toByte()
                    b[1] = 'E'.code.toByte()
                    b[2] = 'L'.code.toByte()
                    b[3] = 'F'.code.toByte()
                    b[4] = 2.toByte()
                    b[5] = 1.toByte()
                    b[6] = 1.toByte()
                    b[18] = 0xB7.toByte()
                }
                ZipOutputStream(FileOutputStream(this)).use { zos ->
                    zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    zos.write(manifestBytes)
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
                    zos.write(libBytes)
                    zos.closeEntry()
                }
            }

            val outcome = com.example.engine.LeviApkVaultManager.importApkOrBundle(
                context = context,
                uri = android.net.Uri.fromFile(singleUniversalApk),
                logger = logger
            )
            assertEquals(null, outcome.errorMessage)
            val pkg = outcome.importedPackage
            assertNotNull(pkg)
            assertTrue(pkg?.isVaultApk == true)
            assertEquals("com.mojang.minecraftpe", pkg?.packageName)
            assertEquals("1.26.70.24", pkg?.versionName)
            assertEquals(982607024L, pkg?.versionCode)
            assertEquals(null, pkg?.targetMismatchReason)
            assertTrue("Expected Vault 1.26.70.24 APK to be launch ready", pkg?.isExactTargetReady == true)
            assertEquals(null, InProcessLaunchCoordinator.validateTargetPackageForLaunch(pkg!!))

            val cachePath = NativeLibraryManager.targetLibDir(context, pkg, "arm64-v8a").absolutePath
            assertTrue(
                "Expected isolated vault_ cache directory, got: $cachePath",
                cachePath.contains("vault_${pkg.vaultId}")
            )

            assertTrue(com.example.engine.LeviApkVaultManager.deleteVaultPackage(context, pkg.vaultId!!, logger))
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", originalAbis)
        }
    }

    @Test
    fun `launch screen enables launch for com_mojang_minecraftpe 1_26_70_24 vault apk and collapses phone packages`() {
        val vaultUniversalPkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe",
            appLabel = "Minecraft (APK Vault)",
            versionName = "1.26.70.24",
            versionCode = 982607024L,
            sourceDir = "/data/user/0/app/files/mc_vault/vault_u1/base.apk",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = false,
            edition = MinecraftEdition.RELEASE,
            hasArm64Support = true,
            hasMainNativeLib = true,
            isVaultApk = true,
            vaultId = "vault_u1"
        )
        val phoneReleasePkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe",
            appLabel = "Minecraft",
            versionName = "1.26.62.1",
            versionCode = 952606201L,
            sourceDir = "/data/app/base.apk",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = false,
            isPreviewPackage = false,
            edition = MinecraftEdition.RELEASE,
            hasArm64Support = true,
            hasMainNativeLib = true,
            targetMismatchReason = "Version 1.26.62.1 != target 1.26.70.24"
        )
        val uiState = LeviUiState(
            language = AppLanguage.AR,
            installedPackages = listOf(vaultUniversalPkg, phoneReleasePkg),
            selectedPackage = vaultUniversalPkg,
            vaultApkCount = 1,
            candidateProbeResults = listOf(
                com.example.model.CandidateProbeResult(
                    packageName = "com.mojang.minecraftpe",
                    found = true,
                    statusNote = "Detected"
                )
            ),
            deviceAbiList = listOf("arm64-v8a"),
            androidVersionLabel = "Android 13 (API 33)"
        )

        composeTestRule.setContent {
            LeviLabTheme(language = AppLanguage.AR) {
                LaunchHealthScreen(
                    state = uiState,
                    strings = stringsFor(AppLanguage.AR),
                    onRescan = {},
                    onSelectPackage = {},
                    onLaunchVerified = {},
                    onLaunchQuickOfficial = {},
                    onLaunchDirectSystem = {},
                    onRepairLibraries = {},
                    onClearCache = {},
                    onInspectExternalApk = {},
                    onImportApkToVault = {},
                    onRemoveVaultApk = {},
                    onInstallExternalApk = {},
                    onDismissFailure = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_health_list").performScrollToKey("packages_section")
        composeTestRule.onNodeWithTag("target_comparison_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("target_mismatch_guard_banner").assertDoesNotExist()
        composeTestRule.onNodeWithTag("launch_primary_button").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithTag("candidate_probe_card").assertDoesNotExist()
        composeTestRule.onNodeWithTag("show_optional_system_packages_chip").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `sandbox package manager isolates vault 1_26_70_24 from phone-installed 1_26_62_1 same package name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logger = LeviLogger.getInstance(context)
        val shadowPm = shadowOf(context.packageManager)

        // Install an unrelated 1.26.62.1 com.mojang.minecraftpe on the simulated phone
        val phoneApk = File(tempFolder.root, "phone_1_26_62_1.apk").apply { writeText("phone") }
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.mojang.minecraftpe"
                versionName = "1.26.62.1"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    longVersionCode = 952606201L
                }
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.mojang.minecraftpe"
                    sourceDir = phoneApk.absolutePath
                }
            }
        )

        // Vault APK uses same manifest packageName "com.mojang.minecraftpe" but version 1.26.70.24
        val vaultApkFile = File(tempFolder.root, "vault_base.apk").apply {
            val manifestBytes = buildMinimalBinaryManifest(
                packageName = "com.mojang.minecraftpe",
                versionName = "1.26.70.24",
                versionCode = 982607024
            )
            ZipOutputStream(FileOutputStream(this)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(manifestBytes)
                zos.closeEntry()
            }
        }
        val vaultPkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe",
            appLabel = "Minecraft (APK Vault)",
            versionName = "1.26.70.24",
            versionCode = 982607024L,
            sourceDir = vaultApkFile.absolutePath,
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = false,
            edition = MinecraftEdition.RELEASE,
            hasArm64Support = true,
            hasMainNativeLib = true,
            isVaultApk = true,
            vaultId = "vault_com.mojang.minecraftpe_1.26.70.24_982607024"
        )
        val prepared = com.example.launcher.InProcessApkHostEngine.prepareAssetsAndResources(context, vaultPkg, logger)
        val (dexCl, libSearchPath) = com.example.launcher.InProcessApkHostEngine.buildApkClassLoader(
            context = context,
            pkg = vaultPkg,
            libraries = emptyList(),
            parentClassLoader = com.example.launcher.InProcessApkHostEngine.isolatedFrameworkParentClassLoader()
        )
        val sandboxCtx = com.example.launcher.InProcessApkHostEngine.buildSandboxContext(
            baseContext = context,
            hostActivity = null,
            pkg = vaultPkg,
            preparedResources = prepared,
            classLoader = dexCl,
            nativeLibDirPath = libSearchPath.substringBefore(File.pathSeparator)
        )

        val pkgInfo = sandboxCtx.packageManager.getPackageInfo("com.mojang.minecraftpe", 0)
        assertEquals("1.26.70.24", pkgInfo.versionName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertEquals(982607024L, pkgInfo.longVersionCode)
        }
        assertEquals(vaultApkFile.absolutePath, pkgInfo.applicationInfo?.sourceDir)
        assertEquals("com.android.vending", sandboxCtx.packageManager.getInstallerPackageName("com.mojang.minecraftpe"))

        val inflater = sandboxCtx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as android.view.LayoutInflater
        assertEquals(sandboxCtx, inflater.context)
    }

    private fun buildMinimalBinaryManifest(
        packageName: String,
        versionName: String,
        versionCode: Int
    ): ByteArray {
        val strings = listOf(
            "manifest",
            "package",
            "versionName",
            "versionCode",
            packageName,
            versionName
        )
        val poolBa = java.io.ByteArrayOutputStream()
        val strOffsets = IntArray(strings.size)
        for ((idx, s) in strings.withIndex()) {
            strOffsets[idx] = poolBa.size()
            val b = s.toByteArray(Charsets.UTF_8)
            poolBa.write(s.length)
            poolBa.write(b.size)
            poolBa.write(b)
            poolBa.write(0)
        }
        while (poolBa.size() % 4 != 0) poolBa.write(0)
        val poolData = poolBa.toByteArray()
        val poolHeaderAndIdxSize = 28 + strings.size * 4
        val poolChunkSize = poolHeaderAndIdxSize + poolData.size

        val attrs = listOf(
            Triple(1, 0x03, 4), // package
            Triple(2, 0x03, 5), // versionName
            Triple(3, 0x10, versionCode) // versionCode
        )
        val cManifestSize = 36 + attrs.size * 20
        val cManifest = java.nio.ByteBuffer.allocate(cManifestSize).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 0x0102.toShort())
            putShort(2, 16.toShort())
            putInt(4, cManifestSize)
            putInt(20, 0) // manifest tag idx
            putShort(24, 20.toShort())
            putShort(26, 20.toShort())
            putShort(28, attrs.size.toShort())
            for ((i, attr) in attrs.withIndex()) {
                val off = 36 + i * 20
                putInt(off + 4, attr.first)
                putInt(off + 8, -1)
                put(off + 15, attr.second.toByte())
                putInt(off + 16, attr.third)
            }
        }.array()

        val totalSize = 8 + poolChunkSize + cManifestSize
        val root = java.nio.ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        root.putShort(0, 0x0003.toShort())
        root.putShort(2, 8.toShort())
        root.putInt(4, totalSize)

        var pos = 8
        root.putShort(pos, 0x0001.toShort())
        root.putShort(pos + 2, 28.toShort())
        root.putInt(pos + 4, poolChunkSize)
        root.putInt(pos + 8, strings.size)
        root.putInt(pos + 16, 1 shl 8) // UTF-8
        root.putInt(pos + 20, poolHeaderAndIdxSize)
        pos += 28
        for (o in strOffsets) {
            root.putInt(pos, o)
            pos += 4
        }
        System.arraycopy(poolData, 0, root.array(), pos, poolData.size)
        pos += poolData.size
        System.arraycopy(cManifest, 0, root.array(), pos, cManifest.size)
        return root.array()
    }
}

