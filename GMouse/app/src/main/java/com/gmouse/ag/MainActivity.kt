package com.gmouse.ag

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.CAMERA
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
            permissions[Manifest.permission.BLUETOOTH_SCAN] == true) {
            startHidService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasBluetoothPermissions()) {
            startHidService()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            BluetoothHidService.stop(this)
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == 
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == 
            PackageManager.PERMISSION_GRANTED
    }

    private fun startHidService() {
        BluetoothHidService.start(this)
    }
}

enum class ControlMode {
    TOUCHPAD, GESTURE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val hidCore = BluetoothHidService.hidCore
    val connectionState by hidCore?.connectionState?.collectAsStateWithLifecycle() 
        ?: remember { mutableStateOf(BluetoothHidCore.ConnectionState.DISCONNECTED) }
    val connectedDeviceName by hidCore?.connectedDeviceName?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf<String?>(null) }

    var controlMode by remember { mutableStateOf(ControlMode.TOUCHPAD) }
    var showDeviceList by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GMouse", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    when (connectionState) {
                                        BluetoothHidCore.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                                        BluetoothHidCore.ConnectionState.CONNECTING -> Color(0xFFFFC107)
                                        BluetoothHidCore.ConnectionState.DISCONNECTED -> Color(0xFFF44336)
                                    },
                                    RoundedCornerShape(50)
                                )
                        )
                    }
                },
                actions = {
                    if (connectionState == BluetoothHidCore.ConnectionState.CONNECTED) {
                        TextButton(onClick = { hidCore?.disconnect() }) {
                            Text("Disconnect", color = Color.Red)
                        }
                    } else {
                        TextButton(onClick = { showDeviceList = true }) {
                            Text("Connect")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = controlMode == ControlMode.TOUCHPAD,
                    onClick = { controlMode = ControlMode.TOUCHPAD },
                    icon = { Icon(Icons.Default.TouchApp, contentDescription = "Touchpad") },
                    label = { Text("Touchpad") }
                )
                NavigationBarItem(
                    selected = controlMode == ControlMode.GESTURE,
                    onClick = { controlMode = ControlMode.GESTURE },
                    icon = { Icon(Icons.Default.Videocam, contentDescription = "Gesture") },
                    label = { Text("Gesture") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (controlMode) {
                ControlMode.TOUCHPAD -> {
                    TouchpadScreen(
                        connectionState = connectionState,
                        connectedDeviceName = connectedDeviceName,
                        onConnect = { showDeviceList = true },
                        hidCore = hidCore
                    )
                }
                ControlMode.GESTURE -> {
                    GestureScreen(
                        connectionState = connectionState,
                        onSendMouseMove = { dx, dy -> hidCore?.sendMouseReport(dx, dy) },
                        onSendLeftClick = { hidCore?.sendLeftClick() },
                        onSendRightClick = { hidCore?.sendRightClick() },
                        onSendScroll = { scroll -> hidCore?.sendMouseReport(0, 0, wheel = scroll) },
                        hidCore = hidCore
                    )
                }
            }
        }
    }

    if (showDeviceList) {
        DeviceListDialog(
            devices = hidCore?.getBondedDevices() ?: emptyList(),
            onDeviceSelected = { device ->
                hidCore?.connect(device)
                showDeviceList = false
            },
            onDismiss = { showDeviceList = false }
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchpadScreen(
    connectionState: BluetoothHidCore.ConnectionState,
    connectedDeviceName: String?,
    onConnect: () -> Unit,
    hidCore: BluetoothHidCore?
) {
    var keyboardActive by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Connection status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    BluetoothHidCore.ConnectionState.CONNECTED -> Color(0xFF1B5E20)
                    BluetoothHidCore.ConnectionState.CONNECTING -> Color(0xFF795548)
                    else -> Color(0xFF424242)
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (connectionState) {
                        BluetoothHidCore.ConnectionState.CONNECTED -> "Connected"
                        BluetoothHidCore.ConnectionState.CONNECTING -> "Connecting..."
                        else -> "Disconnected"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                connectedDeviceName?.let {
                    Text(it, fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (connectionState == BluetoothHidCore.ConnectionState.CONNECTED) {
            // Touchpad area
            TouchpadArea(
                onMove = { dx, dy -> hidCore?.sendMouseReport(dx.toInt(), dy.toInt()) },
                onTap = { hidCore?.sendLeftClick() },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Click buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { hidCore?.sendLeftClick() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Text("Left Click", fontSize = 14.sp)
                }
                Button(
                    onClick = { hidCore?.sendRightClick() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Text("Right Click", fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Keyboard section
            if (keyboardActive) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF37474F))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Real-time Keyboard", color = Color.White, fontSize = 14.sp)
                            IconButton(onClick = {
                                keyboardActive = false
                                keyboardController?.hide()
                            }) {
                                Icon(Icons.Default.KeyboardHide, "Hide", tint = Color.White)
                            }
                        }

                        BasicTextField(
                            value = inputText,
                            onValueChange = { newText ->
                                if (newText.length > inputText.length) {
                                    val newChars = newText.substring(inputText.length)
                                    scope.launch(Dispatchers.IO) {
                                        newChars.forEach { char ->
                                            hidCore?.sendKey(char)
                                        }
                                    }
                                }
                                inputText = newText
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                                .focusRequester(focusRequester)
                                .onKeyEvent { keyEvent ->
                                    when (keyEvent.key) {
                                        Key.Backspace -> {
                                            scope.launch(Dispatchers.IO) { hidCore?.sendBackspace() }
                                            if (inputText.isNotEmpty()) {
                                                inputText = inputText.dropLast(1)
                                            }
                                            true
                                        }
                                        Key.Enter -> {
                                            scope.launch(Dispatchers.IO) { hidCore?.sendEnter() }
                                            true
                                        }
                                        else -> false
                                    }
                                },
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (inputText.isEmpty()) {
                                        Text("Type here...", color = Color.Gray, fontSize = 16.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) { hidCore?.sendBackspace() }
                                    if (inputText.isNotEmpty()) {
                                        inputText = inputText.dropLast(1)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
                            ) {
                                Text("⌫")
                            }
                            Button(
                                onClick = {
                                    scope.launch(Dispatchers.IO) { hidCore?.sendEnter() }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
                            ) {
                                Text("↵")
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Button(
                            onClick = { inputText = "" },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))
                        ) {
                            Text("Clear")
                        }
                    }
                }

                LaunchedEffect(keyboardActive) {
                    if (keyboardActive) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }
            } else {
                Button(
                    onClick = { keyboardActive = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2))
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Keyboard", fontSize = 15.sp)
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Connect to a device to use the touchpad",
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onConnect) {
                        Text("Connect Device")
                    }
                }
            }
        }
    }
}

@Composable
fun TouchpadArea(
    onMove: (Float, Float) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF2D2D2D), RoundedCornerShape(16.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onMove(dragAmount.x * 1.5f, dragAmount.y * 1.5f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Touchpad\n\nDrag to move cursor\nTap to click",
            color = Color.Gray,
            textAlign = TextAlign.Center,
            fontSize = 16.sp
        )
    }
}

@Composable
fun DeviceListDialog(
    devices: List<BluetoothDevice>,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Device") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired devices found. Pair a device in Bluetooth settings first.")
            } else {
                LazyColumn {
                    items(devices) { device ->
                        DeviceItem(device = device, onClick = { onDeviceSelected(device) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeviceItem(device: BluetoothDevice, onClick: () -> Unit) {
    val deviceName = try {
        device.name ?: "Unknown Device"
    } catch (e: SecurityException) {
        "Unknown Device"
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(deviceName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(device.address, fontSize = 12.sp, color = Color.Gray)
        }
    }
}
