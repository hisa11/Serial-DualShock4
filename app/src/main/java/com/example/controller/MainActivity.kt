package com.example.controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controller.ui.theme.ControllerTheme
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var statusMessage: MutableState<String>
    private lateinit var sentDataForUi: MutableState<String>
    private lateinit var receivedData: MutableState<String>

    private val ACTION_USB_PERMISSION = "com.example.controller.USB_PERMISSION"
    private var usbManager: UsbManager? = null
    private var usbSerialPort: UsbSerialPort? = null

    private val inputUpdateIntervalMs = 20L
    private val inputHandler = Handler(Looper.getMainLooper())
    private var latestAxes = FloatArray(4) { 0f }
    private val buttonStates = mutableStateMapOf<Int, Boolean>() // Composeの再描画をトリガーするために mutableStateMapOf を使用

    private val serialSendIntervalMs = 50L
    private var serialSendExecutor: ScheduledExecutorService? = null
    private val dataQueue = LinkedBlockingQueue<String>(1)

    private var readThread: HandlerThread? = null
    private var readHandler: Handler? = null
    private val serialBuffer = StringBuilder()

    // カスタムキーコードの定義
    companion object {
        const val KEYCODE_CUSTOM_CROSS = 96
        const val KEYCODE_CUSTOM_CIRCLE = 97
        const val KEYCODE_CUSTOM_SQUARE = 99
        const val KEYCODE_CUSTOM_TRIANGLE = 100
        val CUSTOM_BUTTON_KEYCODES = setOf(
            KEYCODE_CUSTOM_CROSS,
            KEYCODE_CUSTOM_CIRCLE,
            KEYCODE_CUSTOM_SQUARE,
            KEYCODE_CUSTOM_TRIANGLE
        )
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            // Log.d("USBReceiver", "Received action: $action")
            when (action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                statusMessage.value = "USB権限許可: ${it.deviceName}"
                                Log.i("USBReceiver", "Permission granted for ${it.deviceName}")
                                openUsbDevice(it)
                            }
                        } else {
                            statusMessage.value = "USB権限が拒否されました"
                            Log.w("USBReceiver", "USB permission denied for device ${device?.deviceName}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        statusMessage.value = "USBデバイス接続: ${it.productName ?: it.deviceName}"
                        Log.i("USBReceiver", "USB device attached: ${it.deviceName}")
                        if (usbManager?.hasPermission(it) == false) {
                            requestUsbPermission(it)
                        } else if (usbManager?.hasPermission(it) == true) {
                            openUsbDevice(it)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        statusMessage.value = "USBデバイス切断: ${it.productName ?: it.deviceName}"
                        Log.i("USBReceiver", "USB device detached: ${it.deviceName}")
                        if (usbSerialPort?.device?.deviceId == it.deviceId) {
                            closeUsbPort()
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusMessage = mutableStateOf("USB接続待機中...")
        sentDataForUi = mutableStateOf("N/A")
        receivedData = mutableStateOf("N/A")

        enableEdgeToEdge()

        setContent {
            ControllerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(title = { Text("コントローラーデモ") })
                    }
                ) { innerPadding ->
                    ControllerScreen(
                        modifier = Modifier.padding(innerPadding),
                        statusMessage = statusMessage.value,
                        sentData = sentDataForUi.value,
                        receivedData = receivedData.value,
                        axes = latestAxes,
                        buttonStates = buttonStates // mutableStateMapOf を渡す
                    )
                }
            }
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        checkConnectedUsbDevices()
        startInputProcessingLoop()
        startSerialSendLoop()
        startReadLoop()
    }

    private fun queueSerialData(data: String) {
        dataQueue.clear()
        dataQueue.offer(data)
    }

    private fun actualSendSerial(data: String) {
        if (usbSerialPort == null || usbSerialPort?.isOpen == false) {
            return
        }
        try {
            usbSerialPort?.write((data + "\n").toByteArray(StandardCharsets.UTF_8), 200)
            Handler(Looper.getMainLooper()).post {
                sentDataForUi.value = data
            }
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                statusMessage.value = "送信エラー: ${e.message}"
            }
            Log.e("ActualSerialSend", "Error sending data", e)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        Log.d("USBRequest", "Requesting permission for ${device.deviceName}")
        val intentFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), intentFlags)
        usbManager!!.requestPermission(device, permissionIntent)
    }

    private fun checkConnectedUsbDevices() {
        val deviceList = usbManager?.deviceList
        if (deviceList.isNullOrEmpty()) {
            statusMessage.value = "USBデバイスが見つかりません"
            return
        }
        var foundSerialDevice = false
        deviceList.values.forEach { device ->
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver != null) {
                if (usbManager!!.hasPermission(device)) {
                    openUsbDevice(device)
                } else {
                    requestUsbPermission(device)
                }
                foundSerialDevice = true
                return@forEach
            }
        }
        if (!foundSerialDevice && (usbSerialPort == null || usbSerialPort?.isOpen == false)) {
            statusMessage.value = "互換性のあるUSBシリアルデバイスが見つかりません"
        }
    }

    private fun openUsbDevice(device: UsbDevice) {
        if (usbSerialPort != null && usbSerialPort!!.isOpen) {
            if (usbSerialPort?.device?.deviceId == device.deviceId) return
            closeUsbPort()
        }
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null || driver.ports.isEmpty()) {
            statusMessage.value = "USBシリアルドライバ/ポートが見つかりません (${device.deviceName})"
            return
        }
        usbSerialPort = driver.ports[0]
        val connection = usbManager?.openDevice(driver.device)
        if (connection == null) {
            statusMessage.value = "USBデバイスを開けません (${device.deviceName})"
            if (usbManager?.hasPermission(device) == false) requestUsbPermission(device)
            return
        }
        try {
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            statusMessage.value = "USBシリアル接続成功: ${device.productName ?: device.deviceName}"
        } catch (e: Exception) {
            statusMessage.value = "USBシリアルオープンエラー: ${e.message}"
            Log.e("USBSerial", "Error opening port", e)
            closeUsbPort()
        }
    }

    private fun closeUsbPort() {
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e("USBSerial", "Port close error", e)
        }
        usbSerialPort = null
        if (statusMessage.value.startsWith("USBシリアル接続成功")) {
            statusMessage.value = "USB接続待機中..."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inputHandler.removeCallbacksAndMessages(null)
        serialSendExecutor?.shutdownNow()
        readThread?.quitSafely()
        try {
            readThread?.join(500)
        } catch (e: InterruptedException) { /*Ignore*/ }
        closeUsbPort()
        unregisterReceiver(usbReceiver)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_DPAD)) {
            if (event.action == MotionEvent.ACTION_MOVE) {
                latestAxes[0] = event.getAxisValue(MotionEvent.AXIS_X)
                latestAxes[1] = event.getAxisValue(MotionEvent.AXIS_Y)
                latestAxes[2] = event.getAxisValue(MotionEvent.AXIS_Z)
                latestAxes[3] = event.getAxisValue(MotionEvent.AXIS_RZ)

                val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val dpadThreshold = 0.5f
                updateButtonState(KeyEvent.KEYCODE_DPAD_UP, hatY < -dpadThreshold)
                updateButtonState(KeyEvent.KEYCODE_DPAD_DOWN, hatY > dpadThreshold)
                updateButtonState(KeyEvent.KEYCODE_DPAD_LEFT, hatX < -dpadThreshold)
                updateButtonState(KeyEvent.KEYCODE_DPAD_RIGHT, hatX > dpadThreshold)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // 汎用的なボタン状態更新（Dpadと通常のボタン両方で使えるように）
    private fun updateButtonState(keyCode: Int, newState: Boolean) {
        if (buttonStates[keyCode] != newState) {
            buttonStates[keyCode] = newState
            // Log.d("ButtonStateUpdate", "KeyCode: $keyCode, NewState: $newState, Name: ${KeyEvent.keyCodeToString(keyCode)}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyEvent.isGamepadButton(event.keyCode) || CUSTOM_BUTTON_KEYCODES.contains(keyCode)) {
            updateButtonState(keyCode, true)
            Log.d("ControllerKeyDown", "KeyCode: $keyCode, Name: ${KeyEvent.keyCodeToString(keyCode)}")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (KeyEvent.isGamepadButton(event.keyCode) || CUSTOM_BUTTON_KEYCODES.contains(keyCode)) {
            updateButtonState(keyCode, false)
            Log.d("ControllerKeyUp", "KeyCode: $keyCode, Name: ${KeyEvent.keyCodeToString(keyCode)}")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startInputProcessingLoop() {
        inputHandler.post(object : Runnable {
            override fun run() {
                val axesStr = "n:" + latestAxes.joinToString(":") { "%.2f".format(it) }
                val buttonNames = mapOf(
                    KEYCODE_CUSTOM_CIRCLE to "cr",    // 97
                    KEYCODE_CUSTOM_CROSS to "ci",     // 96
                    KEYCODE_CUSTOM_TRIANGLE to "tri", // 100
                    KEYCODE_CUSTOM_SQUARE to "sq",    // 99
                    KeyEvent.KEYCODE_BUTTON_L1 to "L1",
                    KeyEvent.KEYCODE_BUTTON_R1 to "R1",
                    KeyEvent.KEYCODE_BUTTON_L2 to "L2",
                    KeyEvent.KEYCODE_BUTTON_R2 to "R2",
                    KeyEvent.KEYCODE_BUTTON_SELECT to "SH",
                    KeyEvent.KEYCODE_BUTTON_START to "OP",
                    KeyEvent.KEYCODE_BUTTON_MODE to "PS",
                    KeyEvent.KEYCODE_DPAD_LEFT to "l",
                    KeyEvent.KEYCODE_DPAD_RIGHT to "r",
                    KeyEvent.KEYCODE_DPAD_UP to "u",
                    KeyEvent.KEYCODE_DPAD_DOWN to "d",
                    KeyEvent.KEYCODE_BUTTON_THUMBL to "L3",
                    KeyEvent.KEYCODE_BUTTON_THUMBR to "R3"
                )
                val btnStr = buttonNames.entries.joinToString("|") { (keyCode, label) ->
                    if (buttonStates[keyCode] == true) "$label:p" else "$label:no_p"
                }
                val currentData = "|$axesStr|$btnStr|"
                queueSerialData(currentData)
                inputHandler.postDelayed(this, inputUpdateIntervalMs)
            }
        })
    }

    private fun startSerialSendLoop() {
        serialSendExecutor = Executors.newSingleThreadScheduledExecutor()
        serialSendExecutor?.scheduleAtFixedRate({
            try {
                val dataToSend = dataQueue.poll()
                dataToSend?.let {
                    actualSendSerial(it)
                }
            } catch (e: Exception) {
                Log.e("SerialSendLoop", "Error in send loop", e)
            }
        }, 0, serialSendIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun startReadLoop() {
        readThread = HandlerThread("UsbReadThread").apply { start() }
        readHandler = Handler(readThread!!.looper)

        readHandler?.post(object : Runnable {
            override fun run() {
                if (usbSerialPort == null || usbSerialPort?.isOpen == false) {
                    readHandler?.postDelayed(this, 200)
                    return
                }
                try {
                    val readBufferArray = ByteArray(256)
                    val len = usbSerialPort?.read(readBufferArray, 100) ?: 0
                    if (len > 0) {
                        val data = String(readBufferArray, 0, len, StandardCharsets.UTF_8)
                        serialBuffer.append(data)
                        while (serialBuffer.indexOf('\n') >= 0) {
                            val line = serialBuffer.substring(0, serialBuffer.indexOf('\n'))
                            Handler(Looper.getMainLooper()).post {
                                receivedData.value = line
                            }
                            // Log.d("SerialRead", "Received Line: $line")
                            serialBuffer.delete(0, line.length + 1)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SerialRead", "Read error", e)
                    Handler(Looper.getMainLooper()).post {
                        if (statusMessage.value.startsWith("USBシリアル接続成功")) {
                            statusMessage.value = "受信エラー: ${e.message}"
                        }
                    }
                }
                readHandler?.postDelayed(this, 50)
            }
        })
    }
}

// --- Composable UI Functions ---
// (MainActivityクラスの外に配置するか、別のファイルに定義)

@Composable
fun ControllerScreen(
    modifier: Modifier = Modifier,
    statusMessage: String,
    sentData: String,
    receivedData: String,
    axes: FloatArray,
    buttonStates: Map<Int, Boolean> // SnapshotStateMap を受け取る
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "状態: $statusMessage",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min), // 子要素の高さに合わせる
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top // 上揃えに変更
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                AnalogStickView(x = axes[0], y = axes[1], label = "左スティック")
                Spacer(Modifier.height(24.dp))
                DpadView(buttonStates = buttonStates)
            }

            Spacer(Modifier.width(16.dp)) // 中央のスペース

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                ControllerButtonsView(buttonStates = buttonStates)
            }

            Spacer(Modifier.width(16.dp)) // 中央のスペース

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                AnalogStickView(x = axes[2], y = axes[3], label = "右スティック")
                Spacer(Modifier.height(24.dp))
                OptionButtonsView(buttonStates = buttonStates)
            }
        }

        Spacer(modifier = Modifier.weight(1f)) // 残りのスペースを埋める

        Column(modifier = Modifier.fillMaxWidth()) {
            Text("送信データ (UI):", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max=80.dp)) {
                Text(sentData, modifier = Modifier.padding(8.dp), maxLines = 2)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("受信データ:", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max=80.dp)) {
                Text(receivedData, modifier = Modifier.padding(8.dp), maxLines = 2)
            }
        }
    }
}

@Composable
fun AnalogStickView(x: Float, y: Float, label: String, size: Dp = 80.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Box(
            modifier = Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val stickRadiusRatio = 0.25f // スティックの半径の、外円に対する割合
            val stickDiameter = size * stickRadiusRatio * 2
            // スティックの可動範囲を計算 (外円の半径 - スティックの半径)
            val movementRadius = (size / 2) - (stickDiameter / 2)

            Box(
                modifier = Modifier
                    .offset(
                        x = (x * movementRadius.value).dp,
                        y = (y * movementRadius.value).dp
                    )
                    .size(stickDiameter)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

@Composable
fun DpadView(buttonStates: Map<Int, Boolean>) {
    val buttonSize = 30.dp
    val containerSize = buttonSize * 3 // Dpad全体のコンテナサイズ

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("十字キー", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(containerSize)) {
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_DPAD_UP] == true,
                label = "↑",
                modifier = Modifier.align(Alignment.TopCenter).size(buttonSize)
            )
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_DPAD_LEFT] == true,
                label = "←",
                modifier = Modifier.align(Alignment.CenterStart).size(buttonSize)
            )
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_DPAD_RIGHT] == true,
                label = "→",
                modifier = Modifier.align(Alignment.CenterEnd).size(buttonSize)
            )
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_DPAD_DOWN] == true,
                label = "↓",
                modifier = Modifier.align(Alignment.BottomCenter).size(buttonSize)
            )
            Box(modifier = Modifier.size(buttonSize).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
fun ControllerButtonsView(buttonStates: Map<Int, Boolean>) {
    val buttonSize = 35.dp
    val spacing = 6.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row {
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_L1] == true,
                label = "L1", modifier = Modifier.width(55.dp).height(buttonSize * 0.7f)
            )
            Spacer(Modifier.width(buttonSize + spacing * 3)) // L1とR1の間隔
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_R1] == true,
                label = "R1", modifier = Modifier.width(55.dp).height(buttonSize * 0.7f)
            )
        }

        Box(modifier = Modifier.size(buttonSize * 2 + spacing * 2.5f)) {
            ButtonShape( // △ (Triangle)
                isPressed = buttonStates[MainActivity.KEYCODE_CUSTOM_TRIANGLE] == true,
                label = "△", shape = CircleShape,
                colorOverride = if (buttonStates[MainActivity.KEYCODE_CUSTOM_TRIANGLE] == true) Color(0xFF4CAF50) else Color(0xFFA5D6A7), // Green
                modifier = Modifier.align(Alignment.TopCenter).size(buttonSize)
            )
            ButtonShape( // □ (Square)
                isPressed = buttonStates[MainActivity.KEYCODE_CUSTOM_SQUARE] == true,
                label = "□", shape = CircleShape,
                colorOverride = if (buttonStates[MainActivity.KEYCODE_CUSTOM_SQUARE] == true) Color(0xFFFF9800) else Color(0xFFFFCC80), // Orange
                modifier = Modifier.align(Alignment.CenterStart).size(buttonSize)
            )
            ButtonShape( // ○ (Circle)
                isPressed = buttonStates[MainActivity.KEYCODE_CUSTOM_CIRCLE] == true,
                label = "○", shape = CircleShape,
                colorOverride = if (buttonStates[MainActivity.KEYCODE_CUSTOM_CIRCLE] == true) Color(0xFFF44336) else Color(0xFFEF9A9A), // Red
                modifier = Modifier.align(Alignment.CenterEnd).size(buttonSize)
            )
            ButtonShape( // ✕ (Cross)
                isPressed = buttonStates[MainActivity.KEYCODE_CUSTOM_CROSS] == true,
                label = "✕", shape = CircleShape,
                colorOverride = if (buttonStates[MainActivity.KEYCODE_CUSTOM_CROSS] == true) Color(0xFF2196F3) else Color(0xFF90CAF9), // Blue
                modifier = Modifier.align(Alignment.BottomCenter).size(buttonSize)
            )
        }
        Row {
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_L2] == true,
                label = "L2", modifier = Modifier.width(55.dp).height(buttonSize * 0.7f)
            )
            Spacer(Modifier.width(buttonSize + spacing * 3)) // L2とR2の間隔
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_R2] == true,
                label = "R2", modifier = Modifier.width(55.dp).height(buttonSize * 0.7f)
            )
        }
    }
}

