package ru.samorez.alreaderxsync

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.samorez.alreaderxsync.data.DeviceRole
import ru.samorez.alreaderxsync.data.TransportConfig
import ru.samorez.alreaderxsync.data.TransportMode
import ru.samorez.alreaderxsync.service.SyncServerService
import ru.samorez.alreaderxsync.service.SyncService
import ru.samorez.alreaderxsync.storage.DirectoryAccessManager
import ru.samorez.alreaderxsync.storage.SettingsRepository
import ru.samorez.alreaderxsync.transport.BluetoothPairingHelper
import ru.samorez.alreaderxsync.transport.WifiHelper

/**
 * Экран настроек приложения.
 *
 * Отвечает за:
 *  - выбор роли устройства (телефон / ридер);
 *  - выбор каталогов AlReaderX и Books через SAF;
 *  - флаг «книги внутри AlReaderX»;
 *  - режим транспорта (AUTO / BLUETOOTH_ONLY) через RadioGroup;
 *  - количество повторов и таймаут для каждого транспорта;
 *  - панель состояния (Wi-Fi, IP, Bluetooth);
 *  - проверку транспортов (диалог);
 *  - явное сохранение всех настроек по кнопке «Сохранить настройки».
 *
 * Сохранение настроек:
 *  - роль, флаг «книги внутри AlReaderX», режим транспорта —
 *    автоматически при изменении;
 *  - retry/timeout — при потере фокуса EditText;
 *  - ВСЕ настройки — явно по кнопке btnSave через saveAllSettings();
 *  - страховка: onPause() дополнительно вызывает
 *    saveTransportConfig() на случай, если пользователь
 *    изменил EditText и сразу закрыл Activity.
 *
 * ВАЖНО: системный ActionBar отключён темой NoActionBar.
 * Заголовок — наш Toolbar, зарегистрированный через
 * setSupportActionBar(toolbar). Кнопка «Назад» включается
 * через supportActionBar?.setDisplayHomeAsUpEnabled(true)
 * и обрабатывается в onSupportNavigateUp().
 *
 * ЗАЩИТА ОТ РАССИНХРОНА:
 *  - при открытии экрана проверяется, идёт ли синхронизация
 *    (SyncService.stateFlow / SyncServerService.statusFlow);
 *  - если да — Toast + finish() ДО setContentView;
 *  - дополнительно экран подписывается на stateFlow/statusFlow
 *    и автоматически закрывается, если синхронизация начнётся
 *    уже после открытия настроек.
 */
class SettingsActivity : AppCompatActivity() {

    // ==== Зависимости ====
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var directoryAccessManager: DirectoryAccessManager
    private lateinit var wifiHelper: WifiHelper
    private lateinit var bluetoothHelper: BluetoothPairingHelper

    // ==== Роль ====
    private lateinit var rgRole: RadioGroup
    private lateinit var rbPhone: RadioButton
    private lateinit var rbReader: RadioButton

    // ==== Каталоги ====
    private lateinit var btnSelectAlreader: Button
    private lateinit var tvAlreaderPath: TextView
    private lateinit var btnSelectBooks: Button
    private lateinit var tvBooksPath: TextView
    private lateinit var cbBooksInsideAlreader: CheckBox

    // ==== Транспорты ====
    private lateinit var rgTransportMode: RadioGroup
    private lateinit var rbTransportAuto: RadioButton
    private lateinit var rbTransportBluetoothOnly: RadioButton

    // ==== Retry / timeout ====
    private lateinit var etWifiLanRetries: EditText
    private lateinit var etWifiLanTimeoutSec: EditText
    private lateinit var etBluetoothRetries: EditText
    private lateinit var etBluetoothTimeoutSec: EditText

    // ==== Состояние ====
    private lateinit var tvWifiStatus: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvBtStatus: TextView
    private lateinit var btnCheckTransports: Button

    // ==== Прочее ====
    // btnReset заменён на btnSave (явное сохранение).
    private lateinit var btnSave: Button

    // ==== SAF-лаунчеры ====
    private lateinit var alreaderPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var booksPickerLauncher: ActivityResultLauncher<Intent>

