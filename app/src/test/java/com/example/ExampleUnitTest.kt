package com.example

import com.example.engine.AxmlManifestParser
import com.example.engine.DexInspector
import com.example.engine.Elf64Parser
import com.example.engine.ElfValidator
import com.example.engine.MinecraftPackageDiscovery
import com.example.model.GraphicsPreset
import com.example.model.InstalledMinecraftPackage
import com.example.tools.BedrockNbtReader
import com.example.tools.BedrockServerPinger
import com.example.tools.MinecraftOptionsManager
import com.example.tools.WorldBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExampleUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `text file named libminecraftpe_so is rejected even when larger than 1MB`() {
        val fakeLib = File(tempFolder.root, "libminecraftpe.so")
        val textBlock = "This is plain text pretending to be libminecraftpe.so\n".repeat(25_000)
        fakeLib.writeText(textBlock, Charsets.UTF_8)
        assertTrue(fakeLib.length() > ElfValidator.MIN_MAIN_LIBRARY_BYTES)

        val result = ElfValidator.validateLibrary(fakeLib, requireMinOneMb = true)
        assertFalse(result.hasElfMagic)
        assertFalse(result.isValid)
        assertFalse(ElfValidator.isValidMinecraftMainLibrary(fakeLib))
    }

    @Test
    fun `elf file named libminecraftpe_so smaller than 1MB is rejected`() {
        val smallElf = File(tempFolder.root, "libminecraftpe.so")
        val bytes = ByteArray(1024)
        writeElf64AArch64Header(bytes)
        smallElf.writeBytes(bytes)

        val result = ElfValidator.validateLibrary(smallElf, requireMinOneMb = true)
        assertTrue(result.hasElfMagic)
        assertFalse(result.meetsMinSize)
        assertFalse(result.isValid)
    }

    @Test
    fun `valid elf64 file named libminecraftpe_so larger than 1MB is accepted`() {
        val validElf = File(tempFolder.root, "libminecraftpe.so")
        val totalSize = ElfValidator.MIN_MAIN_LIBRARY_BYTES + 4096L
        RandomAccessFile(validElf, "rw").use { raf ->
            raf.setLength(totalSize)
            val header = ByteArray(64)
            writeElf64AArch64Header(header)
            raf.seek(0L)
            raf.write(header)
        }

        val validResult = ElfValidator.validateLibrary(validElf, requireMinOneMb = true)
        assertTrue(validResult.hasElfMagic)
        assertTrue(validResult.isElf64)
        assertTrue(validResult.isAArch64)
        assertTrue(validResult.meetsMinSize)
        assertTrue(validResult.isValid)
        assertTrue(ElfValidator.isValidMinecraftMainLibrary(validElf))
    }

    @Test
    fun `elf64 x86_64 library is rejected when requireArm64 is true`() {
        val x86Elf = File(tempFolder.root, "libminecraftpe.so")
        val totalSize = ElfValidator.MIN_MAIN_LIBRARY_BYTES + 4096L
        RandomAccessFile(x86Elf, "rw").use { raf ->
            raf.setLength(totalSize)
            val header = ByteArray(64)
            writeElf64Header(header, machine = 0x3E) // EM_X86_64
            raf.seek(0L)
            raf.write(header)
        }

        val result = ElfValidator.validateLibrary(
            file = x86Elf,
            requireMinOneMb = true,
            requireArm64 = true
        )
        assertTrue(result.hasElfMagic)
        assertTrue(result.isElf64)
        assertFalse(result.isAArch64)
        assertFalse(result.isValid)
        assertTrue(result.validationNote.contains("arm64-v8a"))
    }

    @Test
    fun `elf64 parser extracts dt_needed and resolves topological load order`() {
        val elfFile = File(tempFolder.root, "libminecraftpe.so")
        val dynstrText = "\u0000libfmod.so\u0000libc++_shared.so\u0000liblog.so\u0000"
        val dynstrBytes = dynstrText.toByteArray(Charsets.UTF_8)
        val dynstrOffset = 0x200L
        val dynamicOffset = 0x300L
        val fileSize = 0x1000L

        val buf = ByteBuffer.allocate(fileSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 0x7F.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2.toByte())
        buf.put(5, 1.toByte())
        buf.put(6, 1.toByte())
        buf.putShort(16, 3.toShort())
        buf.putShort(18, 0xB7.toShort())
        buf.putLong(32, 64L)
        buf.putShort(54, 56.toShort())
        buf.putShort(56, 2.toShort())

        val ph0 = 64
        buf.putInt(ph0 + 0, 1)
        buf.putLong(ph0 + 8, 0L)
        buf.putLong(ph0 + 16, 0L)
        buf.putLong(ph0 + 32, fileSize)

        val ph1 = 64 + 56
        buf.putInt(ph1 + 0, 2)
        buf.putLong(ph1 + 8, dynamicOffset)
        buf.putLong(ph1 + 16, dynamicOffset)
        buf.putLong(ph1 + 32, 6L * 16L)

        System.arraycopy(dynstrBytes, 0, buf.array(), dynstrOffset.toInt(), dynstrBytes.size)

        var dOff = dynamicOffset.toInt()
        fun putDyn(tag: Long, value: Long) {
            buf.putLong(dOff, tag)
            buf.putLong(dOff + 8, value)
            dOff += 16
        }
        putDyn(5L, dynstrOffset)
        putDyn(10L, dynstrBytes.size.toLong())
        putDyn(1L, dynstrText.indexOf("libfmod.so").toLong())
        putDyn(1L, dynstrText.indexOf("libc++_shared.so").toLong())
        putDyn(1L, dynstrText.indexOf("liblog.so").toLong())
        putDyn(0L, 0L)

        elfFile.writeBytes(buf.array())

        val info = Elf64Parser.parseDynamicInfo(elfFile)
        assertNull(info.parseError)
        assertEquals(listOf("libfmod.so", "libc++_shared.so", "liblog.so"), info.dtNeeded)

        val fmodFile = File(tempFolder.root, "libfmod.so").apply { writeBytes(ByteArray(64)) }
        val cppFile = File(tempFolder.root, "libc++_shared.so").apply { writeBytes(ByteArray(64)) }
        val resolution = Elf64Parser.resolveLoadOrder(
            mainLibraryName = "libminecraftpe.so",
            extractedFilesByName = mapOf(
                "libminecraftpe.so" to elfFile,
                "libfmod.so" to fmodFile,
                "libc++_shared.so" to cppFile
            )
        )
        assertEquals(listOf("libfmod.so", "libc++_shared.so", "libminecraftpe.so"), resolution.bundledLoadOrder)
        assertEquals(listOf("liblog.so"), resolution.systemDependencies)
        assertTrue(resolution.missingDependencies.isEmpty())
    }

    @Test
    fun `elf64 parser extracts dynsym JNI entrypoints via DT_SYMTAB and DT_HASH`() {
        val elfFile = File(tempFolder.root, "libminecraftpe_syms.so")
        val dynstrText = "\u0000JNI_OnLoad\u0000GameActivity_onCreate\u0000Java_com_mojang_minecraftpe_MainActivity_nativeInit\u0000"
        val dynstrBytes = dynstrText.toByteArray(Charsets.UTF_8)
        val dynstrOffset = 0x200L
        val symtabOffset = 0x300L
        val hashOffset = 0x400L
        val dynamicOffset = 0x500L
        val fileSize = 0x1000L

        val buf = ByteBuffer.allocate(fileSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 0x7F.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2.toByte())
        buf.put(5, 1.toByte())
        buf.put(6, 1.toByte())
        buf.putShort(16, 3.toShort())
        buf.putShort(18, 0xB7.toShort())
        buf.putLong(32, 64L)
        buf.putShort(54, 56.toShort())
        buf.putShort(56, 2.toShort())

        val ph0 = 64
        buf.putInt(ph0 + 0, 1)
        buf.putLong(ph0 + 8, 0L)
        buf.putLong(ph0 + 16, 0L)
        buf.putLong(ph0 + 32, fileSize)

        val ph1 = 64 + 56
        buf.putInt(ph1 + 0, 2)
        buf.putLong(ph1 + 8, dynamicOffset)
        buf.putLong(ph1 + 16, dynamicOffset)
        buf.putLong(ph1 + 32, 8L * 16L)

        System.arraycopy(dynstrBytes, 0, buf.array(), dynstrOffset.toInt(), dynstrBytes.size)

        // Write 4 Elf64_Sym entries (24 bytes each): [0]=UNDEF, [1]=JNI_OnLoad, [2]=GameActivity_onCreate, [3]=Java_...
        fun putSym(index: Int, nameStr: String?, shndx: Int) {
            val sOff = symtabOffset.toInt() + index * 24
            val nameOff = if (nameStr != null) dynstrText.indexOf(nameStr) else 0
            buf.putInt(sOff, nameOff)
            buf.putShort(sOff + 6, shndx.toShort())
        }
        putSym(0, null, 0)
        putSym(1, "JNI_OnLoad", 1)
        putSym(2, "GameActivity_onCreate", 1)
        putSym(3, "Java_com_mojang_minecraftpe_MainActivity_nativeInit", 1)

        // DT_HASH: nbucket=1, nchain=4
        buf.putInt(hashOffset.toInt(), 1)
        buf.putInt(hashOffset.toInt() + 4, 4)

        var dOff = dynamicOffset.toInt()
        fun putDyn(tag: Long, value: Long) {
            buf.putLong(dOff, tag)
            buf.putLong(dOff + 8, value)
            dOff += 16
        }
        putDyn(5L, dynstrOffset)
        putDyn(10L, dynstrBytes.size.toLong())
        putDyn(6L, symtabOffset)
        putDyn(11L, 24L)
        putDyn(4L, hashOffset)
        putDyn(0L, 0L)

        elfFile.writeBytes(buf.array())

        val info = Elf64Parser.parseDynamicInfo(elfFile)
        assertNull(info.parseError)
        assertEquals(listOf("JNI_OnLoad", "GameActivity_onCreate"), info.exportedEntrypoints)
        assertEquals(1, info.jniSymbolCount)
    }

    @Test
    fun `version matching and edition detection distinguish 1_26_62_1 and release vs preview 1_26_70_24`() {
        assertTrue(MinecraftPackageDiscovery.isTargetVersionMatch("1.26.70.24"))
        assertTrue(MinecraftPackageDiscovery.isTargetVersionMatch("1.26.70.24-preview"))
        assertFalse(MinecraftPackageDiscovery.isTargetVersionMatch("1.26.62.1"))
        assertFalse(MinecraftPackageDiscovery.isTargetVersionMatch("1.26.70.240"))
        assertFalse(MinecraftPackageDiscovery.isTargetVersionMatch("21.26.70.24"))
        assertFalse(MinecraftPackageDiscovery.isTargetVersionMatch("1.21.50.07"))

        assertEquals(
            com.example.model.MinecraftEdition.PREVIEW,
            MinecraftPackageDiscovery.determineEdition("com.mojang.minecraftpe.preview", "1.26.70.24")
        )
        assertEquals(
            com.example.model.MinecraftEdition.RELEASE,
            MinecraftPackageDiscovery.determineEdition("com.mojang.minecraftpe", "1.26.70.24")
        )
        assertEquals(
            com.example.model.MinecraftEdition.BETA,
            MinecraftPackageDiscovery.determineEdition("com.mojang.minecraftpe.beta", "1.26.70.24")
        )
        assertEquals(
            com.example.model.MinecraftEdition.TRIAL,
            MinecraftPackageDiscovery.determineEdition("com.mojang.minecrafttrialpe", "1.26.70.24")
        )

        val wrongVersionPreview = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe.preview",
            appLabel = "Minecraft Preview",
            versionName = "1.26.62.1",
            versionCode = 952606201L,
            sourceDir = "",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = false,
            isPreviewPackage = true,
            edition = com.example.model.MinecraftEdition.PREVIEW,
            hasArm64Support = true
        )
        val releasePkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe",
            appLabel = "Minecraft",
            versionName = "1.26.70.24",
            versionCode = 100L,
            sourceDir = "",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = false,
            edition = com.example.model.MinecraftEdition.RELEASE,
            hasArm64Support = true
        )
        val exactPreviewPkg = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe.preview",
            appLabel = "Minecraft Preview",
            versionName = "1.26.70.24",
            versionCode = 952607024L,
            sourceDir = "",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = true,
            edition = com.example.model.MinecraftEdition.PREVIEW,
            hasArm64Support = true,
            hasMainNativeLib = true
        )

        val best = MinecraftPackageDiscovery.selectBestPackage(listOf(wrongVersionPreview, releasePkg, exactPreviewPkg))
        assertEquals("com.mojang.minecraftpe.preview", best?.packageName)
        assertEquals("1.26.70.24", best?.versionName)

        assertNotNull(com.example.launcher.InProcessLaunchCoordinator.validateTargetPackageForLaunch(wrongVersionPreview))
        assertNotNull(com.example.launcher.InProcessLaunchCoordinator.validateTargetPackageForLaunch(releasePkg))
        assertNotNull(
            com.example.launcher.InProcessLaunchCoordinator.validateTargetPackageForLaunch(
                exactPreviewPkg.copy(hasArm64Support = false)
            )
        )
        assertFalse(exactPreviewPkg.copy(hasMainNativeLib = false).isExactTargetReady)
        assertNotNull(
            com.example.launcher.InProcessLaunchCoordinator.validateTargetPackageForLaunch(
                exactPreviewPkg.copy(hasMainNativeLib = false)
            )
        )
        assertNull(com.example.launcher.InProcessLaunchCoordinator.validateTargetPackageForLaunch(exactPreviewPkg))

        // Vault APK with 1.26.70.24 + ARM64 + valid libminecraftpe.so passes even if manifest uses com.mojang.minecraftpe
        val vaultUniversalApk = InstalledMinecraftPackage(
            packageName = "com.mojang.minecraftpe",
            appLabel = "Minecraft 1.26.70.24 (APK Vault)",
            versionName = "1.26.70.24",
            versionCode = 982607024L,
            sourceDir = "/data/user/0/app/files/mc_vault/u1/base.apk",
            splitSourceDirs = emptyList(),
            nativeLibraryDir = "",
            supportedAbis = listOf("arm64-v8a"),
            matchesTargetVersion = true,
            isPreviewPackage = false,
            edition = com.example.model.MinecraftEdition.RELEASE,
            hasArm64Support = true,
            hasMainNativeLib = true,
            isVaultApk = true,
            vaultId = "u1"
        )
        assertTrue(vaultUniversalApk.isExactTargetReady)
        assertNull(com.example.launcher.InProcessLaunchCoordinator.validateTargetPackageForLaunch(vaultUniversalApk))

        // Wrong-version Vault APK (1.26.62.1) must still stay blocked
        val wrongVersionVault = vaultUniversalApk.copy(
            versionName = "1.26.62.1",
            matchesTargetVersion = false
        )
        assertFalse(wrongVersionVault.isExactTargetReady)
        assertNotNull(com.example.launcher.InProcessLaunchCoordinator.validateTargetPackageForLaunch(wrongVersionVault))

        // System package (isVaultApk = false) with com.mojang.minecraftpe 1.26.70.24 remains blocked
        assertFalse(releasePkg.copy(hasMainNativeLib = true).isExactTargetReady)
    }

    @Test
    fun `quickScanApkNativeSupport validates ELF64 AArch64 and rejects fake or x86_64 libminecraftpe_so in Split APK`() {
        val fakeZip = File(tempFolder.root, "split_fake.apk")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(fakeZip)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
            val fakePayload = "NOT_AN_ELF_BINARY_PAYLOAD".repeat(60_000).toByteArray(Charsets.UTF_8)
            zos.write(fakePayload)
            zos.closeEntry()
        }
        val fakeScan = MinecraftPackageDiscovery.quickScanApkNativeSupport(
            sourceDir = "",
            splitSourceDirs = listOf(fakeZip.absolutePath),
            nativeLibraryDir = ""
        )
        assertFalse(fakeScan.hasArm64MainLib)

        val x86Zip = File(tempFolder.root, "split_x86.apk")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(x86Zip)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
            val bytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 1024)
            writeElf64Header(bytes, machine = 0x3E) // EM_X86_64 masquerading in arm64 folder
            zos.write(bytes)
            zos.closeEntry()
        }
        val x86Scan = MinecraftPackageDiscovery.quickScanApkNativeSupport(
            sourceDir = "",
            splitSourceDirs = listOf(x86Zip.absolutePath),
            nativeLibraryDir = ""
        )
        assertFalse(x86Scan.hasArm64MainLib)

        val validZip = File(tempFolder.root, "split_arm64_valid.apk")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(validZip)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/libminecraftpe.so"))
            val bytes = ByteArray(ElfValidator.MIN_MAIN_LIBRARY_BYTES.toInt() + 1024)
            writeElf64AArch64Header(bytes)
            zos.write(bytes)
            zos.closeEntry()
        }
        val validScan = MinecraftPackageDiscovery.quickScanApkNativeSupport(
            sourceDir = "",
            splitSourceDirs = listOf(validZip.absolutePath),
            nativeLibraryDir = ""
        )
        assertTrue(validScan.hasArm64MainLib)
        assertTrue(validScan.abis.contains("arm64-v8a"))
    }

    @Test
    fun `legacy launch mode preference migrates cleanly without crash`() {
        assertEquals(
            com.example.model.LaunchMode.DIRECT_NATIVE,
            com.example.core.LeviPreferences.parseLaunchMode("IN_PROCESS_EXPERIMENTAL")
        )
        assertEquals(
            com.example.model.LaunchMode.QUICK_OFFICIAL,
            com.example.core.LeviPreferences.parseLaunchMode("QUICK_OFFICIAL")
        )
        assertEquals(
            com.example.model.LaunchMode.ADJACENT_SPLIT,
            com.example.core.LeviPreferences.parseLaunchMode("ADJACENT_SPLIT")
        )
    }

    @Test
    fun `options txt parser and serializer preserve unknown keys and apply presets`() {
        val raw = """
            mp_username:Steve
            gfx_renderdistance_new:8
            gfx_max_framerate:60
            gfx_fancygraphics:1
            custom_unknown_flag:42
        """.trimIndent()

        val parsed = MinecraftOptionsManager.parseOptionsText(raw)
        assertEquals(8, parsed.renderDistanceChunks)
        assertEquals(60, parsed.maxFramerate)
        assertTrue(parsed.fancyGraphics)

        val tuned = MinecraftOptionsManager.applyPreset(parsed, GraphicsPreset.COMPETITIVE_120FPS)
        assertEquals(120, tuned.maxFramerate)
        assertEquals(10, tuned.renderDistanceChunks)
        assertFalse(tuned.vsyncEnabled)

        val serialized = MinecraftOptionsManager.serializeOptionsText(tuned)
        assertTrue(serialized.contains("mp_username:Steve"))
        assertTrue(serialized.contains("custom_unknown_flag:42"))
        assertTrue(serialized.contains("gfx_max_framerate:120"))
        assertTrue(serialized.contains("gfx_vsync:0"))
    }

    @Test
    fun `zip path sanitizer blocks traversal and accepts valid world entries`() {
        assertNull(WorldBackupManager.sanitizeZipRelativePath("../evil/level.dat"))
        assertNull(WorldBackupManager.sanitizeZipRelativePath("db/../../etc/passwd"))
        assertNull(WorldBackupManager.sanitizeZipRelativePath("C:/bad/path"))
        assertNull(WorldBackupManager.sanitizeZipRelativePath(""))

        val valid = WorldBackupManager.sanitizeZipRelativePath("db/000001.ldb")
        assertNotNull(valid)
        assertEquals(listOf("db", "000001.ldb"), valid)
    }

    @Test
    fun `raknet unconnected pong parser extracts server metadata`() {
        val motdPayload = "MCPE;LeviLab Test;748;1.26.70;12;100;12345;Survival World;Survival;1;19132;19133;"
        val strBytes = motdPayload.toByteArray(Charsets.UTF_8)
        val packet = ByteBuffer.allocate(35 + strBytes.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x1C.toByte())
            putLong(1000L)
            putLong(9999L)
            put(ByteArray(16))
            putShort(strBytes.size.toShort())
            put(strBytes)
        }.array()

        val result = BedrockServerPinger.parseUnconnectedPong("s1", packet, packet.size, 24L)
        assertTrue(result.isOnline)
        assertEquals("LeviLab Test", result.motd)
        assertEquals("1.26.70", result.versionName)
        assertEquals(12, result.onlinePlayers)
        assertEquals(100, result.maxPlayers)
        assertEquals("Survival", result.gameMode)
        assertEquals(24L, result.latencyMs)
    }

    @Test
    fun `bedrock nbt reader parses level name game type and bedrock version from level_dat`() {
        val out = java.io.ByteArrayOutputStream()
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        fun writeNbtStr(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            writeShortLE(b.size)
            out.write(b)
        }

        // 8-byte Bedrock header (storageVersion=10, payloadLen placeholder)
        writeIntLE(10)
        writeIntLE(0)
        // Root TAG_COMPOUND("")
        out.write(10)
        writeNbtStr("")

        // LevelName = "LeviLab Survival"
        out.write(8)
        writeNbtStr("LevelName")
        writeNbtStr("LeviLab Survival")

        // GameType = 1 (Creative)
        out.write(3)
        writeNbtStr("GameType")
        writeIntLE(1)

        // lastOpenedWithVersion = TAG_LIST(TAG_INT, [1, 26, 70, 24])
        out.write(9)
        writeNbtStr("lastOpenedWithVersion")
        out.write(3) // TAG_INT
        writeIntLE(4)
        writeIntLE(1)
        writeIntLE(26)
        writeIntLE(70)
        writeIntLE(24)

        // TAG_END
        out.write(0)

        val meta = BedrockNbtReader.parseLevelDat(out.toByteArray())
        assertNotNull(meta)
        assertEquals(10, meta?.storageVersion)
        assertEquals("LeviLab Survival", meta?.levelName)
        assertEquals("Creative", meta?.gameTypeLabel)
        assertEquals("1.26.70.24", meta?.lastOpenedVersion)
    }

    @Test
    fun `axml manifest parser extracts package version permissions and activities`() {
        val strings = listOf(
            "manifest",
            "package",
            "versionName",
            "versionCode",
            "uses-sdk",
            "minSdkVersion",
            "targetSdkVersion",
            "uses-permission",
            "name",
            "activity",
            "com.mojang.minecraftpe.preview",
            "1.26.70.24",
            "android.permission.INTERNET",
            ".MainActivity"
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

        fun startElementChunk(tagIdx: Int, attrs: List<Triple<Int, Int, Int>>): ByteArray {
            val size = 36 + attrs.size * 20
            val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(0, 0x0102.toShort())
            bb.putShort(2, 16.toShort())
            bb.putInt(4, size)
            bb.putInt(20, tagIdx)
            bb.putShort(24, 20.toShort())
            bb.putShort(26, 20.toShort())
            bb.putShort(28, attrs.size.toShort())
            for ((i, attr) in attrs.withIndex()) {
                val off = 36 + i * 20
                bb.putInt(off + 4, attr.first)
                bb.putInt(off + 8, -1)
                bb.put(off + 15, attr.second.toByte())
                bb.putInt(off + 16, attr.third)
            }
            return bb.array()
        }

        val cManifest = startElementChunk(
            0,
            listOf(
                Triple(1, 0x03, 10), // package = com.mojang.minecraftpe.preview
                Triple(2, 0x03, 11), // versionName = 1.26.70.24
                Triple(3, 0x10, 952607024) // versionCode = 952607024
            )
        )
        val cUsesSdk = startElementChunk(
            4,
            listOf(
                Triple(5, 0x10, 26),
                Triple(6, 0x10, 33)
            )
        )
        val cPerm = startElementChunk(7, listOf(Triple(8, 0x03, 12)))
        val cAct = startElementChunk(9, listOf(Triple(8, 0x03, 13)))

        val totalSize = 8 + poolChunkSize + cManifest.size + cUsesSdk.size + cPerm.size + cAct.size
        val root = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
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
        for (chunk in listOf(cManifest, cUsesSdk, cPerm, cAct)) {
            System.arraycopy(chunk, 0, root.array(), pos, chunk.size)
            pos += chunk.size
        }

        val summary = AxmlManifestParser.parseBinaryXml(root.array())
        assertNotNull(summary)
        assertEquals("com.mojang.minecraftpe.preview", summary?.packageName)
        assertEquals("1.26.70.24", summary?.versionName)
        assertEquals(952607024L, summary?.versionCode)
        assertEquals(26, summary?.minSdkVersion)
        assertEquals(33, summary?.targetSdkVersion)
        assertTrue(summary?.permissions?.contains("android.permission.INTERNET") == true)
        assertTrue(summary?.activities?.contains("com.mojang.minecraftpe.preview.MainActivity") == true)
    }

    @Test
    fun `dex inspector parses inheritance chain and ACC_NATIVE methods from binary dex`() {
        // String indices:
        // 0: Lcom/google/androidgamesdk/GameActivity;
        // 1: Lcom/mojang/minecraftpe/MainActivity;
        // 2: V
        // 3: nativeInitialize
        val strs = listOf(
            "Lcom/google/androidgamesdk/GameActivity;",
            "Lcom/mojang/minecraftpe/MainActivity;",
            "V",
            "nativeInitialize"
        )
        val totalSize = 1024
        val dex = ByteArray(totalSize)
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        "dex\n035\u0000".toByteArray(Charsets.US_ASCII).copyInto(dex, 0)

        val stringIdsOff = 112
        val typeIdsOff = 136
        val protoIdsOff = 152
        val methodIdsOff = 168
        val classDefsOff = 184
        val dataOffStart = 320

        buf.putInt(56, strs.size)
        buf.putInt(60, stringIdsOff)
        buf.putInt(64, 3) // type_ids count
        buf.putInt(68, typeIdsOff)
        buf.putInt(72, 1) // proto_ids count
        buf.putInt(76, protoIdsOff)
        buf.putInt(88, 1) // method_ids count
        buf.putInt(92, methodIdsOff)
        buf.putInt(96, 1) // class_defs count
        buf.putInt(100, classDefsOff)

        var dPos = dataOffStart
        for ((i, s) in strs.withIndex()) {
            buf.putInt(stringIdsOff + i * 4, dPos)
            dex[dPos++] = s.length.toByte()
            val bytes = s.toByteArray(Charsets.UTF_8)
            bytes.copyInto(dex, dPos)
            dPos += bytes.size
            dex[dPos++] = 0
        }

        // type_ids: 0 -> str 0, 1 -> str 1, 2 -> str 2
        buf.putInt(typeIdsOff + 0, 0)
        buf.putInt(typeIdsOff + 4, 1)
        buf.putInt(typeIdsOff + 8, 2)

        // proto_ids[0]: shorty=2, ret=2 (V), params=0
        buf.putInt(protoIdsOff + 0, 2)
        buf.putInt(protoIdsOff + 4, 2)
        buf.putInt(protoIdsOff + 8, 0)

        // method_ids[0]: class_idx=1, proto_idx=0, name_idx=3 ("nativeInitialize")
        buf.putShort(methodIdsOff + 0, 1.toShort())
        buf.putShort(methodIdsOff + 2, 0.toShort())
        buf.putInt(methodIdsOff + 4, 3)

        // class_data_item at dPos
        val classDataOff = dPos
        dex[dPos++] = 0 // static_fields_size
        dex[dPos++] = 0 // instance_fields_size
        dex[dPos++] = 1 // direct_methods_size
        dex[dPos++] = 0 // virtual_methods_size
        // encoded_method: method_idx_diff=0, access_flags=0x0100 (uleb128: 0x80, 0x02), code_off=0
        dex[dPos++] = 0
        dex[dPos++] = 0x80.toByte()
        dex[dPos++] = 0x02
        dex[dPos++] = 0

        // class_defs[0]: class_idx=1 (MainActivity), super_idx=0 (GameActivity), class_data_off=classDataOff
        buf.putInt(classDefsOff + 0, 1)
        buf.putInt(classDefsOff + 8, 0)
        buf.putInt(classDefsOff + 24, classDataOff)

        val dexFile = File(tempFolder.root, "base_with_dex.apk")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(dexFile)).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
            zos.write(dex)
            zos.closeEntry()
        }

        val probe = DexInspector.inspectApks(
            apkPaths = listOf(dexFile.absolutePath),
            launchActivityClass = "com.mojang.minecraftpe.MainActivity"
        )
        assertEquals(1, probe.dexFilesCount)
        assertTrue(probe.gameActivityDetected)
        assertEquals("com.google.androidgamesdk.GameActivity", probe.gameActivityClassName)
        assertEquals(
            listOf("com.mojang.minecraftpe.MainActivity", "com.google.androidgamesdk.GameActivity"),
            probe.inheritanceChain
        )
        assertEquals(1, probe.nativeMethods.size)
        assertEquals("nativeInitialize", probe.nativeMethods.first().methodName)
    }

    @Test
    fun `device hardware profiler scans apk assets and recommends graphics preset`() {
        assertEquals(
            GraphicsPreset.BATTERY_SAVER,
            com.example.engine.DeviceHardwareProfiler.recommendPreset(totalRamMb = 3072, availRamMb = 1200, cpuCores = 8)
        )
        assertEquals(
            GraphicsPreset.COMPETITIVE_120FPS,
            com.example.engine.DeviceHardwareProfiler.recommendPreset(totalRamMb = 8192, availRamMb = 4096, cpuCores = 8)
        )
        assertEquals(
            GraphicsPreset.ULTRA_VISUALS,
            com.example.engine.DeviceHardwareProfiler.recommendPreset(totalRamMb = 6144, availRamMb = 3000, cpuCores = 6)
        )

        val assetsApk = File(tempFolder.root, "assets_sample.apk")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(assetsApk)).use { zos ->
            val entries = listOf(
                "assets/renderer/materials/Actor.material.bin" to ByteArray(512),
                "assets/renderer/materials/Sky.material.bin" to ByteArray(256),
                "assets/resource_packs/vanilla/manifest.json" to "{}".toByteArray(),
                "assets/behavior_packs/vanilla_1.26.70/manifest.json" to "{}".toByteArray(),
                "assets/behavior_packs/experimental_caves/manifest.json" to "{}".toByteArray()
            )
            for ((path, payload) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(payload)
                zos.closeEntry()
            }
        }

        val summary = com.example.engine.DeviceHardwareProfiler.scanApkAssets(listOf(assetsApk.absolutePath))
        assertEquals(5, summary.totalAssetFiles)
        assertEquals(2, summary.shaderMaterialCount)
        assertEquals(1, summary.resourcePackCount)
        assertEquals(2, summary.behaviorPackCount)
        assertTrue(summary.totalAssetsBytes >= 768L)
    }

    private fun writeElf64AArch64Header(bytes: ByteArray) {
        writeElf64Header(bytes, machine = 0xB7)
    }

    private fun writeElf64Header(bytes: ByteArray, machine: Int) {
        bytes[0] = 0x7F.toByte()
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2.toByte()
        bytes[5] = 1.toByte()
        bytes[6] = 1.toByte()
        bytes[18] = (machine and 0xFF).toByte()
        bytes[19] = ((machine shr 8) and 0xFF).toByte()
    }
}
