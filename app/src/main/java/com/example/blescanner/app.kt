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

private fun extractFaceFeatures(face: Face, bitmap: Bitmap): ByteArray {
    // Extract relevant facial features
    val baos = ByteArrayOutputStream()

    // Get all available landmarks
    val landmarks = face.allLandmarks

    // Write number of landmarks
    baos.write(landmarks.size)

    // Write each landmark position
    landmarks.forEach { landmark ->
        // Write the landmark type
        baos.write(landmark.landmarkType)

        // Write x and y coordinates as floats
        val xBytes = ByteBuffer.allocate(4).putFloat(landmark.position.x).array()
        val yBytes = ByteBuffer.allocate(4).putFloat(landmark.position.y).array()
        baos.write(xBytes)
        baos.write(yBytes)
    }

    // Add face bounds as floats
    val bounds = face.boundingBox
    val boundsData = ByteBuffer.allocate(16)
        .putInt(bounds.left)
        .putInt(bounds.top)
        .putInt(bounds.right)
        .putInt(bounds.bottom)
        .array()
    baos.write(boundsData)

    // Add other face probabilities as floats
    val probBuffer = ByteBuffer.allocate(12)

    // Default to 0.5f if not available
    probBuffer.putFloat(face.smilingProbability ?: 0.5f)
    probBuffer.putFloat(face.rightEyeOpenProbability ?: 0.5f)
    probBuffer.putFloat(face.leftEyeOpenProbability ?: 0.5f)

    baos.write(probBuffer.array())

    // Add head rotation if available
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


@Composable
fun FaceAuthenticationScreen(
    onAuthenticationSuccess: () -> Unit,
    onSetupFace: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAuthenticating by remember { mutableStateOf(false) }
    var authStatus by remember { mutableStateOf("") }
    var authAttempts by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    // Check if face is already enrolled
    val isFaceEnrolled = remember {
        context.getSharedPreferences("face_auth", Context.MODE_PRIVATE)
            .contains("enrolled_face")
    }

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
        if (!isFaceEnrolled) {
            Text(
                "No face registered",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )
            Text(
                "You need to enroll a face to use face authentication",
                modifier = Modifier.padding(16.dp)
            )
            Button(
                onClick = onSetupFace,
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Enroll Face")
            }
        } else {
            Text(
                "Face Authentication",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(16.dp)
            )

            if (authStatus.isNotEmpty()) {
                Text(
                    authStatus,
                    color = if (authStatus.contains("Success"))
                        MaterialTheme.colorScheme.primary else
                        MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }

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
                                                if (!isAuthenticating) {
                                                    isAuthenticating = true
                                                    authStatus = "Processing..."

                                                    // Launch in coroutine scope
                                                    coroutineScope.launch {
                                                        try {
                                                            // Add a small delay to avoid too rapid authentication attempts
                                                            delay(300)

                                                            // Check if this face matches the enrolled one
                                                            val success = verifyFace(context, face, bitmap)
                                                            Log.d("FaceAuth", "Verification result: $success")

                                                            withContext(Dispatchers.Main) {
                                                                if (success) {
                                                                    authStatus =
                                                                        "Authentication Successful!"
                                                                    delay(1000) // Give user time to see success message
                                                                    onAuthenticationSuccess()
                                                                } else {
                                                                    authAttempts++
                                                                    authStatus =
                                                                        "Authentication Failed (Attempt $authAttempts)"
                                                                    delay(1000) // Wait before trying again
                                                                    isAuthenticating = false
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("FaceAuth", "Authentication failed", e)
                                                            withContext(Dispatchers.Main) {
                                                                authStatus =
                                                                    "Authentication Error: ${e.localizedMessage}"
                                                                delay(1000) // Wait before trying again
                                                                isAuthenticating = false
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
                                Log.e("FaceAuth", "Camera binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }
                )

                if (isAuthenticating) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Text(
                "Look at the camera to authenticate",
                modifier = Modifier.padding(16.dp)
            )

            Button(
                onClick = onSetupFace,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("Re-enroll Face")
            }
        }
    }
}


private suspend fun verifyFace(
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

            // Compare features
            val similarity = calculateSimilarity(currentFaceFeatures, storedFeatures)
            Log.d("FaceAuth", "Face similarity: $similarity")

            similarity >= 0.40 // Lower threshold for better matching
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

        // Compare landmarks
        for (i in 0 until minLandmarks) {
            // Extract landmark type
            val type1 = features1[offset1].toInt() and 0xFF
            val type2 = features2[offset2].toInt() and 0xFF

            // Skip this comparison if types don't match
            if (type1 == type2) {
                // Extract x, y coordinates
                val x1 = ByteBuffer.wrap(features1, offset1 + 1, 4).float
                val y1 = ByteBuffer.wrap(features1, offset1 + 5, 4).float
                val x2 = ByteBuffer.wrap(features2, offset2 + 1, 4).float
                val y2 = ByteBuffer.wrap(features2, offset2 + 5, 4).float

                // Calculate distance between points
                val distance = Math.sqrt(Math.pow((x1 - x2).toDouble(), 2.0) +
                        Math.pow((y1 - y2).toDouble(), 2.0))

                // If distance is under threshold, count as a match
                if (distance < 50.0) {
                    matchingLandmarks += 1.0 - (distance / 50.0) // Weight by proximity
                }

                comparedLandmarks++
            }

            // Move to next landmark
            offset1 += 9  // type(1) + x(4) + y(4)
            offset2 += 9
        }

        // Calculate final similarity score
        return if (comparedLandmarks > 0) {
            matchingLandmarks / comparedLandmarks
        } else {
            0.0 // No landmarks compared
        }
    } catch (e: Exception) {
        Log.e("FaceAuth", "Error calculating similarity", e)
        return 0.0
    }
}