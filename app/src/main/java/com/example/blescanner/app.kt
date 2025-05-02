package com.example.blescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


@Composable
fun FaceEnrollmentScreen(
    onEnrollmentComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var processingFace by remember { mutableStateOf(false) }
    var enrollmentStage by remember { mutableStateOf(EnrollmentStage.INSTRUCTIONS) }
    var detectionCount by remember { mutableStateOf(0) }
    var enrollmentProgress by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    // Face detector setup
    val faceDetectorOptions = remember {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    }
    val faceDetector = remember { FaceDetection.getClient(faceDetectorOptions) }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (enrollmentStage) {
            EnrollmentStage.INSTRUCTIONS -> {
                Text(
                    "Face Enrollment Instructions",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )
                Text(
                    "This will register your face as the only one that can access this app. " +
                            "Make sure you're in good lighting and position your face in the frame.",
                    modifier = Modifier.padding(16.dp)
                )
                Button(
                    onClick = { enrollmentStage = EnrollmentStage.CAPTURING },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Start Enrollment")
                }
            }

            EnrollmentStage.CAPTURING -> {
                Box(modifier = Modifier.weight(1f)) {
                    // Camera preview
                    AndroidView(
                        factory = { ctx ->
                            androidx.camera.view.PreviewView(ctx).apply {
                                implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { previewView ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalyzer = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            Executors.newSingleThreadExecutor(),
                                            FaceAnalyzer(
                                                faceDetector = faceDetector,
                                                onFaceDetected = { face, bitmap ->
                                                    if (!processingFace && enrollmentStage == EnrollmentStage.CAPTURING) {
                                                        processingFace = true

                                                        // Launch in coroutine scope
                                                        coroutineScope.launch {
                                                            try {
                                                                // Increment detection count
                                                                detectionCount++
                                                                enrollmentProgress = detectionCount / 10f

                                                                if (detectionCount >= 10) {
                                                                    // Process and save the detected face
                                                                    saveFaceFeatures(context, face, bitmap)

                                                                    // Update UI on main thread
                                                                    withContext(Dispatchers.Main) {
                                                                        enrollmentStage =
                                                                            EnrollmentStage.COMPLETE
                                                                    }
                                                                } else {
                                                                    // Short delay before processing the next face
                                                                    delay(300)
                                                                    processingFace = false
                                                                }
                                                            } catch (e: Exception) {
                                                                Log.e("FaceEnrollment", "Face enrollment failed", e)
                                                                // Handle error
                                                                withContext(Dispatchers.Main) {
                                                                    processingFace = false
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        )
                                    }

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_FRONT_CAMERA,
                                        preview,
                                        imageAnalyzer
                                    )
                                } catch(e: Exception) {
                                    Log.e("FaceEnrollment", "Camera binding failed", e)
                                }
                            }, ContextCompat.getMainExecutor(context))
                        }
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (processingFace) {
                            CircularProgressIndicator()
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = enrollmentProgress,
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .padding(vertical = 8.dp)
                        )

                        Text(
                            "Capturing face samples: ${detectionCount}/10",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Text(
                    "Position your face in the frame",
                    modifier = Modifier.padding(16.dp)
                )
            }

            EnrollmentStage.COMPLETE -> {
                Text(
                    "Enrollment Complete!",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )
                Text(
                    "Your face has been successfully registered as the only one that can access this app.",
                    modifier = Modifier.padding(16.dp)
                )
                Button(
                    onClick = onEnrollmentComplete,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Continue")
                }
            }
        }
    }
}

enum class EnrollmentStage {
    INSTRUCTIONS,
    CAPTURING,
    COMPLETE
}

enum class AppState {
    AUTHENTICATION,  // Face authentication screen
    ENROLLMENT,      // First-time face enrollment
    MAIN            // BLE scanner functionality
}

class FaceAnalyzer(
    private val faceDetector: FaceDetector,
    private val onFaceDetected: (Face, Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Convert image to bitmap for feature extraction
            val bitmap = imageProxy.toBitmap()

            faceDetector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        // Get the face with the highest confidence score or the largest face
                        val bestFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                        bestFace?.let {
                            onFaceDetected(it, bitmap)
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            width,
            height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            100,
            out
        )

        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}


private suspend fun saveFaceFeatures(
    context: Context,
    face: Face,
    bitmap: Bitmap
) {
    withContext(Dispatchers.IO) {
        try {
            // Extract facial features
            val faceFeatures = extractFaceFeatures(face, bitmap)

            // Create a secure key for encrypting face data
            val key = getOrCreateSecretKey()

            // Encrypt the face features
            val encryptedFeatures = encryptData(faceFeatures, key)

            // Save encrypted features to secure storage
            context.getSharedPreferences("face_auth", Context.MODE_PRIVATE).edit()
                .putString("enrolled_face", encryptedFeatures.encodeToBase64())
                .apply()

            Log.d("FaceEnrollment", "Successfully saved face features")

        } catch (e: Exception) {
            Log.e("FaceEnrollment", "Failed to save face features", e)
            throw e  // Re-throw to handle in the caller
        }
    }
}


// Encryption/Decryption utilities
private fun getOrCreateSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)

    return if (keyStore.containsAlias("face_auth_key")) {
        val entry = keyStore.getEntry("face_auth_key", null) as? KeyStore.SecretKeyEntry
        entry?.secretKey ?: createSecretKey()
    } else {
        createSecretKey()
    }
}

private fun createSecretKey(): SecretKey {
    val keyGenerator = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
    )

    val keyGenParameterSpec = KeyGenParameterSpec.Builder(
        "face_auth_key",
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setUserAuthenticationRequired(false)
        .build()

    keyGenerator.init(keyGenParameterSpec)
    return keyGenerator.generateKey()
}

private fun encryptData(data: ByteArray, key: SecretKey): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)

    val iv = cipher.iv
    val encrypted = cipher.doFinal(data)

    // Combine IV and encrypted data
    return iv + encrypted
}

private fun ByteArray.encodeToBase64(): String {
    return Base64.encodeToString(this, Base64.DEFAULT)
}

private fun String.decodeBase64(): ByteArray {
    return Base64.decode(this, Base64.DEFAULT)
}





// Updated face feature extraction function to focus on facial landmarks and ignore background
private fun extractFaceFeatures(face: Face, bitmap: Bitmap): ByteArray {
    val baos = ByteArrayOutputStream()

    // Get all available landmarks
    val landmarks = face.allLandmarks

    // Write number of landmarks
    baos.write(landmarks.size)

    // Write each landmark position relative to face bounding box
    // This normalizes positions to be independent of exact face position in the frame
    val bounds = face.boundingBox
    val faceWidth = bounds.width().toFloat()
    val faceHeight = bounds.height().toFloat()
    val faceCenterX = bounds.centerX()
    val faceCenterY = bounds.centerY()

    landmarks.forEach { landmark ->
        // Write the landmark type
        baos.write(landmark.landmarkType)

        // Write NORMALIZED x and y coordinates as floats
        // This makes positions relative to face center and size
        val normalizedX = (landmark.position.x - faceCenterX) / faceWidth
        val normalizedY = (landmark.position.y - faceCenterY) / faceHeight

        val xBytes = ByteBuffer.allocate(4).putFloat(normalizedX).array()
        val yBytes = ByteBuffer.allocate(4).putFloat(normalizedY).array()
        baos.write(xBytes)
        baos.write(yBytes)
    }

    // Face dimensions ratio - this is invariant to distance from camera
    val faceAspectRatio = faceWidth / faceHeight
    val ratioBytes = ByteBuffer.allocate(4).putFloat(faceAspectRatio).array()
    baos.write(ratioBytes)

    // Add other face probabilities as floats
    val probBuffer = ByteBuffer.allocate(12)
    probBuffer.putFloat(face.smilingProbability ?: 0.5f)
    probBuffer.putFloat(face.rightEyeOpenProbability ?: 0.5f)
    probBuffer.putFloat(face.leftEyeOpenProbability ?: 0.5f)
    baos.write(probBuffer.array())

    // Add normalized head rotation if available
    if (face.headEulerAngleX != null && face.headEulerAngleY != null && face.headEulerAngleZ != null) {
        val rotationBuffer = ByteBuffer.allocate(12)
            .putFloat(face.headEulerAngleX!!)
            .putFloat(face.headEulerAngleY!!)
            .putFloat(face.headEulerAngleZ!!)
            .array()
        baos.write(rotationBuffer)
    }

    return baos.toByteArray()
}

// Improved similarity calculation function
private fun calculateSimilarity(features1: ByteArray, features2: ByteArray): Double {
    try {
        // Read landmark counts from the beginning of each feature array
        val landmarkCount1 = features1[0].toInt() and 0xFF
        val landmarkCount2 = features2[0].toInt() and 0xFF

        // Calculate the number of matching landmarks
        var matchingLandmarks = 0.0
        var comparedLandmarks = 0

        // Minimum number of landmarks to compare
        val minLandmarks = minOf(landmarkCount1, landmarkCount2)

        // Offset for the first landmark
        var offset1 = 1
        var offset2 = 1

        // Map to hold landmark comparisons by type
        val landmarkTypeMap = mutableMapOf<Int, Pair<Pair<Float, Float>, Pair<Float, Float>>>()

        // First pass - collect landmarks by type
        for (i in 0 until landmarkCount1) {
            val type1 = features1[offset1].toInt() and 0xFF
            val x1 = ByteBuffer.wrap(features1, offset1 + 1, 4).float
            val y1 = ByteBuffer.wrap(features1, offset1 + 5, 4).float
            landmarkTypeMap[type1] = Pair(Pair(x1, y1), Pair(0f, 0f))
            offset1 += 9
        }

        // Second pass - find matching landmark types
        offset2 = 1
        for (i in 0 until landmarkCount2) {
            val type2 = features2[offset2].toInt() and 0xFF

            if (landmarkTypeMap.containsKey(type2)) {
                val x2 = ByteBuffer.wrap(features2, offset2 + 1, 4).float
                val y2 = ByteBuffer.wrap(features2, offset2 + 5, 4).float
                val pair = landmarkTypeMap[type2]!!
                landmarkTypeMap[type2] = Pair(pair.first, Pair(x2, y2))
            }

            offset2 += 9
        }

        // Calculate similarity based on relative distances between landmarks
        // This approach is more tolerant to slight head rotation and position changes
        for ((_, positions) in landmarkTypeMap) {
            val (pos1, pos2) = positions

            // Skip if we don't have a match for this landmark type
            if (pos2.first == 0f && pos2.second == 0f) continue

            // Calculate Euclidean distance between normalized points
            val distance = Math.sqrt(
                Math.pow((pos1.first - pos2.first).toDouble(), 2.0) +
                        Math.pow((pos1.second - pos2.second).toDouble(), 2.0)
            )

            // Normalized positions should be very close if it's the same person
            // The threshold is much lower (0.2) since we're using normalized coordinates
            if (distance < 0.2) {
                matchingLandmarks += 1.0 - (distance / 0.2) // Weight by proximity
            }

            comparedLandmarks++
        }

        // Compare face aspect ratios (should be similar for same person)
        // Get offset where aspect ratio is stored (after all landmarks)
        val ratioOffset1 = 1 + (landmarkCount1 * 9)
        val ratioOffset2 = 1 + (landmarkCount2 * 9)

        val ratio1 = ByteBuffer.wrap(features1, ratioOffset1, 4).float
        val ratio2 = ByteBuffer.wrap(features2, ratioOffset2, 4).float

        val ratioDiff = Math.abs(ratio1 - ratio2)
        val ratioScore = if (ratioDiff < 0.2) (1.0 - (ratioDiff / 0.2)) else 0.0

        // Calculate final similarity score with weighted components
        val landmarkScore = if (comparedLandmarks > 0) matchingLandmarks / comparedLandmarks else 0.0

        // Final score combines landmark matching (80%) and face ratio (20%)
        return (landmarkScore * 0.8) + (ratioScore * 0.2)
    } catch (e: Exception) {
        Log.e("FaceAuth", "Error calculating similarity", e)
        return 0.0
    }
}

// Updated verification function with adaptive threshold
internal suspend fun verifyFace(
    context: Context,
    face: Face,
    bitmap: Bitmap
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            // Extract features from current face
            val currentFaceFeatures = extractFaceFeatures(face, bitmap)

            // Get stored encrypted face features
            val encryptedFeatures = context.getSharedPreferences("face_auth", Context.MODE_PRIVATE)
                .getString("enrolled_face", null)
                ?.decodeBase64() ?: return@withContext false

            // Get secret key
            val key = getOrCreateSecretKey()

            // Decrypt stored features
            val storedFeatures = decryptData(encryptedFeatures, key)

            // Compare features with adaptive threshold
            val similarity = calculateSimilarity(currentFaceFeatures, storedFeatures)
            Log.d("FaceAuth", "Face similarity: $similarity")

            // Use a lower threshold (0.35) for better matching in varied environments
            similarity >= 0.35
        } catch (e: Exception) {
            Log.e("FaceAuth", "Face verification failed", e)
            false
        }
    }
}

private fun decryptData(encryptedData: ByteArray, key: SecretKey): ByteArray {
    try {
        // IV is stored at the beginning of the encrypted data
        val ivSize = 12 // GCM typically uses 12 bytes
        val iv = encryptedData.sliceArray(0 until ivSize)
        val encrypted = encryptedData.sliceArray(ivSize until encryptedData.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(encrypted)
    } catch (e: Exception) {
        Log.e("FaceAuth", "Decryption failed", e)
        throw e
    }
}

