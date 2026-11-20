package com.example.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.core.LeviLogger
import com.example.core.LeviStrings
import com.example.engine.MinecraftPackageDiscovery
import com.example.engine.MinecraftProbeEngine
import com.example.model.ElfInspectionResult
import com.example.model.InProcessLaunchFailure
import com.example.model.InstalledMinecraftPackage
import com.example.model.LaunchHistoryEntry
import com.example.model.LaunchMode
import com.example.model.LaunchStageStatus
import com.example.model.LaunchStepState
import com.example.model.LibraryLoadSource
import com.example.model.MinecraftEdition
import com.example.model.ProbeReport
import com.example.model.ReadinessCheckItem
import com.example.model.SavedServer
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LaunchExecutionOutcome(
    val mode: LaunchMode,
    val stages: List<LaunchStageStatus>,
    val targetPackage: InstalledMinecraftPackage?,
    val probeReport: ProbeReport?,
    val loadedLibraries: List<String>,
    val mainLibLoadSource: String,
    val gameLaunchedExternally: Boolean,
    val hostedInProcess: Boolean = false,
    val hostedHandle: HostedActivityHandle? = null,
    val failure: InProcessLaunchFailure?,
    val historyEntry: LaunchHistoryEntry?
)

object InProcessLaunchCoordinator {

