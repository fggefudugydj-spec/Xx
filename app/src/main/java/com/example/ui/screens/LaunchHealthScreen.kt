package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.core.LeviStrings
import com.example.engine.MinecraftPackageDiscovery
import com.example.launcher.InProcessLaunchCoordinator
import com.example.model.ElfInspectionResult
import com.example.model.InstalledMinecraftPackage
import com.example.model.LeviUiState
import com.example.model.LibraryLoadSource
import com.example.model.MinecraftEdition
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaunchHealthScreen(
    state: LeviUiState,
    strings: LeviStrings,
    onRescan: () -> Unit,
    onSelectPackage: (InstalledMinecraftPackage) -> Unit,
    onLaunchVerified: () -> Unit,
    onLaunchQuickOfficial: () -> Unit,
    onLaunchAdjacentSplit: () -> Unit = {},
    onLaunchDirectSystem: () -> Unit,
    onRepairLibraries: () -> Unit,
    onClearCache: () -> Unit,
    onInspectExternalApk: (Uri) -> Unit,
    onImportApkToVault: (Uri) -> Unit = {},
    onRemoveVaultApk: (String) -> Unit = {},
    onInstallExternalApk: (String) -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pickExternalApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onInspectExternalApk(uri)
    }

    val importVaultApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportApkToVault(uri)
    }

    val readinessItems = remember(state.selectedPackage, state.extractedLibraries) {
        InProcessLaunchCoordinator.evaluateReadinessChecklist(
            pkg = state.selectedPackage,
            libraries = state.extractedLibraries
        )
    }
    var showOptionalSystemSection by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 760.dp)
            .testTag("launch_health_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        state.lastLaunchFailure?.let { failure ->
            item(key = "last_failure_banner") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = strings.lastFailureBannerTitle,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = strings.lastFailureBannerTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            IconButton(onClick = onDismissFailure) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.dismissBanner,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Text(
                            text = "${strings.fallbackExplanationPrefix} ${failure.failedStage}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = failure.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        val vaultPackages = state.installedPackages.filter { it.isVaultApk }
        val systemPackages = state.installedPackages.filterNot { it.isVaultApk }

        // APK Vault Hero, Version Manager & In-Process Launch Controls
        item(key = "packages_section") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("apk_vault_hero_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = strings.apkVaultHeroTitle,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = strings.apkVaultHeroTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = strings.apkVaultHeroSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { importVaultApkLauncher.launch(arrayOf("*/*")) },
                                enabled = !state.isImportingVaultApk,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 52.dp)
                                    .testTag("import_apk_vault_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                if (state.isImportingVaultApk) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = strings.importApkToVaultButton,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = if (state.isImportingVaultApk) strings.importingVaultStatus else strings.importApkToVaultButton,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                            OutlinedButton(
                                onClick = { pickExternalApkLauncher.launch(arrayOf("*/*")) },
                                enabled = !state.isInspectingExternalApk,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("inspect_external_apk_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = strings.inspectExternalApkButton,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = strings.inspectExternalApkButton,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    state.externalApkInspection?.let { ext ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(
                                1.dp,
                                if (ext.isTargetPreviewApk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${strings.externalApkCardTitle}: ${ext.fileName}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (ext.errorMessage != null) {
                                    Text(
                                        text = ext.errorMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        InfoPill(ext.packageName ?: "unknown")
                                        InfoPill(editionLabel(ext.edition, strings), highlight = ext.edition == MinecraftEdition.PREVIEW)
                                        InfoPill("v${ext.versionName ?: "?"} (${ext.versionCode ?: 0})")
                                        InfoPill(
                                            label = if (ext.matchesTargetVersion) strings.targetMatchExact else strings.targetMatchMismatch,
                                            highlight = ext.matchesTargetVersion
                                        )
                                        InfoPill(
                                            label = if (ext.hasArm64MainLib && ext.mainLibIsValidElf64) {
                                                "arm64 libminecraftpe.so (${formatBytes(ext.mainLibSizeBytes)})"
                                            } else {
                                                "arm64 libminecraftpe.so missing/invalid"
                                            },
                                            highlight = ext.hasArm64MainLib && ext.mainLibIsValidElf64
                                        )
                                        if (ext.manifestPermissionsCount > 0) {
                                            InfoPill("Perms: ${ext.manifestPermissionsCount}")
                                        }
                                        if (ext.isSplitBundleContainer) {
                                            InfoPill(strings.splitBundleBadge, highlight = true)
                                        }
                                    }
                                    if (ext.isSplitBundleContainer) {
                                        Text(
                                            text = strings.splitBundleInstallHint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { onImportApkToVault(Uri.parse(ext.uriString)) },
                                            enabled = !state.isImportingVaultApk,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .defaultMinSize(minHeight = 48.dp)
                                                .testTag("import_inspected_apk_button")
                                        ) {
                                            Text(
                                                text = strings.importApkToVaultButton,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        if (!ext.isSplitBundleContainer) {
                                            OutlinedButton(
                                                onClick = { onInstallExternalApk(ext.uriString) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .defaultMinSize(minHeight = 48.dp)
                                            ) {
                                                Text(
                                                    text = strings.installExternalApkButton,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (vaultPackages.isEmpty() && state.selectedPackage == null && !state.isScanningPackages) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.testTag("empty_vault_prompt_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = strings.noMinecraftInstalledTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = strings.noMinecraftInstalledDesc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (vaultPackages.isNotEmpty()) {
                        vaultPackages.forEach { pkg ->
                            val selected = state.selectedPackage?.packageName == pkg.packageName &&
                                state.selectedPackage?.vaultId == pkg.vaultId
                            PackageCardItem(
                                pkg = pkg,
                                isSelected = selected,
                                strings = strings,
                                onClick = { onSelectPackage(pkg) },
                                onRemoveVaultApk = { pkg.vaultId?.let(onRemoveVaultApk) }
                            )
                        }
                    }

                    state.selectedPackage?.let { selectedPkg ->
                        TargetComparisonCard(
                            pkg = selectedPkg,
                            strings = strings
                        )
                        if (!selectedPkg.isExactTargetReady) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                                shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("target_mismatch_guard_banner")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = strings.mismatchGuardTitle,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = if (selectedPkg.isVaultApk) strings.vaultMismatchGuardTitle else strings.mismatchGuardTitle,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Text(
                                        text = if (selectedPkg.isVaultApk) strings.vaultMismatchGuardBody else strings.mismatchGuardBody,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    selectedPkg.targetMismatchReason?.let { detail ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                                Text(
                                                    text = detail,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    color = MaterialTheme.colorScheme.onBackground,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { importVaultApkLauncher.launch(arrayOf("*/*")) },
                                        enabled = !state.isImportingVaultApk,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 50.dp)
                                            .testTag("mismatch_pick_apk_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = strings.mismatchPickApkButton,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Text(
                                            text = strings.mismatchPickApkButton,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Button(
                            onClick = onLaunchVerified,
                            enabled = selectedPkg.isExactTargetReady,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp)
                                .testTag("launch_primary_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = strings.launchPrimaryButton
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = if (selectedPkg.isExactTargetReady) {
                                    strings.launchPrimaryButton
                                } else {
                                    strings.launchBlockedLabel
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }

                        if (!selectedPkg.isVaultApk) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onLaunchDirectSystem,
                                    enabled = selectedPkg.isExactTargetReady,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("launch_direct_fallback_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Launch,
                                        contentDescription = strings.launchFallbackButton,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(
                                        text = strings.launchFallbackButton,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                OutlinedButton(
                                    onClick = onLaunchQuickOfficial,
                                    enabled = selectedPkg.isExactTargetReady,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .testTag("launch_quick_official_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VerifiedUser,
                                        contentDescription = strings.launchQuickOfficialButton,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(
                                        text = strings.launchQuickOfficialButton,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onLaunchAdjacentSplit,
                                enabled = selectedPkg.isExactTargetReady,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("launch_adjacent_split_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = strings.launchAdjacentSplitButton,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = strings.launchAdjacentSplitButton,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    if (systemPackages.isNotEmpty() || state.candidateProbeResults.isNotEmpty()) {
                        val showSystemBlock = showOptionalSystemSection ||
                            (state.selectedPackage != null && !state.selectedPackage.isVaultApk)
                        if (showSystemBlock) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = strings.systemPackagesOptionalTitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            systemPackages.forEach { pkg ->
                                val selected = state.selectedPackage?.packageName == pkg.packageName &&
                                    state.selectedPackage?.vaultId == pkg.vaultId
                                PackageCardItem(
                                    pkg = pkg,
                                    isSelected = selected,
                                    strings = strings,
                                    onClick = { onSelectPackage(pkg) },
                                    onRemoveVaultApk = {}
                                )
                            }
                            if (state.candidateProbeResults.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = MaterialTheme.shapes.medium,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("candidate_probe_card")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = strings.candidateScanTitle,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        state.candidateProbeResults.forEach { probe ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (probe.found) Icons.Default.CheckCircle else Icons.Default.Close,
                                                    contentDescription = probe.packageName,
                                                    tint = if (probe.found) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = probe.packageName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = JetBrainsMonoFontFamily,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (probe.found) strings.candidateFoundLabel else strings.candidateMissingLabel,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (probe.found) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            AssistChip(
                                onClick = { showOptionalSystemSection = true },
                                label = { Text(strings.toggleSystemPackagesLabel) },
                                modifier = Modifier.testTag("show_optional_system_packages_chip")
                            )
                        }
                    }
                }
            }
        }

        // Device Environment Hero Card
        item(key = "device_env_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                    MaterialTheme.colorScheme.surface
                                )
                            )
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = strings.deviceSummaryTitle,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = strings.deviceSummaryTitle,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            AssistChip(
                                onClick = onRescan,
                                enabled = !state.isScanningPackages,
                                label = { Text(strings.rescanPackagesButton) },
                                leadingIcon = {
                                    if (state.isScanningPackages) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = strings.rescanPackagesButton,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("rescan_packages_button")
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            InfoPill("${strings.androidOsLabel}: ${state.androidVersionLabel}")
                            InfoPill(
                                "${strings.abiLabel}: ${state.deviceAbiList.joinToString(", ").ifBlank { "unknown" }}"
                            )
                            InfoPill(label = strings.targetVersionTag, highlight = true)
                            state.hardwareSnapshot?.let { hw ->
                                if (hw.totalRamMb > 0L) {
                                    InfoPill("${strings.hardwareRamLabel}: ${hw.availableRamMb}/${hw.totalRamMb} MB")
                                }
                                if (hw.availableStorageMb > 0L) {
                                    InfoPill("${strings.hardwareStorageLabel}: ${hw.availableStorageMb} MB")
                                }
                                if (hw.cpuCores > 0) {
                                    InfoPill("CPU: ${hw.cpuCores}c • ${strings.hardwareGlLabel} ${hw.glEsVersion}")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pre-Flight Readiness Matrix
        item(key = "readiness_checklist_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = strings.readinessChecklistTitle,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = strings.readinessChecklistTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    readinessItems.forEach { check ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (check.passed) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = check.label,
                                tint = if (check.passed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = check.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = check.detail,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = if (check.passed) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Game Health & Library Cache Summary
        item(key = "health_header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = strings.gameHealthTitle,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = strings.gameHealthTitle,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                state.cacheSummary?.let { cache ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = strings.cacheSummaryTitle,
                                style = MaterialTheme.typography.titleMedium
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                InfoPill("${strings.cacheSizeLabel}: ${formatBytes(cache.totalCacheBytes)}")
                                InfoPill(
                                    "${strings.cacheCrcVerifiedLabel}: ${cache.crcVerifiedCount}",
                                    highlight = cache.crcVerifiedCount > 0
                                )
                                InfoPill(
                                    "${strings.cacheDirectLoadLabel}: ${cache.directLoadableCount}",
                                    highlight = cache.directLoadableCount > 0
                                )
                                if (cache.staleDirectoriesPruned > 0) {
                                    InfoPill(
                                        "${strings.staleCachePrunedLabel}: ${cache.staleDirectoriesPruned}",
                                        highlight = true
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onRepairLibraries,
                                    enabled = state.selectedPackage != null && !state.isRepairing && !state.isProbing,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("repair_libraries_button")
                                ) {
                                    if (state.isRepairing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Build,
                                            contentDescription = strings.repairReextractButton,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(
                                        if (state.isRepairing) strings.repairingStatus else strings.repairReextractButton,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = onClearCache,
                                    enabled = !state.isClearingCache,
                                    modifier = Modifier.testTag("clear_cache_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = strings.clearCacheButton,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(strings.clearCacheButton)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.extractedLibraries.isEmpty()) {
            item(key = "no_libs") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Text(
                        text = strings.noLibrariesExtracted,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(
                items = state.extractedLibraries,
                key = { it.fileName }
            ) { lib ->
                LibraryHealthCard(lib = lib, strings = strings)
            }
        }

        item(key = "official_launch_scope_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.testTag("official_launch_scope_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = strings.officialLaunchScopeTitle,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = strings.officialLaunchScopeTitle,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = strings.officialLaunchScopeBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetComparisonCard(
    pkg: InstalledMinecraftPackage,
    strings: LeviStrings
) {
    val axmlNote = pkg.axmlVersionName
        ?.takeIf { it.isNotBlank() && it != pkg.versionName }
        ?.let { " (AXML: $it)" }
        .orEmpty()
    val actualSourcePrefix = if (pkg.isVaultApk) strings.importedApkLabel else strings.installedLabel
    val rows = listOf(
        Triple(
            strings.targetRowPackage,
            if (pkg.isVaultApk) strings.vaultPackageIsolatedBadge else MinecraftPackageDiscovery.PREVIEW_PACKAGE_NAME,
            (if (pkg.isVaultApk) "${pkg.packageName} (${strings.vaultPackageIsolatedBadge})" else pkg.packageName) to
                (pkg.isVaultApk || pkg.packageName == MinecraftPackageDiscovery.PREVIEW_PACKAGE_NAME)
        ),
        Triple(
            strings.targetRowEdition,
            if (pkg.isVaultApk) strings.vaultApkBadge else strings.previewBadge,
            (if (pkg.isVaultApk) strings.vaultPackageIsolatedBadge else editionLabel(pkg.edition, strings)) to
                (pkg.isVaultApk || pkg.edition == MinecraftEdition.PREVIEW)
        ),
        Triple(
            strings.targetRowVersion,
            MinecraftPackageDiscovery.TARGET_VERSION_NAME,
            "${pkg.versionName}$axmlNote" to pkg.matchesTargetVersion
        ),
        Triple(
            strings.targetRowVersionCode,
            "> 0",
            "${pkg.versionCode}" to (pkg.versionCode > 0L)
        ),
        Triple(
            strings.targetRowAbi,
            "arm64-v8a",
            (if (pkg.hasArm64Support) "arm64-v8a" else pkg.apkAbis.ifEmpty { pkg.supportedAbis }.joinToString().ifBlank { "missing" }) to pkg.hasArm64Support
        ),
        Triple(
            strings.targetRowMainLib,
            "ELF64 (>1MB)",
            (if (pkg.hasMainNativeLib) strings.elfValidBadge else strings.elfInvalidBadge) to pkg.hasMainNativeLib
        )
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            if (pkg.isExactTargetReady) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("target_comparison_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (pkg.isVaultApk) strings.vaultComparisonTitle else strings.targetComparisonTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            rows.forEach { (fieldLabel, expected, actualPair) ->
                val (actualValue, matched) = actualPair
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (matched) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = fieldLabel,
                        tint = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = fieldLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.weight(1.15f)
                    ) {
                        Text(
                            text = "$actualSourcePrefix: $actualValue",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = if (matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End
                        )
                        Text(
                            text = "${strings.requiredLabel}: $expected",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = JetBrainsMonoFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

private fun editionLabel(edition: MinecraftEdition, strings: LeviStrings): String = when (edition) {
    MinecraftEdition.PREVIEW -> strings.previewBadge
    MinecraftEdition.RELEASE -> strings.releaseBadge
    MinecraftEdition.BETA -> strings.betaBadge
    MinecraftEdition.TRIAL -> strings.trialBadge
    MinecraftEdition.UNKNOWN -> "Unknown"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackageCardItem(
    pkg: InstalledMinecraftPackage,
    isSelected: Boolean,
    strings: LeviStrings,
    onClick: () -> Unit,
    onRemoveVaultApk: () -> Unit = {}
) {
    val border = when {
        isSelected && pkg.isExactTargetReady -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        isSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.medium,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("package_card_${pkg.vaultId ?: pkg.packageName}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pkg.appLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pkg.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pkg.isVaultApk) {
                        IconButton(
                            onClick = onRemoveVaultApk,
                            modifier = Modifier.testTag("delete_vault_${pkg.vaultId ?: pkg.packageName}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = strings.removeVaultApkDesc,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (pkg.isExactTargetReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = pkg.versionName,
                        tint = if (pkg.isExactTargetReady) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        }
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (pkg.isVaultApk) {
                    InfoPill(label = strings.vaultApkBadge, highlight = true)
                }
                InfoPill("${strings.packageVersionLabel}: ${pkg.versionName}")
                InfoPill("${strings.versionCodeLabel}: ${pkg.versionCode}")
                InfoPill(
                    label = if (pkg.isVaultApk) strings.vaultPackageIsolatedBadge else editionLabel(pkg.edition, strings),
                    highlight = pkg.isVaultApk || pkg.edition == MinecraftEdition.PREVIEW
                )
                InfoPill(
                    label = if (pkg.hasArm64Support) strings.arm64ReadyBadge else strings.arm64MissingBadge,
                    highlight = pkg.hasArm64Support
                )
                InfoPill(
                    if (pkg.splitSourceDirs.isNotEmpty()) {
                        "${strings.splitApkBadge} (${pkg.splitSourceDirs.size})"
                    } else {
                        strings.singleApkBadge
                    }
                )
                InfoPill(
                    label = if (pkg.isExactTargetReady) strings.targetMatchExact else strings.targetMatchMismatch,
                    highlight = pkg.isExactTargetReady
                )
                if (pkg.signingCertSha256.isNotBlank()) {
                    InfoPill("${strings.signingCertLabel}: ${pkg.signingCertSha256.take(12)}...")
                }
            }

            pkg.targetMismatchReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHealthCard(
    lib: ElfInspectionResult,
    strings: LeviStrings
) {
    val statusColor = if (lib.isValid) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lib.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatBytes(lib.sizeBytes),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = statusColor
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                InfoPill(
                    label = if (lib.hasElfMagic) strings.elfValidBadge else strings.elfInvalidBadge,
                    highlight = lib.hasElfMagic
                )
                InfoPill(
                    label = if (lib.sizeBytes > 1_048_576L) {
                        strings.sizeOverOneMbBadge
                    } else {
                        strings.sizeUnderOneMbBadge
                    },
                    highlight = lib.sizeBytes > 1_048_576L
                )
                InfoPill("${strings.architectureLabel}: ${lib.machineName}")
                InfoPill(
                    label = "${strings.loadSourceLabel}: ${lib.loadSource.name}",
                    highlight = lib.loadSource == LibraryLoadSource.NATIVE_DIR ||
                        lib.loadSource == LibraryLoadSource.APK_DIRECT_ZIP
                )
                InfoPill("${strings.sourceOriginLabel}: ${lib.sourceOrigin}")
            }

            Text(
                text = lib.validationNote,
                style = MaterialTheme.typography.labelSmall,
                color = if (lib.isValid) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun InfoPill(
    label: String,
    highlight: Boolean = false
) {
    Surface(
        color = if (highlight) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.2f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
