package com.example.launcher

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.core.AppLanguage
import com.example.core.LeviLogger
import com.example.core.LeviPreferences
import com.example.core.LeviStrings
import com.example.core.stringsFor
import com.example.model.LaunchMode
import com.example.model.LaunchStageStatus
import com.example.model.LaunchStepState
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LeviLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EmbeddedMinecraftActivity : ComponentActivity() {

    private var stagesState by mutableStateOf<List<LaunchStageStatus>>(emptyList())
    private var outcomeState by mutableStateOf<LaunchExecutionOutcome?>(null)
    private var isExecuting by mutableStateOf(true)
    private var isHostedSurfaceActive by mutableStateOf(false)
    private var hostedHandle: HostedActivityHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val logger = LeviLogger.getInstance(applicationContext)
        val preferences = LeviPreferences(applicationContext)
        val langName = intent.getStringExtra(EXTRA_LANGUAGE) ?: AppLanguage.AR.name
        val language = runCatching { AppLanguage.valueOf(langName) }.getOrDefault(AppLanguage.AR)
        val preferredPkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val preferredVaultId = intent.getStringExtra(EXTRA_VAULT_ID)
        val modeName = intent.getStringExtra(EXTRA_LAUNCH_MODE) ?: LaunchMode.DIRECT_NATIVE.name
        val launchMode = runCatching { LaunchMode.valueOf(modeName) }.getOrDefault(LaunchMode.DIRECT_NATIVE)
        val verifyCache = intent.getBooleanExtra(EXTRA_VERIFY_CACHE, true)
        val strings = stringsFor(language)

        logger.info(
            "HOST",
            "Host console started (mode=${launchMode.name}, pkg=${preferredPkg ?: "auto"}, vault=${preferredVaultId ?: "none"})"
        )

        setContent {
            LeviLabTheme(language = language) {
                if (isHostedSurfaceActive && outcomeState?.failure == null) {
                    // Leave the hosted NativeActivity/GameActivity Surface unobstructed
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    LaunchVerificationHostScreen(
                    strings = strings,
                    stages = stagesState,
                    outcome = outcomeState,
                    isExecuting = isExecuting,
                    onRelaunchGame = {
                        val pkg = outcomeState?.targetPackage
                        if (pkg != null && !pkg.isVaultApk) {
                            val ok = InProcessLaunchCoordinator.launchInstalledPackage(this, pkg, logger)
                            if (ok) {
                                Toast.makeText(this, strings.embeddedDirectSuccessHint, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, strings.embeddedFailureTitle, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onShareFailure = {
                        shareDiagnosticReport(outcomeState, logger)
                    },
                    onReturnToLab = { finish() }
                )
                }
            }
        }

        lifecycleScope.launch {
            val outcome = InProcessLaunchCoordinator.executeVerifiedLaunch(
                activityContext = this@EmbeddedMinecraftActivity,
                preferredPackageName = preferredPkg,
                preferredVaultId = preferredVaultId,
                verifyCacheBeforeLaunch = verifyCache,
                launchMode = launchMode,
                strings = strings,
                logger = logger,
                onStageUpdate = { updated -> stagesState = updated }
            )
            outcomeState = outcome
            isExecuting = false
            if (outcome.hostedInProcess && outcome.hostedHandle != null) {
                hostedHandle = outcome.hostedHandle
                isHostedSurfaceActive = true
            }
            outcome.historyEntry?.let { entry ->
                withContext(Dispatchers.IO) {
                    preferences.appendLaunchHistory(entry)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        hostedHandle?.dispatchStart()
    }

    override fun onResume() {
        super.onResume()
        hostedHandle?.dispatchResume()
    }

    override fun onPause() {
        hostedHandle?.dispatchPause()
        super.onPause()
    }

    override fun onStop() {
        hostedHandle?.dispatchStop()
        super.onStop()
    }

    override fun onDestroy() {
        hostedHandle?.dispatchDestroy()
        hostedHandle = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        hostedHandle?.dispatchNewIntent(intent)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        hostedHandle?.dispatchLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        hostedHandle?.dispatchTrimMemory(level)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hostedHandle?.dispatchWindowFocusChanged(hasFocus)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hostedHandle?.dispatchConfigurationChanged(newConfig)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handle = hostedHandle
        if (handle != null && handle.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handle = hostedHandle
        if (handle != null && handle.dispatchTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun shareDiagnosticReport(outcome: LaunchExecutionOutcome?, logger: LeviLogger) {
        val failure = outcome?.failure
        val report = buildString {
            appendLine("=== LeviLab Launch Diagnostic Report ===")
            appendLine("Mode: ${outcome?.mode?.name ?: "unknown"}")
            appendLine("Hosted In-Process: ${outcome?.hostedInProcess ?: false}")
            appendLine("Package: ${outcome?.targetPackage?.packageName ?: "unknown"}")
            appendLine("Source: ${if (outcome?.targetPackage?.isVaultApk == true) "APK Vault (${outcome.targetPackage.vaultId})" else "Installed Package"}")
            appendLine("Edition: ${outcome?.targetPackage?.edition?.name ?: "unknown"}")
            appendLine("Version: ${outcome?.targetPackage?.versionName ?: "unknown"} (code=${outcome?.targetPackage?.versionCode ?: 0})")
            appendLine("Failed Stage: ${failure?.failedStage ?: "none"}")
            appendLine("Exception: ${failure?.exceptionClass ?: "N/A"}")
            appendLine("Reason: ${failure?.reason ?: "N/A"}")
            appendLine()
            appendLine("Stages:")
            outcome?.stages?.forEach { st ->
                appendLine("- [${st.state.name}] ${st.title}: ${st.detail}")
            }
            appendLine()
            appendLine("Recent Logs:")
            appendLine(logger.readFullFileText().lines().takeLast(60).joinToString("\n"))
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LeviLab Launch Report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Launch Report"))
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_VAULT_ID = "extra_vault_id"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_LAUNCH_MODE = "extra_launch_mode"
        const val EXTRA_VERIFY_CACHE = "extra_verify_cache"
        const val EXTRA_PREFER_DIRECT_APK = "extra_prefer_direct_apk"
    }
}

@Composable
private fun LaunchVerificationHostScreen(
    strings: LeviStrings,
    stages: List<LaunchStageStatus>,
    outcome: LaunchExecutionOutcome?,
    isExecuting: Boolean,
    onRelaunchGame: () -> Unit,
    onShareFailure: () -> Unit,
    onReturnToLab: () -> Unit
) {
    val failure = outcome?.failure
    val directSuccess = (outcome?.gameLaunchedExternally == true || outcome?.hostedInProcess == true) && failure == null
    val targetNotVault = outcome?.targetPackage != null && !outcome.targetPackage.isVaultApk

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = strings.embeddedDirectHostTitle,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = strings.embeddedDirectHostTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                OutlinedButton(
                    onClick = onReturnToLab,
                    modifier = Modifier.testTag("embedded_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = strings.embeddedBackToLabButton,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(strings.embeddedBackToLabButton)
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            text = strings.embeddedRunningStages,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    stages.forEach { stage ->
                        StageRow(stage = stage)
                    }
                }
            }

            if (directSuccess) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = strings.embeddedDirectSuccessHint,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (targetNotVault) {
                            Button(
                                onClick = onRelaunchGame,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("embedded_relaunch_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = strings.embeddedOpenExternalButton,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(strings.embeddedOpenExternalButton)
                            }
                        }
                    }
                }
            }

            if (failure != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = strings.embeddedFailureTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "${failure.failedStage} — ${failure.exceptionClass}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = failure.reason,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        if (failure.stackTracePreview.isNotBlank() && failure.stackTracePreview != failure.reason) {
                            Surface(
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = failure.stackTracePreview,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        Text(
                            text = strings.embeddedFallbackMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        if (failure.fallbackAvailable && targetNotVault) {
                            Button(
                                onClick = onRelaunchGame,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("embedded_fallback_official_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = strings.embeddedOpenExternalButton,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(strings.embeddedOpenExternalButton)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onReturnToLab,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("embedded_error_return_button")
                            ) {
                                Text(strings.embeddedBackToLabButton)
                            }
                            OutlinedButton(
                                onClick = onShareFailure,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("embedded_share_failure_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = strings.embeddedShareReportButton,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(strings.embeddedShareReportButton)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageRow(stage: LaunchStageStatus) {
    val icon = when (stage.state) {
        LaunchStepState.PENDING -> Icons.Default.RadioButtonUnchecked
        LaunchStepState.RUNNING -> Icons.Default.HourglassTop
        LaunchStepState.PASSED -> Icons.Default.CheckCircle
        LaunchStepState.FAILED -> Icons.Default.Error
    }
    val tint = when (stage.state) {
        LaunchStepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        LaunchStepState.RUNNING -> MaterialTheme.colorScheme.secondary
        LaunchStepState.PASSED -> MaterialTheme.colorScheme.primary
        LaunchStepState.FAILED -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stage.title,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stage.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (stage.detail.isNotBlank()) {
                Text(
                    text = stage.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