    suspend fun executeVerifiedLaunch(
        activityContext: Context,
        preferredPackageName: String?,
        preferredVaultId: String? = null,
        verifyCacheBeforeLaunch: Boolean,
        launchMode: LaunchMode = LaunchMode.DIRECT_NATIVE,
        strings: LeviStrings,
        logger: LeviLogger,
        onStageUpdate: (List<LaunchStageStatus>) -> Unit
    ): LaunchExecutionOutcome {
        val stages = mutableListOf(
            LaunchStageStatus("A", strings.stageALabel, LaunchStepState.RUNNING, "..."),
            LaunchStageStatus("B", strings.stageBLabel, LaunchStepState.PENDING, ""),
            LaunchStageStatus("C", strings.stageCLabel, LaunchStepState.PENDING, ""),
            LaunchStageStatus("D", strings.stageDLabel, LaunchStepState.PENDING, ""),
            LaunchStageStatus("E", strings.stageELabel, LaunchStepState.PENDING, "")
        )
        withContext(Dispatchers.Main.immediate) {
            onStageUpdate(stages.toList())
        }

        fun updateStageImmediate(index: Int, state: LaunchStepState, detail: String) {
            stages[index] = stages[index].copy(state = state, detail = detail)
            onStageUpdate(stages.toList())
        }

        suspend fun updateStage(index: Int, state: LaunchStepState, detail: String) {
            stages[index] = stages[index].copy(state = state, detail = detail)
            val snapshot = stages.toList()
            withContext(Dispatchers.Main.immediate) {
                onStageUpdate(snapshot)
            }
        }

        suspend fun failLaunch(
            stageIndex: Int,
            stageTitle: String,
            reason: String,
            pkg: InstalledMinecraftPackage?,
            probe: ProbeReport? = null,
            mainSource: String = LibraryLoadSource.MISSING.name,
            throwable: Throwable? = null,
            fallbackAvailable: Boolean = false
        ): LaunchExecutionOutcome {
            updateStage(stageIndex, LaunchStepState.FAILED, reason)
            logger.error("STAGE_${stages[stageIndex].stageKey}", "$stageTitle blocked/failed: $reason", throwable)
            val failure = buildFailure(
                stageName = stageTitle,
                reason = reason,
                throwable = throwable,
                fallbackAvailable = fallbackAvailable
            )
            LaunchDiagnosticsBus.publishFailure(failure)
            return LaunchExecutionOutcome(
                mode = launchMode,
                stages = stages.toList(),
                targetPackage = pkg,
                probeReport = probe,
                loadedLibraries = emptyList(),
                mainLibLoadSource = mainSource,
                gameLaunchedExternally = false,
                hostedInProcess = false,
                hostedHandle = null,
                failure = failure,
                historyEntry = buildHistoryEntry(
                    pkg = pkg,
                    mode = launchMode,
                    succeeded = false,
                    usedFallback = false,
                    mainSource = mainSource,
                    summary = reason
                )
            )
        }

        // Stage A: Merge LeviLab APK Vault + installed Minecraft packages & enforce Preview 1.26.70.24 + ARM64
        val (vaultPackages, installedPackages) = withContext(Dispatchers.IO) {
            val vault = com.example.engine.LeviApkVaultManager.loadAllVaultPackages(activityContext, logger)
            val installed = MinecraftPackageDiscovery.discoverInstalledPackages(activityContext, logger)
            vault to installed
        }
        val allCandidates = vaultPackages + installedPackages

        val targetPkg = when {
            !preferredVaultId.isNullOrBlank() ->
                vaultPackages.firstOrNull { it.vaultId == preferredVaultId }
            !preferredPackageName.isNullOrBlank() ->
                allCandidates.firstOrNull { it.packageName == preferredPackageName }
            else -> null
        } ?: MinecraftPackageDiscovery.selectBestPackage(allCandidates)

        if (targetPkg == null) {
            return failLaunch(
                stageIndex = 0,
                stageTitle = strings.stageALabel,
                reason = "No Minecraft APK imported in Vault or installed among: ${MinecraftPackageDiscovery.CANDIDATE_PACKAGES.joinToString(", ")}",
                pkg = null
            )
        }

        val targetValidationError = validateTargetPackageForLaunch(targetPkg)
        if (targetValidationError != null) {
            return failLaunch(
                stageIndex = 0,
                stageTitle = strings.stageALabel,
                reason = targetValidationError,
                pkg = targetPkg
            )
        }

        val sourceLabel = if (targetPkg.isVaultApk) "APK Vault" else if (targetPkg.splitSourceDirs.isNotEmpty()) {
            "SplitAPK(${targetPkg.splitSourceDirs.size})"
        } else {
            "SingleAPK"
        }
        updateStage(
            0,
            LaunchStepState.PASSED,
            "${targetPkg.packageName} [PREVIEW] v${targetPkg.versionName} (code=${targetPkg.versionCode}) • ARM64 • $sourceLabel"
        )

        // Stage B & C: Verify libminecraftpe.so (ELF64 AArch64 > 1MB), Library Cache & DT_NEEDED
        var probeReport: ProbeReport? = null
        var mainLibSource = LibraryLoadSource.NATIVE_DIR.name

        if (verifyCacheBeforeLaunch || launchMode == LaunchMode.DIRECT_NATIVE) {
            updateStage(1, LaunchStepState.RUNNING, "Verifying libminecraftpe.so (ARM64 ELF64) & Library Cache...")
            val probe = withContext(Dispatchers.IO) {
                MinecraftProbeEngine.runProbe(
                    context = activityContext.applicationContext,
                    pkg = targetPkg,
                    logger = logger,
                    forceReextract = false
                )
            }
            probeReport = probe
            LaunchDiagnosticsBus.publishProbe(probe)

            val mainLib = probe.discoveredLibraries.firstOrNull {
                it.fileName == "libminecraftpe.so" && it.isValid && it.isAArch64
            }
            if (mainLib == null) {
                val note = probe.discoveredLibraries
                    .firstOrNull { it.fileName == "libminecraftpe.so" }
                    ?.validationNote ?: "Valid ARM64 libminecraftpe.so not found in Base/Split APK or nativeLibraryDir"
                return failLaunch(
                    stageIndex = 1,
                    stageTitle = strings.stageBLabel,
                    reason = note,
                    pkg = targetPkg,
                    probe = probe
                )
            }
            mainLibSource = mainLib.loadSource.name
            updateStage(
                1,
                LaunchStepState.PASSED,
                "libminecraftpe.so (${mainLib.sizeBytes / (1024 * 1024)} MB, ${mainLib.machineName}) • ${mainLib.loadSource.name}"
            )

            updateStage(2, LaunchStepState.RUNNING, "Checking ELF64 DT_NEEDED dependency graph...")
            val depRes = probe.dependencyResolution
            if (depRes != null && depRes.missingDependencies.isNotEmpty()) {
                val missing = "Missing non-system native dependencies: ${depRes.missingDependencies.joinToString(", ")}"
                return failLaunch(
                    stageIndex = 2,
                    stageTitle = strings.stageCLabel,
                    reason = missing,
                    pkg = targetPkg,
                    probe = probe,
                    mainSource = mainLibSource
                )
            }
            if (depRes != null) {
                updateStage(
                    2,
                    LaunchStepState.PASSED,
                    "${depRes.bundledLoadOrder.size} bundled ARM64 ELF64 libs verified (system=${depRes.systemDependencies.size}, missing=0)"
                )
            } else {
                updateStage(2, LaunchStepState.PASSED, "ELF64 AArch64 header & APK linker path verified")
            }
        } else {
            updateStage(1, LaunchStepState.PASSED, "Quick launch: APK arm64-v8a libminecraftpe.so detected")
            updateStage(2, LaunchStepState.PASSED, "Using installed package Android 13 linker namespace")
        }

        // Stage D & E: In-Process APK Host inside LeviLab (DIRECT_NATIVE) vs Official External Activity Intent
        if (launchMode == LaunchMode.DIRECT_NATIVE && activityContext is Activity) {
            updateStage(3, LaunchStepState.RUNNING, "Mounting APK AssetManager, DexClassLoader & DT_NEEDED native bridge...")
            val libs = probeReport?.discoveredLibraries.orEmpty()
            return try {
                val hostedHandle = withContext(Dispatchers.Main.immediate) {
                    InProcessApkHostEngine.launchHostedMainActivity(
                        hostActivity = activityContext,
                        pkg = targetPkg,
                        libraries = libs,
                        logger = logger,
                        onBeforeActivityOnCreate = { mountedCount, loadedLibsCount, actName ->
                            updateStageImmediate(
                                3,
                                LaunchStepState.PASSED,
                                "Mounted $mountedCount APK assets & loaded $loadedLibsCount ARM64 .so libs"
                            )
                            updateStageImmediate(4, LaunchStepState.RUNNING, "Starting $actName inside LeviLab...")
                        }
                    )
                }
                val msg = "Running ${hostedHandle.activityClassName} (${targetPkg.packageName} v${targetPkg.versionName}) in-process inside LeviLab"
                updateStage(4, LaunchStepState.PASSED, msg)
                logger.success("STAGE_E", msg)
                LaunchDiagnosticsBus.publishFailure(null)
                LaunchExecutionOutcome(
                    mode = launchMode,
                    stages = stages.toList(),
                    targetPackage = targetPkg,
                    probeReport = probeReport,
                    loadedLibraries = hostedHandle.loadedNativeLibraries,
                    mainLibLoadSource = mainLibSource,
                    gameLaunchedExternally = false,
                    hostedInProcess = true,
                    hostedHandle = hostedHandle,
                    failure = null,
                    historyEntry = buildHistoryEntry(
                        pkg = targetPkg,
                        mode = launchMode,
                        succeeded = true,
                        usedFallback = false,
                        mainSource = mainLibSource,
                        summary = msg
                    )
                )
            } catch (t: Throwable) {
                val rootCause = generateSequence(t) { it.cause }.lastOrNull() ?: t
                failLaunch(
                    stageIndex = 4,
                    stageTitle = strings.stageELabel,
                    reason = "In-process APK host error: ${rootCause.javaClass.simpleName}: ${rootCause.message}",
                    pkg = targetPkg,
                    probe = probeReport,
                    mainSource = mainLibSource,
                    throwable = rootCause,
                    fallbackAvailable = !targetPkg.isVaultApk
                )
            }
        }

        if (targetPkg.isVaultApk) {
            return failLaunch(
                stageIndex = 3,
                stageTitle = strings.stageDLabel,
                reason = "Vault APK cannot be launched via external package Intent; select DIRECT_NATIVE (In-Process APK Host).",
                pkg = targetPkg,
                probe = probeReport,
                mainSource = mainLibSource
            )
        }

        updateStage(3, LaunchStepState.RUNNING, "Resolving official Android Activity Intent (${launchMode.name})...")
        val launchIntent = withContext(Dispatchers.IO) {
            buildLaunchIntentForPackage(activityContext, targetPkg, launchMode)
        }
        if (launchIntent == null) {
            return failLaunch(
                stageIndex = 3,
                stageTitle = strings.stageDLabel,
                reason = "No exported launchable Activity resolved for ${targetPkg.packageName}",
                pkg = targetPkg,
                probe = probeReport,
                mainSource = mainLibSource
            )
        }
        updateStage(
            3,
            LaunchStepState.PASSED,
            "Official Intent (${launchMode.name}) -> ${launchIntent.component?.className ?: targetPkg.packageName}"
        )

        updateStage(4, LaunchStepState.RUNNING, "Launching ${targetPkg.packageName} v${targetPkg.versionName}...")
        return try {
            withContext(Dispatchers.Main.immediate) {
                if (launchMode == LaunchMode.ADJACENT_SPLIT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    activityContext.startActivity(launchIntent, ActivityOptions.makeBasic().toBundle())
                } else {
                    activityContext.startActivity(launchIntent)
                }
            }
            val msg = "Launched ${targetPkg.packageName} v${targetPkg.versionName} [${launchMode.name}] (${launchIntent.component?.shortClassName ?: "MainActivity"})"
            logger.success("STAGE_E", msg)
            updateStage(4, LaunchStepState.PASSED, msg)
            val history = buildHistoryEntry(
                pkg = targetPkg,
                mode = launchMode,
                succeeded = true,
                usedFallback = false,
                mainSource = mainLibSource,
                summary = msg
            )
            LaunchDiagnosticsBus.publishFailure(null)
            LaunchExecutionOutcome(
                mode = launchMode,
                stages = stages.toList(),
                targetPackage = targetPkg,
                probeReport = probeReport,
                loadedLibraries = probeReport?.discoveredLibraries?.filter { it.isValid }?.map { it.fileName }.orEmpty(),
                mainLibLoadSource = mainLibSource,
                gameLaunchedExternally = true,
                hostedInProcess = false,
                hostedHandle = null,
                failure = null,
                historyEntry = history
            )
        } catch (t: Throwable) {
            failLaunch(
                stageIndex = 4,
                stageTitle = strings.stageELabel,
                reason = "startActivity failed: ${t.javaClass.simpleName}: ${t.message}",
                pkg = targetPkg,
                probe = probeReport,
                mainSource = mainLibSource,
                throwable = t
            )
        }
    }

