package com.example.tools

import android.os.Build
import com.example.core.LeviLogger
import com.example.model.LeviUiState

object DiagnosticReportBuilder {

    fun buildReport(state: LeviUiState, logger: LeviLogger): String {
        return buildString {
            appendLine("=== LeviLab Diagnostic Report ===")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Preferred Launch Mode: ${state.preferredLaunchMode.name}")
            state.hardwareSnapshot?.let { hw ->
                appendLine("Hardware: RAM=${hw.availableRamMb}/${hw.totalRamMb} MB | FreeStorage=${hw.availableStorageMb} MB | CPU=${hw.cpuCores} cores | OpenGL ES=${hw.glEsVersion} | Recommended=${hw.recommendedPreset.name}")
            }
            appendLine()

            appendLine("--- Discovered Minecraft Packages (${state.installedPackages.size}) ---")
            if (state.installedPackages.isEmpty()) {
                appendLine("None installed.")
            } else {
                state.installedPackages.forEach { pkg ->
                    appendLine(
                        "- ${pkg.packageName} [${pkg.edition.name}] | v${pkg.versionName} (${pkg.versionCode}) | " +
                            "axml=${pkg.axmlVersionName ?: "-"}(${pkg.axmlVersionCode ?: "-"}) | " +
                            "exactTargetReady=${pkg.isExactTargetReady} | versionMatch=${pkg.matchesTargetVersion} | " +
                            "arm64=${pkg.hasArm64Support} | mainLib=${pkg.hasMainNativeLib} | " +
                            "apkAbis=${pkg.apkAbis.joinToString()} | splits=${pkg.splitSourceDirs.size} | " +
                            "extractNativeLibs=${pkg.extractNativeLibs} | " +
                            "activity=${pkg.launchActivityName ?: "N/A"} | sigSha256=${pkg.signingCertSha256.ifBlank { "n/a" }}"
                    )
                    pkg.targetMismatchReason?.let { reason ->
                        appendLine("  * Mismatch Reason: $reason")
                    }
                }
            }
            if (state.candidateProbeResults.isNotEmpty()) {
                appendLine("Candidate Lookup Telemetry:")
                state.candidateProbeResults.forEach { probe ->
                    appendLine("  - ${probe.packageName}: found=${probe.found} (${probe.statusNote})")
                }
            }
            appendLine()

            appendLine("Protected World Backups Recorded: ${state.worldBackupRecords.size}")
            appendLine()

            state.cacheSummary?.let { cache ->
                appendLine("--- Library Cache Summary ---")
                appendLine("Active Cache Dir: ${cache.activeCacheDirPath}")
                appendLine("Total Cache Bytes: ${cache.totalCacheBytes} (${cache.cachedFileCount} files)")
                appendLine(
                    "CRC32 Verified: ${cache.crcVerifiedCount} | Direct Loadable: ${cache.directLoadableCount} | " +
                        "Stale Dirs Pruned: ${cache.staleDirectoriesPruned}"
                )
                appendLine()
            }

            val probe = state.probeReport
            if (probe != null) {
                appendLine("--- Probe Summary (${probe.packageInfo.packageName}) ---")
                appendLine("Timestamp: ${probe.probeTimestamp}")
                appendLine("Preferred ABI: ${probe.preferredAbi} | APK ABIs: ${probe.availableApkAbis.joinToString(", ")}")
                appendLine("Context Created: ${probe.contextCreated} ${probe.contextError ?: ""}")
                appendLine("DEX Source: ${probe.dexAnalysisSource}")
                probe.axmlManifestSummary?.let { axml ->
                    appendLine("AXML Manifest: minSdk=${axml.minSdkVersion} targetSdk=${axml.targetSdkVersion} perms=${axml.permissions.size} activities=${axml.activities.size}")
                }
                probe.apkAssetsSummary?.let { assets ->
                    appendLine("APK Assets: files=${assets.totalAssetFiles} | RenderDragonMaterials=${assets.shaderMaterialCount} | ResourcePacks=${assets.resourcePackCount} | BehaviorPacks=${assets.behaviorPackCount} | bytes=${assets.totalAssetsBytes}")
                }
                appendLine("MainActivity: ${probe.mainActivityClass ?: "N/A"}")
                appendLine("Inheritance: ${probe.inheritanceChain.joinToString(" -> ")}")
                appendLine("GameActivity Detected: ${probe.gameActivityDetected} (${probe.gameActivityClassName ?: "none"})")
                appendLine("Native Methods Count: ${probe.nativeMethods.size}")
                probe.nativeMethods.take(25).forEach { m ->
                    appendLine("  * ${m.declaringClass}.${m.methodName}${m.signature}")
                }
                appendLine("DT_NEEDED (libminecraftpe.so): ${probe.mainElfDynamicInfo?.dtNeeded?.joinToString(", ") ?: "N/A"}")
                appendLine("ELF64 Entrypoints: ${probe.mainElfDynamicInfo?.exportedEntrypoints?.joinToString(", ") ?: "none"} (jniSymbols=${probe.mainElfDynamicInfo?.jniSymbolCount ?: 0})")
                appendLine("Load Order: ${probe.dependencyResolution?.bundledLoadOrder?.joinToString(" -> ") ?: "N/A"}")
                appendLine()
            }

            appendLine("--- Native Libraries (${state.extractedLibraries.size}) ---")
            state.extractedLibraries.forEach { lib ->
                appendLine(
                    "- ${lib.fileName} | ${lib.sizeBytes} bytes | loadSource=${lib.loadSource.name} | " +
                        "elf64=${lib.isElf64} | valid=${lib.isValid} | ${lib.validationNote}"
                )
            }
            appendLine()

            if (state.launchHistory.isNotEmpty()) {
                appendLine("--- Recent Launch History (${state.launchHistory.size}) ---")
                state.launchHistory.take(10).forEach { h ->
                    appendLine(
                        "- [${h.timestamp}] ${h.packageName} v${h.versionName} | ${h.mode.name} | " +
                            "ok=${h.succeeded} | fallback=${h.usedFallback} | libSrc=${h.mainLibLoadSource} | ${h.summary}"
                    )
                }
                appendLine()
            }

            val failure = state.lastLaunchFailure
            if (failure != null) {
                appendLine("--- Last In-Process Launch Failure ---")
                appendLine("Time: ${failure.timestamp}")
                appendLine("Failed Stage: ${failure.failedStage}")
                appendLine("Exception: ${failure.exceptionClass}")
                appendLine("Reason: ${failure.reason}")
                appendLine("Stacktrace:")
                appendLine(failure.stackTracePreview)
                appendLine()
            }

            appendLine("--- Persistent Log Tail (${logger.filePath}) ---")
            val logTail = logger.readFullFileText().lines().takeLast(120).joinToString("\n")
            appendLine(logTail.ifBlank { "(empty)" })
        }
    }
}