@Composable
fun OptionButtonsView(buttonStates: Map<Int, Boolean>) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("オプション", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_SELECT] == true,
                label = "SH", modifier = Modifier.width(50.dp).height(25.dp)
            )
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_START] == true,
                label = "OP", modifier = Modifier.width(50.dp).height(25.dp)
            )
        }
        ButtonShape(
            isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_MODE] == true,
            label = "PS", shape = CircleShape, modifier = Modifier.size(35.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_THUMBL] == true,
                label = "L3", shape = CircleShape, modifier = Modifier.size(28.dp)
            )
            ButtonShape(
                isPressed = buttonStates[KeyEvent.KEYCODE_BUTTON_THUMBR] == true,
                label = "R3", shape = CircleShape, modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun ButtonShape(
    isPressed: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    colorOverride: Color? = null // 特定の色を強制する場合
) {
    val baseColor = colorOverride ?: if (isPressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (colorOverride != null) { // 固定色が指定された場合、押下状態で明暗を調整
        if (isPressed) Color.White else Color.Black.copy(alpha = 0.7f)
    } else {
        if (isPressed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    }


    Box(
        modifier = modifier
            .background(baseColor, shape)
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (isPressed) 0.7f else 0.3f),
                shape
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = when {
                label.length > 2 -> 9.sp
                label == "↑" || label == "←" || label == "→" || label == "↓" -> 14.sp
                else -> 11.sp
            },
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}