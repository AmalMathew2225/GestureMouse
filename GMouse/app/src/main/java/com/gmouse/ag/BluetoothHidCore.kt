package com.gmouse.ag

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Core Bluetooth HID implementation for mouse, keyboard, and TV remote control.
 * V6: Added TV remote keys (D-pad, volume, home, back).
 */
class BluetoothHidCore(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidCore"
        
        private const val MOUSE_REPORT_ID = 1
        private const val KEYBOARD_REPORT_ID = 2
        private const val CONSUMER_REPORT_ID = 3  // For volume control
        
        // Combined HID Report Descriptor for Mouse + Keyboard + Consumer
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            // ========== MOUSE ==========
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), // Report ID (1)
            0x09.toByte(), 0x01.toByte(), // Usage (Pointer)
            0xA1.toByte(), 0x00.toByte(), // Collection (Physical)
            0x05.toByte(), 0x09.toByte(), // Usage Page (Buttons)
            0x19.toByte(), 0x01.toByte(), // Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(), // Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(), // Report Count (3)
            0x75.toByte(), 0x01.toByte(), // Report Size (1)
            0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), // Report Count (1)
            0x75.toByte(), 0x05.toByte(), // Report Size (5)
            0x81.toByte(), 0x01.toByte(), // Input (Constant)
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), // Usage (X)
            0x09.toByte(), 0x31.toByte(), // Usage (Y)
            0x09.toByte(), 0x38.toByte(), // Usage (Wheel)
            0x15.toByte(), 0x81.toByte(), // Logical Minimum (-127)
            0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), // Report Size (8)
            0x95.toByte(), 0x03.toByte(), // Report Count (3)
            0x81.toByte(), 0x06.toByte(), // Input (Data, Variable, Relative)
            0xC0.toByte(),                // End Collection
            0xC0.toByte(),                // End Collection
            
            // ========== KEYBOARD ==========
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x02.toByte(), // Report ID (2)
            0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
            0x19.toByte(), 0xE0.toByte(), // Usage Minimum (224)
            0x29.toByte(), 0xE7.toByte(), // Usage Maximum (231)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), // Report Size (1)
            0x95.toByte(), 0x08.toByte(), // Report Count (8)
            0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)
            0x95.toByte(), 0x01.toByte(), // Report Count (1)
            0x75.toByte(), 0x08.toByte(), // Report Size (8)
            0x81.toByte(), 0x01.toByte(), // Input (Constant)
            0x95.toByte(), 0x06.toByte(), // Report Count (6)
            0x75.toByte(), 0x08.toByte(), // Report Size (8)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(), // Logical Maximum (101)
            0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
            0x19.toByte(), 0x00.toByte(), // Usage Minimum (0)
            0x29.toByte(), 0x65.toByte(), // Usage Maximum (101)
            0x81.toByte(), 0x00.toByte(), // Input (Data, Array)
            0xC0.toByte(),                // End Collection
            
            // ========== CONSUMER CONTROL (Volume, Media) ==========
            0x05.toByte(), 0x0C.toByte(), // Usage Page (Consumer)
            0x09.toByte(), 0x01.toByte(), // Usage (Consumer Control)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x03.toByte(), // Report ID (3)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), // Report Size (1)
            0x95.toByte(), 0x08.toByte(), // Report Count (8)
            0x09.toByte(), 0xE9.toByte(), // Usage (Volume Up)
            0x09.toByte(), 0xEA.toByte(), // Usage (Volume Down)
            0x09.toByte(), 0xE2.toByte(), // Usage (Mute)
            0x09.toByte(), 0x23.toByte(), // Usage (Home)
            0x09.toByte(), 0x24.toByte(), // Usage (Back)
            0x09.toByte(), 0xB5.toByte(), // Usage (Next Track)
            0x09.toByte(), 0xB6.toByte(), // Usage (Previous Track)
            0x09.toByte(), 0xCD.toByte(), // Usage (Play/Pause)
            0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute)
            0xC0.toByte()                 // End Collection
        )
        
        // Keyboard key codes
        private val KEY_MAP = mapOf(
            'a' to 0x04, 'b' to 0x05, 'c' to 0x06, 'd' to 0x07,
            'e' to 0x08, 'f' to 0x09, 'g' to 0x0A, 'h' to 0x0B,
            'i' to 0x0C, 'j' to 0x0D, 'k' to 0x0E, 'l' to 0x0F,
            'm' to 0x10, 'n' to 0x11, 'o' to 0x12, 'p' to 0x13,
            'q' to 0x14, 'r' to 0x15, 's' to 0x16, 't' to 0x17,
            'u' to 0x18, 'v' to 0x19, 'w' to 0x1A, 'x' to 0x1B,
            'y' to 0x1C, 'z' to 0x1D,
            '1' to 0x1E, '2' to 0x1F, '3' to 0x20, '4' to 0x21,
            '5' to 0x22, '6' to 0x23, '7' to 0x24, '8' to 0x25,
            '9' to 0x26, '0' to 0x27,
            ' ' to 0x2C, '-' to 0x2D, '=' to 0x2E, '[' to 0x2F,
            ']' to 0x30, '\\' to 0x31, ';' to 0x33, '\'' to 0x34,
            '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38
        )
        
        private val SHIFT_KEY_MAP = mapOf(
            'A' to 0x04, 'B' to 0x05, 'C' to 0x06, 'D' to 0x07,
            'E' to 0x08, 'F' to 0x09, 'G' to 0x0A, 'H' to 0x0B,
            'I' to 0x0C, 'J' to 0x0D, 'K' to 0x0E, 'L' to 0x0F,
            'M' to 0x10, 'N' to 0x11, 'O' to 0x12, 'P' to 0x13,
            'Q' to 0x14, 'R' to 0x15, 'S' to 0x16, 'T' to 0x17,
            'U' to 0x18, 'V' to 0x19, 'W' to 0x1A, 'X' to 0x1B,
            'Y' to 0x1C, 'Z' to 0x1D,
            '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21,
            '%' to 0x22, '^' to 0x23, '&' to 0x24, '*' to 0x25,
            '(' to 0x26, ')' to 0x27,
            '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30,
            '|' to 0x31, ':' to 0x33, '"' to 0x34, '~' to 0x35,
            '<' to 0x36, '>' to 0x37, '?' to 0x38
        )
        
        // Keyboard keys
        const val KEY_ENTER = 0x28
        const val KEY_ESCAPE = 0x29
        const val KEY_BACKSPACE = 0x2A
        const val KEY_TAB = 0x2B
        const val KEY_DELETE = 0x4C
        
        // Arrow keys (D-pad)
        const val KEY_RIGHT = 0x4F
        const val KEY_LEFT = 0x50
        const val KEY_DOWN = 0x51
        const val KEY_UP = 0x52
        
        const val MODIFIER_NONE = 0x00
        const val MODIFIER_SHIFT = 0x02
        
        // Consumer control bits
        const val CONSUMER_VOLUME_UP = 0x01
        const val CONSUMER_VOLUME_DOWN = 0x02
        const val CONSUMER_MUTE = 0x04
        const val CONSUMER_HOME = 0x08
        const val CONSUMER_BACK = 0x10
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    private val hidSettings = BluetoothHidDeviceAppSdpSettings(
        "GMouse",
        "Bluetooth HID Mouse, Keyboard & Remote",
        "GMouse",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        HID_REPORT_DESCRIPTOR
    )

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = proxy as? BluetoothHidDevice
                registerApp()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                bluetoothHidDevice = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {}
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            try {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        _connectedDeviceName.value = device?.name ?: "Unknown"
                        _connectionState.value = ConnectionState.CONNECTED
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectedDevice = null
                        _connectedDeviceName.value = null
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        _connectionState.value = ConnectionState.CONNECTING
                    }
                }
            } catch (e: SecurityException) { }
        }
    }

    fun start() {
        try {
            bluetoothAdapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) { }
    }

    fun stop() {
        try {
            bluetoothHidDevice?.let { hid ->
                connectedDevice?.let { hid.disconnect(it) }
                hid.unregisterApp()
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
            bluetoothHidDevice = null
            connectedDevice = null
            _connectionState.value = ConnectionState.DISCONNECTED
        } catch (e: SecurityException) { }
    }

    private fun registerApp() {
        try {
            bluetoothHidDevice?.registerApp(hidSettings, null, null, Executors.newSingleThreadExecutor(), hidCallback)
        } catch (e: SecurityException) { }
    }

    fun getBondedDevices(): List<BluetoothDevice> = try {
        bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) { emptyList() }

    fun connect(device: BluetoothDevice): Boolean = try {
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothHidDevice?.connect(device) ?: false
    } catch (e: SecurityException) {
        _connectionState.value = ConnectionState.DISCONNECTED
        false
    }

    fun disconnect(): Boolean = try {
        connectedDevice?.let { bluetoothHidDevice?.disconnect(it) } ?: false
    } catch (e: SecurityException) { false }

    // ========== MOUSE ==========
    
    fun sendMouseReport(dx: Int, dy: Int, buttons: Int = 0, wheel: Int = 0): Boolean {
        val device = connectedDevice ?: return false
        val hidDevice = bluetoothHidDevice ?: return false
        return try {
            val report = byteArrayOf(
                buttons.toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
            hidDevice.sendReport(device, MOUSE_REPORT_ID, report)
        } catch (e: SecurityException) { false }
    }

    fun sendLeftClick() {
        sendMouseReport(0, 0, buttons = 1)
        Thread.sleep(50)
        sendMouseReport(0, 0, buttons = 0)
    }

    fun sendRightClick() {
        sendMouseReport(0, 0, buttons = 2)
        Thread.sleep(50)
        sendMouseReport(0, 0, buttons = 0)
    }

    // ========== KEYBOARD ==========
    
    fun sendKey(char: Char): Boolean {
        val device = connectedDevice ?: return false
        val hidDevice = bluetoothHidDevice ?: return false
        
        val keyCode: Int
        val modifier: Int
        when {
            KEY_MAP.containsKey(char) -> { keyCode = KEY_MAP[char]!!; modifier = MODIFIER_NONE }
            SHIFT_KEY_MAP.containsKey(char) -> { keyCode = SHIFT_KEY_MAP[char]!!; modifier = MODIFIER_SHIFT }
            char == '\n' -> { keyCode = KEY_ENTER; modifier = MODIFIER_NONE }
            char == '\t' -> { keyCode = KEY_TAB; modifier = MODIFIER_NONE }
            else -> return false
        }
        
        return try {
            val press = byteArrayOf(modifier.toByte(), 0x00, keyCode.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00)
            hidDevice.sendReport(device, KEYBOARD_REPORT_ID, press)
            Thread.sleep(30)
            val release = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            hidDevice.sendReport(device, KEYBOARD_REPORT_ID, release)
            Thread.sleep(20)
            true
        } catch (e: Exception) { false }
    }
    
    fun sendEnter(): Boolean = sendKeyCode(KEY_ENTER)
    fun sendBackspace(): Boolean = sendKeyCode(KEY_BACKSPACE)
    
    private fun sendKeyCode(keyCode: Int): Boolean {
        val device = connectedDevice ?: return false
        val hidDevice = bluetoothHidDevice ?: return false
        return try {
            val press = byteArrayOf(0x00, 0x00, keyCode.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00)
            hidDevice.sendReport(device, KEYBOARD_REPORT_ID, press)
            Thread.sleep(50)
            val release = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            hidDevice.sendReport(device, KEYBOARD_REPORT_ID, release)
            true
        } catch (e: Exception) { false }
    }

    // ========== TV REMOTE CONTROLS ==========
    
    fun sendDpadRight(): Boolean = sendKeyCode(KEY_RIGHT)
    fun sendDpadLeft(): Boolean = sendKeyCode(KEY_LEFT)
    fun sendDpadUp(): Boolean = sendKeyCode(KEY_UP)
    fun sendDpadDown(): Boolean = sendKeyCode(KEY_DOWN)
    
    fun sendVolumeUp(): Boolean = sendConsumerKey(CONSUMER_VOLUME_UP)
    fun sendVolumeDown(): Boolean = sendConsumerKey(CONSUMER_VOLUME_DOWN)
    fun sendHome(): Boolean = sendConsumerKey(CONSUMER_HOME)
    fun sendBack(): Boolean = sendKeyCode(KEY_ESCAPE)  // Escape works as back on most devices
    
    private fun sendConsumerKey(key: Int): Boolean {
        val device = connectedDevice ?: return false
        val hidDevice = bluetoothHidDevice ?: return false
        return try {
            // Press
            val press = byteArrayOf(key.toByte())
            hidDevice.sendReport(device, CONSUMER_REPORT_ID, press)
            Thread.sleep(50)
            // Release
            val release = byteArrayOf(0x00)
            hidDevice.sendReport(device, CONSUMER_REPORT_ID, release)
            true
        } catch (e: Exception) { 
            Log.e(TAG, "Error sending consumer key", e)
            false 
        }
    }
}
