package ru.samorez.alreaderxsync

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.samorez.alreaderxsync.data.*
import ru.samorez.alreaderxsync.service.SyncServerService
import ru.samorez.alreaderxsync.service.SyncService
import ru.samorez.alreaderxsync.storage.DirectoryAccessManager
import ru.samorez.alreaderxsync.storage.SettingsRepository
import ru.samorez.alreaderxsync.transport.BluetoothPairingHelper
import ru.samorez.alreaderxsync.ui.MainViewModel
import ru.samorez.alreaderxsync.ui.SyncViewModel

/**
 * Главная активность.
 *
 *  PHONE  — при нажатии «Синхронизировать» запускает
 *           foreground service SyncService, который выполняет
 *           всю логику (handshake, выбор транспорта, цикл
 *           по задачам). MainActivity наблюдает за
 *           SyncService.stateFlow и обновляет UI.
 *  READER — при нажатии «Start Server» запускает
 *           foreground service SyncServerService, который
 *           поднимает TCP-сервер + Bluetooth RFCOMM и ждёт
 *           Phone. MainActivity наблюдает за
 *           SyncServerService.statusFlow и обновляет UI.
 *
 * ВАЖНО (restoreFromPreferences):
 *   mode и direction устанавливаются ПЕРВЫМИ, независимо
 *   от того, выбрана ли роль. Иначе при первом запуске
 *   showRoleSelectionDialog() + return оставляли RadioGroup
 *   «Режим» и «Направление» пустыми до перезапуска.
 *
 * ВАЖНО (validateBeforeSync):
 *   Перед запуском синхронизации и перед «Сменить устройство»
 *   проверяется наличие каталогов AlReaderX (и Books, если
 *   книги не внутри AlReaderX). Это защищает от NPE в
 *   SyncService / SyncRepository.
 *
 * ВАЖНО (pendingBtAction):
 *   При нехватке Bluetooth-разрешений или при выключенном
 *   Bluetooth сохранённое действие выполняется после выдачи
 *   разрешений / включения адаптера. Так избегаем краша
 *   SecurityException при запуске ACTION_REQUEST_DISCOVERABLE.
 */
