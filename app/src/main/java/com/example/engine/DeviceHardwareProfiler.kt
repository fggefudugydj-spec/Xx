package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.example.model.ApkAssetsSummary
import com.example.model.DeviceHardwareSnapshot
import com.example.model.GraphicsPreset
import java.io.File
import java.util.zip.ZipFile

object DeviceHardwareProfiler {

    fun collect(context: Context): DeviceHardwareSnapshot {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        runCatching { activityManager?.getMemoryInfo(memoryInfo) }

        val totalRamMb = (memoryInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
        val availRamMb = (memoryInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L)

        val availStorageMb = runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            (stat.availableBytes / (1024L * 1024L)).coerceAtLeast(0L)
        }.getOrDefault(0L)

        val cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

        val glVersion = runCatching {
            val req = activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0x30000
            val major = (req ushr 16) and 0xFFFF
            val minor = req and 0xFFFF
            "$major.$minor"
        }.getOrDefault("3.0")

        val recommended = recommendPreset(totalRamMb = totalRamMb, availRamMb = availRamMb, cpuCores = cpuCores)

        return DeviceHardwareSnapshot(
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            availableStorageMb = availStorageMb,
            cpuCores = cpuCores,
            glEsVersion = glVersion,
            recommendedPreset = recommended
        )
    }

    fun recommendPreset(
        totalRamMb: Long,
        availRamMb: Long,
        cpuCores: Int
    ): GraphicsPreset {
        return when {
            totalRamMb in 1..3200 || availRamMb in 1..900 -> GraphicsPreset.BATTERY_SAVER
            totalRamMb >= 7500 && cpuCores >= 8 -> GraphicsPreset.COMPETITIVE_120FPS
            totalRamMb >= 5500 -> GraphicsPreset.ULTRA_VISUALS
            else -> GraphicsPreset.BALANCED
        }
    }

    fun scanApkAssets(apkPaths: List<String>): ApkAssetsSummary {
        var totalAssetFiles = 0
        var shaderMaterialCount = 0
        val resourcePacks = linkedSetOf<String>()
        val behaviorPacks = linkedSetOf<String>()
        var totalAssetsBytes = 0L

        for (path in apkPaths.distinct()) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) continue
            runCatching {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (!name.startsWith("assets/")) continue

                        totalAssetFiles++
                        if (entry.size > 0L) {
                            totalAssetsBytes += entry.size
                        }

                        val lower = name.lowercase()
                        if (lower.endsWith(".material.bin")) {
                            shaderMaterialCount++
                        }

                        val parts = name.split('/')
                        if (parts.size >= 4) {
                            when (parts[1].lowercase()) {
                                "resource_packs" -> resourcePacks.add(parts[2])
                                "behavior_packs" -> behaviorPacks.add(parts[2])
                            }
                        }
                    }
                }
            }
        }

        return ApkAssetsSummary(
            totalAssetFiles = totalAssetFiles,
            shaderMaterialCount = shaderMaterialCount,
            resourcePackCount = resourcePacks.size,
            behaviorPackCount = behaviorPacks.size,
            totalAssetsBytes = totalAssetsBytes
        )
    }
}
