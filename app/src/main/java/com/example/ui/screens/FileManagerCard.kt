package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.LeviStrings
import com.example.model.FileBrowserMode
import com.example.model.FileManagerEntry
import com.example.model.LeviUiState
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun FileManagerCard(
    state: LeviUiState,
    strings: LeviStrings,
    onSwitchMode: (FileBrowserMode) -> Unit,
    onOpenSafFolder: (Uri) -> Unit,
    onEntryClick: (FileManagerEntry) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateSafSubfolder: (String) -> Unit,
    onDeleteEntry: (FileManagerEntry) -> Unit,
    onDismissPreview: () -> Unit
) {
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val pickSafFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onOpenSafFolder(uri)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag("file_manager_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = strings.fileManagerTitle,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = strings.fileManagerTitle,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Text(
                text = strings.fileManagerDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.fileBrowserMode == FileBrowserMode.APP_CACHE,
                    onClick = { onSwitchMode(FileBrowserMode.APP_CACHE) },
                    label = { Text(strings.fileTabAppCache) },
                    modifier = Modifier.testTag("file_tab_app_cache")
                )
                FilterChip(
                    selected = state.fileBrowserMode == FileBrowserMode.SAF_TREE,
                    onClick = { onSwitchMode(FileBrowserMode.SAF_TREE) },
                    label = { Text(strings.fileTabSafBrowser) },
                    modifier = Modifier.testTag("file_tab_saf")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateUp,
                    modifier = Modifier.testTag("file_manager_up_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = strings.folderUpButton,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(strings.folderUpButton)
                }

                if (state.fileBrowserMode == FileBrowserMode.SAF_TREE) {
                    OutlinedButton(
                        onClick = { pickSafFolderLauncher.launch(null) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pick_saf_folder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = strings.pickSafFolderButton,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = strings.pickSafFolderButton,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.fileBrowserSafStack.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showNewFolderDialog = true },
                            modifier = Modifier.testTag("new_saf_folder_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = strings.newFolderButton,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(strings.newFolderButton)
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = "${state.fileBrowserRootLabel}: ${state.fileBrowserCurrentPath.ifBlank { "/" }}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }

            if (state.isLoadingFiles) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "...",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            } else if (state.fileBrowserEntries.isEmpty()) {
                Text(
                    text = strings.noFilesFound,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.fileBrowserEntries.take(60).forEach { entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.isDirectory || entry.canPreview) {
                                    onEntryClick(entry)
                                }
                                .testTag("file_entry_${entry.name}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) {
                                        Icons.Default.Folder
                                    } else {
                                        Icons.AutoMirrored.Filled.InsertDriveFile
                                    },
                                    contentDescription = entry.name,
                                    tint = if (entry.isDirectory) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondary
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (entry.isDirectory) {
                                            "DIR • ${formatBytes(entry.sizeBytes)}"
                                        } else {
                                            "${entry.mimeType.ifBlank { "file" }} • ${formatBytes(entry.sizeBytes)}"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (entry.canDelete) {
                                    IconButton(
                                        onClick = { onDeleteEntry(entry) },
                                        modifier = Modifier.testTag("delete_file_${entry.name}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "${strings.cancelButton} ${entry.name}",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            state.fileManagerStatusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(strings.newFolderButton) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(strings.newFolderHint) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_folder_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            onCreateSafSubfolder(newFolderName)
                            newFolderName = ""
                            showNewFolderDialog = false
                        }
                    },
                    enabled = newFolderName.isNotBlank(),
                    modifier = Modifier.testTag("confirm_create_folder_button")
                ) {
                    Text(strings.createButton)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(strings.cancelButton)
                }
            }
        )
    }

    if (state.filePreviewTitle != null && state.filePreviewContent != null) {
        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = {
                Text(
                    text = "${strings.previewTitle}: ${state.filePreviewTitle}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                    Text(
                        text = state.filePreviewContent,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDismissPreview,
                    modifier = Modifier.testTag("close_file_preview_button")
                ) {
                    Text(strings.closeButton)
                }
            }
        )
    }
}
