package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.hooks.useThrottle
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.rikkahub.utils.UpdateDownload
import me.rerere.rikkahub.utils.UpdateDownloadLogic
import me.rerere.rikkahub.utils.UpdateDownloadState
import me.rerere.rikkahub.utils.Version
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.stringRes
import me.rerere.rikkahub.utils.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@OptIn(ExperimentalTime::class)
@Composable
fun UpdateCard(vm: ChatVM) {
    val state by vm.updateState.collectAsStateWithLifecycle()
    // The download state lives in UpdateDownloadManager (in-app), never in a notification.
    val downloadState by vm.updateDownloadManager.state.collectAsStateWithLifecycle()
    state.onError {
        Card {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.update_card_check_failed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = it.message ?: stringResource(R.string.update_card_unknown_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
    state.onSuccess { info ->
        var showDetail by remember { mutableStateOf(false) }
        var dismissed by remember { mutableStateOf(false) }
        val current = remember { Version(BuildConfig.VERSION_NAME) }
        val latest = remember(info) { Version(info.version) }
        val downloadForThisRelease = downloadState.takeIf { it.version == info.version }

        if (latest > current && !dismissed) {
            Card(
                onClick = {
                    showDetail = true
                }
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.update_card_new_version_found, info.version),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { dismissed = true }) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.update_card_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MarkdownBlock(
                        content = info.changelog,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 200.dp)
                    )
                    // The progress is visible directly on the card, so it never "disappears"
                    // when the sheet is closed.
                    if (downloadForThisRelease != null) {
                        UpdateDownloadPanel(
                            state = downloadForThisRelease,
                            onInstall = { vm.updateDownloadManager.install() },
                            onRetry = { vm.updateDownloadManager.retry() },
                            onCancel = { vm.updateDownloadManager.cancel() },
                            onDismiss = { vm.updateDownloadManager.dismiss() },
                        )
                    }
                }
            }
        }
        if (showDetail) {
            val downloadHandler = useThrottle<UpdateDownload>(500) { item ->
                vm.updateDownloadManager.start(item, info.version)
            }
            ModalBottomSheet(
                onDismissRequest = { showDetail = false },
                sheetState = rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = info.version,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = Instant.parse(info.publishedAt).toJavaInstant().toLocalDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    MarkdownBlock(
                        content = info.changelog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (downloadForThisRelease != null) {
                        UpdateDownloadPanel(
                            state = downloadForThisRelease,
                            onInstall = { vm.updateDownloadManager.install() },
                            onRetry = { vm.updateDownloadManager.retry() },
                            onCancel = { vm.updateDownloadManager.cancel() },
                            onDismiss = { vm.updateDownloadManager.dismiss() },
                        )
                    } else {
                        info.downloads.fastForEach { downloadItem ->
                            OutlinedCard(
                                onClick = { downloadHandler(downloadItem) },
                            ) {
                                ListItem(
                                    headlineContent = { Text(text = downloadItem.name) },
                                    supportingContent = { Text(text = downloadItem.size) },
                                    leadingContent = {
                                        Icon(imageVector = HugeIcons.Download01, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Persistent in-app status for the update download: progress bar, percentage, sizes, speed,
 * clear state text and the matching action button.
 */
@Composable
private fun UpdateDownloadPanel(
    state: UpdateDownloadState,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            is UpdateDownloadState.Downloading -> {
                val percent = UpdateDownloadLogic.progressPercent(state.bytesDownloaded, state.totalBytes)
                if (state.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = stringResource(R.string.update_card_downloading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.totalBytes > 0L) {
                    Text(
                        text = stringResource(
                            R.string.update_card_progress_detail,
                            percent,
                            UpdateDownloadLogic.formatBytes(state.bytesDownloaded),
                            UpdateDownloadLogic.formatBytes(state.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UpdateDownloadLogic.remainingSeconds(
                        state.bytesDownloaded, state.totalBytes, state.bytesPerSecond,
                    )?.let { remaining ->
                        Text(
                            text = stringResource(
                                R.string.update_card_speed_detail,
                                UpdateDownloadLogic.formatSpeed(state.bytesPerSecond),
                                UpdateDownloadLogic.formatDuration(remaining),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onCancel) {
                    Text(text = stringResource(R.string.update_card_cancel))
                }
            }

            is UpdateDownloadState.Validating -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.update_card_validating),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is UpdateDownloadState.Paused -> {
                Text(
                    text = stringResource(R.string.update_card_downloading) + " — " +
                        stringResource(state.reason.stringRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.update_card_resumable_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) {
                        Text(text = stringResource(R.string.update_card_resume))
                    }
                    TextButton(onClick = onCancel) {
                        Text(text = stringResource(R.string.update_card_cancel))
                    }
                }
            }

            is UpdateDownloadState.Ready -> {
                Text(
                    text = "✅ " + stringResource(R.string.update_card_ready),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall) {
                        Text(text = stringResource(R.string.update_card_install))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.update_card_dismiss))
                    }
                }
            }

            is UpdateDownloadState.Failed -> {
                Text(
                    text = stringResource(R.string.update_download_state_failed, stringResource(state.reason.stringRes())),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry) {
                        Text(text = stringResource(R.string.update_card_retry))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.update_card_dismiss))
                    }
                }
            }

            UpdateDownloadState.Idle -> {
                Text(
                    text = stringResource(R.string.update_download_state_idle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
