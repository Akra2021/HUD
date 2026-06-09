package com.hud.extension

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class TurnSignalCameraActivity : ComponentActivity() {

    private var controller: TurnSignalCameraController? = null
    private val statusState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cameraController = TurnSignalCameraController(applicationContext) { status ->
            runOnUiThread { statusState.value = status }
        }
        controller = cameraController
        statusState.value = getString(R.string.turn_signal_stopped)

        setContent {
            val status by statusState
            val context = LocalContext.current
            var monitoring by remember { mutableStateOf(false) }
            var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

            DisposableEffect(Unit) {
                onDispose {
                    cameraController.release()
                    controller = null
                }
            }

            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = getString(R.string.turn_signal_title),
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }

                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(0.85f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFD0D0D0)),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = getString(R.string.turn_signal_description),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = getString(
                                            R.string.turn_signal_crop_info,
                                            CameraRegionCapture.CROP_X,
                                            CameraRegionCapture.CROP_Y,
                                            CameraRegionCapture.CROP_WIDTH,
                                            CameraRegionCapture.CROP_HEIGHT,
                                            CameraRegionCapture.OUTPUT_WIDTH,
                                            CameraRegionCapture.OUTPUT_HEIGHT
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                    )
                                    Text(
                                        text = status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = getString(R.string.turn_signal_monitor_switch),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = if (monitoring) {
                                                    getString(R.string.turn_signal_monitor_on)
                                                } else {
                                                    getString(R.string.turn_signal_monitor_off)
                                                },
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Switch(
                                            modifier = Modifier.scale(0.85f),
                                            checked = monitoring,
                                            onCheckedChange = { enabled ->
                                                overlayGranted = Settings.canDrawOverlays(context)
                                                if (enabled && !overlayGranted) {
                                                    statusState.value = getString(R.string.turn_signal_overlay_required)
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                    return@Switch
                                                }
                                                monitoring = if (enabled) {
                                                    cameraController.startMonitoring()
                                                } else {
                                                    cameraController.stopMonitoring()
                                                    false
                                                }
                                            }
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            overlayGranted = Settings.canDrawOverlays(context)
                                            if (!overlayGranted) {
                                                statusState.value = getString(R.string.turn_signal_overlay_required)
                                                return@Button
                                            }
                                            cameraController.captureOnceForTest()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(getString(R.string.turn_signal_test_capture))
                                    }

                                    if (monitoring && cameraController.isManualMode()) {
                                        Text(
                                            text = getString(R.string.turn_signal_manual_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { cameraController.triggerLeftIndicator(true) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(getString(R.string.turn_signal_manual_left))
                                            }
                                            Button(
                                                onClick = { cameraController.triggerRightIndicator(true) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(getString(R.string.turn_signal_manual_right))
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                cameraController.triggerLeftIndicator(false)
                                                cameraController.triggerRightIndicator(false)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(getString(R.string.turn_signal_manual_off))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