    fun validateTargetPackageForLaunch(pkg: InstalledMinecraftPackage): String? {
        if (!pkg.isVaultApk && (pkg.edition != MinecraftEdition.PREVIEW || !pkg.isPreviewPackage)) {
            return "Target mismatch: '${pkg.packageName}' is ${pkg.edition.name} (v${pkg.versionName}), NOT Minecraft Preview (${MinecraftPackageDiscovery.PREVIEW_PACKAGE_NAME} v${MinecraftPackageDiscovery.TARGET_VERSION_NAME}). Launch blocked to prevent running the wrong edition."
        }
        if (!pkg.matchesTargetVersion) {
            val subject = if (pkg.isVaultApk) "selected APK" else "installed Minecraft"
            return "Version mismatch: $subject is v${pkg.versionName} (code=${pkg.versionCode}), but target is v${MinecraftPackageDiscovery.TARGET_VERSION_NAME}. Launch blocked."
        }
        if (!pkg.versionCodeValid) {
            return "Invalid versionCode (${pkg.versionCode}) for ${pkg.packageName} v${pkg.versionName}."
        }
        if (!pkg.hasArm64Support) {
            return "ABI mismatch: ARM64 (arm64-v8a) not satisfied (device=${pkg.supportedAbis.joinToString()}, apk=${pkg.apkAbis.joinToString()})."
        }
        if (!pkg.hasMainNativeLib) {
            return "Native library check failed: valid ARM64 ELF64 libminecraftpe.so (>1MB) missing in Base/Split APK or nativeLibraryDir."
        }
        return null
    }

