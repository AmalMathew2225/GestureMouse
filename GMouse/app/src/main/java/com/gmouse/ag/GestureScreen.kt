package com.gmouse.ag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.abs

@Composable
fun GestureScreen(
    connectionState: BluetoothHidCore.ConnectionState,
    onSendMouseMove: (Int, Int) -> Unit,
    onSendLeftClick: () -> Unit,
    onSendRightClick: () -> Unit,
    onSendScroll: (Int) -> Unit,
    hidCore: BluetoothHidCore?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    var gestureResult by remember { mutableStateOf<HandGestureTracker.GestureResult?>(null) }
    var gestureTracker by remember { mutableStateOf<HandGestureTracker?>(null) }
    var lastX by remember { mutableFloatStateOf(0.5f) }
    var lastY by remember { mutableFloatStateOf(0.5f) }
    var isTracking by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var wasPointing by remember { mutableStateOf(false) }
    
    // V6: TV Mode
    var tvMode by remember { mutableStateOf(false) }
    var gestureStartTime by remember { mutableLongStateOf(0L) }
    var currentHeldGesture by remember { mutableStateOf(HandGestureTracker.Gesture.NONE) }
    var gestureConfirmed by remember { mutableStateOf(false) }
    var lastActionTime by remember { mutableLongStateOf(0L) }
    var tvActionJob by remember { mutableStateOf<Job?>(null) }
    
    var sensitivity by remember { mutableFloatStateOf(500f) }
    var accelerationEnabled by remember { mutableStateOf(true) }
    val accelerationFactor = 2.5f
    
    var accumulatedDx by remember { mutableFloatStateOf(0f) }
    var accumulatedDy by remember { mutableFloatStateOf(0f) }
    
    // TV Mode action intervals (ms)
    val confirmationTime = 2000L  // 2 seconds to confirm
    val volumeInterval = 500L     // 0.5 sec for volume
    val dpadInterval = 2000L      // 2 sec for D-pad/back
    
    DisposableEffect(Unit) {
        val tracker = HandGestureTracker(context) { result ->
            gestureResult = result
            
            if (connectionState != BluetoothHidCore.ConnectionState.CONNECTED || !isTracking) return@HandGestureTracker
            
            if (tvMode) {
                // ===== TV MODE =====
                val currentTime = System.currentTimeMillis()
                val gesture = result.gesture
                
                // Check if gesture changed
                if (gesture != currentHeldGesture) {
                    // Gesture changed - reset timer
                    currentHeldGesture = gesture
                    gestureStartTime = currentTime
                    gestureConfirmed = false
                    tvActionJob?.cancel()
                    tvActionJob = null
                    return@HandGestureTracker
                }
                
                // Same gesture held
                val holdDuration = currentTime - gestureStartTime
                
                // Check if 2 seconds passed (confirmation)
                if (!gestureConfirmed && holdDuration >= confirmationTime) {
                    gestureConfirmed = true
                    lastActionTime = currentTime
                    
                    // Execute first action
                    when (gesture) {
                        HandGestureTracker.Gesture.POINTING -> {
                            // Tile RIGHT
                            scope.launch(Dispatchers.IO) { hidCore?.sendDpadRight() }
                        }
                        HandGestureTracker.Gesture.LEFT_CLICK -> {
                            // V-sign = Tile LEFT
                            scope.launch(Dispatchers.IO) { hidCore?.sendDpadLeft() }
                        }
                        HandGestureTracker.Gesture.SCROLL_UP -> {
                            // Thumbs up = Volume UP
                            scope.launch(Dispatchers.IO) { hidCore?.sendVolumeUp() }
                        }
                        HandGestureTracker.Gesture.THUMBS_DOWN -> {
                            // Thumbs down = Volume DOWN
                            scope.launch(Dispatchers.IO) { hidCore?.sendVolumeDown() }
                        }
                        HandGestureTracker.Gesture.THUMB_PINKY -> {
                            // Thumb + Pinky = Scroll UP (continuous)
                            tvActionJob = scope.launch(Dispatchers.IO) {
                                while (isActive) {
                                    hidCore?.sendMouseReport(0, 0, wheel = -3)
                                    delay(50)  // Continuous smooth scroll
                                }
                            }
                        }
                        HandGestureTracker.Gesture.INDEX_PINKY -> {
                            // Index + Pinky = Scroll DOWN (continuous)
                            tvActionJob = scope.launch(Dispatchers.IO) {
                                while (isActive) {
                                    hidCore?.sendMouseReport(0, 0, wheel = 3)
                                    delay(50)
                                }
                            }
                        }
                        HandGestureTracker.Gesture.OPEN_HAND -> {
                            // Open hand = HOME (one-time)
                            scope.launch(Dispatchers.IO) { hidCore?.sendHome() }
                        }
                        HandGestureTracker.Gesture.SCROLL_DOWN -> {
                            // Fist = BACK
                            scope.launch(Dispatchers.IO) { hidCore?.sendBack() }
                        }
                        else -> {}
                    }
                } else if (gestureConfirmed) {
                    // Gesture already confirmed - check for repeat actions
                    val timeSinceLastAction = currentTime - lastActionTime
                    
                    when (gesture) {
                        HandGestureTracker.Gesture.POINTING -> {
                            // Tile RIGHT - repeat every 2s
                            if (timeSinceLastAction >= dpadInterval) {
                                scope.launch(Dispatchers.IO) { hidCore?.sendDpadRight() }
                                lastActionTime = currentTime
                            }
                        }
                        HandGestureTracker.Gesture.LEFT_CLICK -> {
                            // Tile LEFT - repeat every 2s
                            if (timeSinceLastAction >= dpadInterval) {
                                scope.launch(Dispatchers.IO) { hidCore?.sendDpadLeft() }
                                lastActionTime = currentTime
                            }
                        }
                        HandGestureTracker.Gesture.SCROLL_UP -> {
                            // Volume UP - every 0.5s
                            if (timeSinceLastAction >= volumeInterval) {
                                scope.launch(Dispatchers.IO) { hidCore?.sendVolumeUp() }
                                lastActionTime = currentTime
                            }
                        }
                        HandGestureTracker.Gesture.THUMBS_DOWN -> {
                            // Volume DOWN - every 0.5s
                            if (timeSinceLastAction >= volumeInterval) {
                                scope.launch(Dispatchers.IO) { hidCore?.sendVolumeDown() }
                                lastActionTime = currentTime
                            }
                        }
                        HandGestureTracker.Gesture.SCROLL_DOWN -> {
                            // Back - every 2s
                            if (timeSinceLastAction >= dpadInterval) {
                                scope.launch(Dispatchers.IO) { hidCore?.sendBack() }
                                lastActionTime = currentTime
                            }
                        }
                        // THUMB_PINKY and INDEX_PINKY use continuous job, no interval needed
                        else -> {}
                    }
                }
                
            } else {
                // ===== LAPTOP MODE (V5 unchanged) =====
                val isPointing = result.gesture == HandGestureTracker.Gesture.POINTING
                
                when (result.gesture) {
                    HandGestureTracker.Gesture.POINTING -> {
                        if (!wasPointing) {
                            lastX = result.indexFingerX
                            lastY = result.indexFingerY
                            accumulatedDx = 0f
                            accumulatedDy = 0f
                        } else if (result.shouldMoveCursor) {
                            val rawDx = result.indexFingerX - lastX
                            val rawDy = result.indexFingerY - lastY
                            
                            val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)
                            val multiplier = if (accelerationEnabled && speed > 0.01f) {
                                sensitivity * (1 + speed * accelerationFactor * 10)
                            } else sensitivity
                            
                            accumulatedDx += rawDx * multiplier
                            accumulatedDy += rawDy * multiplier
                            
                            val sendDx = accumulatedDx.toInt()
                            val sendDy = accumulatedDy.toInt()
                            
                            if (sendDx != 0 || sendDy != 0) {
                                val steps = maxOf(abs(sendDx) / 127 + 1, abs(sendDy) / 127 + 1)
                                if (steps > 1) {
                                    repeat(steps) {
                                        onSendMouseMove((-sendDx / steps).coerceIn(-127, 127), (sendDy / steps).coerceIn(-127, 127))
                                    }
                                } else {
                                    onSendMouseMove((-sendDx).coerceIn(-127, 127), sendDy.coerceIn(-127, 127))
                                }
                                accumulatedDx -= sendDx
                                accumulatedDy -= sendDy
                            }
                            lastX = result.indexFingerX
                            lastY = result.indexFingerY
                        }
                    }
                    HandGestureTracker.Gesture.LEFT_CLICK -> onSendLeftClick()
                    HandGestureTracker.Gesture.RIGHT_CLICK -> onSendRightClick()
                    HandGestureTracker.Gesture.SCROLL_UP -> onSendScroll(-5)
                    HandGestureTracker.Gesture.SCROLL_DOWN -> onSendScroll(5)
                    HandGestureTracker.Gesture.OPEN_HAND -> {
                        accumulatedDx = 0f
                        accumulatedDy = 0f
                    }
                    else -> {}
                }
                wasPointing = isPointing
            }
        }
        tracker.initialize()
        gestureTracker = tracker
        
        onDispose { 
            tvActionJob?.cancel()
            tracker.close() 
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status bar with TV toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (tvMode) Color(0xFF0D47A1) else when (connectionState) {
                    BluetoothHidCore.ConnectionState.CONNECTED -> Color(0xFF1B5E20)
                    else -> Color(0xFF424242)
                }
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (connectionState == BluetoothHidCore.ConnectionState.CONNECTED) "✓" else "✗",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // TV Mode toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                if (tvMode) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Tv,
                            contentDescription = "TV Mode",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (tvMode) "TV" else "PC",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = tvMode,
                            onCheckedChange = { 
                                tvMode = it
                                // Reset TV mode state
                                gestureConfirmed = false
                                currentHeldGesture = HandGestureTracker.Gesture.NONE
                                tvActionJob?.cancel()
                            },
                            modifier = Modifier.height(24.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF1565C0)
                            )
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Gesture status with confirmation progress for TV mode
                    val gestureText = if (tvMode) {
                        val holdProgress = if (currentHeldGesture != HandGestureTracker.Gesture.NONE && !gestureConfirmed) {
                            val elapsed = System.currentTimeMillis() - gestureStartTime
                            " ${(elapsed * 100 / confirmationTime).coerceIn(0, 100)}%"
                        } else if (gestureConfirmed) " ✓" else ""
                        
                        when (gestureResult?.gesture) {
                            HandGestureTracker.Gesture.POINTING -> "☝→$holdProgress"
                            HandGestureTracker.Gesture.LEFT_CLICK -> "✌←$holdProgress"
                            HandGestureTracker.Gesture.SCROLL_UP -> "👍🔊$holdProgress"
                            HandGestureTracker.Gesture.THUMBS_DOWN -> "👎🔉$holdProgress"
                            HandGestureTracker.Gesture.THUMB_PINKY -> "🤙⬆$holdProgress"
                            HandGestureTracker.Gesture.INDEX_PINKY -> "🤟⬇$holdProgress"
                            HandGestureTracker.Gesture.OPEN_HAND -> "✋🏠$holdProgress"
                            HandGestureTracker.Gesture.SCROLL_DOWN -> "✊⬅$holdProgress"
                            else -> "👀"
                        }
                    } else {
                        when (gestureResult?.gesture) {
                            HandGestureTracker.Gesture.POINTING -> "☝ Move"
                            HandGestureTracker.Gesture.LEFT_CLICK -> "✌ Left"
                            HandGestureTracker.Gesture.RIGHT_CLICK -> "🤙 Right"
                            HandGestureTracker.Gesture.SCROLL_UP -> "👍 Up"
                            HandGestureTracker.Gesture.SCROLL_DOWN -> "✊ Down"
                            HandGestureTracker.Gesture.OPEN_HAND -> "✋ Pause"
                            else -> "👀"
                        }
                    }
                    
                    Text(gestureText, color = Color.White, fontSize = 12.sp)
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Settings, "Settings", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(12.dp))
        ) {
            CameraPreview(gestureTracker = gestureTracker, modifier = Modifier.fillMaxSize())
            
            gestureResult?.landmarks?.let { landmarks ->
                HandLandmarksOverlay(
                    landmarks = landmarks,
                    gesture = gestureResult?.gesture ?: HandGestureTracker.Gesture.NONE,
                    tvMode = tvMode,
                    gestureConfirmed = gestureConfirmed,
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Gesture guide
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (tvMode) "TV: ☝→ ✌← 👍🔊 👎🔉 ✋🏠 ✊⬅ (2s hold)" 
                           else "PC: ☝Move ✌Left 🤙Right 👍↑ ✊↓",
                    color = Color.White,
                    fontSize = 9.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Track", color = Color.White, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = isTracking,
                    onCheckedChange = { isTracking = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF1B5E20))
                )
            }
            
            Button(
                onClick = {
                    gestureTracker?.resetSmoothing()
                    lastX = 0.5f; lastY = 0.5f
                    accumulatedDx = 0f; accumulatedDy = 0f
                    wasPointing = false
                    gestureConfirmed = false
                    currentHeldGesture = HandGestureTracker.Gesture.NONE
                    tvActionJob?.cancel()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Reset", fontSize = 13.sp)
            }
        }
    }
    
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column {
                    Text("Sensitivity: ${sensitivity.toInt()}", fontSize = 14.sp)
                    Slider(
                        value = sensitivity,
                        onValueChange = { sensitivity = it },
                        valueRange = 100f..800f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF1B5E20), activeTrackColor = Color(0xFF1B5E20))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("Acceleration", fontSize = 14.sp)
                        Switch(checked = accelerationEnabled, onCheckedChange = { accelerationEnabled = it })
                    }
                }
            },
            confirmButton = { Button(onClick = { showSettings = false }) { Text("Done") } }
        )
    }
}

