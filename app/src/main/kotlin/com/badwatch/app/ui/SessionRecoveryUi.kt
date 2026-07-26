package com.badwatch.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.badwatch.app.R
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.theme.CourtColors
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.effectiveWindow
import com.badwatch.core.sync.knownProcessAbsenceMillisInEffectiveWindow

/** Effective reviewed time for which the recording process was actually present. */
internal val SessionExport.observedEffectiveDurationMillis: Long
    get() = (effectiveWindow().durationMillis - knownProcessAbsenceMillisInEffectiveWindow)
        .coerceAtLeast(0L)

/** Null keeps gap-free cards compact; a value is always the exact effective-window overlap. */
internal val SessionExport.knownUnobservedMillisForDisplay: Long?
    get() = knownProcessAbsenceMillisInEffectiveWindow.takeIf { it > 0L }

@Composable
internal fun KnownUnobservedMarker(export: SessionExport) {
    val unobservedMillis = export.knownUnobservedMillisForDisplay ?: return
    Text(
        text = stringResource(
            R.string.session_known_unobserved_compact,
            formatDuration(unobservedMillis)
        ),
        style = MaterialTheme.typography.bodyExtraSmall,
        color = CourtColors.Warning
    )
}
