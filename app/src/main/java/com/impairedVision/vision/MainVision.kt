package com.impairedVision.vision

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.impairedVision.R
import com.impairedVision.tts.SpeechHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainVision : ComponentActivity() {

    // --- CONFIGURATION ---
    companion object {
        private const val API_KEY = "AIzaSyClmxCY7pdYfncvb-Wf3YNCnV65LRc5Ob4" // TODO: Paste your Key
        private const val MODEL_NAME = "gemini-2.5-flash"
        private const val TAG = "GeminiVision"
        private const val THROTTLE_MS = 3000L // Analyze every 3 seconds
    }

    // --- UI & HELPERS ---
    private lateinit var previewView: PreviewView
    private lateinit var speechHelper: SpeechHelper
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // --- STATE MANAGEMENT ---
    private var lastAnalysisTime = 0L
    private var isProcessing = false // Prevents overlapping requests

    // --- GEMINI SETUP ---
    // We use 'lazy' to initialize this only when needed
    private val generativeModel by lazy {
        GenerativeModel(
            modelName = MODEL_NAME,
            apiKey = API_KEY,
            // Optimized config for speed
            generationConfig = generationConfig {
                temperature = 0.4f // Lower = more direct instructions
                maxOutputTokens = 3000 // Limit length to keep TTS snappy
            },
            // The "Brain" instructions
            systemInstruction = content {
                text("""
    ROLE: Navigational Guide for a blind user.
    INPUT: Camera view from user's chest/head level.
    
    CRITICAL DISTANCE RULES:
    1. THE "FLOOR" CHECK: Look at the BOTTOM 25% of the image. 
       - If you see clear floor/ground extending from the bottom edge upwards, the path is SAFE. Say "Walk forward".
       - Ignore obstacles that are in the middle/distance (unless they are moving cars).
    
    2. THE "STOP" TRIGGER: Only say "STOP" if an object is HUGE and blocking the immediate ground at the bottom edge of the frame.
       - Example: A wall taking up 80% of the view.
       - Example: A table edge cutting off the bottom of the image.
    
    3. INSTRUCTIONS:
       - CLEAR: "Walk forward."
       - DISTANT OBSTACLE: "Veer [Direction] for [Object]." (Do not say stop).
       - IMMEDIATE DANGER: "STOP. [Object]. [Corrective Action]."
    
    4. CONSTRAINT: Under 8 words. No fluff.
                """)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yolo) // Make sure this matches your XML filename

        previewView = findViewById(R.id.previewView)
        speechHelper = SpeechHelper(this)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 10)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // 1. Viewfinder
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 2. Image Analysis (The Vision Pipeline)
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // 1. Throttle: Only run if enough time passed AND we aren't busy
        if (currentTime - lastAnalysisTime >= THROTTLE_MS && !isProcessing) {
            lastAnalysisTime = currentTime
            isProcessing = true // Lock the door

            // 2. Convert to Bitmap (Fast)
            val bitmap = imageProxyToBitmap(imageProxy)

            // Close the frame immediately so CameraX can continue showing preview
            imageProxy.close()

            // 3. Send to Cloud (Background Thread)
            if (bitmap != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    analyzeWithGemini(bitmap)
                }
            } else {
                isProcessing = false // Unlock if conversion failed
            }
        } else {
            // Drop frame
            imageProxy.close()
        }
    }

    private suspend fun analyzeWithGemini(originalBitmap: Bitmap) {
        try {
            // Optimization: Scale down to 512x512.
            // This is "Vision Standard" size - huge speed boost, negligible accuracy loss.
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 512, 512, true)

            val response = generativeModel.generateContent(
                content {
                    image(scaledBitmap)
                    // Note: We don't need text prompt here because we put it in 'systemInstruction' above!
                }
            )

            val resultText = response.text
            if (!resultText.isNullOrBlank()) {
                Log.d(TAG, "Gemini Said: $resultText")

                // Safety: If it's a "STOP" command, interrupt current speech immediately.
                val isUrgent = resultText.contains("STOP", ignoreCase = true)
                speechHelper.speak(resultText, force = isUrgent)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Gemini API Error: ${e.message}")
            // Optional: Speak an error to the user if internet drops
            // speechHelper.speak("Connection lost")
        } finally {
            isProcessing = false // Unlock the door for the next frame
        }
    }

    // --- IMAGE CONVERSION UTILITIES ---
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 75, out)
            val imageBytes = out.toByteArray()

            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // Fix Rotation (Crucial for directional instructions)
            val rotation = image.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion failed", e)
            return null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        speechHelper.shutdown()
    }
}