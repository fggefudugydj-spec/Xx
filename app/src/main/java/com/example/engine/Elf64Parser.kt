package com.example.engine

import com.example.model.DependencyResolutionResult
import com.example.model.Elf64DynamicInfo
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Elf64Parser {

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2

    private const val SHT_DYNSYM = 11

    private const val DT_NULL = 0L
    private const val DT_NEEDED = 1L
    private const val DT_HASH = 4L
    private const val DT_STRTAB = 5L
    private const val DT_SYMTAB = 6L
    private const val DT_STRSZ = 10L
    private const val DT_SYMENT = 11L
    private const val DT_SONAME = 14L

    private val trackedEntrypointNames = setOf(
        "JNI_OnLoad",
        "JNI_OnUnload",
        "ANativeActivity_onCreate",
        "android_main",
        "GameActivity_onCreate"
    )

    private val systemLibraryPrefixes = listOf(
        "libc.so",
        "libm.so",
        "libdl.so",
        "liblog.so",
        "libandroid.so",
        "libz.so",
        "libEGL.so",
        "libGLESv1_CM.so",
        "libGLESv2.so",
        "libGLESv3.so",
        "libOpenSLES.so",
        "libaaudio.so",
        "libvulkan.so",
        "libmediandk.so",
        "libjnigraphics.so",
        "libcamera2ndk.so",
        "libnativewindow.so",
        "libstdc++.so",
        "libbinder_ndk.so",
        "libneuralnetworks.so",
        "libsync.so",
        "libOpenMAXAL.so",
        "libamidi.so"
    )

    private data class LoadSegment(
        val offset: Long,
        val vaddr: Long,
        val filesz: Long
    )

    fun parseDynamicInfo(file: File): Elf64DynamicInfo {
        if (!file.exists() || !file.isFile) {
            return Elf64DynamicInfo(
                libraryName = file.name,
                soname = null,
                dtNeeded = emptyList(),
                parseError = "File does not exist: ${file.absolutePath}"
            )
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 64L) {
                    return Elf64DynamicInfo(
                        libraryName = file.name,
                        soname = null,
                        dtNeeded = emptyList(),
                        parseError = "File smaller than 64-byte ELF64 header"
                    )
                }

                val ehdr = ByteArray(64)
                raf.seek(0L)
                raf.readFully(ehdr)

                if (!ElfValidator.isElfMagic(ehdr)) {
                    return Elf64DynamicInfo(
                        libraryName = file.name,
                        soname = null,
                        dtNeeded = emptyList(),
                        parseError = "Invalid ELF magic"
                    )
                }

                val eiClass = ehdr[4].toInt() and 0xFF
                if (eiClass != 2) {
                    return Elf64DynamicInfo(
                        libraryName = file.name,
                        soname = null,
                        dtNeeded = emptyList(),
                        parseError = "Not an ELF64 binary (EI_CLASS=$eiClass)"
                    )
                }

                val eiData = ehdr[5].toInt() and 0xFF
                val order = if (eiData == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val buf = ByteBuffer.wrap(ehdr).order(order)

                val ePhoff = buf.getLong(32)
                val eShoff = buf.getLong(40)
                val ePhentsize = buf.getShort(54).toInt() and 0xFFFF
                val ePhnum = buf.getShort(56).toInt() and 0xFFFF
                val eShentsize = buf.getShort(58).toInt() and 0xFFFF
                val eShnum = buf.getShort(60).toInt() and 0xFFFF

                if (ePhoff <= 0L || ePhnum <= 0 || ePhentsize < 56) {
                    return Elf64DynamicInfo(
                        libraryName = file.name,
                        soname = null,
                        dtNeeded = emptyList(),
                        parseError = "Missing valid ELF64 program headers"
                    )
                }

                val loadSegments = mutableListOf<LoadSegment>()
                var dynamicOffset = -1L
                var dynamicSize = 0L

                val phdrBytes = ByteArray(ePhentsize)
                for (i in 0 until ePhnum) {
                    val pos = ePhoff + i.toLong() * ePhentsize.toLong()
                    if (pos + ePhentsize > raf.length()) break
                    raf.seek(pos)
                    raf.readFully(phdrBytes)
                    val pBuf = ByteBuffer.wrap(phdrBytes).order(order)
                    val pType = pBuf.getInt(0)
                    val pOffset = pBuf.getLong(8)
                    val pVaddr = pBuf.getLong(16)
                    val pFilesz = pBuf.getLong(32)

                    if (pType == PT_LOAD) {
                        loadSegments.add(LoadSegment(pOffset, pVaddr, pFilesz))
                    } else if (pType == PT_DYNAMIC) {
                        dynamicOffset = pOffset
                        dynamicSize = pFilesz
                    }
                }

                if (dynamicOffset < 0L || dynamicSize <= 0L) {
                    return Elf64DynamicInfo(
                        libraryName = file.name,
                        soname = null,
                        dtNeeded = emptyList(),
                        parseError = "PT_DYNAMIC segment not found"
                    )
                }

                val dynCount = (dynamicSize / 16L).toInt().coerceAtMost(4096)
                val dynBytes = ByteArray(dynCount * 16)
                raf.seek(dynamicOffset)
                raf.readFully(dynBytes)
                val dynBuf = ByteBuffer.wrap(dynBytes).order(order)

                var strtabVaddr = -1L
                var strtabSize = 0L
                var symtabVaddr = -1L
                var symentSize = 24L
                var hashVaddr = -1L
                var sonameOffset = -1L
                val neededOffsets = mutableListOf<Long>()

                for (i in 0 until dynCount) {
                    val base = i * 16
                    val dTag = dynBuf.getLong(base)
                    val dVal = dynBuf.getLong(base + 8)
                    when (dTag) {
                        DT_NULL -> break
                        DT_NEEDED -> neededOffsets.add(dVal)
                        DT_HASH -> hashVaddr = dVal
                        DT_STRTAB -> strtabVaddr = dVal
                        DT_SYMTAB -> symtabVaddr = dVal
                        DT_STRSZ -> strtabSize = dVal
                        DT_SYMENT -> if (dVal >= 24L) symentSize = dVal
                        DT_SONAME -> sonameOffset = dVal
                    }
                }

                if (strtabVaddr < 0L || strtabSize <= 0L) {
                    return Elf64DynamicInfo(
                        libraryName = file.name,
                        soname = null,
                        dtNeeded = emptyList(),
                        parseError = "DT_STRTAB / DT_STRSZ missing in PT_DYNAMIC"
                    )
                }

                val strtabFileOffset = vaddrToFileOffset(loadSegments, strtabVaddr)
                    ?: return Elf64DynamicInfo(
                        libraryName = file.name,
                        soname = null,
                        dtNeeded = emptyList(),
                        parseError = "Could not map DT_STRTAB vaddr 0x${strtabVaddr.toString(16)} to file offset"
                    )

                val safeStrSize = strtabSize.coerceAtMost(12L * 1024L * 1024L).toInt()
                val dynstr = ByteArray(safeStrSize)
                raf.seek(strtabFileOffset)
                raf.readFully(dynstr)

                val neededNames = neededOffsets.mapNotNull { offset ->
                    readCString(dynstr, offset.toInt())
                }
                val soname = if (sonameOffset >= 0L) {
                    readCString(dynstr, sonameOffset.toInt())
                } else {
                    null
                }

                val (entrypoints, jniCount) = scanDynamicSymbols(
                    raf = raf,
                    order = order,
                    loadSegments = loadSegments,
                    dynstr = dynstr,
                    symtabVaddr = symtabVaddr,
                    symentSize = symentSize,
                    hashVaddr = hashVaddr,
                    eShoff = eShoff,
                    eShentsize = eShentsize,
                    eShnum = eShnum
                )

                Elf64DynamicInfo(
                    libraryName = file.name,
                    soname = soname,
                    dtNeeded = neededNames,
                    exportedEntrypoints = entrypoints,
                    jniSymbolCount = jniCount,
                    parseError = null
                )
            }
        } catch (t: Throwable) {
            Elf64DynamicInfo(
                libraryName = file.name,
                soname = null,
                dtNeeded = emptyList(),
                parseError = "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    private fun scanDynamicSymbols(
        raf: RandomAccessFile,
        order: ByteOrder,
        loadSegments: List<LoadSegment>,
        dynstr: ByteArray,
        symtabVaddr: Long,
        symentSize: Long,
        hashVaddr: Long,
        eShoff: Long,
        eShentsize: Int,
        eShnum: Int
    ): Pair<List<String>, Int> {
        var symOffset = -1L
        var symCount = 0

        // Prefer section header SHT_DYNSYM if present (gives exact symbol table size)
        if (eShoff > 0L && eShnum in 1..256 && eShentsize >= 64) {
            val shdrBytes = ByteArray(eShentsize)
            for (i in 0 until eShnum) {
                val pos = eShoff + i.toLong() * eShentsize.toLong()
                if (pos + eShentsize > raf.length()) break
                raf.seek(pos)
                raf.readFully(shdrBytes)
                val sBuf = ByteBuffer.wrap(shdrBytes).order(order)
                val shType = sBuf.getInt(4)
                if (shType == SHT_DYNSYM) {
                    val shOffset = sBuf.getLong(24)
                    val shSize = sBuf.getLong(32)
                    val shEntsize = sBuf.getLong(56).coerceAtLeast(24L)
                    symOffset = shOffset
                    symCount = (shSize / shEntsize).toInt().coerceIn(0, 120_000)
                    break
                }
            }
        }

        // Fallback to DT_SYMTAB + DT_HASH nchain when section headers are stripped
        if (symOffset < 0L && symtabVaddr >= 0L) {
            symOffset = vaddrToFileOffset(loadSegments, symtabVaddr) ?: -1L
            if (symOffset >= 0L && hashVaddr >= 0L) {
                val hashOffset = vaddrToFileOffset(loadSegments, hashVaddr)
                if (hashOffset != null && hashOffset + 8 <= raf.length()) {
                    val hdr = ByteArray(8)
                    raf.seek(hashOffset)
                    raf.readFully(hdr)
                    val nchain = ByteBuffer.wrap(hdr).order(order).getInt(4)
                    symCount = nchain.coerceIn(0, 120_000)
                }
            }
        }

        if (symOffset < 0L || symCount <= 0) return emptyList<String>() to 0

        val foundEntrypoints = linkedSetOf<String>()
        var jniCount = 0
        val entSize = symentSize.toInt().coerceAtLeast(24)
        val batchCount = 2048
        val chunkBytes = ByteArray(batchCount * entSize)
        var remaining = symCount
        var filePos = symOffset

        while (remaining > 0 && filePos < raf.length()) {
            val toRead = remaining.coerceAtMost(batchCount)
            val byteLen = (toRead * entSize).toLong().coerceAtMost(raf.length() - filePos).toInt()
            if (byteLen < entSize) break
            raf.seek(filePos)
            raf.readFully(chunkBytes, 0, byteLen)
            val cBuf = ByteBuffer.wrap(chunkBytes, 0, byteLen).order(order)
            val actualSyms = byteLen / entSize
            for (s in 0 until actualSyms) {
                val base = s * entSize
                val stName = cBuf.getInt(base)
                val stShndx = cBuf.getShort(base + 6).toInt() and 0xFFFF
                if (stName <= 0 || stShndx == 0) continue // SHN_UNDEF (imported, not exported)
                val name = readCString(dynstr, stName) ?: continue
                if (name in trackedEntrypointNames) {
                    foundEntrypoints.add(name)
                } else if (name.startsWith("Java_com_mojang_") || name.startsWith("Java_org_fmod_")) {
                    jniCount++
                }
            }
            filePos += actualSyms.toLong() * entSize.toLong()
            remaining -= actualSyms
        }

        return foundEntrypoints.toList() to jniCount
    }

    private fun vaddrToFileOffset(segments: List<LoadSegment>, vaddr: Long): Long? {
        for (seg in segments) {
            if (vaddr >= seg.vaddr && vaddr < seg.vaddr + seg.filesz) {
                return seg.offset + (vaddr - seg.vaddr)
            }
        }
        return null
    }

    private fun readCString(bytes: ByteArray, offset: Int): String? {
        if (offset < 0 || offset >= bytes.size) return null
        var end = offset
        while (end < bytes.size && bytes[end] != 0.toByte()) {
            end++
        }
        if (end == offset) return null
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    fun isSystemLibrary(name: String): Boolean {
        if (name in systemLibraryPrefixes) return true
        return name.startsWith("libGLES") ||
            name.startsWith("libvulkan") ||
            name == "libvndksupport.so" ||
            name == "libdl_android.so"
    }

    fun resolveLoadOrder(
        mainLibraryName: String,
        extractedFilesByName: Map<String, File>
    ): DependencyResolutionResult {
        val perLibraryNeeded = linkedMapOf<String, List<String>>()
        val parsedCache = mutableMapOf<String, Elf64DynamicInfo>()
        val bundledLoadOrder = mutableListOf<String>()
        val systemDependencies = linkedSetOf<String>()
        val missingDependencies = linkedSetOf<String>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(libName: String) {
            if (libName in visited || libName in visiting) return
            visiting.add(libName)

            val file = extractedFilesByName[libName]
            if (file == null) {
                if (isSystemLibrary(libName)) {
                    systemDependencies.add(libName)
                } else {
                    missingDependencies.add(libName)
                }
                visiting.remove(libName)
                visited.add(libName)
                return
            }

            val dyn = parsedCache.getOrPut(libName) { parseDynamicInfo(file) }
            perLibraryNeeded[libName] = dyn.dtNeeded

            for (dep in dyn.dtNeeded) {
                if (extractedFilesByName.containsKey(dep)) {
                    visit(dep)
                } else if (isSystemLibrary(dep)) {
                    systemDependencies.add(dep)
                } else {
                    missingDependencies.add(dep)
                }
            }

            visiting.remove(libName)
            visited.add(libName)
            bundledLoadOrder.add(libName)
        }

        // First visit main library to preserve its dependency graph order
        visit(mainLibraryName)

        // Also include any other bundled libraries not directly referenced by DT_NEEDED
        // before the main library if needed, or keep them after checking
        for (extraLib in extractedFilesByName.keys.sorted()) {
            if (extraLib !in visited) {
                visit(extraLib)
            }
        }

        // Ensure mainLibraryName is last in load order
        val finalOrder = bundledLoadOrder.filter { it != mainLibraryName } +
            if (extractedFilesByName.containsKey(mainLibraryName)) listOf(mainLibraryName) else emptyList()

        return DependencyResolutionResult(
            bundledLoadOrder = finalOrder,
            systemDependencies = systemDependencies.toList(),
            missingDependencies = missingDependencies.toList(),
            perLibraryNeeded = perLibraryNeeded
        )
    }
}
