package dev.chaichai.mobile.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.chaichai.mobile.core.contracts.PlaybackDiagnostics

/**
 * Opt-in playback diagnostics (Playback Polish, #35 AC3). OFF by default: the [Switch] reflects and
 * writes through [PlaybackDiagnostics.enabled]/[PlaybackDiagnostics.setEnabled] directly, so there is no
 * local draft state to accidentally leave "on" without persisting. The scope explanation is always
 * visible so a person can read what turning this on means BEFORE they opt in, not after. Once enabled,
 * "View diagnostics" reveals the same [PlaybackDiagnostics.snapshot] report shown here and available to
 * copy — this UI never assembles or filters content itself, so it can only ever show/copy exactly what
 * the redacted report already contains.
 */
@Composable
fun PlaybackDiagnosticsSection(
    diagnostics: PlaybackDiagnostics,
    modifier: Modifier = Modifier,
) {
    val enabled by diagnostics.enabled.collectAsState()
    var showReport by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Playback diagnostics",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Off by default. When on, this only captures device and server playback CAPABILITY " +
                "(supported formats, scale-mode support) and the KIND of the most recent playback " +
                "failure — never a server token, a full media or stream URL, subtitle text, or a " +
                "library title.",
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable diagnostics", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = enabled,
                onCheckedChange = { diagnostics.setEnabled(it); if (!it) showReport = false },
                modifier = Modifier.testTag("diagnostics-enable-switch").semantics {
                    contentDescription = if (enabled) "Turn playback diagnostics off" else "Turn playback diagnostics on"
                },
            )
        }
        if (enabled) {
            TextButton(
                onClick = { showReport = !showReport },
                modifier = Modifier.heightIn(min = 48.dp).testTag("view-diagnostics-report"),
            ) { Text(if (showReport) "Hide diagnostics report" else "View diagnostics report") }
            if (showReport) {
                val report = diagnostics.snapshot()
                Card(Modifier.fillMaxWidth().testTag("diagnostics-report-card")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            report.text,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("diagnostics-report-text")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(report.text)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("copy-diagnostics-report"),
                        ) { Text("Copy report") }
                    }
                }
            }
        }
    }
}