class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val syncViewModel: SyncViewModel by viewModels()

    private lateinit var directoryAccessManager: DirectoryAccessManager
    private lateinit var bluetoothHelper: BluetoothPairingHelper
    private lateinit var settingsRepository: SettingsRepository

    // --- View-поля: блок PHONE ---
    private lateinit var layoutPhone: LinearLayout
    private lateinit var tvLastPeer: TextView
    private lateinit var btnSelectPeer: Button

    private lateinit var rgMode: RadioGroup
    private lateinit var rbReplace: RadioButton
    private lateinit var rbMerge: RadioButton
    private lateinit var btnModeHelp: ImageButton

    private lateinit var rgDirection: RadioGroup
    private lateinit var rbPhoneToReader: RadioButton
    private lateinit var rbReaderToPhone: RadioButton
    private lateinit var btnDirectionHelp: ImageButton

    private lateinit var btnSync: Button
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var tvTransport: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCancel: Button

    // --- View-поля: блок READER ---
    private lateinit var layoutReader: LinearLayout
    private lateinit var tvServerStatus: TextView
    private lateinit var tvServerAddress: TextView
    private lateinit var btnStartStop: Button

    // --- Лаунчеры ---
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var enableBtLauncher: ActivityResultLauncher<Intent>
    private lateinit var discoverableLauncher: ActivityResultLauncher<Intent>

    /** Флаг активной синхронизации Phone. Блокирует btnSync. */
    private var isSyncing: Boolean = false

    /**
     * Флаг «UI обновляется программно». Пока true — слушатели
     * RadioGroup игнорируют изменения, чтобы не сохранять те же
     * значения обратно в SettingsRepository и не зацикливаться.
     */
    private var isUpdatingUi: Boolean = false

    /**
     * MAC-адрес, для которого ждём разрешения.
     * Если пользователь запустил синхронизацию, но не выдал
     * разрешения — сохраняем MAC и продолжаем после выдачи.
     */
    private var pendingPeerMac: String? = null

    /**
     * Флаг ожидания разрешения POST_NOTIFICATIONS перед
     * запуском SyncServerService.
     */
    private var pendingServerStart: Boolean = false

    /**
     * Действие, которое нужно выполнить после выдачи Bluetooth-
     * разрешений (или включения Bluetooth). Например, открытие
     * диалога discoverable для «Сменить устройство».
     */
    private var pendingBtAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupEdgeToEdge()
        setContentView(R.layout.activity_main)

        directoryAccessManager = DirectoryAccessManager(this)
        bluetoothHelper = BluetoothPairingHelper(this)
        settingsRepository = SettingsRepository(this)

        bindViews()
        setupToolbar()
        applyWindowInsets()
        setupLaunchers()
        restoreFromPreferences()
        setupListeners()
        observeViewModels()

        requestNotificationPermissionIfNeeded()

        // Явно установить состояние кнопок по текущим
        // stateFlow.value — защита от пересоздания Activity,
        // когда View созданы из XML с isEnabled=true.
        updateAllControlsEnabled()
    }

    override fun onResume() {
        super.onResume()

        Log.d(TAG, "onResume called")

        // 1. Перечитать настройки из репозиториев
        //    (могли быть изменены в SettingsActivity).
        mainViewModel.refresh()

        // 2. Синхронизировать состояние кнопок с текущими stateFlow.
        updateAllControlsEnabled()

        // 3. Обновить UI по текущим stateFlow сервисов.
        updateSyncUiFromService(SyncService.stateFlow.value)
        updateServerUiFromService(SyncServerService.statusFlow.value)

        Log.d(
            TAG,
            "onResume: sync=${SyncService.stateFlow.value}, " +
                    "server=${SyncServerService.statusFlow.value}"
        )
    }

    // ================== Edge-to-edge ==================

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun applyWindowInsets() {
        val rootLayout = findViewById<View>(R.id.rootLayout)
        val initialLeft = rootLayout.paddingLeft
        val initialTop = rootLayout.paddingTop
        val initialRight = rootLayout.paddingRight
        val initialBottom = rootLayout.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(rootLayout)
    }

    // ================== Привязка View ==================

    private fun bindViews() {
        layoutPhone = findViewById(R.id.layoutPhone)
        tvLastPeer = findViewById(R.id.tvLastPeer)
        btnSelectPeer = findViewById(R.id.btnSelectPeer)

        rgMode = findViewById(R.id.rgMode)
        rbReplace = findViewById(R.id.rbReplace)
        rbMerge = findViewById(R.id.rbMerge)
        btnModeHelp = findViewById(R.id.btnModeHelp)

        rgDirection = findViewById(R.id.rgDirection)
        rbPhoneToReader = findViewById(R.id.rbPhoneToReader)
        rbReaderToPhone = findViewById(R.id.rbReaderToPhone)
        btnDirectionHelp = findViewById(R.id.btnDirectionHelp)

        btnSync = findViewById(R.id.btnSync)
        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        tvCurrentFile = findViewById(R.id.tvCurrentFile)
        tvTransport = findViewById(R.id.tvTransport)
        progressBar = findViewById(R.id.progressBar)
        btnCancel = findViewById(R.id.btnCancel)

        layoutReader = findViewById(R.id.layoutReader)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvServerAddress = findViewById(R.id.tvServerAddress)
        btnStartStop = findViewById(R.id.btnStartStop)
    }

    // ================== Toolbar ==================

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = getString(R.string.app_name)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        val settingsItem = menu?.findItem(R.id.action_settings)

        val syncRunning = SyncService.stateFlow.value is
                SyncService.SyncServiceState.Running
        val serverRunning = SyncServerService.statusFlow.value !is
                SyncServerService.ServerStatus.Stopped

        settingsItem?.isEnabled = !syncRunning && !serverRunning

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val syncRunning = SyncService.stateFlow.value is
                        SyncService.SyncServiceState.Running
                val serverRunning = SyncServerService.statusFlow.value !is
                        SyncServerService.ServerStatus.Stopped

                if (syncRunning || serverRunning) {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_wait_sync_finish),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ================== Лаунчеры ==================

    private fun setupLaunchers() {
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result.values.all { it }) {
                // 1. Phone: если ждали MAC — запускаем SyncService.
                val mac = pendingPeerMac
                pendingPeerMac = null
                if (mac != null) {
                    launchSyncService(mac)
                    return@registerForActivityResult
                }

                // 2. Reader: если ждали запуска сервера — запускаем.
                if (pendingServerStart) {
                    pendingServerStart = false
                    SyncServerService.start(applicationContext)
                    return@registerForActivityResult
                }

                // 3. Отложенное действие (например, discoverable).
                val action = pendingBtAction
                pendingBtAction = null
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.toast_permissions_required),
                    Toast.LENGTH_LONG
                ).show()
                pendingPeerMac = null
                pendingServerStart = false
                pendingBtAction = null
            }
        }

        enableBtLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val mac = pendingPeerMac
                pendingPeerMac = null
                if (mac != null) {
                    startSyncWithPeer(mac)
                    return@registerForActivityResult
                }

                val action = pendingBtAction
                pendingBtAction = null
                action?.invoke()
            } else {
                Toast.makeText(this, getString(R.string.toast_bluetooth_not_enabled), Toast.LENGTH_LONG).show()
                pendingPeerMac = null
                pendingBtAction = null
            }
        }

        discoverableLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != RESULT_CANCELED) {
                startDiscoveryAndShowPicker()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.toast_device_not_discoverable),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ================== Восстановление настроек ==================

    private fun restoreFromPreferences() {
        // --- 1. Режим и направление (всегда) ---
        val savedMode = settingsRepository.getSyncMode()
            ?: SyncMode.MERGE_NEWEST_WINS
        mainViewModel.setMode(savedMode)
        when (savedMode) {
            SyncMode.FULL_REPLACE -> rbReplace.isChecked = true
            SyncMode.MERGE_NEWEST_WINS -> rbMerge.isChecked = true
        }

        val savedDirection = settingsRepository.getSyncDirection()
            ?: SyncDirection.PHONE_TO_READER
        mainViewModel.setDirection(savedDirection)
        when (savedDirection) {
            SyncDirection.PHONE_TO_READER ->
                rbPhoneToReader.isChecked = true
            SyncDirection.READER_TO_PHONE ->
                rbReaderToPhone.isChecked = true
        }

        // --- 2. Роль (после mode/direction) ---
        val savedRole = settingsRepository.getRole()
        if (savedRole == null) {
            showRoleSelectionDialog()
        } else {
            mainViewModel.setRole(savedRole)
            updateLayoutForRole(savedRole)
        }

        updateLastPeerLabel()
    }

    private fun updateLastPeerLabel() {
        val mac = settingsRepository.getLastPeerMac()
        val name = settingsRepository.getLastPeerName()
        tvLastPeer.text = if (mac != null) {
            getString(
                R.string.main_last_peer_format,
                name ?: mac
            )
        } else {
            getString(R.string.main_last_peer_none)
        }
    }

    // ================== Слушатели UI ==================

    private fun setupListeners() {
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener

            val mode = when (checkedId) {
                rbReplace.id -> SyncMode.FULL_REPLACE
                rbMerge.id -> SyncMode.MERGE_NEWEST_WINS
                else -> return@setOnCheckedChangeListener
            }
            mainViewModel.setMode(mode)
            settingsRepository.setSyncMode(mode)
        }

        rgDirection.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingUi) return@setOnCheckedChangeListener

            val direction = when (checkedId) {
                rbPhoneToReader.id -> SyncDirection.PHONE_TO_READER
                rbReaderToPhone.id -> SyncDirection.READER_TO_PHONE
                else -> return@setOnCheckedChangeListener
            }
            mainViewModel.setDirection(direction)
            settingsRepository.setSyncDirection(direction)
        }

        btnModeHelp.setOnClickListener { showModeHelpDialog() }
        btnDirectionHelp.setOnClickListener { showDirectionHelpDialog() }

        // Сменить устройство (с валидацией каталогов)
        btnSelectPeer.setOnClickListener {
            if (!validateBeforeSync()) return@setOnClickListener
            onSelectPeerClicked()
        }

        // Синхронизация через SyncService (с валидацией каталогов)
        btnSync.setOnClickListener {
            if (!validateBeforeSync()) return@setOnClickListener

            val mac = settingsRepository.getLastPeerMac()
            if (mac == null) {
                onSelectPeerClicked()
            } else {
                startSyncWithPeer(mac)
            }
        }

        // Отмена
        btnCancel.setOnClickListener {
            Log.d(TAG, "User pressed Cancel")
            SyncService.cancel(applicationContext)
        }

        // Reader: старт/стоп SyncServerService
        btnStartStop.setOnClickListener { onStartStopClicked() }
    }

    // ================== Управление Reader-сервисом ==================

    private fun onStartStopClicked() {
        if (!validateBeforeSync()) return
        val serverState = SyncServerService.statusFlow.value
        val isRunning = serverState !is SyncServerService.ServerStatus.Stopped

        if (isRunning) {
            Log.d(TAG, "Stopping SyncServerService")
            SyncServerService.stop(applicationContext)
        } else {
            Log.d(TAG, "Starting SyncServerService")

            // Сохраняем отложенное действие ДО проверки.
            pendingBtAction = { onStartStopClicked() }

            if (!ensureBluetoothReady()) return

            pendingBtAction = null

            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingServerStart = true
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                    )
                    return
                }
            }

            SyncServerService.start(applicationContext)
        }
    }

    // ================== Разрешения ==================

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                )
            }
        }
    }

    // ================== Запуск синхронизации Phone ==================

    private fun startSyncWithPeer(peerMac: String) {
        if (!validateBeforeSync()) return

        if (isSyncing ||
            SyncService.stateFlow.value is SyncService.SyncServiceState.Running
        ) {
            Toast.makeText(
                this,
                getString(R.string.toast_sync_in_progress),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Сохраняем MAC — понадобится в колбэках лаунчеров.
        pendingPeerMac = peerMac

        // Проверка Bluetooth (адаптер, включение, разрешения).
        if (!ensureBluetoothReady()) return

        // POST_NOTIFICATIONS (API 33+) — отдельно от Bluetooth.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            )
            return
        }

        // Всё готово — сбрасываем pendingPeerMac и запускаем сервис.
        pendingPeerMac = null
        launchSyncService(peerMac)
    }

    private fun launchSyncService(peerMac: String) {
        Log.d(TAG, "Launching SyncService for $peerMac")

        isSyncing = true
        tvSyncStatus.text = getString(R.string.sync_status_preparing)
        tvCurrentFile.visibility = View.GONE
        tvTransport.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressBar.progress = 0
        btnCancel.visibility = View.VISIBLE
        btnCancel.isEnabled = true
        updatePhoneControlsEnabled(isRunning = true)

        SyncService.start(applicationContext, peerMac)
    }

    // ================== Валидация ==================

    private fun validateBeforeSync(): Boolean {
        val alreaderRoot = directoryAccessManager
            .getDocumentFile(DirectoryAccessManager.KEY_ALREADER_DIR)
        if (alreaderRoot == null) {
            Toast.makeText(
                this,
                getString(R.string.validation_alreader_not_selected),
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        val booksInside = mainViewModel.uiState.value.booksInsideAlreader
        if (!booksInside) {
            val booksRoot = directoryAccessManager
                .getDocumentFile(DirectoryAccessManager.KEY_BOOKS_DIR)
            if (booksRoot == null) {
                Toast.makeText(
                    this,
                    getString(R.string.validation_books_not_selected),
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
        }

        return true
    }

    // ================== Диалоги ==================

    private fun showRoleSelectionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_role_title)
            .setMessage(R.string.dialog_role_message)
            .setPositiveButton(R.string.role_phone) { _, _ ->
                setRoleAndUpdateUi(DeviceRole.PHONE)
            }
            .setNegativeButton(R.string.role_reader) { _, _ ->
                setRoleAndUpdateUi(DeviceRole.READER)
            }
            .setCancelable(false)
            .show()
    }

    private fun setRoleAndUpdateUi(role: DeviceRole) {
        mainViewModel.setRole(role)
        settingsRepository.setRole(role)
        updateLayoutForRole(role)
    }

    private fun updateLayoutForRole(role: DeviceRole?) {
        when (role) {
            DeviceRole.PHONE -> {
                layoutPhone.visibility = View.VISIBLE
                layoutReader.visibility = View.GONE
            }
            DeviceRole.READER -> {
                layoutPhone.visibility = View.GONE
                layoutReader.visibility = View.VISIBLE
            }
            null -> {
                layoutPhone.visibility = View.GONE
                layoutReader.visibility = View.GONE
            }
        }
    }

    private fun showModeHelpDialog() {
        val currentRole = mainViewModel.uiState.value.role
            ?: settingsRepository.getRole()
            ?: DeviceRole.PHONE

        val direction = when (rgDirection.checkedRadioButtonId) {
            rbPhoneToReader.id -> SyncDirection.PHONE_TO_READER
            rbReaderToPhone.id -> SyncDirection.READER_TO_PHONE
            else -> mainViewModel.uiState.value.direction
                ?: SyncDirection.PHONE_TO_READER
        }

        val (sourceRole, targetRole) = when (direction) {
            SyncDirection.PHONE_TO_READER ->
                Pair(DeviceRole.PHONE, DeviceRole.READER)
            SyncDirection.READER_TO_PHONE ->
                Pair(DeviceRole.READER, DeviceRole.PHONE)
        }

        val sourceName = getRoleDisplayNameWithHint(sourceRole, currentRole)
        val targetName = getRoleDisplayNameWithHint(targetRole, currentRole)

        val mode = when (rgMode.checkedRadioButtonId) {
            rbReplace.id -> SyncMode.FULL_REPLACE
            rbMerge.id -> SyncMode.MERGE_NEWEST_WINS
            else -> SyncMode.MERGE_NEWEST_WINS
        }

        val messageRes = when (mode) {
            SyncMode.FULL_REPLACE -> R.string.mode_replace_help
            SyncMode.MERGE_NEWEST_WINS -> R.string.mode_merge_help
        }

        val message = getString(messageRes, targetName, sourceName)

        AlertDialog.Builder(this)
            .setTitle(R.string.mode_help_desc)
            .setMessage(message)
            .setPositiveButton(R.string.help_button_ok, null)
            .show()
    }

    private fun showDirectionHelpDialog() {
        val currentRole = mainViewModel.uiState.value.role
            ?: settingsRepository.getRole()
            ?: DeviceRole.PHONE

        val direction = when (rgDirection.checkedRadioButtonId) {
            rbPhoneToReader.id -> SyncDirection.PHONE_TO_READER
            rbReaderToPhone.id -> SyncDirection.READER_TO_PHONE
            else -> SyncDirection.PHONE_TO_READER
        }

        val (sourceRole, targetRole) = when (direction) {
            SyncDirection.PHONE_TO_READER ->
                Pair(DeviceRole.PHONE, DeviceRole.READER)
            SyncDirection.READER_TO_PHONE ->
                Pair(DeviceRole.READER, DeviceRole.PHONE)
        }

        val sourceName = getRoleDisplayNameWithHint(sourceRole, currentRole)
        val targetName = getRoleDisplayNameWithHint(targetRole, currentRole)

        val message = getString(
            R.string.direction_help,
            sourceName, targetName
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.direction_help_desc)
            .setMessage(message)
            .setPositiveButton(R.string.help_button_ok, null)
            .show()
    }

    private fun getRoleDisplayName(role: DeviceRole): String {
        return when (role) {
            DeviceRole.PHONE -> getString(R.string.role_phone)
            DeviceRole.READER -> getString(R.string.role_reader)
        }
    }

    private fun getRoleDisplayNameWithHint(
        role: DeviceRole,
        currentRole: DeviceRole
    ): String {
        val name = getRoleDisplayName(role)
        return if (role == currentRole) {
            "$name (${getString(R.string.role_this_device)})"
        } else {
            name
        }
    }

    // ================== Хелперы ==================

    private fun updatePhoneControlsEnabled(isRunning: Boolean) {
        rbReplace.isEnabled = !isRunning
        rbMerge.isEnabled = !isRunning
        rbPhoneToReader.isEnabled = !isRunning
        rbReaderToPhone.isEnabled = !isRunning

        btnSelectPeer.isEnabled = !isRunning
        btnSync.isEnabled = !isRunning

        invalidateOptionsMenu()
    }

    private fun updateServerControlsEnabled(isRunning: Boolean) {
        btnStartStop.isEnabled = true
        invalidateOptionsMenu()
    }

    private fun updateAllControlsEnabled() {
        val syncRunning = SyncService.stateFlow.value is
                SyncService.SyncServiceState.Running
        val serverRunning = SyncServerService.statusFlow.value !is
                SyncServerService.ServerStatus.Stopped

        Log.d(
            TAG,
            "updateAllControlsEnabled: syncRunning=$syncRunning, " +
                    "serverRunning=$serverRunning"
        )

        updatePhoneControlsEnabled(syncRunning)
        updateServerControlsEnabled(serverRunning)
    }

    private fun updateRoleUi(role: DeviceRole?) {
        updateLayoutForRole(role)
    }

    private fun updateModeUi(mode: SyncMode?) {
        val target = when (mode) {
            SyncMode.FULL_REPLACE -> rbReplace
            SyncMode.MERGE_NEWEST_WINS -> rbMerge
            null -> null
        } ?: return

        if (!target.isChecked) {
            isUpdatingUi = true
            target.isChecked = true
            isUpdatingUi = false
        }
    }

    private fun updateDirectionUi(direction: SyncDirection?) {
        val target = when (direction) {
            SyncDirection.PHONE_TO_READER -> rbPhoneToReader
            SyncDirection.READER_TO_PHONE -> rbReaderToPhone
            null -> null
        } ?: return

        if (!target.isChecked) {
            isUpdatingUi = true
            target.isChecked = true
            isUpdatingUi = false
        }
    }

    private fun updateBooksInsideUi(booksInside: Boolean) {
        // no-op: cbBooksInsideAlreader в SettingsActivity.
    }

    private fun transportDisplayName(name: String): String = when (name) {
        "wifi_lan" -> getString(R.string.transport_display_wifi_lan)
        "bluetooth" -> getString(R.string.transport_display_bluetooth)
        else -> name
    }

    // ================== ТЕЛЕФОН: подключение ==================

    private fun onSelectPeerClicked() {
        // Сохраняем отложенное действие ДО проверки:
        // после выдачи разрешений снова вызовем onSelectPeerClicked().
        pendingBtAction = { onSelectPeerClicked() }

        if (!ensureBluetoothReady()) {
            // Запрос разрешений / включения Bluetooth.
            // pendingBtAction выполнится в колбэке лаунчера.
            return
        }

        // Всё готово — сбрасываем отложенное действие и открываем
        // диалог discoverable.
        pendingBtAction = null
        discoverableLauncher.launch(bluetoothHelper.createDiscoverableIntent(300))
    }

    @SuppressLint("MissingPermission")
    private fun startDiscoveryAndShowPicker() {
        lifecycleScope.launch {
            val bonded = bluetoothHelper.getBondedDevices()
            Toast.makeText(this@MainActivity, R.string.toast_searching_devices, Toast.LENGTH_SHORT).show()

            val discovered = withTimeoutOrNull(15_000L) {
                bluetoothHelper.discoverDevices().take(20).toList()
            } ?: emptyList()

            val allDevices = (bonded + discovered).distinctBy { it.address }
            if (allDevices.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.toast_devices_not_found,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val unknownName = getString(R.string.device_unknown)
            val names = allDevices.map { device ->
                val name = try { device.name } catch (e: SecurityException) { null }
                "${name ?: unknownName} (${device.address})"
            }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.dialog_select_device_title)
                .setItems(names) { _, which -> onDeviceSelected(allDevices[which]) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceSelected(device: BluetoothDevice) {
        lifecycleScope.launch {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.toast_confirm_pairing,
                    Toast.LENGTH_LONG
                ).show()
                val paired = bluetoothHelper.pairDevice(device)
                if (!paired) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.toast_pairing_failed,
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }

            val name = try { device.name } catch (e: SecurityException) { null }
            settingsRepository.setLastPeerMac(device.address)
            settingsRepository.setLastPeerName(name ?: getString(R.string.device_unknown))
            updateLastPeerLabel()

            //startSyncWithPeer(device.address)
        }
    }

    /**
     * Проверяет готовность Bluetooth: адаптер доступен, включён,
     * и выданы runtime-разрешения.
     *
     * Если чего-то не хватает — запускает соответствующий launcher
     * и возвращает false. После выдачи разрешений (или включения
     * Bluetooth) выполнится pendingBtAction — если он установлен.
     *
     * @return true, если Bluetooth полностью готов к работе.
     */
    private fun ensureBluetoothReady(): Boolean {
        if (!bluetoothHelper.isBluetoothAvailable()) {
            Toast.makeText(this, R.string.toast_bluetooth_unavailable, Toast.LENGTH_LONG).show()
            return false
        }

        if (!bluetoothHelper.isBluetoothEnabled()) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }

        if (!bluetoothHelper.hasRequiredPermissions()) {
            permissionLauncher.launch(bluetoothHelper.getRequiredPermissions())
            return false
        }

        return true
    }

    // ================== Наблюдение за сервисами ==================

    private fun observeViewModels() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.uiState.collect { state ->
                    updateLastPeerLabel()
                    updateRoleUi(state.role)
                    updateModeUi(state.mode)
                    updateDirectionUi(state.direction)
                    updateBooksInsideUi(state.booksInsideAlreader)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncService.stateFlow.collect { state ->
                    updateSyncUiFromService(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncServerService.statusFlow.collect { status ->
                    updateServerUiFromService(status)
                }
            }
        }
    }

    // ================== Обновление UI по состоянию сервисов ==================

    private fun updateSyncUiFromService(
        state: SyncService.SyncServiceState
    ) {
        when (state) {
            is SyncService.SyncServiceState.Idle -> {
                tvSyncStatus.text = getString(R.string.main_status_ready)
                tvCurrentFile.visibility = View.GONE
                tvTransport.visibility = View.GONE
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                btnCancel.visibility = View.GONE
                isSyncing = false
                updatePhoneControlsEnabled(isRunning = false)
            }

            is SyncService.SyncServiceState.Running -> {
                tvSyncStatus.text = state.status
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = state.progress
                btnCancel.visibility = View.VISIBLE
                btnCancel.isEnabled = true

                if (state.transportName != null) {
                    tvTransport.visibility = View.VISIBLE
                    tvTransport.text = getString(
                        R.string.transport_display_format,
                        transportDisplayName(state.transportName)
                    )
                } else {
                    tvTransport.visibility = View.GONE
                }

                if (state.currentFile != null) {
                    tvCurrentFile.visibility = View.VISIBLE
                    tvCurrentFile.text = state.currentFile
                } else {
                    tvCurrentFile.visibility = View.GONE
                }

                isSyncing = true
                updatePhoneControlsEnabled(isRunning = true)
            }

            is SyncService.SyncServiceState.Complete -> {
                tvSyncStatus.text = getString(
                    R.string.sync_status_complete_format,
                    state.transferred,
                    state.errors
                )
                tvCurrentFile.visibility = View.GONE
                tvTransport.visibility = View.GONE
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                btnCancel.visibility = View.GONE
                isSyncing = false
                updatePhoneControlsEnabled(isRunning = false)
            }

            is SyncService.SyncServiceState.Error -> {
                tvSyncStatus.text = getString(
                    R.string.sync_status_error_format,
                    state.message
                )
                tvCurrentFile.visibility = View.GONE
                tvTransport.visibility = View.GONE
                progressBar.visibility = View.GONE
                progressBar.isIndeterminate = false
                btnCancel.visibility = View.GONE
                isSyncing = false
                updatePhoneControlsEnabled(isRunning = false)
            }
        }
    }
    private fun updateServerUiFromService(
        state: SyncServerService.ServerStatus
    ) {
        when (state) {
            is SyncServerService.ServerStatus.Stopped -> {
                tvServerStatus.text = getString(R.string.main_server_status_stopped)
                tvServerAddress.text = getString(
                    R.string.main_server_address_format, "—", 0
                )
                btnStartStop.isEnabled = true
                btnStartStop.text = getString(R.string.main_server_start)
                updateServerControlsEnabled(isRunning = false)
            }

            is SyncServerService.ServerStatus.WaitingForConnection -> {
                tvServerStatus.text = getString(R.string.server_waiting_for_client)
                tvServerAddress.text = getString(
                    R.string.main_server_address_format,
                    state.ip,
                    state.port
                )
                btnStartStop.isEnabled = true
                btnStartStop.text = getString(R.string.main_server_stop)
                updateServerControlsEnabled(isRunning = true)
            }

            is SyncServerService.ServerStatus.Connected -> {
                tvServerStatus.text = getString(
                    R.string.server_connected_format,
                    state.peerMac
                )
                updateServerControlsEnabled(isRunning = true)
            }

            is SyncServerService.ServerStatus.Syncing -> {
                tvServerStatus.text = if (state.transportName != null) {
                    getString(
                        R.string.server_task_transport_format,
                        state.taskIndex,
                        state.totalTasks,
                        state.taskName,
                        transportDisplayName(state.transportName)
                    )
                } else {
                    getString(
                        R.string.server_task_format,
                        state.taskIndex,
                        state.totalTasks,
                        state.taskName
                    )
                }
                updateServerControlsEnabled(isRunning = true)
            }

            is SyncServerService.ServerStatus.Complete -> {
                tvServerStatus.text = getString(
                    R.string.server_complete_format,
                    state.transferred,
                    state.errors
                )
                tvServerAddress.text = getString(
                    R.string.main_server_address_format, "—", 0
                )
                updateServerControlsEnabled(isRunning = false)
            }

            is SyncServerService.ServerStatus.Error -> {
                tvServerStatus.text = getString(
                    R.string.server_error_format,
                    state.message
                )
                updateServerControlsEnabled(isRunning = false)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}