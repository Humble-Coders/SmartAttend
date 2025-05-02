package com.example.blescanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.*
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
    // Create an instance of the BLEScannerLogic class
    private val bleLogic = BLEScannerLogic()
    private var scanJob: Job? = null
    private var attendanceJob: Job? = null
    // Add after the existing field declarations
    private val PREF_NAME = "UserInfoPreferences"
    private val KEY_STUDENT_NAME = "studentName"
    private val KEY_ROLL_NUMBER = "rollNumber"
    private val KEY_FIRST_LAUNCH = "firstLaunch"

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize BLE components
        bleLogic.initialize(this)

        setContent {
            // Wrap your BLE app with authentication
            AuthenticatedBLEApp(bleLogic)
        }
    }
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onDestroy() {
        super.onDestroy()
        bleLogic.cleanup()
        scanJob?.cancel()
        attendanceJob?.cancel()
    }
}


@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AuthenticatedBLEApp(bleLogic: BLEScannerLogic) {
    // Track app state
    var appState by remember { mutableStateOf(AppState.MAIN) } // Changed from AUTHENTICATION to MAIN
    var showEnrollment by remember { mutableStateOf(false) }

    // Shared Preferences for storing user info
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("user_info", Context.MODE_PRIVATE) }

    // Get the stored user info or use defaults
    val savedName = remember { sharedPrefs.getString("studentName", "Alex Johnson") ?: "Alex Johnson" }
    val savedRoll = remember { sharedPrefs.getString("rollNumber", "20230045") ?: "20230045" }
    var studentName by remember { mutableStateOf(savedName) }
    var rollNumber by remember { mutableStateOf(savedRoll) }

    // Check if first launch (for showing initial dialog)
    var isFirstLaunch by remember { mutableStateOf(sharedPrefs.getBoolean("isFirstLaunch", true)) }

    // Function to save user info
    val saveUserInfo: (String, String) -> Unit = { name, roll ->
        studentName = name
        rollNumber = roll
        sharedPrefs.edit()
            .putString("studentName", name)
            .putString("rollNumber", roll)
            .putBoolean("isFirstLaunch", false)
            .apply()
        isFirstLaunch = false
    }

    LaunchedEffect(Unit) {
        // Check if face is enrolled - if not, show enrollment screen when needed
        val isFaceEnrolled = context.getSharedPreferences("face_auth", Context.MODE_PRIVATE)
            .contains("enrolled_face")

        if (!isFaceEnrolled) {
            showEnrollment = true
        }
    }

    if (showEnrollment) {
        FaceEnrollmentScreen(
            onEnrollmentComplete = {
                showEnrollment = false
            }
        )
    } else {
        // Go directly to main screen without authentication
        BLEScannerApp(
            bleLogic = bleLogic,
            initialStudentName = studentName,
            initialRollNumber = rollNumber,
            showInitialDialog = isFirstLaunch,
            onSaveUserInfo = saveUserInfo
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun BLEScannerApp(bleLogic: BLEScannerLogic,
                  initialStudentName: String,
                  initialRollNumber: String,
                  showInitialDialog: Boolean,
                  onSaveUserInfo: (String, String) -> Unit) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(false) }
    var studentName by remember { mutableStateOf(initialStudentName) }
    var rollNumber by remember { mutableStateOf(initialRollNumber) }
    var showUserInfoDialog by remember { mutableStateOf(showInitialDialog) }
    var showAttendanceDialog by remember { mutableStateOf(false) }
    var showAuthenticationDialog by remember { mutableStateOf(false) } // New state for authentication
    var detectedSubject by remember { mutableStateOf("") }
    var scanResults by remember { mutableStateOf<List<BLEScannerLogic.ScanResultWithText>>(emptyList()) }
    var isAttendanceMarked by remember { mutableStateOf(false) }
    var isMarkingAttendance by remember { mutableStateOf(false) }
    var showConfetti by remember { mutableStateOf(false) }

    // Coroutine scope for this composable
    val coroutineScope = rememberCoroutineScope()

    // Refined subtle animation with lower intensity for better performance
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f, // Reduced animation range
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutQuad), // Slower animation
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnimation"
    )

    // Success animation after attendance is marked
    val successScale by animateFloatAsState(
        targetValue = if (isAttendanceMarked) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "successAnimation"
    )

    // Trigger confetti when attendance is marked
    LaunchedEffect(isAttendanceMarked) {
        if (isAttendanceMarked) {
            showConfetti = true
            delay(3000) // Show confetti for 3 seconds
            showConfetti = false
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            hasPermissions = true
        } else {
            Toast.makeText(context, "All permissions are required for this app", Toast.LENGTH_LONG).show()
            hasPermissions = false
        }
    }

