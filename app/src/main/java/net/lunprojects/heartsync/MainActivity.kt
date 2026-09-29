package com.example.heartsync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.launch
import net.lunprojects.heartsync.HeartRateService
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HeartSyncScreen(this)
                }
            }
        }
    }
}

@Composable
fun HeartSyncScreen(activity: ComponentActivity) {
    val context = activity
    val coroutineScope = rememberCoroutineScope()
    val bpm by HeartRateService.latestBpm.collectAsState()
    val isRunning by HeartRateService.isRunning.collectAsState()

    val healthConnectClient = remember { HealthConnectClient.getOrCreate(context) }
    val hrPermission = remember { HealthPermission.getWritePermission(HeartRateRecord::class) }

    // Health Connect Permission Launcher
    val healthPermissionsLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.contains(hrPermission)) {
            Toast.makeText(context, "Health Connect Authorized", Toast.LENGTH_SHORT).show()
        }
    }

    // Android System Permission Launcher (BLE & Notification)
    val systemPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            context.startForegroundService(Intent(context, HeartRateService::class.java))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Current Heart Rate", fontSize = 18.sp)
        Text(
            text = if (isRunning) "$bpm BPM" else "-- BPM",
            fontSize = 48.sp,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // START TRACKING BUTTON
        Button(
            onClick = {
                val neededPermissions = mutableListOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                systemPermissionLauncher.launch(neededPermissions.toTypedArray())
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Workout")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // STOP TRACKING BUTTON
        Button(
            onClick = {
                context.stopService(Intent(context, HeartRateService::class.java))
            },
            enabled = isRunning,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop Workout")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // SYNC TO HEALTH CONNECT BUTTON
        OutlinedButton(
            onClick = {
                coroutineScope.launch {
                    val granted = healthConnectClient.permissionController.getGrantedPermissions()
                    if (!granted.contains(hrPermission)) {
                        healthPermissionsLauncher.launch(setOf(hrPermission))
                        return@launch
                    }

                    // Read local backup file
                    val file = File(context.filesDir, "temp_session.jsonl")
                    if (!file.exists() || file.length() == 0L) {
                        Toast.makeText(context, "No saved workout session found", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val samples = mutableListOf<HeartRateRecord.Sample>()
                    file.forEachLine { line ->
                        if (line.isNotBlank()) {
                            val json = JSONObject(line)
                            samples.add(
                                HeartRateRecord.Sample(
                                    time = Instant.ofEpochMilli(json.getLong("ts")),
                                    beatsPerMinute = json.getLong("bpm")
                                )
                            )
                        }
                    }

                    if (samples.isNotEmpty()) {
                        val record = HeartRateRecord(
                            startTime = samples.first().time,
                            startZoneOffset = ZoneOffset.UTC,
                            endTime = samples.last().time,
                            endZoneOffset = ZoneOffset.UTC,
                            samples = samples,
                            metadata = Metadata.manualEntry()
                        )
                        healthConnectClient.insertRecords(listOf(record))
                        file.delete() // Clean up after successful commit
                        Toast.makeText(context, "Synced ${samples.size} points to Health Connect!", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sync Session to Health Connect")
        }
    }
}