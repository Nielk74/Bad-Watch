package com.badwatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.badwatch.app.AppContainer
import com.badwatch.app.data.StoredSession
import com.badwatch.app.domain.SessionController
import com.badwatch.app.domain.SessionState
import com.badwatch.core.model.Handedness
import com.badwatch.core.model.PlayerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state holder. Note that it does *not* own the session — [SessionController] does,
 * at application scope, because a session outlives this ViewModel every single time the
 * watch screen sleeps.
 */
class BadWatchViewModel(
    private val container: AppContainer
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = container.sessionController.state

    val history: StateFlow<List<StoredSession>> = container.sessionStore.sessions

    val profile: StateFlow<PlayerProfile> = container.settingsStore.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerProfile())

    val onboardingComplete: StateFlow<Boolean?> = container.settingsStore.onboardingComplete
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _dashboardUrl = MutableStateFlow<String?>(null)
    val dashboardUrl: StateFlow<String?> = _dashboardUrl.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            container.sessionStore.refresh()
            _dashboardUrl.value = container.settingsStore.dashboardUrl.first()
        }
    }

    fun completeOnboarding(handedness: Handedness) {
        viewModelScope.launch {
            container.settingsStore.setHandedness(handedness)
            container.settingsStore.setOnboardingComplete(true)
        }
    }

    fun setHandedness(handedness: Handedness) {
        viewModelScope.launch { container.settingsStore.setHandedness(handedness) }
    }

    fun setDashboard(url: String?, token: String?) {
        viewModelScope.launch {
            container.settingsStore.setDashboard(url, token)
            _dashboardUrl.value = url
        }
    }

    fun discardSession() {
        container.sessionController.discard()
    }

    fun acknowledge() {
        container.sessionController.acknowledge()
        viewModelScope.launch { container.sessionStore.refresh() }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { container.sessionStore.delete(sessionId) }
    }

    fun showMessage(text: String?) {
        _message.value = text
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BadWatchViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return BadWatchViewModel(container) as T
        }
    }
}
