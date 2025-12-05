package com.gmouse.ag

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Hand gesture tracking using MediaPipe Hands.
 * V6: Added TV mode gestures.
 */
class HandGestureTracker(
    private val context: Context,
    private val onGestureDetected: (GestureResult) -> Unit
) {
    companion object {
        private const val TAG = "HandGestureTracker"
        private const val MODEL_ASSET = "hand_landmarker.task"
        
        const val WRIST = 0
        const val THUMB_CMC = 1
        const val THUMB_MCP = 2
        const val THUMB_IP = 3
        const val THUMB_TIP = 4
        const val INDEX_FINGER_MCP = 5
        const val INDEX_FINGER_TIP = 8
        const val MIDDLE_FINGER_MCP = 9
        const val MIDDLE_FINGER_TIP = 12
        const val RING_FINGER_MCP = 13
        const val RING_FINGER_TIP = 16
        const val PINKY_MCP = 17
        const val PINKY_TIP = 20
    }

    data class GestureResult(
        val indexFingerX: Float,
        val indexFingerY: Float,
        val gesture: Gesture,
        val landmarks: List<Pair<Float, Float>>? = null,
        val confidence: Float = 0f,
        val shouldMoveCursor: Boolean = false
    )

    enum class Gesture {
        NONE,
        // Laptop mode gestures
        POINTING,       // Index only = cursor move
        LEFT_CLICK,     // V-sign = left click
        RIGHT_CLICK,    // Point + pinky = right click
        SCROLL_UP,      // Thumbs up = scroll up
        SCROLL_DOWN,    // Fist = scroll down
        OPEN_HAND,      // All fingers = pause
        // TV mode gestures
        THUMBS_DOWN,    // Thumbs down = volume down (TV)
        THUMB_PINKY,    // Thumb + pinky = scroll up (TV)
        INDEX_PINKY     // Index + pinky = scroll down (TV)
    }

    private var handLandmarker: HandLandmarker? = null
    private var lastGesture: Gesture = Gesture.NONE
    private var clickCooldown = 0L
    private var scrollCooldown = 0L
    
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f
    private val smoothingFactor = 0.2f
    
    private val resultListener = { result: HandLandmarkerResult, _: com.google.mediapipe.framework.image.MPImage ->
        processResult(result)
    }
    
    fun initialize(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.GPU)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(resultListener)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            true
        } catch (e: Exception) {
            initializeFallback()
        }
    }
    
    private fun initializeFallback(): Boolean {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(resultListener)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            true
        } catch (e: Exception) { false }
    }

    private var lastTimestamp = 0L
    
    fun processFrame(bitmap: Bitmap, timestamp: Long) {
        val landmarker = handLandmarker ?: return
        val frameTimestamp = if (timestamp <= lastTimestamp) lastTimestamp + 1 else timestamp
        lastTimestamp = frameTimestamp
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(mpImage, frameTimestamp)
        } catch (e: Exception) { }
    }

    private fun processResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) {
            onGestureDetected(GestureResult(smoothedX, smoothedY, Gesture.NONE, shouldMoveCursor = false))
            return
        }

        val landmarks = result.landmarks()[0]
        val indexTip = landmarks[INDEX_FINGER_TIP]
        
        smoothedX = smoothedX * smoothingFactor + indexTip.x() * (1 - smoothingFactor)
        smoothedY = smoothedY * smoothingFactor + indexTip.y() * (1 - smoothingFactor)
        
        val landmarkPairs = landmarks.map { Pair(it.x(), it.y()) }
        val gesture = detectGesture(landmarks)
        
        val currentTime = System.currentTimeMillis()
        val finalGesture = when (gesture) {
            Gesture.LEFT_CLICK, Gesture.RIGHT_CLICK -> {
                if (currentTime - clickCooldown > 350) {
                    clickCooldown = currentTime
                    gesture
                } else Gesture.POINTING
            }
            Gesture.SCROLL_UP, Gesture.SCROLL_DOWN -> {
                if (currentTime - scrollCooldown > 120) {
                    scrollCooldown = currentTime
                    gesture
                } else lastGesture
            }
            else -> gesture
        }
        
        val shouldMoveCursor = finalGesture == Gesture.POINTING
        val confidence = result.handedness().firstOrNull()?.firstOrNull()?.score() ?: 0f
        onGestureDetected(GestureResult(smoothedX, smoothedY, finalGesture, landmarkPairs, confidence, shouldMoveCursor))
        lastGesture = finalGesture
    }

    private fun detectGesture(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): Gesture {
        val thumbTip = landmarks[THUMB_TIP]
        val thumbMcp = landmarks[THUMB_MCP]
        val indexTip = landmarks[INDEX_FINGER_TIP]
        val middleTip = landmarks[MIDDLE_FINGER_TIP]
        val ringTip = landmarks[RING_FINGER_TIP]
        val pinkyTip = landmarks[PINKY_TIP]
        
        val indexMcp = landmarks[INDEX_FINGER_MCP]
        val middleMcp = landmarks[MIDDLE_FINGER_MCP]
        val ringMcp = landmarks[RING_FINGER_MCP]
        val pinkyMcp = landmarks[PINKY_MCP]
        val wrist = landmarks[WRIST]
        
        // Finger extended checks
        val indexExtended = indexTip.y() < indexMcp.y() - 0.03f
        val middleExtended = middleTip.y() < middleMcp.y() - 0.03f
        val ringExtended = ringTip.y() < ringMcp.y() - 0.03f
        val pinkyExtended = pinkyTip.y() < pinkyMcp.y() - 0.03f
        
        // Thumb checks
        val thumbUp = thumbTip.y() < thumbMcp.y() - 0.05f  // Thumb pointing up
        val thumbDown = thumbTip.y() > thumbMcp.y() + 0.05f  // Thumb pointing down
        val thumbExtended = kotlin.math.abs(thumbTip.x() - thumbMcp.x()) > 0.05f || thumbUp
        
        // === GESTURE DETECTION ===
        
        // 1. Open hand: all 5 fingers extended = PAUSE/HOME
        if (indexExtended && middleExtended && ringExtended && pinkyExtended) {
            return Gesture.OPEN_HAND
        }
        
        // 2. Thumb + Pinky only (for TV scroll up)
        if (thumbExtended && !indexExtended && !middleExtended && !ringExtended && pinkyExtended) {
            return Gesture.THUMB_PINKY
        }
        
        // 3. Index + Pinky only (for TV scroll down)
        if (indexExtended && !middleExtended && !ringExtended && pinkyExtended && !thumbExtended) {
            return Gesture.INDEX_PINKY
        }
        
        // 4. Thumbs up: only thumb up, others closed = SCROLL UP / VOLUME UP
        if (thumbUp && !indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            return Gesture.SCROLL_UP
        }
        
        // 5. Thumbs down: only thumb down, others closed = VOLUME DOWN
        if (thumbDown && !indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            return Gesture.THUMBS_DOWN
        }
        
        // 6. Fist: all fingers closed = SCROLL DOWN / BACK
        if (!thumbExtended && !indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            return Gesture.SCROLL_DOWN
        }
        
        // 7. Right click: Point (index) + Pinky extended
        if (indexExtended && !middleExtended && !ringExtended && pinkyExtended) {
            return Gesture.RIGHT_CLICK
        }
        
        // 8. Left click: V-sign (index + middle, others closed)
        if (indexExtended && middleExtended && !ringExtended && !pinkyExtended) {
            return Gesture.LEFT_CLICK
        }
        
        // 9. Pointing: only index extended = MOVE CURSOR / TILE RIGHT
        if (indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            return Gesture.POINTING
        }
        
        return Gesture.NONE
    }

    fun resetSmoothing() {
        smoothedX = 0.5f
        smoothedY = 0.5f
    }

    fun close() {
        handLandmarker?.close()
        handLandmarker = null
    }
}
