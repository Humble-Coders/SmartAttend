package com.example.blescanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BLEScannerLogic {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var context: Context? = null
    private val scannerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Restart scan parameters
    private val SCAN_PERIOD: Long = 10000 // 10 seconds scan time
    private var isScanning = false

    // Set to store already detected device addresses to prevent duplicate processing
    private val detectedDevices = mutableSetOf<String>()

    // The target ESP device name (can be partial match)
    val targetEspName = "Humble Coders" // Match the name in ESP32 code: BLEDevice::init("Humble Coders")

    // Data class to hold scan result with text information
    data class ScanResultWithText(
        val result: ScanResult,
        val hasTextData: Boolean,
        val message: String,
        val isEspDevice: Boolean
    )

    fun initialize(context: Context) {
        this.context = context
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScanning(onDeviceDetected: (ScanResult) -> Unit) {
        context?.let { ctx ->
            if (ActivityCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            if (isScanning) {
                // Already scanning, don't start again
                return
            }

            isScanning = true

            // Initialize scanCallback if null
            if (scanCallback == null) {
                scanCallback = createScanCallback(onDeviceDetected)
            }

            // Configure scan settings for low latency but without any UUID filters
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Start scanning WITHOUT any filters to capture all devices
            bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            Log.d("BLE", "Started BLE scan with name filtering")

            // Schedule scan restart after SCAN_PERIOD
            mainHandler.postDelayed({
                restartScan(onDeviceDetected)
            }, SCAN_PERIOD)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun restartScan(onDeviceDetected: (ScanResult) -> Unit) {
        stopScanning()
        // Short delay before restarting
        mainHandler.postDelayed({
            startScanning(onDeviceDetected)
        }, 100)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScanning() {
        context?.let { ctx ->
            if (ActivityCompat.checkSelfPermission(
                    ctx,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            // Remove any pending scan restart
            mainHandler.removeCallbacksAndMessages(null)

            // Stop scanning only if we are currently scanning
            if (isScanning) {
                scanCallback?.let { callback ->
                    bluetoothLeScanner?.stopScan(callback)
                    Log.d("BLE", "Stopped BLE scan")
                }
                isScanning = false;
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun createScanCallback(onDeviceDetected: (ScanResult) -> Unit): ScanCallback {
        return object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)

                // Process in the background to avoid UI thread blocking

                scannerScope.launch  {
                    // Process the result to check if it's our target ESP device
                    val deviceName = result.device.name

                    // If device name matches our target or contains "ESP", process it
                    if (deviceName != null &&
                        (deviceName.contains(targetEspName, ignoreCase = true) ||
                                deviceName.contains("ESP", ignoreCase = true))
                    ) {
                        // Check if we've already processed this device
                        val deviceKey = "${result.device.address}_${result.scanRecord?.bytes?.contentHashCode() ?: 0}"
                        if (!detectedDevices.contains(deviceKey)) {
                            // Add to our set of detected devices
                            detectedDevices.add(deviceKey)

                            Log.d("BLE", "NEW ESP Device detected: ${result.device.address}, Name: $deviceName")

                            // Call the onDeviceDetected callback on main thread
                            withContext(Dispatchers.Main) {
                                onDeviceDetected(result)
                            }
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e("BLE", "Scan failed with error code: $errorCode")

                // Try to restart scan after a short delay
                mainHandler.postDelayed({
                    isScanning = false
                    startScanning(onDeviceDetected)
                }, 1000)
            }
        }
    }
    // Add this method to BLEScannerLogic class
    suspend fun markDefaulterAsync(subjectName: String, studentName: String, rollNumber: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val db = Firebase.firestore

                // Get today's date in the format "dd-MM-yyyy"
                val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                val todayDate = dateFormat.format(Date())

                // Add student to Defaulters document
                db.collection("Subjects")
                    .document(subjectName)
                    .collection(todayDate)
                    .document("Defaulters")
                    .set(mapOf(rollNumber to studentName), SetOptions.merge())
                    .await()

                Log.d("Firestore", "Defaulter marked: $studentName ($rollNumber) in $subjectName")
                true
            } catch (e: Exception) {
                Log.e("Firestore", "Error marking defaulter", e)
                false
            }
        }
    }
    // Process scan result to detect if it's from our ESP device and extract the message
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun processScanResult(result: ScanResult): ScanResultWithText {
        val scanRecord = result.scanRecord
        var message = ""
        var isEspDevice = false

        // Check if this is our ESP device based on device name
        if (result.device.name?.contains(targetEspName, ignoreCase = true) == true) {
            isEspDevice = true
        }

        // If device name is null but we're still interested in checking any ESP device
        if (!isEspDevice && result.device.name?.contains("ESP", ignoreCase = true) == true) {
            isEspDevice = true
        }

        // For ESP devices, try to extract the message
        if (isEspDevice && scanRecord != null) {
            // Method 1: Try reading from manufacturer data (this is what the ESP32 code uses)
            val manufacturerData = scanRecord.manufacturerSpecificData
            if (manufacturerData != null && manufacturerData.size() > 0) {
                for (i in 0 until manufacturerData.size()) {
                    val manData = manufacturerData.valueAt(i)
                    if (manData != null) {
                        // Try to interpret as text (skip first 2 bytes if they're the manufacturer ID in ESP code)
                        var startIndex = 0

                        // If the first two bytes match the manufacturer ID format from ESP code
                        if (manData.size >= 2 &&
                            (manData[0].toInt() and 0xFF) == 0xFF &&
                            (manData[1].toInt() and 0xFF) == 0xFF
                        ) {
                            startIndex = 2
                        }

                        val potentialMessage = StringBuilder()
                        for (j in startIndex until manData.size) {
                            val c = manData[j].toInt().toChar()
                            // Only add printable ASCII characters
                            if (c.code in 32..126) {
                                potentialMessage.append(c)
                            }
                        }

                        if (potentialMessage.isNotEmpty()) {
                            message = potentialMessage.toString()
                            break
                        }
                    }
                }
            }

            // Method 2: Parse raw advertisement bytes (fallback)
            if (message.isBlank() && scanRecord.bytes != null) {
                val rawData = scanRecord.bytes

                try {
                    // In BLE advertisements, manufacturer data typically has type 0xFF
                    var index = 0
                    while (index < rawData.size - 2) {
                        val length = rawData[index].toInt() and 0xFF
                        if (length == 0 || index + length + 1 > rawData.size) break

                        val type = rawData[index + 1].toInt() and 0xFF

                        // Check for manufacturer data (type 0xFF)
                        if (type == 0xFF && index + length + 1 <= rawData.size) {
                            // Manufacturer ID should be the next 2 bytes (0xFFFF for our ESP)
                            if (length >= 4 && // At least 2 bytes for mfg ID + some data
                                rawData[index + 2].toInt() == 0xFF &&
                                rawData[index + 3].toInt() == 0xFF
                            ) {

                                // Extract the data portion (skip the 2-byte manufacturer ID)
                                val dataBytes = ByteArray(length - 3) // -1 for type byte, -2 for mfg ID
                                System.arraycopy(rawData, index + 4, dataBytes, 0, length - 3)

                                // Convert to string
                                val extractedText = StringBuilder()
                                for (b in dataBytes) {
                                    val c = b.toInt().toChar()
                                    if (c.code in 32..126) {
                                        extractedText.append(c)
                                    }
                                }

                                if (extractedText.isNotEmpty()) {
                                    message = extractedText.toString()
                                    break
                                }
                            }
                        }

                        // Move to next field
                        index += length + 1
                    }
                } catch (e: Exception) {
                    Log.e("BLE_DATA", "Error parsing raw advertisement: ${e.message}")
                }
            }
        }

        return ScanResultWithText(result, message.isNotBlank(), message, isEspDevice)
    }

    suspend fun markAttendanceAsync(subjectName: String, studentName: String, rollNumber: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val db = Firebase.firestore

                // Get today's date in the format "dd-MM-yyyy"
                val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                val todayDate = dateFormat.format(Date())

                // Create or update the Firestore document
                db.collection("Subjects")
                    .document(subjectName)
                    .collection(todayDate)
                    .document(rollNumber)
                    .set(mapOf("name" to studentName))
                    .await()

                Log.d("Firestore", "Attendance marked for $studentName in $subjectName")
                true
            } catch (e: Exception) {
                Log.e("Firestore", "Error marking attendance", e)
                false
            }
        }
    }

    // Original markAttendance method kept for backward compatibility
    fun markAttendance(subjectName: String, studentName: String, rollNumber: String, onSuccess: () -> Unit = {}) {
        val db = Firebase.firestore

        scannerScope.launch {
            try {
                // Get today's date in the format "dd-MM-yyyy"
                val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                val todayDate = dateFormat.format(Date())

                // Create or update the Firestore document
                db.collection("Subjects")
                    .document(subjectName)
                    .collection(todayDate)
                    .document(rollNumber)
                    .set(mapOf("name" to studentName))
                    .await()

                Log.d("Firestore", "Attendance marked for $studentName in $subjectName")

                // Call success callback on the main thread
                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("Firestore", "Error marking attendance", e)
                withContext(Dispatchers.Main) {
                    context?.let {
                        android.widget.Toast.makeText(it, "Failed to mark attendance", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Method to clear detected devices (useful when restarting scan)
    fun clearDetectedDevices() {
        detectedDevices.clear()
    }

    // Clean up resources
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun cleanup() {
        stopScanning()
        mainHandler.removeCallbacksAndMessages(null)
        scannerScope.cancel()
    }
}