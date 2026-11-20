package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.AppLanguage
import com.example.core.LeviLogger
import com.example.core.LeviPreferences
import com.example.engine.DeviceHardwareProfiler
import com.example.engine.LeviApkVaultManager
import com.example.engine.MinecraftPackageDiscovery
import com.example.engine.MinecraftProbeEngine
import com.example.engine.NativeLibraryManager
import com.example.launcher.InProcessLaunchCoordinator
import com.example.launcher.LaunchDiagnosticsBus
import com.example.model.FileBrowserMode
import com.example.model.FileManagerEntry
import com.example.model.GraphicsPreset
import com.example.model.InstalledMinecraftPackage
import com.example.model.LaunchMode
import com.example.model.LeviTab
import com.example.model.LeviUiState
import com.example.model.LibraryLoadSource
import com.example.model.LogLevel
import com.example.model.LogsSubTab
import com.example.model.MinecraftGraphicsOptions
import com.example.model.SavedServer
import com.example.tools.BedrockServerPinger
import com.example.tools.DiagnosticReportBuilder
import com.example.tools.LeviFileManager
import com.example.tools.MinecraftOptionsManager
import com.example.tools.WorldBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LeviViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val logger: LeviLogger = LeviLogger.getInstance(appContext)
    private val preferences = LeviPreferences(appContext)

    private var activeSafTreeUri: Uri? = null

    private val _uiState = MutableStateFlow(
        LeviUiState(
            logFilePath = logger.filePath,
            deviceAbiList = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            hardwareSnapshot = runCatching { DeviceHardwareProfiler.collect(appContext) }.getOrNull(),
            fileBrowserCurrentPath = appContext.filesDir.absolutePath
        )
    )
    val uiState: StateFlow<LeviUiState> = _uiState.asStateFlow()

    init {
        observeFlows()
        scanPackagesAndAutoProbe()
        loadAppFiles(appContext.filesDir)
    }

    private fun observeFlows() {
        viewModelScope.launch {
            preferences.languageFlow.collect { lang ->
                _uiState.update { it.copy(language = lang) }
            }
        }
        viewModelScope.launch {
            preferences.launchModeFlow.collect { mode ->
                _uiState.update { it.copy(preferredLaunchMode = mode) }
            }
        }
        viewModelScope.launch {
            preferences.autoVerifyCacheFlow.collect { enabled ->
                _uiState.update { it.copy(autoVerifyCacheBeforeLaunch = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.preferDirectApkLoadFlow.collect { enabled ->
                _uiState.update { it.copy(preferDirectApkNativeLoad = enabled) }
            }
        }
        viewModelScope.launch {
            preferences.savedServersFlow.collect { servers ->
                _uiState.update { it.copy(savedServers = servers) }
            }
        }
        viewModelScope.launch {
            preferences.launchHistoryFlow.collect { history ->
                _uiState.update { it.copy(launchHistory = history) }
            }
        }
        viewModelScope.launch {
            preferences.worldBackupsFlow.collect { backups ->
                _uiState.update { it.copy(worldBackupRecords = backups) }
            }
        }
        viewModelScope.launch {
            logger.entries.collect { entries ->
                _uiState.update { it.copy(logs = entries) }
            }
        }
        viewModelScope.launch {
            LaunchDiagnosticsBus.lastFailure.collect { failure ->
                _uiState.update { it.copy(lastLaunchFailure = failure) }
            }
        }
        viewModelScope.launch {
            LaunchDiagnosticsBus.latestProbe.collect { probe ->
                if (probe != null) {
                    _uiState.update {
                        it.copy(
                            probeReport = probe,
                            extractedLibraries = probe.discoveredLibraries,
                            cacheSummary = probe.cacheSummary
                        )
                    }
                }
            }
        }
    }

    fun selectTab(tab: LeviTab) {
        _uiState.update { it.copy(currentTab = tab) }
        if (tab == LeviTab.PROBE && _uiState.value.probeReport == null && !_uiState.value.isProbing) {
            val selected = _uiState.value.selectedPackage
            if (selected != null) {
                runProbeForPackage(selected, forceReextract = false)
            } else {
                scanPackagesAndAutoProbe()
            }
        } else if (tab == LeviTab.TOOLS && _uiState.value.fileBrowserMode == FileBrowserMode.APP_CACHE) {
            loadAppFiles(File(_uiState.value.fileBrowserCurrentPath.ifBlank { appContext.filesDir.absolutePath }))
        }
    }

    fun selectLogsSubTab(subTab: LogsSubTab) {
        _uiState.update { it.copy(logsSubTab = subTab) }
    }

    fun toggleLanguage() {
        val next = if (_uiState.value.language == AppLanguage.AR) AppLanguage.EN else AppLanguage.AR
        viewModelScope.launch {
            preferences.setLanguage(next)
        }
    }

    fun setAutoVerifyCache(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoVerifyCache(enabled) }
    }

    fun setPreferDirectApkLoad(enabled: Boolean) {
        viewModelScope.launch { preferences.setPreferDirectApkLoad(enabled) }
    }

    fun setPreferredLaunchMode(mode: LaunchMode) {
        viewModelScope.launch { preferences.setLaunchMode(mode) }
    }

    fun scanPackagesAndAutoProbe() {
        if (_uiState.value.isScanningPackages) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningPackages = true) }
            val (vaultList, outcome, hwSnapshot) = withContext(Dispatchers.IO) {
                val vault = LeviApkVaultManager.loadAllVaultPackages(appContext, logger)
                val discovery = MinecraftPackageDiscovery.discoverWithDiagnostics(appContext, logger)
                val hw = runCatching { DeviceHardwareProfiler.collect(appContext) }.getOrNull()
                Triple(vault, discovery, hw)
            }
            val merged = (vaultList + outcome.packages).sortedWith(
                compareByDescending<InstalledMinecraftPackage> { it.isExactTargetReady }
                    .thenByDescending { it.isPreviewPackage && it.matchesTargetVersion }
                    .thenByDescending { it.isVaultApk }
                    .thenByDescending { it.isPreviewPackage }
                    .thenByDescending { it.matchesTargetVersion }
                    .thenBy { it.packageName }
            )
            val autoSelected = vaultList.firstOrNull { it.isExactTargetReady }
                ?: vaultList.firstOrNull()
                ?: outcome.packages.firstOrNull { it.isExactTargetReady }
            _uiState.update {
                it.copy(
                    isScanningPackages = false,
                    vaultApkCount = vaultList.size,
                    installedPackages = merged,
                    candidateProbeResults = outcome.candidateProbes,
                    selectedPackage = autoSelected,
                    probeReport = if (autoSelected == null) null else it.probeReport,
                    extractedLibraries = if (autoSelected == null) emptyList() else it.extractedLibraries,
                    cacheSummary = if (autoSelected == null) null else it.cacheSummary,
                    hardwareSnapshot = hwSnapshot ?: it.hardwareSnapshot
                )
            }
            if (autoSelected != null) {
                runProbeForPackage(autoSelected, forceReextract = false)
            }
        }
    }

    fun selectPackageAndProbe(pkg: InstalledMinecraftPackage) {
        _uiState.update { it.copy(selectedPackage = pkg) }
        runProbeForPackage(pkg, forceReextract = false)
    }

    fun runProbeForSelected(forceReextract: Boolean = false) {
        val pkg = _uiState.value.selectedPackage
        if (pkg != null) {
            runProbeForPackage(pkg, forceReextract = forceReextract)
        } else {
            scanPackagesAndAutoProbe()
        }
    }

    private fun runProbeForPackage(pkg: InstalledMinecraftPackage, forceReextract: Boolean) {
        if (_uiState.value.isProbing || _uiState.value.isRepairing) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isProbing = true,
                    isExtracting = true,
                    isRepairing = forceReextract
                )
            }
            val report = withContext(Dispatchers.IO) {
                MinecraftProbeEngine.runProbe(
                    context = appContext,
                    pkg = pkg,
                    logger = logger,
                    forceReextract = forceReextract
                )
            }
            LaunchDiagnosticsBus.publishProbe(report)
            _uiState.update {
                it.copy(
                    isProbing = false,
                    isExtracting = false,
                    isRepairing = false,
                    probeReport = report,
                    extractedLibraries = report.discoveredLibraries,
                    cacheSummary = report.cacheSummary
                )
            }
        }
    }

    fun repairLibraries() {
        val pkg = _uiState.value.selectedPackage ?: return
        if (_uiState.value.isRepairing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRepairing = true, isExtracting = true) }
            val outcome = withContext(Dispatchers.IO) {
                NativeLibraryManager.inspectAndPrepareCache(
                    context = appContext,
                    pkg = pkg,
                    logger = logger,
                    forceReextract = true
                )
            }
            val cacheSummary = withContext(Dispatchers.IO) {
                NativeLibraryManager.buildCacheSummary(
                    context = appContext,
                    pkg = pkg,
                    libraries = outcome.libraries,
                    stalePruned = outcome.staleDirectoriesPruned,
                    abi = outcome.preferredAbi
                )
            }
            _uiState.update {
                it.copy(
                    isRepairing = false,
                    isExtracting = false,
                    extractedLibraries = outcome.libraries,
                    cacheSummary = cacheSummary
                )
            }
            runProbeForPackage(pkg, forceReextract = false)
        }
    }

    fun clearLibraryCache() {
        if (_uiState.value.isClearingCache) return
        viewModelScope.launch {
            _uiState.update { it.copy(isClearingCache = true) }
            withContext(Dispatchers.IO) {
                NativeLibraryManager.clearAllCache(appContext, logger)
            }
            _uiState.update {
                it.copy(
                    isClearingCache = false,
                    extractedLibraries = emptyList(),
                    cacheSummary = null
                )
            }
            if (_uiState.value.fileBrowserMode == FileBrowserMode.APP_CACHE) {
                loadAppFiles(appContext.filesDir)
            }
            val pkg = _uiState.value.selectedPackage
            if (pkg != null) {
                runProbeForPackage(pkg, forceReextract = false)
            }
        }
    }

    fun inspectExternalApk(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isInspectingExternalApk = true) }
            val result = withContext(Dispatchers.IO) {
                MinecraftPackageDiscovery.inspectExternalApk(appContext, uri, logger)
            }
            _uiState.update {
                it.copy(
                    isInspectingExternalApk = false,
                    externalApkInspection = result
                )
            }
        }
    }

    fun importApkToVault(uri: Uri) {
        if (_uiState.value.isImportingVaultApk) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingVaultApk = true, statusBannerMessage = null) }
            val importOutcome = withContext(Dispatchers.IO) {
                LeviApkVaultManager.importApkOrBundle(appContext, uri, logger)
            }
            val imported = importOutcome.importedPackage
            if (imported != null) {
                val (vaultList, discovery) = withContext(Dispatchers.IO) {
                    LeviApkVaultManager.loadAllVaultPackages(appContext, logger) to
                        MinecraftPackageDiscovery.discoverWithDiagnostics(appContext, logger)
                }
                val merged = (vaultList + discovery.packages).sortedWith(
                    compareByDescending<InstalledMinecraftPackage> { it.isExactTargetReady }
                        .thenByDescending { it.isPreviewPackage && it.matchesTargetVersion }
                        .thenByDescending { it.isVaultApk }
                        .thenByDescending { it.isPreviewPackage }
                        .thenByDescending { it.matchesTargetVersion }
                        .thenBy { it.packageName }
                )
                val matched = merged.firstOrNull { it.vaultId == imported.vaultId } ?: imported
                LaunchDiagnosticsBus.publishFailure(null)
                _uiState.update {
                    it.copy(
                        isImportingVaultApk = false,
                        vaultApkCount = vaultList.size,
                        installedPackages = merged,
                        candidateProbeResults = discovery.candidateProbes,
                        selectedPackage = matched
                    )
                }
                runProbeForPackage(matched, forceReextract = false)
            } else {
                _uiState.update {
                    it.copy(
                        isImportingVaultApk = false,
                        statusBannerMessage = importOutcome.errorMessage
                    )
                }
            }
        }
    }

    fun removeVaultApk(vaultId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LeviApkVaultManager.deleteVaultPackage(appContext, vaultId, logger)
            }
            scanPackagesAndAutoProbe()
        }
    }

    fun openExternalApkInstaller(
        uriString: String,
        launchContext: android.content.Context = appContext
    ): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (launchContext !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            launchContext.startActivity(intent)
            true
        } catch (t: Throwable) {
            logger.error("VERSION_MGR", "Unable to open APK installer: ${t.message}", t)
            false
        }
    }

    fun launchInstalledDirectly(launchContext: android.content.Context = appContext): Boolean {
        val pkg = _uiState.value.selectedPackage ?: return false
        val ok = InProcessLaunchCoordinator.launchInstalledPackage(launchContext, pkg, logger)
        val mainSource = _uiState.value.extractedLibraries
            .firstOrNull { it.fileName == "libminecraftpe.so" }
            ?.loadSource?.name ?: LibraryLoadSource.NATIVE_DIR.name
        val summary = if (ok) {
            "Official direct launch of ${pkg.packageName} v${pkg.versionName} (${pkg.versionCode})"
        } else {
            InProcessLaunchCoordinator.validateTargetPackageForLaunch(pkg)
                ?: "Failed to resolve launch intent for ${pkg.packageName}"
        }
        viewModelScope.launch {
            preferences.appendLaunchHistory(
                InProcessLaunchCoordinator.buildHistoryEntry(
                    pkg = pkg,
                    mode = LaunchMode.QUICK_OFFICIAL,
                    succeeded = ok,
                    usedFallback = false,
                    mainSource = mainSource,
                    summary = summary
                )
            )
        }
        return ok
    }

    fun dismissLastFailure() {
        LaunchDiagnosticsBus.publishFailure(null)
    }

    fun clearLaunchHistory() {
        viewModelScope.launch { preferences.clearLaunchHistory() }
    }

    // Graphics & Performance (options.txt)
    fun onOptionsTxtPicked(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingGraphicsOptions = true, graphicsStatusMessage = null) }
            val result = withContext(Dispatchers.IO) {
                MinecraftOptionsManager.loadFromUri(appContext, uri, logger)
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { loaded ->
                        state.copy(
                            isLoadingGraphicsOptions = false,
                            graphicsOptions = loaded,
                            graphicsStatusMessage = "Loaded ${loaded.fileName} (${loaded.rawLineCount} keys)"
                        )
                    },
                    onFailure = { err ->
                        state.copy(
                            isLoadingGraphicsOptions = false,
                            graphicsStatusMessage = "Error: ${err.message}"
                        )
                    }
                )
            }
        }
    }

    fun updateGraphicsOptions(transform: (MinecraftGraphicsOptions) -> MinecraftGraphicsOptions) {
        _uiState.update { state ->
            state.copy(graphicsOptions = transform(state.graphicsOptions))
        }
    }

    fun applyGraphicsPreset(preset: GraphicsPreset) {
        _uiState.update { state ->
            state.copy(
                graphicsOptions = MinecraftOptionsManager.applyPreset(state.graphicsOptions, preset),
                graphicsStatusMessage = "Preset applied: ${preset.name}"
            )
        }
    }

    fun saveGraphicsOptionsToLoadedFile() {
        val current = _uiState.value.graphicsOptions
        val uriString = current.fileUriString ?: return
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                MinecraftOptionsManager.saveToUri(appContext, Uri.parse(uriString), current, logger)
            }
            _uiState.update { state ->
                state.copy(
                    graphicsStatusMessage = res.fold(
                        onSuccess = { "Saved changes to ${current.fileName}" },
                        onFailure = { err -> "Save failed: ${err.message}" }
                    )
                )
            }
        }
    }

    fun exportGraphicsOptionsToUri(destUri: Uri) {
        val current = _uiState.value.graphicsOptions
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                MinecraftOptionsManager.saveToUri(appContext, destUri, current, logger)
            }
            _uiState.update { state ->
                state.copy(
                    graphicsOptions = state.graphicsOptions.copy(fileUriString = destUri.toString()),
                    graphicsStatusMessage = res.fold(
                        onSuccess = { "Exported options.txt" },
                        onFailure = { err -> "Export failed: ${err.message}" }
                    )
                )
            }
        }
    }

    // Server Pinger
    fun addServer(label: String, host: String, portText: String) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return
        val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 19132
        val cleanLabel = label.trim().ifEmpty { cleanHost }
        val newServer = SavedServer(
            id = UUID.randomUUID().toString(),
            label = cleanLabel,
            host = cleanHost,
            port = port
        )
        val updated = _uiState.value.savedServers + newServer
        viewModelScope.launch {
            preferences.saveServers(updated)
            logger.info("TOOLS_PING", "Added Bedrock server $cleanLabel ($cleanHost:$port)")
            pingServer(newServer)
        }
    }

    fun deleteServer(serverId: String) {
        val updated = _uiState.value.savedServers.filterNot { it.id == serverId }
        viewModelScope.launch {
            preferences.saveServers(updated)
        }
    }

    fun pingServer(server: SavedServer) {
        if (server.id in _uiState.value.pingingServerIds) return
        viewModelScope.launch {
            _uiState.update { it.copy(pingingServerIds = it.pingingServerIds + server.id) }
            val result = withContext(Dispatchers.IO) {
                BedrockServerPinger.ping(server)
            }
            logger.info(
                "TOOLS_PING",
                if (result.isOnline) {
                    "Pong from ${server.host}:${server.port} | ${result.onlinePlayers}/${result.maxPlayers} | ${result.latencyMs}ms"
                } else {
                    "Ping failed ${server.host}:${server.port}: ${result.errorMessage}"
                }
            )
            _uiState.update { state ->
                state.copy(
                    pingingServerIds = state.pingingServerIds - server.id,
                    serverPingResults = state.serverPingResults + (server.id to result)
                )
            }
        }
    }

    fun pingAllServers() {
        _uiState.value.savedServers.forEach { server ->
            pingServer(server)
        }
    }

    fun launchServerInMinecraft(
        server: SavedServer,
        launchContext: android.content.Context = appContext
    ): Boolean {
        return InProcessLaunchCoordinator.launchServerDeepLink(
            context = launchContext,
            pkg = _uiState.value.selectedPackage,
            server = server,
            logger = logger
        )
    }

    // World Backup, Protection & Restore
    fun onWorldFolderPicked(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningWorld = true, worldBackupStatusMessage = null) }
            val summary = withContext(Dispatchers.IO) {
                runCatching {
                    WorldBackupManager.inspectWorldTree(appContext, treeUri, logger)
                }.getOrNull()
            }
            _uiState.update {
                it.copy(
                    isScanningWorld = false,
                    selectedWorldFolder = summary,
                    worldBackupStatusMessage = if (summary == null) "Failed to read selected folder" else null
                )
            }
        }
    }

    fun onExportWorldDestinationPicked(destUri: Uri) {
        val summary = _uiState.value.selectedWorldFolder ?: return
        val treeUri = Uri.parse(summary.treeUriString)
        viewModelScope.launch {
            _uiState.update { it.copy(isBackingUpWorld = true, worldBackupStatusMessage = null) }
            val res = withContext(Dispatchers.IO) {
                WorldBackupManager.exportWorldWithRecord(appContext, treeUri, destUri, logger)
            }
            res.onSuccess { record ->
                preferences.appendWorldBackupRecord(record)
            }
            _uiState.update {
                it.copy(
                    isBackingUpWorld = false,
                    worldBackupStatusMessage = res.fold(
                        onSuccess = { rec ->
                            "Exported ${rec.fileName} (${rec.fileCount} files, SHA-256: ${rec.sha256Short})"
                        },
                        onFailure = { err -> "Export failed: ${err.message}" }
                    )
                )
            }
        }
    }

    fun onRestoreMcWorldPicked(fileUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningWorld = true, worldBackupStatusMessage = null) }
            val info = withContext(Dispatchers.IO) {
                WorldBackupManager.inspectMcWorldFile(appContext, fileUri, logger)
            }
            _uiState.update {
                it.copy(
                    isScanningWorld = false,
                    selectedRestoreWorldFile = info
                )
            }
        }
    }

    fun onRestoreWorldTargetFolderPicked(targetTreeUri: Uri) {
        val restoreInfo = _uiState.value.selectedRestoreWorldFile ?: return
        viewModelScope.launch {
            val existingWorld = withContext(Dispatchers.IO) {
                WorldBackupManager.checkTargetFolderExistingWorld(appContext, targetTreeUri)
            }
            if (existingWorld != null) {
                logger.warn(
                    "TOOLS_WORLD",
                    "World Data Protection paused restore: target already contains world '$existingWorld'"
                )
                _uiState.update {
                    it.copy(
                        pendingRestoreTargetTreeUri = targetTreeUri.toString(),
                        restoreOverwriteWarningName = existingWorld
                    )
                }
            } else {
                executeWorldRestore(restoreInfo.fileUriString, restoreInfo.levelName, targetTreeUri)
            }
        }
    }

    fun confirmRestoreOverwrite() {
        val restoreInfo = _uiState.value.selectedRestoreWorldFile ?: return
        val targetTreeStr = _uiState.value.pendingRestoreTargetTreeUri ?: return
        _uiState.update {
            it.copy(
                pendingRestoreTargetTreeUri = null,
                restoreOverwriteWarningName = null
            )
        }
        viewModelScope.launch {
            executeWorldRestore(restoreInfo.fileUriString, restoreInfo.levelName, Uri.parse(targetTreeStr))
        }
    }

    fun cancelRestoreOverwrite() {
        _uiState.update {
            it.copy(
                pendingRestoreTargetTreeUri = null,
                restoreOverwriteWarningName = null,
                worldBackupStatusMessage = "Restore cancelled by World Data Protection"
            )
        }
    }

    private suspend fun executeWorldRestore(
        mcWorldUriString: String,
        levelName: String,
        targetTreeUri: Uri
    ) {
        _uiState.update { it.copy(isRestoringWorld = true, worldBackupStatusMessage = null) }
        val res = withContext(Dispatchers.IO) {
            WorldBackupManager.restoreMcWorldToFolder(
                context = appContext,
                mcWorldUri = Uri.parse(mcWorldUriString),
                targetTreeUri = targetTreeUri,
                logger = logger
            )
        }
        _uiState.update {
            it.copy(
                isRestoringWorld = false,
                worldBackupStatusMessage = res.fold(
                    onSuccess = { count -> "Restored $levelName ($count files)" },
                    onFailure = { err -> "Restore failed: ${err.message}" }
                )
            )
        }
    }

    fun openSelectedRestoreWorldInMinecraft(launchContext: android.content.Context = appContext) {
        val restoreInfo = _uiState.value.selectedRestoreWorldFile ?: return
        val pkgName = _uiState.value.selectedPackage?.packageName
        val ok = WorldBackupManager.openPackInMinecraft(
            context = launchContext,
            fileUri = Uri.parse(restoreInfo.fileUriString),
            targetPackageName = pkgName,
            logger = logger
        )
        _uiState.update {
            it.copy(
                worldBackupStatusMessage = if (ok) {
                    "Opened ${restoreInfo.fileName} in Minecraft"
                } else {
                    "Failed to open ${restoreInfo.fileName} in Minecraft"
                }
            )
        }
    }

    // Resource Packs
    fun onMcPackPicked(fileUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isInspectingPack = true, packImportStatusMessage = null) }
            val info = withContext(Dispatchers.IO) {
                WorldBackupManager.inspectMcPack(appContext, fileUri, logger)
            }
            _uiState.update {
                it.copy(
                    isInspectingPack = false,
                    selectedMcPack = info
                )
            }
        }
    }

    fun openSelectedPackInMinecraft(launchContext: android.content.Context = appContext) {
        val pack = _uiState.value.selectedMcPack ?: return
        val pkgName = _uiState.value.selectedPackage?.packageName
        val ok = WorldBackupManager.openPackInMinecraft(
            context = launchContext,
            fileUri = Uri.parse(pack.fileUriString),
            targetPackageName = pkgName,
            logger = logger
        )
        _uiState.update {
            it.copy(
                packImportStatusMessage = if (ok) {
                    "Opened ${pack.fileName} via Intent"
                } else {
                    "Failed to open ${pack.fileName} in Minecraft"
                }
            )
        }
    }

    // File Manager
    fun switchFileBrowserMode(mode: FileBrowserMode) {
        _uiState.update {
            it.copy(
                fileBrowserMode = mode,
                fileManagerStatusMessage = null
            )
        }
        if (mode == FileBrowserMode.APP_CACHE) {
            loadAppFiles(appContext.filesDir)
        } else {
            val treeUri = activeSafTreeUri
            val stack = _uiState.value.fileBrowserSafStack
            if (treeUri != null && stack.isNotEmpty()) {
                loadSafDirectory(treeUri, stack)
            } else {
                _uiState.update {
                    it.copy(
                        fileBrowserEntries = emptyList(),
                        fileBrowserRootLabel = "SAF"
                    )
                }
            }
        }
    }

    fun openSafFolderInFileManager(treeUri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        activeSafTreeUri = treeUri
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        _uiState.update {
            it.copy(
                fileBrowserMode = FileBrowserMode.SAF_TREE,
                fileBrowserRootLabel = rootDocId.substringAfterLast('/'),
                fileBrowserCurrentPath = rootDocId,
                fileBrowserSafStack = listOf(rootDocId),
                fileManagerStatusMessage = null
            )
        }
        loadSafDirectory(treeUri, listOf(rootDocId))
    }

    fun onFileManagerEntryClick(entry: FileManagerEntry) {
        val mode = _uiState.value.fileBrowserMode
        if (entry.isDirectory) {
            if (mode == FileBrowserMode.APP_CACHE) {
                loadAppFiles(File(entry.pathOrUri))
            } else {
                val treeUri = activeSafTreeUri ?: return
                val updatedStack = _uiState.value.fileBrowserSafStack + entry.id
                loadSafDirectory(treeUri, updatedStack)
            }
        } else if (entry.canPreview) {
            viewModelScope.launch {
                val res = withContext(Dispatchers.IO) {
                    if (mode == FileBrowserMode.APP_CACHE) {
                        LeviFileManager.readAppTextFilePreview(appContext, entry.pathOrUri)
                    } else {
                        LeviFileManager.readSafTextPreview(appContext, Uri.parse(entry.pathOrUri))
                    }
                }
                _uiState.update {
                    it.copy(
                        filePreviewTitle = entry.name,
                        filePreviewContent = res.getOrElse { err -> "Preview error: ${err.message}" }
                    )
                }
            }
        }
    }

    fun navigateFileManagerUp() {
        val mode = _uiState.value.fileBrowserMode
        if (mode == FileBrowserMode.APP_CACHE) {
            val current = File(_uiState.value.fileBrowserCurrentPath)
            val root = appContext.filesDir.canonicalFile
            val parent = current.canonicalFile.parentFile
            if (parent != null && parent.path.startsWith(root.path)) {
                loadAppFiles(parent)
            }
        } else {
            val treeUri = activeSafTreeUri ?: return
            val stack = _uiState.value.fileBrowserSafStack
            if (stack.size > 1) {
                loadSafDirectory(treeUri, stack.dropLast(1))
            }
        }
    }

    fun createSafSubfolder(folderName: String) {
        val treeUri = activeSafTreeUri ?: return
        val parentDocId = _uiState.value.fileBrowserSafStack.lastOrNull() ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                LeviFileManager.createSafSubfolder(appContext, treeUri, parentDocId, folderName, logger)
            }
            if (ok) {
                loadSafDirectory(treeUri, _uiState.value.fileBrowserSafStack)
            } else {
                _uiState.update { it.copy(fileManagerStatusMessage = "Could not create folder '$folderName'") }
            }
        }
    }

    fun deleteFileManagerEntry(entry: FileManagerEntry) {
        val mode = _uiState.value.fileBrowserMode
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (mode == FileBrowserMode.APP_CACHE) {
                    LeviFileManager.deleteAppFileOrDir(appContext, entry.pathOrUri, logger)
                } else {
                    LeviFileManager.deleteSafDocument(appContext, Uri.parse(entry.pathOrUri), logger)
                }
            }
            if (ok) {
                if (mode == FileBrowserMode.APP_CACHE) {
                    loadAppFiles(File(_uiState.value.fileBrowserCurrentPath))
                } else {
                    activeSafTreeUri?.let { loadSafDirectory(it, _uiState.value.fileBrowserSafStack) }
                }
            }
        }
    }

    fun dismissFilePreview() {
        _uiState.update { it.copy(filePreviewTitle = null, filePreviewContent = null) }
    }

    private fun loadAppFiles(dir: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFiles = true) }
            val entries = withContext(Dispatchers.IO) {
                LeviFileManager.listAppFiles(appContext, dir)
            }
            _uiState.update {
                it.copy(
                    isLoadingFiles = false,
                    fileBrowserRootLabel = "filesDir",
                    fileBrowserCurrentPath = dir.absolutePath,
                    fileBrowserEntries = entries
                )
            }
        }
    }

    private fun loadSafDirectory(treeUri: Uri, stack: List<String>) {
        val docId = stack.lastOrNull() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFiles = true) }
            val entries = withContext(Dispatchers.IO) {
                LeviFileManager.listSafDirectory(appContext, treeUri, docId)
            }
            _uiState.update {
                it.copy(
                    isLoadingFiles = false,
                    fileBrowserSafStack = stack,
                    fileBrowserCurrentPath = docId,
                    fileBrowserEntries = entries
                )
            }
        }
    }

    fun setLogFilter(level: LogLevel?) {
        _uiState.update { it.copy(logLevelFilter = level) }
    }

    fun clearLogs() {
        logger.clear()
    }

    fun buildDiagnosticReportText(): String {
        return DiagnosticReportBuilder.buildReport(_uiState.value, logger)
    }
}
