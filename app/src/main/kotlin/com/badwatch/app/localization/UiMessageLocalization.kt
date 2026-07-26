package com.badwatch.app.localization

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.badwatch.app.R

/** Localizes bounded controller errors; unknown developer failures become a safe generic message. */
@Composable
fun localizedUiMessage(message: String): String = when {
    message.startsWith("The saved match could not be read.") ->
        stringResource(R.string.error_match_unreadable)
    message.startsWith("The saved shadow routine could not be read.") ->
        stringResource(R.string.error_routine_unreadable)
    message.startsWith("Dashboard connection failed: HTTP ") ->
        stringResource(R.string.error_dashboard_http, message.substringAfterLast(' '))
    message == "Sensor stream stopped" ||
        message == "Sensor stream stopped unexpectedly" ->
        stringResource(R.string.error_sensor_stream_stopped)
    message == "Capture metadata was not available" ->
        stringResource(R.string.error_capture_metadata)
    message == "Capture could not start" -> stringResource(R.string.error_capture_start)
    message == "Capture could not be saved" -> stringResource(R.string.error_capture_save)
    message == "Session could not start" -> stringResource(R.string.error_session_start)
    message == "Session could not be saved" -> stringResource(R.string.error_session_save)
    message == "The previous session is waiting to be saved" ->
        stringResource(R.string.error_session_pending_save)
    message == "Could not create the session recovery checkpoint" ->
        stringResource(R.string.error_session_checkpoint)
    message == "Could not update the session recovery checkpoint" ->
        stringResource(R.string.error_session_checkpoint_update)
    message == "Could not close the saved match." -> stringResource(R.string.error_match_close)
    message == "Match could not start because it could not be saved." ->
        stringResource(R.string.error_match_start_save)
    message == "Score change was not applied because it could not be saved." ->
        stringResource(R.string.error_match_change_save)
    message == "Could not remove the damaged match file." ->
        stringResource(R.string.error_match_remove)
    message == "Score is visible but not safely saved." ->
        stringResource(R.string.error_match_unsafe)
    message == "Could not close the saved routine." ->
        stringResource(R.string.error_routine_close)
    message == "Routine could not start because it could not be saved." ->
        stringResource(R.string.error_routine_start_save)
    message == "Routine change was not applied because it could not be saved." ->
        stringResource(R.string.error_routine_change_save)
    message == "The restored routine could not be paused safely." ->
        stringResource(R.string.error_routine_restore_pause)
    message == "Could not remove the damaged shadow routine." ->
        stringResource(R.string.error_routine_remove)
    message == "Routine is visible but not safely saved." ->
        stringResource(R.string.error_routine_unsafe)
    message == "Enter a server URL" -> stringResource(R.string.error_dashboard_url)
    message == "Connection failed" -> stringResource(R.string.error_dashboard_connection)
    message == "Dashboard returned an invalid status" ||
        message == "Dashboard uses an incompatible data schema" ||
        message == "Dashboard URL must use https:// or http://" ||
        message == "Put the token in its own field" ->
        stringResource(R.string.error_dashboard_connection)
    message.contains("no gyroscope", ignoreCase = true) ->
        stringResource(R.string.error_gyroscope_missing)
    message.contains("gyroscope could not start", ignoreCase = true) ->
        stringResource(R.string.error_gyroscope_start)
    else -> stringResource(R.string.error_unexpected)
}
