package ru.samorez.alreaderxsync.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.SyncDirection
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.data.SyncSettings
import ru.samorez.alreaderxsync.storage.DirectoryAccessManager
import ru.samorez.alreaderxsync.storage.SettingsRepository

/**
 * ViewModel главного экрана.
 *
 * URI каталогов хранит [DirectoryAccessManager] (через SAF),
 * остальные настройки — [SettingsRepository] (SharedPreferences).
 *
 * Поля mode/direction добавлены для сохранения выбора пользователя
 * между перезапусками приложения. null означает «не выбрано»
 * (на практике в onCreate выставляется безопасный дефолт).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Состояние главного экрана.
     */
    data class MainUiState(
        val alreaderUri: Uri? = null,
        val booksUri: Uri? = null,
        val booksInsideAlreader: Boolean = false,
        val settings: SyncSettings = SyncSettings(),
        val role: DeviceRole? = null,
        val showAlreaderWarning: Boolean = true,
        val mode: SyncMode? = null,           // NEW
        val direction: SyncDirection? = null  // NEW
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val settingsRepository = SettingsRepository(application)
    private val directoryAccessManager = DirectoryAccessManager(application)

    init {
        // Загружаем сохранённые настройки при создании ViewModel.
        viewModelScope.launch {
            loadSettings()
        }
    }

    /**
     * Читает текущее состояние из DirectoryAccessManager и SettingsRepository.
     */
    private fun loadSettings() {
        // URI каталогов — из DirectoryAccessManager (SAF).
        val alreaderUri = directoryAccessManager.getConfiguredUri(
            DirectoryAccessManager.KEY_ALREADER_DIR
        )
        val booksUri = directoryAccessManager.getConfiguredUri(
            DirectoryAccessManager.KEY_BOOKS_DIR
        )

        // Остальные настройки — из SettingsRepository.
        val booksInside = settingsRepository.isBooksInsideAlreader()
        val role = settingsRepository.getRole()
        val showWarning = settingsRepository.shouldShowAlreaderWarning()

        // NEW: режим и направление синхронизации.
        val mode = settingsRepository.getSyncMode()
        val direction = settingsRepository.getSyncDirection()

        // TransportConfig -> SyncSettings.
        // В C1 из TransportConfig удалены wifiLanEnabled/bluetoothEnabled,
        // вместо них добавлен transportMode + retry-поля.
        val config = settingsRepository.getTransportConfig()
        val settings = SyncSettings(
            transportMode = config.transportMode,
            wifiLanTimeoutMs = config.wifiLanTimeoutMs,
            bluetoothTimeoutMs = config.bluetoothTimeoutMs,
            wifiLanMaxRetries = config.wifiLanMaxRetries,
            wifiLanRetryDelayMs = config.wifiLanRetryDelayMs,
            bluetoothMaxRetries = config.bluetoothMaxRetries,
            bluetoothRetryDelayMs = config.bluetoothRetryDelayMs
        )

        _uiState.value = _uiState.value.copy(
            alreaderUri = alreaderUri,
            booksUri = booksUri,
            booksInsideAlreader = booksInside,
            settings = settings,
            role = role,
            showAlreaderWarning = showWarning,
            mode = mode,           // NEW
            direction = direction  // NEW
        )
    }

    /**
     * Устанавливает URI папки AlReader.
     *
     * Сохранение URI выполняет вызывающая сторона через
     * [DirectoryAccessManager.handleTreeUriResult] (после SAF-диалога),
     * здесь только обновляем UI-состояние.
     */
    fun setAlreaderDirectory(uri: Uri) {
        _uiState.value = _uiState.value.copy(alreaderUri = uri)
    }

    /**
     * Устанавливает URI папки книг.
     */
    fun setBooksDirectory(uri: Uri) {
        _uiState.value = _uiState.value.copy(booksUri = uri)
    }

    /**
     * Указывает, находится ли папка книг внутри папки AlReader.
     */
    fun setBooksInsideAlreader(inside: Boolean) {
        settingsRepository.setBooksInsideAlreader(inside)
        _uiState.value = _uiState.value.copy(booksInsideAlreader = inside)
    }

    /**
     * Устанавливает роль устройства.
     */
    fun setRole(role: DeviceRole) {
        settingsRepository.setRole(role)
        _uiState.value = _uiState.value.copy(role = role)
    }

    /**
     * Устанавливает режим синхронизации.
     * Сохраняет выбор в SharedPreferences, чтобы он пережил перезапуск.
     */
    fun setMode(mode: SyncMode) {
        settingsRepository.setSyncMode(mode)
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    /**
     * Устанавливает направление синхронизации.
     * Сохраняет выбор в SharedPreferences, чтобы он пережил перезапуск.
     */
    fun setDirection(direction: SyncDirection) {
        settingsRepository.setSyncDirection(direction)
        _uiState.value = _uiState.value.copy(direction = direction)
    }

    /**
     * Обновляет настройки синхронизации.
     *
     * [SyncSettings.toTransportConfig] обновлён в C3 и больше не
     * ссылается на удалённые поля, поэтому метод оставлен без изменений.
     */
    fun updateSettings(settings: SyncSettings) {
        settingsRepository.saveTransportConfig(settings.toTransportConfig())
        _uiState.value = _uiState.value.copy(settings = settings)
    }

    /**
     * Скрывает предупреждение об AlReader.
     *
     * @param neverShowAgain если true — предупреждение больше не показывать.
     */
    fun dismissAlreaderWarning(neverShowAgain: Boolean) {
        val show = !neverShowAgain
        settingsRepository.setShowAlreaderWarning(show)
        _uiState.value = _uiState.value.copy(showAlreaderWarning = show)
    }

    /**
     * Перечитывает состояние из репозиториев.
     * Полезно вызывать после возврата из SAF-диалога.
     */
    fun refresh() {
        loadSettings()
    }
}