@Composable
fun CameraPreview(gestureTracker: HandGestureTracker?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    
    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = modifier,
        update = { previewView -> startCamera(context, lifecycleOwner, previewView, gestureTracker, executor) }
    )
}

private fun startCamera(
    context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView,
    gestureTracker: HandGestureTracker?, executor: java.util.concurrent.ExecutorService
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor) { imageProxy -> processImage(imageProxy, gestureTracker) } }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
        } catch (e: Exception) { Log.e("GestureScreen", "Camera binding failed", e) }
    }, ContextCompat.getMainExecutor(context))
}

private fun processImage(imageProxy: ImageProxy, gestureTracker: HandGestureTracker?) {
    try {
        val bitmap = imageProxy.toBitmap()
        val rotatedBitmap = if (imageProxy.imageInfo.rotationDegrees != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }, true)
        } else bitmap
        gestureTracker?.processFrame(rotatedBitmap, System.currentTimeMillis())
        if (rotatedBitmap !== bitmap) rotatedBitmap.recycle()
    } catch (e: Exception) { } finally { imageProxy.close() }
}

@Composable
fun HandLandmarksOverlay(
    landmarks: List<Pair<Float, Float>>, 
    gesture: HandGestureTracker.Gesture, 
    tvMode: Boolean = false,
    gestureConfirmed: Boolean = false,
    modifier: Modifier = Modifier
) {
    val gestureColor = if (tvMode && gestureConfirmed) Color.Yellow else when (gesture) {
        HandGestureTracker.Gesture.LEFT_CLICK -> Color.Green
        HandGestureTracker.Gesture.RIGHT_CLICK -> Color.Red
        HandGestureTracker.Gesture.POINTING -> Color.Cyan
        HandGestureTracker.Gesture.SCROLL_UP -> Color(0xFF2196F3)
        HandGestureTracker.Gesture.SCROLL_DOWN, HandGestureTracker.Gesture.THUMBS_DOWN -> Color(0xFFFF9800)
        HandGestureTracker.Gesture.OPEN_HAND -> Color.Magenta
        HandGestureTracker.Gesture.THUMB_PINKY, HandGestureTracker.Gesture.INDEX_PINKY -> Color(0xFF9C27B0)
        else -> Color.White
    }
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        
        val connections = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8,
            9 to 10, 10 to 11, 11 to 12, 13 to 14, 14 to 15, 15 to 16,
            0 to 17, 17 to 18, 18 to 19, 19 to 20, 5 to 9, 9 to 13, 13 to 17
        )
        
        connections.forEach { (start, end) ->
            if (start < landmarks.size && end < landmarks.size) {
                drawLine(gestureColor.copy(alpha = 0.7f),
                    Offset((1 - landmarks[start].first) * width, landmarks[start].second * height),
                    Offset((1 - landmarks[end].first) * width, landmarks[end].second * height), 4f)
            }
        }
        
        landmarks.forEachIndexed { index, (x, y) ->
            val color = when (index) {
                HandGestureTracker.INDEX_FINGER_TIP -> Color.Red
                HandGestureTracker.THUMB_TIP -> Color.Green
                else -> gestureColor
            }
            drawCircle(color, if (index == HandGestureTracker.INDEX_FINGER_TIP) 20f else 10f, Offset((1 - x) * width, y * height))
        }
        
        if (landmarks.size > HandGestureTracker.INDEX_FINGER_TIP) {
            val indexTip = landmarks[HandGestureTracker.INDEX_FINGER_TIP]
            val ringColor = if (tvMode && gestureConfirmed) Color.Yellow else Color.Red
            drawCircle(ringColor.copy(alpha = 0.4f), 35f, Offset((1 - indexTip.first) * width, indexTip.second * height), style = Stroke(4f))
        }
    }
}