// Check and request permissions
    LaunchedEffect(Unit) {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA  // For face authentication
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CAMERA  // For face authentication
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            hasPermissions = true
        }
    }

    // Start continuous scanning
    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            bleLogic.clearDetectedDevices()
            scanResults = emptyList()

            Log.d("BLE", "Starting continuous BLE scanning")
            bleLogic.startScanning { result ->
                val processedResult = bleLogic.processScanResult(result)

                if (processedResult.isEspDevice) {
                    // Update scan results with new device or update existing one
                    scanResults = scanResults.toMutableList().apply {
                        val existingIndex = indexOfFirst { it.result.device.address == result.device.address }
                        if (existingIndex >= 0) {
                            this[existingIndex] = processedResult
                        } else {
                            add(processedResult)
                        }
                    }

                    if (processedResult.hasTextData &&
                        processedResult.message.isNotBlank() &&
                        !isAttendanceMarked &&
                        !isMarkingAttendance &&
                        !showAttendanceDialog &&
                        !showAuthenticationDialog) {
                        // Store the detected subject
                        detectedSubject = processedResult.message
                        // Show authentication before marking attendance
                        showAuthenticationDialog = true
                    }
                }
            }
        }
    }

    // More professional gradient background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A2151),  // Dark navy blue
                        Color(0xFF263464)   // Slightly lighter navy blue
                    )
                )
            )) {
        // Confetti animation overlay
        SuccessConfetti(isActive = showConfetti)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // More professional header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = "Attendance",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp))

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Attendance Management",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        style = TextStyle(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.2f),
                                offset = Offset(1f, 1f),
                                blurRadius = 2f
                            ),
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }

            // Status card with optimized animation
            StatusCard(
                isAttendanceMarked = isAttendanceMarked,
                isMarkingAttendance = isMarkingAttendance,
                pulse = pulse
            )

            // Student info card with elegant design
            StudentInfoCard(
                studentName = studentName,
                rollNumber = rollNumber,
                isAttendanceMarked = isAttendanceMarked,
                onStudentNameChange = { studentName = it },
                onRollNumberChange = { rollNumber = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Reset button always present (no AnimatedVisibility)
            Button(
                onClick = {
                    isAttendanceMarked = false
                    showConfetti = false
                    bleLogic.clearDetectedDevices()
                    scanResults = emptyList()
                },
                modifier = Modifier
                    .width(240.dp)
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAttendanceMarked) Color(0xFF0D47A1) else Color(0xFF424242),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset",
                    modifier = Modifier.padding(end = 8.dp))
                Text(
                    text = if (isAttendanceMarked) "Reset for Next Session" else "Reset Scanner",
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isAttendanceMarked) {
                // Scanning status with refined presentation
                ScanningStatusBar(scanResults.size, bleLogic.targetEspName)
            }

            // Device list with optimized animations
            DeviceList(
                scanResults = scanResults.filter { it.isEspDevice },
                isAttendanceMarked = isAttendanceMarked
            )
        }
        // User Info dialog (displayed on first launch or if name is empty)
        if (showUserInfoDialog) {
            UserInfoDialog(
                initialName = studentName,
                initialRollNumber = rollNumber,
                onSave = { name, roll ->
                    studentName = name
                    rollNumber = roll
                    showUserInfoDialog = false
                    onSaveUserInfo(name, roll)
                },
                onDismiss = {
                    // Don't allow dismissal if actually first launch or no name provided
                    if (studentName.isNotBlank()) {
                        showUserInfoDialog = false
                    } else {
                        Toast.makeText(context, "Please enter your information", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        if (showAuthenticationDialog) {
            AuthenticationDialog(
                onAuthenticationSuccess = {
                    showAuthenticationDialog = false
                    showAttendanceDialog = true // Show attendance dialog after successful authentication
                },
                onAuthenticationFailure = {
                    showAuthenticationDialog = false
                    Toast.makeText(context, "Authentication failed. Please try again.", Toast.LENGTH_SHORT).show()
                },
                onDismiss = {
                    showAuthenticationDialog = false
                }
            )
        }
        // Auto attendance dialog - no confirmation needed
        if (showAttendanceDialog) {
            AutoAttendanceDialog(
                subject = detectedSubject,
                studentName = studentName,
                rollNumber = rollNumber,
                onDismiss = {
                    showAttendanceDialog = false
                },
                onComplete = {
                    showAttendanceDialog = false

                    // Mark attendance automatically in the background
                    coroutineScope.launch {
                        isMarkingAttendance = true
                        try {
                            // Check signal strength from the scan result to determine if student is present or defaulter
                            val signalStrength = scanResults.find { it.message == detectedSubject }?.result?.rssi ?: 0

                            if (signalStrength < -100) {
                                // Signal is too weak, mark as defaulter
                                val isDefaulterMarked = bleLogic.markDefaulterAsync(detectedSubject, studentName, rollNumber)
                                if (isDefaulterMarked) {
                                    isAttendanceMarked = true
                                    Toast.makeText(context, "Marked as defaulter due to weak signal", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to mark defaulter", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // Normal attendance marking
                                val success = bleLogic.markAttendanceAsync(detectedSubject, studentName, rollNumber)
                                if (success) {
                                    isAttendanceMarked = true
                                    Toast.makeText(context, "Attendance recorded for $detectedSubject", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to mark attendance", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isMarkingAttendance = false
                        }
                    }
                }
            )
        }
    }
}


@Composable
fun AuthenticationDialog(
    onAuthenticationSuccess: () -> Unit,
    onAuthenticationFailure: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAuthenticating by remember { mutableStateOf(false) }
    var authStatus by remember { mutableStateOf("Looking for face...") }
    var authAttempts by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val maxAttempts = 3

    // Face detector setup
    val faceDetectorOptions = remember {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    }
    val faceDetector = remember { FaceDetection.getClient(faceDetectorOptions) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .height(480.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Face Authentication",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(16.dp)
                )

                Text(
                    text = "Verify your identity to mark attendance",
                    fontSize = 14.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp)
                        .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
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
                                                        authStatus = "Verifying..."

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
                                                                        authStatus = "Authentication Successful!"
                                                                        delay(1000) // Give user time to see success message
                                                                        onAuthenticationSuccess()
                                                                    } else {
                                                                        authAttempts++
                                                                        if (authAttempts >= maxAttempts) {
                                                                            authStatus = "Too many failed attempts"
                                                                            delay(1000)
                                                                            onAuthenticationFailure()
                                                                        } else {
                                                                            authStatus = "Authentication Failed (Attempt $authAttempts/$maxAttempts)"
                                                                            delay(1000) // Wait before trying again
                                                                            isAuthenticating = false
                                                                        }
                                                                    }
                                                                }
                                                            } catch (e: Exception) {
                                                                Log.e("FaceAuth", "Authentication failed", e)
                                                                withContext(Dispatchers.Main) {
                                                                    authStatus = "Authentication Error"
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

                    // Show authentication status and progress
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = authStatus,
                            color = Color.White,
                            fontSize = 14.sp
                        )

                        if (isAuthenticating) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .padding(top = 8.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A2151)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun StudentInfoCard(
    studentName: String,
    rollNumber: String,
    isAttendanceMarked: Boolean,
    onStudentNameChange: (String) -> Unit,
    onRollNumberChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFECEFF1).copy(alpha = 0.9f),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Student Information",
                color = Color(0xFF1A2151),
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Name field with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Name",
                    tint = Color(0xFF1A2151),
                    modifier = Modifier.padding(end = 8.dp))

                TextField(
                    value = studentName,
                    onValueChange = onStudentNameChange,
                    label = { Text("Student Name", color = Color(0xFF1A2151).copy(alpha = 0.7f)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAttendanceMarked,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color(0xFF1A2151),
                        unfocusedIndicatorColor = Color(0xFF1A2151).copy(alpha = 0.5f),
                        cursorColor = Color(0xFF1A2151),
                        focusedTextColor = Color(0xFF1A2151),
                        unfocusedTextColor = Color(0xFF1A2151).copy(alpha = 0.9f)
                    )
                )
            }

            // Roll number field with icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Numbers,
                    contentDescription = "Roll Number",
                    tint = Color(0xFF1A2151),
                    modifier = Modifier.padding(end = 8.dp))

                TextField(
                    value = rollNumber,
                    onValueChange = onRollNumberChange,
                    label = { Text("Roll Number", color = Color(0xFF1A2151).copy(alpha = 0.7f)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAttendanceMarked,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color(0xFF1A2151),
                        unfocusedIndicatorColor = Color(0xFF1A2151).copy(alpha = 0.5f),
                        cursorColor = Color(0xFF1A2151),
                        focusedTextColor = Color(0xFF1A2151),
                        unfocusedTextColor = Color(0xFF1A2151).copy(alpha = 0.9f)
                    )
                )
            }
        }
    }
}

@Composable
fun ScanningStatusBar(deviceCount: Int, targetName: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0D47A1).copy(alpha = 0.15f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BluetoothSearching,
                contentDescription = "Scanning",
                tint = Color.White,
                modifier = Modifier.size(24.dp))

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "Devices Detected: $deviceCount",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = "Scanning for '$targetName' devices",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun DeviceList(
    scanResults: List<BLEScannerLogic.ScanResultWithText>,
    isAttendanceMarked: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)

    ) {
        items(scanResults) { resultWithText ->
            DeviceCard(
                result = resultWithText,
                isAttendanceMarked = isAttendanceMarked
            )
        }

        if (scanResults.isEmpty()) {
            item {
                EmptyDeviceList()
            }
        }
    }
}




// Copy this from your FaceHope app to ensure compatibility


@Composable
fun EmptyDeviceList() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.BluetoothDisabled,
            contentDescription = "No devices",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(40.dp))

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No ESP Devices Detected",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.8f)
        )
        Text(
            text = "Please ensure your ESP32 device is powered on and broadcasting",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
            textAlign = TextAlign.Center
        )
    }
}



