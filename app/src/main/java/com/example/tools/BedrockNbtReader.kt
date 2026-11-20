package com.example.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BedrockLevelDatMetadata(
    val storageVersion: Int,
    val levelName: String?,
    val gameTypeLabel: String,
    val lastOpenedVersion: String
)

object BedrockNbtReader {

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    fun parseLevelDat(bytes: ByteArray): BedrockLevelDatMetadata? {
        if (bytes.size < 12) return null
        return runCatching {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val storageVersion = buf.getInt(0)
            // bytes[4..7] is payload length; root NBT begins at offset 8
            buf.position(8)
            val rootType = buf.get().toInt() and 0xFF
            if (rootType != TAG_COMPOUND) return null
            readString(buf) // root name (usually empty)

            var levelName: String? = null
            var gameType = -1
            var lastOpenedVersion = ""
            var nestedStorageVer = storageVersion

            readCompound(buf, depth = 0) { key, tagType, value ->
                when (key) {
                    "LevelName" -> if (tagType == TAG_STRING) levelName = value as? String
                    "GameType" -> if (tagType == TAG_INT) gameType = (value as? Int) ?: -1
                    "StorageVersion" -> if (tagType == TAG_INT) nestedStorageVer = (value as? Int) ?: storageVersion
                    "lastOpenedWithVersion" -> {
                        if (value is List<*>) {
                            val parts = value.filterIsInstance<Int>()
                            if (parts.isNotEmpty()) {
                                lastOpenedVersion = parts.take(4).joinToString(".")
                            }
                        }
                    }
                }
            }

            val gameTypeLabel = when (gameType) {
                0 -> "Survival"
                1 -> "Creative"
                2 -> "Adventure"
                3 -> "Spectator"
                else -> ""
            }

            BedrockLevelDatMetadata(
                storageVersion = nestedStorageVer,
                levelName = levelName?.takeIf { it.isNotBlank() },
                gameTypeLabel = gameTypeLabel,
                lastOpenedVersion = lastOpenedVersion
            )
        }.getOrNull()
    }

    private fun readCompound(
        buf: ByteBuffer,
        depth: Int,
        onEntry: ((String, Int, Any?) -> Unit)? = null
    ) {
        if (depth > 24) return
        while (buf.hasRemaining()) {
            val tagType = buf.get().toInt() and 0xFF
            if (tagType == TAG_END) break
            val name = readString(buf)
            val value = readPayload(buf, tagType, depth + 1)
            onEntry?.invoke(name, tagType, value)
        }
    }

    private fun readPayload(buf: ByteBuffer, tagType: Int, depth: Int): Any? {
        return when (tagType) {
            TAG_BYTE -> buf.get()
            TAG_SHORT -> buf.short
            TAG_INT -> buf.int
            TAG_LONG -> buf.long
            TAG_FLOAT -> buf.float
            TAG_DOUBLE -> buf.double
            TAG_BYTE_ARRAY -> {
                val len = buf.int.coerceIn(0, buf.remaining())
                buf.position(buf.position() + len)
                null
            }
            TAG_STRING -> readString(buf)
            TAG_LIST -> {
                val elemType = buf.get().toInt() and 0xFF
                val count = buf.int.coerceIn(0, 10_000)
                val list = ArrayList<Any?>(count.coerceAtMost(64))
                repeat(count) {
                    val item = readPayload(buf, elemType, depth + 1)
                    if (list.size < 64) list.add(item)
                }
                list
            }
            TAG_COMPOUND -> {
                readCompound(buf, depth + 1, null)
                null
            }
            TAG_INT_ARRAY -> {
                val count = buf.int.coerceIn(0, buf.remaining() / 4)
                buf.position(buf.position() + count * 4)
                null
            }
            TAG_LONG_ARRAY -> {
                val count = buf.int.coerceIn(0, buf.remaining() / 8)
                buf.position(buf.position() + count * 8)
                null
            }
            else -> null
        }
    }

    private fun readString(buf: ByteBuffer): String {
        val len = (buf.short.toInt() and 0xFFFF).coerceIn(0, buf.remaining())
        if (len == 0) return ""
        val bytes = ByteArray(len)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
