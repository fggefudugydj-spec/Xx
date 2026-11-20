package com.example.engine

import com.example.model.NativeMethodDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

data class DexProbeResult(
    val dexFilesCount: Int,
    val totalClassesParsed: Int,
    val inheritanceChain: List<String>,
    val gameActivityDetected: Boolean,
    val gameActivityClassName: String?,
    val nativeMethods: List<NativeMethodDescriptor>,
    val matchedClasses: List<String>
)

object DexInspector {

    private const val ACC_NATIVE = 0x0100
    private const val NO_INDEX = -1

    private val targetClassPrefixes = listOf(
        "com.mojang.minecraftpe.",
        "com.google.androidgamesdk.GameActivity",
        "org.fmod."
    )

    private class DexCursor(val data: ByteArray, var pos: Int) {
        fun readUleb128(): Int {
            var result = 0
            var shift = 0
            while (pos < data.size && shift <= 28) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result
        }
    }

    fun inspectApks(
        apkPaths: List<String>,
        launchActivityClass: String?,
        maxDexBytes: Long = 28L * 1024L * 1024L
    ): DexProbeResult {
        val superclassMap = linkedMapOf<String, String>()
        val nativeMethods = mutableListOf<NativeMethodDescriptor>()
        val matchedClasses = linkedSetOf<String>()
        var dexFilesCount = 0
        var totalClassesParsed = 0

        for (apkPath in apkPaths.distinct()) {
            val apkFile = File(apkPath)
            if (!apkFile.exists() || !apkFile.canRead()) continue
            runCatching {
                ZipFile(apkFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (!name.startsWith("classes") || !name.endsWith(".dex") || '/' in name) continue
                        if (entry.size > maxDexBytes) continue

                        val dexBytes = zip.getInputStream(entry).use { it.readBytes() }
                        val parsed = parseSingleDex(dexBytes)
                        if (parsed != null) {
                            dexFilesCount++
                            totalClassesParsed += parsed.classCount
                            superclassMap.putAll(parsed.superclasses)
                            nativeMethods.addAll(parsed.nativeMethods)
                            matchedClasses.addAll(parsed.matchedClasses)
                        }
                    }
                }
            }
        }

        val startActivity = launchActivityClass?.takeIf { it.isNotBlank() }
            ?: if ("com.mojang.minecraftpe.MainActivity" in superclassMap) {
                "com.mojang.minecraftpe.MainActivity"
            } else {
                superclassMap.keys.firstOrNull { it.endsWith(".MainActivity") }
            }

        val chain = mutableListOf<String>()
        var current: String? = startActivity
        val visited = mutableSetOf<String>()
        while (current != null && current !in visited && chain.size < 16) {
            visited.add(current)
            chain.add(current)
            current = superclassMap[current]
        }

        val gameActivityClass = chain.firstOrNull {
            it.endsWith(".GameActivity") || it == "com.google.androidgamesdk.GameActivity"
        } ?: matchedClasses.firstOrNull {
            it.endsWith(".GameActivity") || it == "com.google.androidgamesdk.GameActivity"
        }

