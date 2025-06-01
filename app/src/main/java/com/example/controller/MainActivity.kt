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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.controller.ui.theme.ControllerTheme
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.os.Handler
import android.os.Looper

class MainActivity : ComponentActivity() {
    private lateinit var statusMessage: MutableState<String>
    private lateinit var sentData: MutableState<String>
    private lateinit var receivedData: MutableState<String>

    private val ACTION_USB_PERMISSION = "com.example.controller.USB_PERMISSION"
    private var usbManager: UsbManager? = null
    private var usbSerialPort: UsbSerialPort? = null
    private val updateIntervalMs = 50L
    private val handler = Handler(Looper.getMainLooper())
    private val readHandler = Handler(Looper.getMainLooper())
    private var latestAxes = FloatArray(4) { 0f }
    private val buttonStates = mutableMapOf<Int, Boolean>()
    private val buffer = StringBuilder()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (intent.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { openUsbDevice(it) }
                    } else {
                        statusMessage.value = "USB権限が拒否されました"
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusMessage = mutableStateOf("USB接続を試行中...")
        sentData = mutableStateOf("")
        receivedData = mutableStateOf("")

        enableEdgeToEdge()

        setContent {
            ControllerTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(16.dp)
                    ) {
                        Text("状態: ${statusMessage.value}", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("送信データ: ${sentData.value}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("受信データ: ${receivedData.value}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbReceiver, filter)

        checkUsbDevice()
        startControllerLoop()
        startReadLoop()
    }

    private fun sendSerial(data: String) {
        try {
            usbSerialPort?.write((data + "\n").toByteArray(), 1000)
            sentData.value = data
        } catch (e: Exception) {
            statusMessage.value = "送信エラー: ${e.message}"
            Log.e("SerialSend", "Error sending data", e)
        }
    }

    private fun checkUsbDevice() {
        val deviceList = usbManager?.deviceList
        deviceList?.values?.forEach { device ->
            if (!usbManager!!.hasPermission(device)) {
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
                )
                usbManager!!.requestPermission(device, permissionIntent)
            } else {
                openUsbDevice(device)
            }
        }
    }

    private fun openUsbDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            statusMessage.value = "USBシリアルドライバが見つかりません"
            return
        }
        usbSerialPort = driver.ports[0]
        try {
            usbSerialPort?.apply {
                usbManager?.openDevice(driver.device)?.let { connection ->
                    open(connection)
                    setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    statusMessage.value = "USBシリアル接続成功"
                } ?: run {
                    statusMessage.value = "USB接続失敗"
                }
            }
        } catch (e: Exception) {
            statusMessage.value = "USBシリアルオープンエラー: ${e.message}"
            Log.e("USBSerial", "Error opening port", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e("USBSerial", "Port close error", e)
        }
        unregisterReceiver(usbReceiver)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {

            latestAxes[0] = event.getAxisValue(MotionEvent.AXIS_X)
            latestAxes[1] = event.getAxisValue(MotionEvent.AXIS_Y)
            latestAxes[2] = event.getAxisValue(MotionEvent.AXIS_Z)
            latestAxes[3] = event.getAxisValue(MotionEvent.AXIS_RZ)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        buttonStates[keyCode] = true
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        buttonStates[keyCode] = false
        return true
    }

    private fun startControllerLoop() {
        handler.post(object : Runnable {
            override fun run() {
                val axesStr = "n:" + latestAxes.joinToString(":") { "%.2f".format(it) }

                val buttonNames = mapOf(
                    KeyEvent.KEYCODE_BUTTON_C to "cr",
                    KeyEvent.KEYCODE_BUTTON_X to "ci",
                    KeyEvent.KEYCODE_BUTTON_Y to "tri",
                    KeyEvent.KEYCODE_BUTTON_B to "sq",
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

                val btnStr = buttonNames.entries.joinToString("|") { (key, label) ->
                    if (buttonStates[key] == true) "$label:p" else "$label:no_p"
                }

                val outStr = "|$axesStr|$btnStr"
                sendSerial(outStr)
                handler.postDelayed(this, updateIntervalMs)
            }
        })
    }

    private fun startReadLoop() {
        readHandler.post(object : Runnable {
            override fun run() {
                try {
                    val bufferArray = ByteArray(64)
                    val len = usbSerialPort?.read(bufferArray, 100) ?: 0
                    if (len > 0) {
                        val data = String(bufferArray, 0, len)
                        buffer.append(data)
                        val lines = buffer.split("\n")
                        for (i in 0 until lines.size - 1) {
                            receivedData.value = lines[i]
                        }
                        buffer.clear()
                        if (!data.endsWith("\n")) buffer.append(lines.last())
                    }
                } catch (e: Exception) {
                    Log.e("SerialRead", "Read error", e)
                }
                readHandler.postDelayed(this, 50)
            }
        })
    }
}
