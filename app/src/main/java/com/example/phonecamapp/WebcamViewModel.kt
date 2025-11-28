package com.example.phonecamapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.phonecamapp.data.WebcamRepository
import com.example.phonecamapp.data.LogEntity
import com.example.phonecamapp.data.SettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebcamUiState(
    val isStreaming: Boolean = false,
    val cameraSettings: CameraSettings = CameraSettings(1920, 1080, 30, "Back Camera", null),
    val currentProtocol: String = "RTSP",
    val publicIp: String = "Завантаження...",
    val isFrontCamera: Boolean = false,
    // Стан орієнтації: true = Horizontal (Landscape), false = Vertical (Portrait)
    val isLandscape: Boolean = false
)

class WebcamViewModel(
    application: Application,
    private val repository: WebcamRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WebcamUiState())
    val uiState: StateFlow<WebcamUiState> = _uiState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow: SharedFlow<String> = _eventFlow.asSharedFlow()

    val logsPagingFlow: Flow<PagingData<LogEntity>> = repository.getPagedLogs()
        .cachedIn(viewModelScope)

    init {
        loadSettings()
        fetchPublicIp()
    }

    private fun fetchPublicIp() {
        viewModelScope.launch {
            val ip = repository.getPublicIp()
            _uiState.update { it.copy(publicIp = ip) }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            repository.settingsFlow.collect { savedSettings ->
                if (savedSettings != null) {
                    _uiState.update { it.copy(
                        cameraSettings = it.cameraSettings.copy(
                            width = savedSettings.resolutionWidth,
                            height = savedSettings.resolutionHeight,
                            fps = savedSettings.fps,
                            cameraName = savedSettings.cameraName
                        ),
                        currentProtocol = savedSettings.protocol,
                        // Завантажуємо збережену орієнтацію
                        isLandscape = savedSettings.isLandscape
                    )}
                } else {
                    saveCurrentSettings()
                }
            }
        }
    }

    private fun saveCurrentSettings() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val entity = SettingsEntity(
                id = 1,
                cameraName = currentState.cameraSettings.cameraName,
                resolutionWidth = currentState.cameraSettings.width,
                resolutionHeight = currentState.cameraSettings.height,
                fps = currentState.cameraSettings.fps,
                protocol = currentState.currentProtocol,
                isLandscape = currentState.isLandscape
            )
            repository.saveSettings(entity)
        }
    }

    // Функція для зміни орієнтації
    fun toggleOrientation(isLandscape: Boolean) {
        // Змінюємо ширину/висоту місцями для коректності налаштувань, якщо треба
        // Але головне - прапорець isLandscape
        _uiState.update {
            it.copy(isLandscape = isLandscape)
        }
        saveCurrentSettings()
        viewModelScope.launch {
            val mode = if (isLandscape) "Горизонтальний (Landscape)" else "Вертикальний (Portrait)"
            repository.addLog("Орієнтацію змінено: $mode")
        }
    }

    fun updateProtocol(newProtocol: String) {
        _uiState.update { it.copy(currentProtocol = newProtocol) }
        saveCurrentSettings()
        viewModelScope.launch {
            repository.addLog("Протокол змінено на $newProtocol")
            _eventFlow.emit("Протокол змінено: $newProtocol")
        }
    }

    fun toggleCameraLens() {
        val newLensState = !_uiState.value.isFrontCamera
        _uiState.update {
            it.copy(
                isFrontCamera = newLensState,
                cameraSettings = it.cameraSettings.copy(
                    cameraName = if (newLensState) "Front Camera" else "Back Camera"
                )
            )
        }
        viewModelScope.launch {
            repository.addLog("Камеру змінено на: ${if (newLensState) "Front" else "Back"}")
        }
    }

    fun clearLogs() {
        viewModelScope.launch { repository.clearAllLogs() }
    }

    fun toggleStreaming() {
        if (!_uiState.value.isStreaming) startStreaming() else stopStreaming()
    }

    private fun startStreaming() {
        _uiState.update { it.copy(
            isStreaming = true,
            cameraSettings = it.cameraSettings.copy(serverIp = it.publicIp)
        )}
        viewModelScope.launch { repository.addLog("Трансляцію розпочато") }
    }

    private fun stopStreaming() {
        _uiState.update { it.copy(
            isStreaming = false,
            cameraSettings = it.cameraSettings.copy(serverIp = null)
        )}
        viewModelScope.launch { repository.addLog("Трансляцію зупинено") }
    }
}

class WebcamViewModelFactory(
    private val application: Application,
    private val repository: WebcamRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebcamViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WebcamViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}