        return DexProbeResult(
            dexFilesCount = dexFilesCount,
            totalClassesParsed = totalClassesParsed,
            inheritanceChain = chain,
            gameActivityDetected = gameActivityClass != null,
            gameActivityClassName = gameActivityClass,
            nativeMethods = nativeMethods.distinctBy { "${it.declaringClass}#${it.methodName}${it.signature}" },
            matchedClasses = matchedClasses.toList()
        )
    }

    internal data class SingleDexParsed(
        val classCount: Int,
        val superclasses: Map<String, String>,
        val nativeMethods: List<NativeMethodDescriptor>,
        val matchedClasses: List<String>
    )

    internal fun parseSingleDex(dex: ByteArray): SingleDexParsed? {
        if (dex.size < 112) return null
        // Magic: "dex\n03X\0"
        if (dex[0] != 'd'.code.toByte() ||
            dex[1] != 'e'.code.toByte() ||
            dex[2] != 'x'.code.toByte() ||
            dex[3] != '\n'.code.toByte() ||
            dex[7] != 0.toByte()
        ) {
            return null
        }

        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(56).coerceAtLeast(0)
        val stringIdsOff = buf.getInt(60)
        val typeIdsSize = buf.getInt(64).coerceAtLeast(0)
        val typeIdsOff = buf.getInt(68)
        val protoIdsSize = buf.getInt(72).coerceAtLeast(0)
        val protoIdsOff = buf.getInt(76)
        val methodIdsSize = buf.getInt(88).coerceAtLeast(0)
        val methodIdsOff = buf.getInt(92)
        val classDefsSize = buf.getInt(96).coerceAtLeast(0)
        val classDefsOff = buf.getInt(100)

        if (stringIdsOff <= 0 || typeIdsOff <= 0 || classDefsOff <= 0) return null
        if (classDefsOff + classDefsSize.toLong() * 32L > dex.size) return null

        val stringCache = HashMap<Int, String>(2048)
        fun readString(idx: Int): String? {
            if (idx < 0 || idx >= stringIdsSize) return null
            stringCache[idx]?.let { return it }
            val entryOff = stringIdsOff + idx * 4
            if (entryOff + 4 > dex.size) return null
            val dataOff = buf.getInt(entryOff)
            if (dataOff < 0 || dataOff >= dex.size) return null
            val cursor = DexCursor(dex, dataOff)
            cursor.readUleb128() // utf16_size
            val start = cursor.pos
            var end = start
            while (end < dex.size && dex[end] != 0.toByte()) {
                end++
            }
            val str = decodeMutf8(dex, start, end - start)
            stringCache[idx] = str
            return str
        }

        fun readTypeDescriptor(typeIdx: Int): String? {
            if (typeIdx < 0 || typeIdx >= typeIdsSize) return null
            val off = typeIdsOff + typeIdx * 4
            if (off + 4 > dex.size) return null
            val strIdx = buf.getInt(off)
            return readString(strIdx)
        }

        fun readClassName(typeIdx: Int): String? {
            val desc = readTypeDescriptor(typeIdx) ?: return null
            return descriptorToJavaName(desc)
        }

        fun readProtoSignature(protoIdx: Int): String {
            if (protoIdx < 0 || protoIdx >= protoIdsSize || protoIdsOff <= 0) return "() -> void"
            val off = protoIdsOff + protoIdx * 12
            if (off + 12 > dex.size) return "() -> void"
            val retTypeIdx = buf.getInt(off + 4)
            val paramsOff = buf.getInt(off + 8)
            val retName = descriptorToJavaName(readTypeDescriptor(retTypeIdx) ?: "V")
                .substringAfterLast('.')
            if (paramsOff <= 0 || paramsOff + 4 > dex.size) {
                return "() -> $retName"
            }
            val paramCount = buf.getInt(paramsOff).coerceIn(0, 32)
            val params = buildList {
                for (p in 0 until paramCount) {
                    val pOff = paramsOff + 4 + p * 2
                    if (pOff + 2 > dex.size) break
                    val pTypeIdx = buf.getShort(pOff).toInt() and 0xFFFF
                    val pName = descriptorToJavaName(readTypeDescriptor(pTypeIdx) ?: "?")
                        .substringAfterLast('.')
                    add(pName)
                }
            }
            return "(${params.joinToString(", ")}) -> $retName"
        }

        fun readMethodNameAndProto(methodIdx: Int): Pair<String, String>? {
            if (methodIdx < 0 || methodIdx >= methodIdsSize || methodIdsOff <= 0) return null
            val off = methodIdsOff + methodIdx * 8
            if (off + 8 > dex.size) return null
            val protoIdx = buf.getShort(off + 2).toInt() and 0xFFFF
            val nameIdx = buf.getInt(off + 4)
            val mName = readString(nameIdx) ?: return null
            val sig = readProtoSignature(protoIdx)
            return mName to sig
        }

        val superclasses = linkedMapOf<String, String>()
        val nativeMethods = mutableListOf<NativeMethodDescriptor>()
        val matchedClasses = mutableListOf<String>()

        for (i in 0 until classDefsSize) {
            val defOff = classDefsOff + i * 32
            if (defOff + 32 > dex.size) break
            val classIdx = buf.getInt(defOff)
            val superIdx = buf.getInt(defOff + 8)
            val classDataOff = buf.getInt(defOff + 24)

            val className = readClassName(classIdx) ?: continue
            if (superIdx != NO_INDEX) {
                readClassName(superIdx)?.let { superName ->
                    superclasses[className] = superName
                }
            }

            val isTargetClass = targetClassPrefixes.any { className.startsWith(it) || className == it }
            if (!isTargetClass) continue

            matchedClasses.add(className)
            if (classDataOff <= 0 || classDataOff >= dex.size) continue

            val cursor = DexCursor(dex, classDataOff)
            val staticFieldsSize = cursor.readUleb128()
            val instanceFieldsSize = cursor.readUleb128()
            val directMethodsSize = cursor.readUleb128()
            val virtualMethodsSize = cursor.readUleb128()

            // Skip static & instance encoded_field (2 uleb128s each)
            repeat(staticFieldsSize + instanceFieldsSize) {
                cursor.readUleb128()
                cursor.readUleb128()
            }

            fun readEncodedMethods(count: Int) {
                var methodIdx = 0
                repeat(count) {
                    val diff = cursor.readUleb128()
                    val accessFlags = cursor.readUleb128()
                    cursor.readUleb128() // code_off
                    methodIdx += diff
                    if ((accessFlags and ACC_NATIVE) != 0) {
                        readMethodNameAndProto(methodIdx)?.let { (mName, sig) ->
                            nativeMethods.add(
                                NativeMethodDescriptor(
                                    declaringClass = className,
                                    methodName = mName,
                                    signature = sig
                                )
                            )
                        }
                    }
                }
            }

            readEncodedMethods(directMethodsSize)
            readEncodedMethods(virtualMethodsSize)
        }

        return SingleDexParsed(
            classCount = classDefsSize,
            superclasses = superclasses,
            nativeMethods = nativeMethods,
            matchedClasses = matchedClasses
        )
    }

    internal fun descriptorToJavaName(desc: String): String {
        return when {
            desc.startsWith("L") && desc.endsWith(";") ->
                desc.substring(1, desc.length - 1).replace('/', '.')
            desc.startsWith("[") ->
                descriptorToJavaName(desc.substring(1)) + "[]"
            desc == "V" -> "void"
            desc == "Z" -> "boolean"
            desc == "B" -> "byte"
            desc == "S" -> "short"
            desc == "C" -> "char"
            desc == "I" -> "int"
            desc == "J" -> "long"
            desc == "F" -> "float"
            desc == "D" -> "double"
            else -> desc
        }
    }

    private fun decodeMutf8(bytes: ByteArray, offset: Int, len: Int): String {
        val sb = StringBuilder(len)
        var i = offset
        val end = (offset + len).coerceAtMost(bytes.size)
        while (i < end) {
            val a = bytes[i].toInt() and 0xFF
            if (a < 0x80) {
                sb.append(a.toChar())
                i++
            } else if ((a and 0xE0) == 0xC0 && i + 1 < end) {
                val b = bytes[i + 1].toInt() and 0xFF
                sb.append((((a and 0x1F) shl 6) or (b and 0x3F)).toChar())
                i += 2
            } else if ((a and 0xF0) == 0xE0 && i + 2 < end) {
                val b = bytes[i + 1].toInt() and 0xFF
                val c = bytes[i + 2].toInt() and 0xFF
                sb.append((((a and 0x0F) shl 12) or ((b and 0x3F) shl 6) or (c and 0x3F)).toChar())
                i += 3
            } else {
                sb.append('?')
                i++
            }
        }
        return sb.toString()
    }
}