    fun buildLaunchIntentForPackage(
        context: Context,
        pkg: InstalledMinecraftPackage,
        launchMode: LaunchMode = LaunchMode.DIRECT_NATIVE
    ): Intent? {
        val pm = context.packageManager
        val extraFlags = if (launchMode == LaunchMode.ADJACENT_SPLIT) {
            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        } else {
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }

        val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(extraFlags)
            return launchIntent
        }

        val exportedPackageActivities = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    pkg.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg.packageName, PackageManager.GET_ACTIVITIES)
            }
            info.activities
                ?.filter { it.exported }
                ?.sortedByDescending {
                    it.name.endsWith("MainActivity") || it.name.endsWith("GameActivity")
                }
                ?.map { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())

        val axmlActivities = if (pkg.sourceDir.isNotBlank()) {
            com.example.engine.AxmlManifestParser.parseFromApk(java.io.File(pkg.sourceDir))
                ?.activities
                .orEmpty()
        } else {
            emptyList()
        }

        val candidateActivities = buildList {
            pkg.launchActivityName?.takeIf { it.isNotBlank() }?.let { add(it) }
            MinecraftPackageDiscovery.resolveLaunchActivityName(pm, pkg.packageName)
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it) }
            addAll(exportedPackageActivities)
            addAll(axmlActivities)
            add("${pkg.packageName}.MainActivity")
            add("com.mojang.minecraftpe.MainActivity")
            add("com.mojang.minecraftpe.preview.MainActivity")
            add("com.mojang.minecraftpe.GameActivity")
            add("org.levimc.LauncherActivity")
        }.distinct()

        for (activityName in candidateActivities) {
            val candidate = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(pkg.packageName, activityName)
                addFlags(extraFlags)
            }
            val resolved = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.resolveActivity(candidate, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.resolveActivity(candidate, 0)
                }
            }.getOrNull()
            if (resolved?.activityInfo != null && resolved.activityInfo.exported) {
                return candidate
            }
        }

        // Last-resort official deep-link fallback if APK stripped CATEGORY_LAUNCHER
        val deepLinkCandidate = Intent(Intent.ACTION_VIEW, Uri.parse("minecraft://")).apply {
            setPackage(pkg.packageName)
            addFlags(extraFlags)
        }
        val deepResolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(deepLinkCandidate, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(deepLinkCandidate, 0)
            }
        }.getOrNull()
        if (deepResolved?.activityInfo != null && deepResolved.activityInfo.exported) {
            return deepLinkCandidate
        }

        return null
    }

    fun launchInstalledPackage(
        context: Context,
        pkg: InstalledMinecraftPackage,
        logger: LeviLogger,
        launchMode: LaunchMode = LaunchMode.QUICK_OFFICIAL
    ): Boolean {
        val validationError = validateTargetPackageForLaunch(pkg)
        if (validationError != null) {
            logger.error("STAGE_E", "Blocked direct launch: $validationError")
            LaunchDiagnosticsBus.publishFailure(
                buildFailure(
                    stageName = "Target Version & Edition Guard",
                    reason = validationError,
                    throwable = null,
                    fallbackAvailable = false
                )
            )
            return false
        }

        if (pkg.isVaultApk) {
            val msg = "Imported Vault APKs launch inside LeviLab In-Process Host (DIRECT_NATIVE), not via external package Intent."
            logger.warn("STAGE_E", msg)
            LaunchDiagnosticsBus.publishFailure(
                buildFailure("APK Vault Mode", msg, null, false)
            )
            return false
        }

        val intent = buildLaunchIntentForPackage(context, pkg, launchMode)
        return if (intent != null) {
            try {
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                logger.success(
                    "STAGE_E",
                    "Launching verified ${pkg.packageName} v${pkg.versionName} [${launchMode.name}] (${intent.component?.className ?: "default"})"
                )
                if (launchMode == LaunchMode.ADJACENT_SPLIT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.startActivity(intent, ActivityOptions.makeBasic().toBundle())
                } else {
                    context.startActivity(intent)
                }
                LaunchDiagnosticsBus.publishFailure(null)
                true
            } catch (t: Throwable) {
                logger.error("STAGE_E", "startActivity failed for ${pkg.packageName}: ${t.message}", t)
                LaunchDiagnosticsBus.publishFailure(
                    buildFailure("Official Activity Launch", "${t.javaClass.simpleName}: ${t.message}", t, false)
                )
                false
            }
        } else {
            val msg = "Could not resolve official launch intent for ${pkg.packageName}"
            logger.error("STAGE_E", msg)
            LaunchDiagnosticsBus.publishFailure(
                buildFailure("Official Activity Launch", msg, null, false)
            )
            false
        }
    }

    fun launchServerDeepLink(
        context: Context,
        pkg: InstalledMinecraftPackage?,
        server: SavedServer,
        logger: LeviLogger
    ): Boolean {
        if (pkg == null) {
            logger.error("TOOLS_PING", "Cannot open server ${server.host}: No Minecraft Preview package selected")
            return false
        }
        val validationError = validateTargetPackageForLaunch(pkg)
        if (validationError != null) {
            logger.error("TOOLS_PING", "Blocked server deep link: $validationError")
            LaunchDiagnosticsBus.publishFailure(
                buildFailure("Target Version & Edition Guard", validationError, null, false)
            )
            return false
        }
        val payload = Uri.encode("${server.label}|${server.host}:${server.port}")
        val uri = Uri.parse("minecraft://?addExternalServer=$payload")
        val deepIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(pkg.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(deepIntent)
            logger.success("TOOLS_PING", "Dispatched verified deep link for ${server.host}:${server.port} to ${pkg.packageName} v${pkg.versionName}")
            true
        } catch (t: Throwable) {
            logger.error("TOOLS_PING", "minecraft:// deep link failed: ${t.message}", t)
            false
        }
    }

    fun evaluateReadinessChecklist(
        pkg: InstalledMinecraftPackage?,
        libraries: List<ElfInspectionResult>
    ): List<ReadinessCheckItem> {
        val deviceArm64 = "arm64-v8a" in (Build.SUPPORTED_ABIS?.toList().orEmpty())
        val mainLib = libraries.firstOrNull { it.fileName == "libminecraftpe.so" }
        val androidOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

        return listOf(
            ReadinessCheckItem(
                id = "edition",
                label = if (pkg?.isVaultApk == true) "APK Vault Mode" else "Preview Package",
                passed = pkg != null && (pkg.isVaultApk || (pkg.edition == MinecraftEdition.PREVIEW && pkg.isPreviewPackage)),
                detail = pkg?.let {
                    if (it.isVaultApk) "${it.packageName} [IN-PROCESS APK]" else "${it.packageName} [${it.edition.name}]"
                } ?: "Import APK"
            ),
            ReadinessCheckItem(
                id = "version",
                label = "Version 1.26.70.24",
                passed = pkg != null && pkg.matchesTargetVersion && pkg.versionCodeValid,
                detail = pkg?.let { "v${it.versionName} (code ${it.versionCode})" } ?: "Missing"
            ),
            ReadinessCheckItem(
                id = "abi",
                label = "ARM64 (arm64-v8a)",
                passed = deviceArm64 && (pkg?.hasArm64Support == true),
                detail = if (pkg != null) "Device=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} | APK=${pkg.apkAbis.joinToString()}" else "Device=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
            ),
            ReadinessCheckItem(
                id = "mainlib",
                label = "libminecraftpe.so",
                passed = mainLib?.isValid == true && mainLib.isAArch64,
                detail = mainLib?.let { "${it.machineName} • ${it.loadSource.name}" } ?: (if (pkg?.hasMainNativeLib == true) "Detected in APK" else "Pending scan")
            ),
            ReadinessCheckItem(
                id = "splits",
                label = "APK / Split Integrity",
                passed = pkg != null && pkg.sourceDir.isNotBlank(),
                detail = pkg?.let {
                    if (it.splitSourceDirs.isNotEmpty()) "${it.splitSourceDirs.size} Split APKs" else "Single APK"
                } ?: "N/A"
            ),
            ReadinessCheckItem(
                id = "android13",
                label = if (pkg?.isVaultApk == true) "In-Process APK Host" else "Android 13 Safe Launch",
                passed = androidOk && pkg?.launchActivityName != null,
                detail = "API ${Build.VERSION.SDK_INT} • ${pkg?.launchActivityName?.substringAfterLast('.') ?: "No activity"}"
            )
        )
    }

    private fun buildFailure(
        stageName: String,
        reason: String,
        throwable: Throwable?,
        fallbackAvailable: Boolean
    ): InProcessLaunchFailure {
        val trace = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            sw.toString().lines().take(12).joinToString("\n")
        } else {
            reason
        }
        val exClass = throwable?.javaClass?.name ?: "TargetValidationGuard"
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return InProcessLaunchFailure(
            failedStage = stageName,
            reason = reason,
            exceptionClass = exClass,
            stackTracePreview = trace,
            fallbackAvailable = fallbackAvailable,
            fallbackTriggered = false,
            timestamp = ts
        )
    }

    fun buildHistoryEntry(
        pkg: InstalledMinecraftPackage?,
        mode: LaunchMode,
        succeeded: Boolean,
        usedFallback: Boolean,
        mainSource: String,
        summary: String
    ): LaunchHistoryEntry {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return LaunchHistoryEntry(
            id = UUID.randomUUID().toString(),
            timestamp = ts,
            packageName = pkg?.packageName ?: "none",
            versionName = pkg?.versionName ?: "unknown",
            mode = mode,
            succeeded = succeeded,
            usedFallback = usedFallback,
            mainLibLoadSource = mainSource,
            summary = summary
        )
    }
}
