package com.example.engine

import com.example.model.AxmlManifestSummary
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

object AxmlManifestParser {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    fun parseFromApk(apkFile: File): AxmlManifestSummary? {
        if (!apkFile.exists() || !apkFile.canRead()) return null
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                parseBinaryXml(bytes)
            }
        }.getOrNull()
    }

    fun parseBinaryXml(data: ByteArray): AxmlManifestSummary? {
        if (data.size < 8) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val rootType = buf.getShort(0).toInt() and 0xFFFF
        if (rootType != RES_XML_TYPE) return null

        val rootHeaderSize = buf.getShort(2).toInt() and 0xFFFF
        var offset = rootHeaderSize
        var strings: List<String> = emptyList()

        var packageName: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var extractNativeLibs: Boolean? = null
        var applicationClassName: String? = null
        var applicationThemeRes = 0
        val metaData = linkedMapOf<String, String>()
        val permissions = linkedSetOf<String>()
        val activities = linkedSetOf<String>()

        while (offset + 8 <= data.size) {
            val chunkType = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > data.size) break

            when (chunkType) {
                RES_STRING_POOL_TYPE -> {
                    strings = parseStringPool(data, offset, chunkSize, buf)
                }

                RES_XML_START_ELEMENT_TYPE -> {
                    if (offset + 36 <= data.size) {
                        val nameIdx = buf.getInt(offset + 20)
                        val attrStart = buf.getShort(offset + 24).toInt() and 0xFFFF
                        val attrSize = (buf.getShort(offset + 26).toInt() and 0xFFFF).coerceAtLeast(20)
                        val attrCount = buf.getShort(offset + 28).toInt() and 0xFFFF
                        val tagName = strings.getOrNull(nameIdx).orEmpty()

                        val attrsBase = offset + 16 + attrStart
                        val attrs = mutableMapOf<String, Any?>()
                        for (i in 0 until attrCount) {
                            val aOff = attrsBase + i * attrSize
                            if (aOff + 20 > offset + chunkSize) break
                            val attrNameIdx = buf.getInt(aOff + 4)
                            val rawValueIdx = buf.getInt(aOff + 8)
                            val dataType = buf.get(aOff + 15).toInt() and 0xFF
                            val attrData = buf.getInt(aOff + 16)
                            val attrName = strings.getOrNull(attrNameIdx) ?: continue

                            val value: Any? = when {
                                dataType == TYPE_STRING && attrData in strings.indices -> strings[attrData]
                                rawValueIdx in strings.indices -> strings[rawValueIdx]
                                dataType == TYPE_INT_BOOLEAN -> attrData != 0
                                dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX -> attrData
                                else -> attrData
                            }
                            attrs[attrName] = value
                        }

                        when (tagName) {
                            "manifest" -> {
                                packageName = attrs["package"] as? String
                                versionName = attrs["versionName"]?.toString()
                                val vc = attrs["versionCode"]
                                val vcMajor = attrs["versionCodeMajor"]
                                val low = (vc as? Number)?.toLong() ?: vc?.toString()?.toLongOrNull()
                                val high = (vcMajor as? Number)?.toLong() ?: 0L
                                if (low != null) {
                                    versionCode = (high shl 32) or (low and 0xFFFFFFFFL)
                                }
                            }

                            "uses-sdk" -> {
                                minSdk = (attrs["minSdkVersion"] as? Number)?.toInt()
                                targetSdk = (attrs["targetSdkVersion"] as? Number)?.toInt()
                            }

                            "application" -> {
                                val ex = attrs["extractNativeLibs"]
                                if (ex is Boolean) extractNativeLibs = ex
                                val themeAttr = attrs["theme"]
                                if (themeAttr is Number && themeAttr.toInt() != 0) {
                                    applicationThemeRes = themeAttr.toInt()
                                }
                                (attrs["name"] as? String)?.takeIf { it.isNotBlank() }?.let { rawApp ->
                                    applicationClassName = if (rawApp.startsWith(".") && packageName != null) {
                                        "$packageName$rawApp"
                                    } else {
                                        rawApp
                                    }
                                }
                            }

                            "meta-data" -> {
                                val metaName = attrs["name"]?.toString()?.takeIf { it.isNotBlank() }
                                val metaValue = attrs["value"]?.toString() ?: attrs["resource"]?.toString()
                                if (metaName != null && metaValue != null) {
                                    metaData[metaName] = metaValue
                                }
                            }

                            "uses-permission", "uses-permission-sdk-23" -> {
                                (attrs["name"] as? String)?.takeIf { it.isNotBlank() }?.let {
                                    permissions.add(it)
                                }
                            }

                            "activity", "activity-alias" -> {
                                (attrs["name"] as? String)?.takeIf { it.isNotBlank() }?.let { rawName ->
                                    val resolved = if (rawName.startsWith(".") && packageName != null) {
                                        "$packageName$rawName"
                                    } else {
                                        rawName
                                    }
                                    activities.add(resolved)
                                }
                            }
                        }
                    }
                }
            }
            offset += chunkSize
        }

        return AxmlManifestSummary(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            extractNativeLibs = extractNativeLibs,
            applicationClassName = applicationClassName,
            applicationThemeRes = applicationThemeRes,
            metaData = metaData.toMap(),
            permissions = permissions.toList(),
            activities = activities.toList()
        )
    }

    private fun parseStringPool(
        data: ByteArray,
        offset: Int,
        chunkSize: Int,
        buf: ByteBuffer
    ): List<String> {
        if (offset + 28 > data.size) return emptyList()
        val stringCount = buf.getInt(offset + 8).coerceIn(0, 100_000)
        val flags = buf.getInt(offset + 16)
        val stringsStart = buf.getInt(offset + 20)
        val isUtf8 = (flags and (1 shl 8)) != 0

        val poolBase = offset + stringsStart
        val chunkEnd = offset + chunkSize
        val result = ArrayList<String>(stringCount)

        for (i in 0 until stringCount) {
            val idxOff = offset + 28 + i * 4
            if (idxOff + 4 > chunkEnd) break
            val strRelOff = buf.getInt(idxOff)
            var pos = poolBase + strRelOff
            if (pos < poolBase || pos >= chunkEnd) {
                result.add("")
                continue
            }

            if (isUtf8) {
                // Skip UTF-16 char len (1 or 2 bytes)
                val first = data[pos++].toInt() and 0xFF
                if ((first and 0x80) != 0 && pos < chunkEnd) pos++
                // Read UTF-8 byte len (1 or 2 bytes)
                if (pos >= chunkEnd) {
                    result.add("")
                    continue
                }
                var byteLen = data[pos++].toInt() and 0xFF
                if ((byteLen and 0x80) != 0 && pos < chunkEnd) {
                    val low = data[pos++].toInt() and 0xFF
                    byteLen = ((byteLen and 0x7F) shl 8) or low
                }
                val safeLen = byteLen.coerceIn(0, chunkEnd - pos)
                result.add(String(data, pos, safeLen, Charsets.UTF_8))
            } else {
                if (pos + 2 > chunkEnd) {
                    result.add("")
                    continue
                }
                var charLen = ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8))
                pos += 2
                if ((charLen and 0x8000) != 0 && pos + 2 <= chunkEnd) {
                    val low = ((data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8))
                    pos += 2
                    charLen = ((charLen and 0x7FFF) shl 16) or low
                }
                val byteLen = (charLen * 2).coerceIn(0, chunkEnd - pos)
                result.add(String(data, pos, byteLen, Charsets.UTF_16LE))
            }
        }
        return result
    }
}