    // ==== Защита от рассинхрона ====
    // Флаг, чтобы finish() + Toast сработали только один раз,
    // если оба сервиса (SyncService и SyncServerService) активны.
    private var isClosingForSync = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ============================================================
        // ЗАЩИТА ОТ РАССИНХРОНА (шаг 1).
        // Проверяем, не идёт ли синхронизация прямо сейчас.
        // Выполняется ДО setContentView, чтобы не тратить ресурсы
        // на инфляцию разметки и инициализацию UI.
        // ============================================================
        val syncRunning = SyncService.stateFlow.value is
                SyncService.SyncServiceState.Running
        val serverRunning = SyncServerService.statusFlow.value !is
                SyncServerService.ServerStatus.Stopped

        if (syncRunning || serverRunning) {
            Toast.makeText(
                this,
                getString(R.string.toast_settings_sync_unavailable),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        // ШАГ 1: edge-to-edge ДО setContentView.
        setupEdgeToEdge()

        // ШАГ 2: разметка.
        setContentView(R.layout.activity_settings)

        settingsRepository = SettingsRepository(this)
        directoryAccessManager = DirectoryAccessManager(this)
        wifiHelper = WifiHelper(this)
        bluetoothHelper = BluetoothPairingHelper(this)

        bindViews()

        // ШАГ 3: Toolbar + кнопка «Назад».
        setupToolbar()

        // ШАГ 4: Insets listener.
        applyWindowInsets()

        setupLaunchers()
        loadSettings()
        setupListeners()

        // ============================================================
        // ЗАЩИТА ОТ РАССИНХРОНА (шаг 2).
        // Если синхронизация начнётся ПОСЛЕ открытия настроек —
        // автоматически закрываем Activity.
        // ============================================================
        observeSyncState()
    }

    override fun onResume() {
        super.onResume()
        updateInfoPanel()
    }

    override fun onPause() {
        super.onPause()
        // Страховка: если пользователь изменил EditText и сразу
        // ушёл с экрана (например, свернул приложение), значения
        // retry/timeout могли не сохраниться по onFocusChange.
        // saveTransportConfig() идемпотентен и безопасен
        // при вызове в onPause.
        //
        // ВАЖНО: onPause также вызывается при навигации в SAF
        // (выбор каталога) — это нормально, конфиг пересохранится
        // с теми же значениями.
        saveTransportConfig()
        Log.d(TAG, "onPause: config saved")
    }

    // ================== Toolbar ==================

    /**
     * Регистрирует Toolbar как ActionBar и включает кнопку «Назад».
     * setDisplayHomeAsUpEnabled(true) показывает стандартную стрелку
     * AppCompat слева. Нажатие обрабатывается в onSupportNavigateUp().
     */
    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbarSettings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ================== Edge-to-edge ==================

    /**
     * Включает edge-to-edge: контент рисуется под системными
     * панелями. WindowInsets обрабатываются в applyWindowInsets().
     *
     * ВАЖНО: вызывать ДО setContentView.
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    // ================== Window Insets ==================

    /**
     * Обработка WindowInsets:
     *  - settingsRoot получает padding top (status bar) и bottom
     *    (navigation bar), а также left/right (display cutout).
     *
     * ВАЖНО: listener возвращает WindowInsetsCompat.CONSUMED.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.settingsRoot)

        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
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

        ViewCompat.requestApplyInsets(root)
    }

    // ================== Защита от рассинхрона ==================

    /**
     * Подписка на stateFlow / statusFlow.
     *
     * Если во время нахождения на экране настроек запускается
     * синхронизация (или стартует сервер) — показываем Toast
     * и закрываем Activity, чтобы пользователь не изменил
     * настройки в разгар активной сессии.
     *
     * Подписка живёт только в состоянии STARTED (repeatOnLifecycle):
     * при уходе с экрана корутины останавливаются, при возврате —
     * запускаются снова.
     */
    private fun observeSyncState() {
        // Наблюдение за локальной синхронизацией (SyncService).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncService.stateFlow.collect { state ->
                    if (state is SyncService.SyncServiceState.Running) {
                        closeForSync(
                            getString(R.string.toast_settings_closed_sync_started)
                        )
                    }
                }
            }
        }

        // Наблюдение за сервером синхронизации (SyncServerService).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncServerService.statusFlow.collect { status ->
                    if (status !is SyncServerService.ServerStatus.Stopped) {
                        closeForSync(getString(R.string.toast_settings_closed_server_started))
                    }
                }
            }
        }
    }

    /**
     * Идемпотентное закрытие экрана настроек при старте синхронизации.
     * Защищает от двойного Toast/finish, если оба сервиса активны.
     */
    private fun closeForSync(message: String) {
        if (isClosingForSync) return
        isClosingForSync = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    // ================== Привязка View ==================

    private fun bindViews() {
        rgRole = findViewById(R.id.rgRole)
        rbPhone = findViewById(R.id.rbPhone)
        rbReader = findViewById(R.id.rbReader)

        btnSelectAlreader = findViewById(R.id.btnSelectAlreader)
        tvAlreaderPath = findViewById(R.id.tvAlreaderPath)
        btnSelectBooks = findViewById(R.id.btnSelectBooks)
        tvBooksPath = findViewById(R.id.tvBooksPath)
        cbBooksInsideAlreader = findViewById(R.id.cbBooksInsideAlreader)

        rgTransportMode = findViewById(R.id.rgTransportMode)
        rbTransportAuto = findViewById(R.id.rbTransportAuto)
        rbTransportBluetoothOnly = findViewById(R.id.rbTransportBluetoothOnly)

        etWifiLanRetries = findViewById(R.id.etWifiLanRetries)
        etWifiLanTimeoutSec = findViewById(R.id.etWifiLanTimeoutSec)
        etBluetoothRetries = findViewById(R.id.etBluetoothRetries)
        etBluetoothTimeoutSec = findViewById(R.id.etBluetoothTimeoutSec)

        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvBtStatus = findViewById(R.id.tvBtStatus)
        btnCheckTransports = findViewById(R.id.btnCheckTransports)

        // btnSave вместо btnReset (явное сохранение настроек).
        btnSave = findViewById(R.id.btnSave)
    }

    // ================== Лаунчеры ==================

    private fun setupLaunchers() {
        alreaderPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (directoryAccessManager.handleTreeUriResult(
                        uri, DirectoryAccessManager.KEY_ALREADER_DIR)) {
                    updateAlreaderPath()
                }
            }
        }

        booksPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (directoryAccessManager.handleTreeUriResult(
                        uri, DirectoryAccessManager.KEY_BOOKS_DIR)) {
                    updateBooksPath()
                }
            }
        }
    }

    // ================== Загрузка настроек ==================

    private fun loadSettings() {
        when (settingsRepository.getRole()) {
            DeviceRole.PHONE -> rbPhone.isChecked = true
            DeviceRole.READER -> rbReader.isChecked = true
            null -> { /* роль не выбрана */ }
        }

        cbBooksInsideAlreader.isChecked =
            settingsRepository.isBooksInsideAlreader()

        val config = settingsRepository.getTransportConfig()

        when (config.transportMode) {
            TransportMode.AUTO -> rbTransportAuto.isChecked = true
            TransportMode.BLUETOOTH_ONLY ->
                rbTransportBluetoothOnly.isChecked = true
        }

        etWifiLanRetries.setText(config.wifiLanMaxRetries.toString())
        etWifiLanTimeoutSec.setText(
            (config.wifiLanTimeoutMs / 1000).toString()
        )

        etBluetoothRetries.setText(config.bluetoothMaxRetries.toString())
        etBluetoothTimeoutSec.setText(
            (config.bluetoothTimeoutMs / 1000).toString()
        )

        updateAlreaderPath()
        updateBooksPath()
    }

    private fun updateAlreaderPath() {
        val uri = directoryAccessManager.getConfiguredUri(
            DirectoryAccessManager.KEY_ALREADER_DIR
        )
        tvAlreaderPath.text = if (uri != null) {
            getString(
                R.string.path_alreader_selected_format,
                uri.path ?: uri.toString()
            )
        } else {
            getString(R.string.path_alreader_not_selected)
        }
    }

    private fun updateBooksPath() {
        val uri = directoryAccessManager.getConfiguredUri(
            DirectoryAccessManager.KEY_BOOKS_DIR
        )
        tvBooksPath.text = if (uri != null) {
            getString(
                R.string.path_books_selected_format,
                uri.path ?: uri.toString()
            )
        } else {
            getString(R.string.path_books_not_selected)
        }
    }

    // ================== Слушатели ==================

    private fun setupListeners() {
        rgRole.setOnCheckedChangeListener { _, checkedId ->
            val role = when (checkedId) {
                rbPhone.id -> DeviceRole.PHONE
                rbReader.id -> DeviceRole.READER
                else -> return@setOnCheckedChangeListener
            }
            settingsRepository.setRole(role)
        }

        btnSelectAlreader.setOnClickListener {
            alreaderPickerLauncher.launch(
                directoryAccessManager.createOpenDocumentTreeIntent()
            )
        }

        btnSelectBooks.setOnClickListener {
            booksPickerLauncher.launch(
                directoryAccessManager.createOpenDocumentTreeIntent()
            )
        }

        cbBooksInsideAlreader.setOnCheckedChangeListener { _, checked ->
            settingsRepository.setBooksInsideAlreader(checked)
        }

        // --- Режим транспорта: сохраняем сразу при выборе ---
        rgTransportMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                rbTransportAuto.id -> TransportMode.AUTO
                rbTransportBluetoothOnly.id -> TransportMode.BLUETOOTH_ONLY
                else -> return@setOnCheckedChangeListener
            }
            val current = settingsRepository.getTransportConfig()
            settingsRepository.saveTransportConfig(
                current.copy(transportMode = mode)
            )
        }

        // --- Retry / timeout: сохраняем при потере фокуса ---
        etWifiLanRetries.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTransportConfig()
        }
        etWifiLanTimeoutSec.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTransportConfig()
        }
        etBluetoothRetries.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTransportConfig()
        }
        etBluetoothTimeoutSec.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTransportConfig()
        }

        btnCheckTransports.setOnClickListener {
            checkTransports()
        }

        // --- Явное сохранение всех настроек ---
        // Кнопка btnSave дублирует автосохранение, но даёт
        // пользователю уверенность и страхует от потери
        // значений EditText, если фокус не был потерян.
        btnSave.setOnClickListener {
            saveAllSettings()
            Toast.makeText(
                this,
                getString(R.string.settings_saved),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Сохраняет ВСЕ настройки экрана:
     *  1. Роль (Phone / Reader).
     *  2. Флаг «книги внутри AlReaderX».
     *  3. Конфигурацию транспорта (режим + retry/timeout).
     *
     * Вызывается по нажатию кнопки btnSave.
     */
    private fun saveAllSettings() {
        // 1. Роль
        val role = when (rgRole.checkedRadioButtonId) {
            rbPhone.id -> DeviceRole.PHONE
            rbReader.id -> DeviceRole.READER
            else -> null
        }
        if (role != null) {
            settingsRepository.setRole(role)
        }

        // 2. Books inside Alreader
        settingsRepository.setBooksInsideAlreader(
            cbBooksInsideAlreader.isChecked
        )

        // 3. TransportConfig (режим + retry/timeout)
        saveTransportConfig()

        Log.d(TAG, "All settings saved")
    }

    /**
     * Сохраняет конфигурацию транспортов:
     *  - режим (AUTO / BLUETOOTH_ONLY) из rgTransportMode;
     *  - retry / timeout из EditText-полей (с приведением
     *    к безопасным границам).
     *
     * Идемпотентен: повторный вызов с теми же значениями
     * не меняет результат.
     */
    private fun saveTransportConfig() {
        val wifiLanRetries = etWifiLanRetries.text.toString()
            .toIntOrNull()?.coerceIn(1, 99) ?: 3
        val wifiLanTimeoutSec = etWifiLanTimeoutSec.text.toString()
            .toIntOrNull()?.coerceIn(1, 60) ?: 5
        val btRetries = etBluetoothRetries.text.toString()
            .toIntOrNull()?.coerceIn(1, 99) ?: 3
        val btTimeoutSec = etBluetoothTimeoutSec.text.toString()
            .toIntOrNull()?.coerceIn(1, 60) ?: 10

        val mode = when (rgTransportMode.checkedRadioButtonId) {
            rbTransportAuto.id -> TransportMode.AUTO
            rbTransportBluetoothOnly.id -> TransportMode.BLUETOOTH_ONLY
            else -> TransportMode.AUTO
        }

        val config = TransportConfig(
            transportMode = mode,
            wifiLanTimeoutMs = wifiLanTimeoutSec * 1000L,
            bluetoothTimeoutMs = btTimeoutSec * 1000L,
            wifiLanMaxRetries = wifiLanRetries,
            wifiLanRetryDelayMs = 1_000L,
            bluetoothMaxRetries = btRetries,
            bluetoothRetryDelayMs = 1_000L
        )

        settingsRepository.saveTransportConfig(config)
    }

    // ================== Панель состояния ==================

    private fun updateInfoPanel() {
        // Wi-Fi
        val wifiEnabled = wifiHelper.isWifiEnabled()
        val wifiStateText = getString(
            if (wifiEnabled) R.string.wifi_state_enabled
            else R.string.wifi_state_disabled
        )
        tvWifiStatus.text = getString(
            R.string.wifi_status_format,
            wifiStateText
        )

        // IP
        val ip = wifiHelper.getLocalIpAddress() ?: "—"
        tvIpAddress.text = getString(R.string.ip_status_format, ip)

        // Bluetooth
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val btStateText = when {
            adapter == null -> getString(R.string.bt_state_unavailable)
            adapter.isEnabled -> getString(R.string.bt_state_enabled)
            else -> getString(R.string.bt_state_disabled)
        }
        tvBtStatus.text = getString(R.string.bt_status_format, btStateText)
    }

    // ================== Проверка транспортов ==================

    /**
     * Формирует подробный отчёт о состоянии транспортов
     * и показывает его в AlertDialog. Учитывает выбранный
     * режим (AUTO / BLUETOOTH_ONLY).
     */
    private fun checkTransports() {
        val mode = settingsRepository.getTransportConfig().transportMode

        val report = buildString {
            append(getString(R.string.check_transports_report_header))
            append("\n\n")

            // === Wi-Fi LAN ===
            append(getString(R.string.check_transports_section_wifi))
            append("\n")

            val wifiEnabled = wifiHelper.isWifiEnabled()
            append(
                if (wifiEnabled) getString(R.string.check_transports_wifi_enabled)
                else getString(R.string.check_transports_wifi_disabled)
            )
            append("\n")

            val ip = wifiHelper.getLocalIpAddress()
            if (ip != null) {
                append(
                    getString(
                        R.string.check_transports_ip_ok_format,
                        ip
                    )
                )
            } else {
                append(getString(R.string.check_transports_ip_fail))
            }
            append("\n\n")

            // === Bluetooth ===
            append(getString(R.string.check_transports_section_bt))
            append("\n")

            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            if (btAdapter == null) {
                append(getString(R.string.check_transports_bt_unsupported))
            } else {
                append(
                    if (btAdapter.isEnabled)
                        getString(R.string.check_transports_bt_enabled)
                    else
                        getString(R.string.check_transports_bt_disabled)
                )
                append("\n")

                val bondedDevices = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (checkSelfPermission(
                                Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED) {
                            btAdapter.bondedDevices?.toList() ?: emptyList()
                        } else {
                            append(
                                getString(
                                    R.string.check_transports_bt_no_permission
                                )
                            )
                            append("\n")
                            emptyList()
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        btAdapter.bondedDevices?.toList() ?: emptyList()
                    }
                } catch (e: SecurityException) {
                    emptyList()
                }

                if (bondedDevices.isNotEmpty()) {
                    append(
                        getString(
                            R.string.check_transports_bt_bonded_count_format,
                            bondedDevices.size
                        )
                    )
                    append("\n")

                    val unknownName = getString(R.string.device_unknown)
                    bondedDevices.take(5).forEach { device ->
                        append(
                            getString(
                                R.string.check_transports_bt_bonded_item_format,
                                device.name ?: unknownName,
                                device.address
                            )
                        )
                        append("\n")
                    }
                    if (bondedDevices.size > 5) {
                        append(
                            getString(
                                R.string.check_transports_bt_bonded_more_format,
                                bondedDevices.size - 5
                            )
                        )
                        append("\n")
                    }
                } else {
                    append(getString(R.string.check_transports_bt_no_bonded))
                }
            }

            // === Выбранный режим и рекомендация ===
            append("\n")
            append(getString(R.string.check_transports_mode_header))
            append(" ")
            append(
                when (mode) {
                    TransportMode.AUTO ->
                        getString(R.string.check_transports_mode_auto)
                    TransportMode.BLUETOOTH_ONLY ->
                        getString(R.string.check_transports_mode_bt_only)
                }
            )
            append("\n\n")

            when (mode) {
                TransportMode.BLUETOOTH_ONLY -> {
                    if (btAdapter?.isEnabled != true) {
                        append(
                            getString(R.string.check_transports_recommend_bt_off)
                        )
                    } else {
                        append(
                            getString(R.string.check_transports_recommend_bt_only)
                        )
                    }
                }
                TransportMode.AUTO -> {
                    if (!wifiEnabled && (btAdapter?.isEnabled != true)) {
                        append(
                            getString(R.string.check_transports_recommend_none)
                        )
                    } else if (!wifiEnabled) {
                        append(
                            getString(R.string.check_transports_recommend_wifi_off)
                        )
                    } else {
                        append(
                            getString(R.string.check_transports_recommend_wifi_on)
                        )
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.check_transports_title)
            .setMessage(report)
            .setPositiveButton(R.string.check_transports_ok, null)
            .show()
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}