@Composable
fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color(0xFF1A2151).copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp))

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "$label ",
            color = Color.DarkGray,
            fontSize = 13.sp
        )
        Text(
            text = value,
            color = Color.Black,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}


@Composable
fun AutoAttendanceDialog(
    subject: String,
    studentName: String,
    rollNumber: String,
    onDismiss: () -> Unit,
    onComplete: () -> Unit
) {
    // State to track animation progress
    var animationState by remember { mutableStateOf(0) }

    // Animation timing
    val totalDuration = 3000 // 3 seconds total

    // Animation values
    val checkScale by animateFloatAsState(
        targetValue = if (animationState >= 1) 1f else 0f,
        animationSpec = tween(durationMillis = 500, easing = EaseOutBack),
        label = "checkScale"
    )

    val circleScale by animateFloatAsState(
        targetValue = if (animationState >= 1) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = EaseOutBack),
        label = "circleScale"
    )

    val successAlpha by animateFloatAsState(
        targetValue = if (animationState >= 2) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "successAlpha"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = if (animationState == 0) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "contentAlpha"
    )

    // Auto start the animation sequence
    LaunchedEffect(Unit) {
        // First show the dialog content
        delay(2000)

        // Then start the confirmation animation
        animationState = 1
        delay(1500)

        // Then show the success message
        animationState = 2
        delay(1000)

        // Then dismiss and call onComplete
        onComplete()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = Color.White
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Initial content - fades out during animation
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .alpha(contentAlpha),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Class,
                        contentDescription = "Class",
                        tint = Color(0xFF1A2151),
                        modifier = Modifier.size(40.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Class Session Detected",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A2151)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Class info card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF5F5F5)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            InfoRow(icon = Icons.Default.Book, label = "Course:", value = subject)
                            InfoRow(icon = Icons.Default.Person, label = "Student:", value = studentName)
                            InfoRow(icon = Icons.Default.Numbers, label = "ID:", value = rollNumber)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Marking attendance automatically...",
                        fontSize = 15.sp,
                        color = Color.DarkGray,
                        textAlign = TextAlign.Center
                    )
                }

                // Success animation that appears after content fades
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Background circle
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(circleScale)
                            .background(
                                color = Color(0xFF43A047).copy(alpha = 0.2f),
                                shape = CircleShape
                            )
                    )

                    // Checkmark
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = Color(0xFF43A047),
                        modifier = Modifier
                            .size(80.dp)
                            .scale(checkScale)
                    )

                    // Success text
                    Text(
                        text = "Attendance Marked!",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF43A047),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .alpha(successAlpha)
                    )
                }
            }
        }
    }
}


