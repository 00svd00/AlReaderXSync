package ru.samorez.alreaderxsync.ui

import android.app.Application
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.SyncDirection
import ru.samorez.alreaderxsync.data.SyncMode
import ru.samorez.alreaderxsync.data.SyncResult
import ru.samorez.alreaderxsync.data.SyncState
import ru.samorez.alreaderxsync.sync.SyncRepository
import ru.samorez.alreaderxsync.transport.SyncTransport

/**
 * ViewModel, управляющая процессом синхронизации.
 *
 * Держит [SyncUiState] и предоставляет методы запуска/отмены/сброса.
 * Реальная работа делегируется [SyncRepository].
 *
 * Транспорт ([SyncTransport]) создаётся и подготавливается снаружи
 * (например, из Activity/Fragment), а сюда передаётся уже готовым к работе.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SyncViewModel"
    }

    /**
     * Состояние UI синхронизации.
     */
    data class SyncUiState(
        val status: SyncStatus = SyncStatus.IDLE,
        val progress: Int = 0,
        val currentFile: String? = null,
        val transferredCount: Int = 0,
        val deletedCount: Int = 0,
        val transportUsed: String? = null,
        val errorMessage: String? = null,
        val role: DeviceRole? = null
    )

    /**
     * Статусы жизненного цикла синхронизации.
     */
    enum class SyncStatus {
        IDLE,
        DISCOVERING,
        CONNECTING,
        COMPARING,
        TRANSFERRING,
        COMPLETE,
        ERROR
    }

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    /** Текущая задача синхронизации (для отмены). */
    private var currentJob: Job? = null

    /**
     * Устанавливает роль устройства и отражает её в UI-состоянии.
     */
    fun setRole(role: DeviceRole) {
        _uiState.value = _uiState.value.copy(role = role)
    }

    /**
     * Обновляет прогресс текущей задачи.
     *
     * Используется вызывающей стороной (Activity/Fragment) для отображения
     * подготовительных этапов (например, "подключение к Wi-Fi Direct"),
     * которые происходят до старта [startSyncWithTransportAndWait].
     */
    fun setTaskProgress(progress: Int, currentFile: String? = null) {
        _uiState.value = _uiState.value.copy(
            progress = progress.coerceIn(0, 100),
            currentFile = currentFile
        )
    }

    /**
     * Сбрасывает UI-состояние синхронизации в исходное (IDLE).
     *
     * Вызывается после завершения всех задач синхронизации
     * (обычно из MainActivity после обработки всех TaskSpec).
     *
     * Сохраняет transportUsed — чтобы пользователь видел,
     * через какой транспорт прошла последняя синхронизация.
     */
    fun resetToIdle() {
        Log.d(TAG, "resetToIdle called")
        _uiState.value = _uiState.value.copy(
            status = SyncStatus.IDLE,
            progress = 0,
            currentFile = null,
            transferredCount = 0,
            deletedCount = 0,
            errorMessage = null
            // transportUsed НЕ сбрасываем — оставляем для отображения
        )
    }

    /**
     * Запускает синхронизацию через уже готовый [transport] и ждёт её завершения.
     *
     * Метод suspend — вызывающая сторона сама решает, запускать его
     * в собственном скоупе или через [launchSync].
     *
     * @param transport    подготовленный транспорт.
     * @param sourceRoot   корневая папка-источник.
     * @param targetRoot   корневая папка-назначение.
     * @param mode         режим синхронизации ([SyncMode]).
     * @param direction    направление обмена ([SyncDirection]).
     * @param excludedPaths пути, исключённые из синхронизации.
     * @return итоговый результат; при отсутствии роли возвращается
     *         результат с ошибкой "Role not set".
     */
    suspend fun startSyncWithTransportAndWait(
        transport: SyncTransport,
        sourceRoot: DocumentFile,
        targetRoot: DocumentFile,
        mode: SyncMode,
        direction: SyncDirection,
        excludedPaths: List<String>
    ): SyncResult {
        Log.d(TAG, "startSyncWithTransportAndWait called, transport=${transport.name}")

        // Роль обязательна для репозитория (localRole). Без неё — ошибка.
        val role = uiState.value.role ?: run {
            Log.e(TAG, "Role not set")
            return SyncResult(
                transferred = 0,
                deleted = 0,
                errors = listOf("Role not set"),
                transportUsed = ""
            )
        }

        Log.d(TAG, "Role=$role, updating uiState to CONNECTING")

        // Готовим UI к работе: фиксируем транспорт и обнуляем счётчики.
        // Дальнейшие переходы статуса делает updateUiState через onProgress.
        _uiState.value = _uiState.value.copy(
            status = SyncStatus.CONNECTING,
            progress = 0,
            currentFile = null,
            transferredCount = 0,
            deletedCount = 0,
            transportUsed = transport.name,
            errorMessage = null
        )

        Log.d(TAG, "Creating SyncRepository")
        val repository = SyncRepository(getApplication())

        Log.d(TAG, "Calling repository.sync()")
        // Статус вручную после sync(...) НЕ выставляем:
        // SyncRepository сам вызывает onProgress(SyncState.Complete(result))
        // или onProgress(SyncState.Error(...)), а updateUiState это отработает.
        val result = repository.sync(
            transport = transport,
            sourceRoot = sourceRoot,
            targetRoot = targetRoot,
            mode = mode,
            excludedPaths = excludedPaths,
            localRole = role,
            direction = direction,
            onProgress = { state ->
                Log.d(TAG, "onProgress: $state")
                updateUiState(state)
            }
        )

        Log.d(
            TAG,
            "repository.sync() returned: transferred=${result.transferred}, " +
                    "errors=${result.errors.size}"
        )

        return result
    }

    /**
     * Удобная обёртка: запускает [startSyncWithTransportAndWait] в [viewModelScope]
     * и сохраняет [Job] в [currentJob] для последующей отмены.
     */
    fun launchSync(
        transport: SyncTransport,
        sourceRoot: DocumentFile,
        targetRoot: DocumentFile,
        mode: SyncMode,
        direction: SyncDirection,
        excludedPaths: List<String>
    ) {
        Log.d(TAG, "launchSync called, transport=${transport.name}")
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                startSyncWithTransportAndWait(
                    transport = transport,
                    sourceRoot = sourceRoot,
                    targetRoot = targetRoot,
                    mode = mode,
                    direction = direction,
                    excludedPaths = excludedPaths
                )
            } catch (ce: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "launchSync cancelled")
                _uiState.value = _uiState.value.copy(
                    status = SyncStatus.IDLE,
                    currentFile = null
                )
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "launchSync failed", t)
                _uiState.value = _uiState.value.copy(
                    status = SyncStatus.ERROR,
                    errorMessage = t.message ?: "Неизвестная ошибка синхронизации"
                )
            }
        }
    }

    /**
     * Применяет очередное [SyncState] к UI-состоянию.
     *
     * Единый маппинг: сначала вычисляем новый [SyncStatus], затем
     * одним `copy(...)` обновляем все поля.
     */
    private fun updateUiState(state: SyncState) {
        Log.d(TAG, "updateUiState: $state")

        val newStatus = when (state) {
            is SyncState.Idle -> SyncStatus.IDLE
            is SyncState.Discovering -> SyncStatus.DISCOVERING
            is SyncState.Connecting -> SyncStatus.CONNECTING
            is SyncState.Comparing -> SyncStatus.COMPARING
            is SyncState.Transferring -> SyncStatus.TRANSFERRING
            is SyncState.Complete -> SyncStatus.COMPLETE
            is SyncState.Error -> SyncStatus.ERROR
        }

        _uiState.value = _uiState.value.copy(
            status = newStatus,
            progress = when (state) {
                is SyncState.Transferring -> state.progress
                is SyncState.Complete -> 100
                else -> _uiState.value.progress
            },
            currentFile = when (state) {
                is SyncState.Transferring -> state.currentFile
                is SyncState.Complete -> null
                is SyncState.Error -> null
                else -> _uiState.value.currentFile
            },
            transferredCount = when (state) {
                is SyncState.Transferring -> state.transferred
                is SyncState.Complete -> state.result.transferred
                else -> _uiState.value.transferredCount
            },
            deletedCount = when (state) {
                is SyncState.Complete -> state.result.deleted
                else -> _uiState.value.deletedCount
            },
            errorMessage = when (state) {
                is SyncState.Error -> state.message
                else -> null
            }
        )

        Log.d(
            TAG,
            "updateUiState: status=$newStatus, " +
                    "progress=${_uiState.value.progress}"
        )
    }

    /**
     * Отменяет текущую синхронизацию, если она выполняется.
     *
     * Сбрасывает счётчики и статус в IDLE, оставляя сообщение
     * "Отменено пользователем" для отображения.
     */
    fun cancelSync() {
        Log.d(TAG, "cancelSync called")
        currentJob?.cancel()
        currentJob = null
        _uiState.value = _uiState.value.copy(
            status = SyncStatus.IDLE,
            progress = 0,
            currentFile = null,
            errorMessage = "Отменено пользователем"
        )
    }
}