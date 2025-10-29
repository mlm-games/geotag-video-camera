package org.app.geotagvideocamera.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val state: StateFlow<SettingsState> =
        repo.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    fun <T> update(spec: SettingSpec<T>, value: T) = viewModelScope.launch {
        repo.update(spec, value)
    }

    // Mark the one-time demo tiles notice as shown
    fun markDemoNoticeShown() = viewModelScope.launch {
        repo.setFlag("demoNoticeShown", true)
    }
}

/* Simple factory to create the VM from a Context */
class SettingsViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    override fun <T: ViewModel> create(modelClass: Class<T>): T {
        val repo = SettingsRepository(app)
        @Suppress("UNCHECKED_CAST")
        return SettingsViewModel(repo) as T
    }
}