@Composable
fun StatusCard(isAttendanceMarked: Boolean, isMarkingAttendance: Boolean, pulse: Float) {
    // Animations for success state
    val successScale = remember { Animatable(0f) }
    val checkmarkScale = remember { Animatable(0f) }
    val cardColor = remember { Animatable(Color(0xFF0D47A1).copy(alpha = 0.9f).toArgb().toFloat()) }

    // Launch success animation when attendance is marked
    LaunchedEffect(isAttendanceMarked) {
        if (isAttendanceMarked) {
            // Animate card color change
            cardColor.animateTo(
                Color(0xFF2E7D32).copy(alpha = 0.9f).toArgb().toFloat(),
                animationSpec = tween(500, easing = EaseOutCubic)
            )

            // Animate checkmark appearing with bounce effect
            successScale.animateTo(
                1.2f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            successScale.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )

            // Animate checkmark with slight delay
            delay(200)
            checkmarkScale.animateTo(
                1.2f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            checkmarkScale.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(500)) + expandVertically(tween(500, easing = EaseOutQuart)),
        exit = fadeOut() + shrinkVertically()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .scale(
                    when {
                        isAttendanceMarked -> successScale.value
                        isMarkingAttendance -> pulse
                        else -> 1f
                    }
                ),
            colors = CardDefaults.cardColors(
                containerColor = Color(cardColor.value.toInt())
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Show appropriate icon based on state
                    when {
                        isAttendanceMarked -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Attendance Marked",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(36.dp)
                                    .scale(checkmarkScale.value)
                            )
                        }
                        isMarkingAttendance -> {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Marking Attendance",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(32.dp)
                                    .rotate(pulse * 360) // Rotate icon during marking
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = "Awaiting Signal",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when {
                        isAttendanceMarked -> "Attendance Recorded"
                        isMarkingAttendance -> "Recording Attendance..."
                        else -> "Awaiting Class Signal"
                    },
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                if (isAttendanceMarked) {
                    // Show success message with animation
                    AnimatedVisibility(
                        visible = true,
                        enter = expandVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(tween(500))
                    ) {
                        Text(
                            text = "Your attendance has been successfully recorded",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.padding(top = 4.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else if (!isMarkingAttendance) {
                    Text(
                        text = "Please ensure Bluetooth is activated and you are within proximity of the classroom",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 4.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}



@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun DeviceCard(
    result: BLEScannerLogic.ScanResultWithText,
    isAttendanceMarked: Boolean
) {
    val scanResult = result.result
    val hasMessage = result.hasTextData
    val deviceName = scanResult.device.name ?: "ESP Device"
    val deviceAddress = scanResult.device.address
    val rssi = scanResult.rssi
    val message = result.message

    // Use key for better animation handling
    key(deviceAddress) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(400)) + expandVertically(tween(400, easing = EaseOutQuint)),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White.copy(alpha = 0.95f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = deviceName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1A2151)
                        )

                        // Signal strength indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.NetworkCell,
                                contentDescription = "Signal",
                                tint = when {
                                    rssi > -50 -> Color(0xFF2E7D32)
                                    rssi > -70 -> Color(0xFFFFA000)
                                    else -> Color(0xFFC62828)
                                },
                                modifier = Modifier.size(18.dp))
                            Text(
                                text = "$rssi dBm",
                                fontSize = 13.sp,
                                color = Color.DarkGray
                            )
                        }
                    }

                    Text(
                        text = deviceAddress,
                        fontSize = 13.sp,
                        color = Color.DarkGray.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (hasMessage) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE8F5E9))
                                .padding(8.dp)
                                .border(
                                    width = 1.dp,
                                    color = Color(0xFF2E7D32),
                                    shape = RoundedCornerShape(4.dp)
                                )
                        ) {
                            Text(
                                text = "Class Identifier:",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = Color(0xFF2E7D32)
                            )
                            Text(
                                text = message,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF2E7D32)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (isAttendanceMarked) {
                            Text(
                                text = "Attendance recorded for this session",
                                fontSize = 13.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "No class identifier detected",
                            fontSize = 13.sp,
                            color = Color.Gray,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun UserInfoDialog(
    initialName: String,
    initialRollNumber: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var rollNumber by remember { mutableStateOf(initialRollNumber) }
    var nameError by remember { mutableStateOf(false) }
    var rollError by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = "User Info",
                    tint = Color(0xFF1A2151),
                    modifier = Modifier.size(40.dp))

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Welcome to Attendance",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1A2151)
                )

                Text(
                    text = "Please enter your information",
                    fontSize = 14.sp,
                    color = Color.DarkGray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                // Student name input
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text("Student Name") },
                    isError = nameError,
                    supportingText = {
                        if (nameError) {
                            Text("Name is required", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = "Name")
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Roll number input
                OutlinedTextField(
                    value = rollNumber,
                    onValueChange = {
                        rollNumber = it
                        rollError = it.isBlank()
                    },
                    label = { Text("Roll Number") },
                    isError = rollError,
                    supportingText = {
                        if (rollError) {
                            Text("Roll number is required", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Default.Numbers, contentDescription = "Roll Number")
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        nameError = name.isBlank()
                        rollError = rollNumber.isBlank()

                        if (!nameError && !rollError) {
                            onSave(name, rollNumber)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A2151),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = "Save",
                        modifier = Modifier.padding(end = 8.dp))
                    Text("Continue")
                }
            }
        }
    }
}