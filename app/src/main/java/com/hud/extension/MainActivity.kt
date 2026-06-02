package com.hud.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

data class NavAppData(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val isInstalled: Boolean,
    val isRunning: Boolean
)

class MainActivity : ComponentActivity() {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    private val navReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("command") == "clear") {
                updateOverlay("", "Waiting for navigation...", null)
                return
            }
            
            val title = intent.getStringExtra("title") ?: ""
            val text = intent.getStringExtra("text") ?: ""
            val subText = intent.getStringExtra("subText") ?: ""
            val iconBytes = intent.getByteArrayExtra("icon")
            
            val icon = iconBytes?.let { 
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            
            val displayTitle = if (title.isNotEmpty()) title else text
            val displayText = if (title.isNotEmpty()) "$text $subText".trim() else subText
            
            updateOverlay(displayTitle, displayText, icon)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setupNavOverlay()
        
        android.widget.Toast.makeText(this, "HUD Extension Ready", android.widget.Toast.LENGTH_SHORT).show()

        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavAppList(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun setupNavOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter("com.hud.extension.NAV_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(navReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(navReceiver, filter)
        }
        showOverlay()
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 50 
        }

        // Создаем контейнер программно
        val context = this
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = ImageView(context).apply {
            id = View.generateViewId()
            layoutParams = android.widget.LinearLayout.LayoutParams(120, 120).apply {
                marginEnd = 32
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val textContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, 
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 
                1f
            )
        }

        val titleView = TextView(context).apply {
            id = View.generateViewId()
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val descView = TextView(context).apply {
            id = View.generateViewId()
            textSize = 20f
            setTextColor(android.graphics.Color.LTGRAY)
        }

        textContainer.addView(titleView)
        textContainer.addView(descView)
        layout.addView(iconView)
        layout.addView(textContainer)

        overlayView = layout

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e("HUD_EXT", "Failed to add overlay: ${e.message}")
        }
    }

    private fun updateOverlay(title: String, text: String, icon: android.graphics.Bitmap?) {
        overlayView?.let { view ->
            val layout = view as android.widget.LinearLayout
            val iconView = layout.getChildAt(0) as ImageView
            val textContainer = layout.getChildAt(1) as android.widget.LinearLayout
            val titleView = textContainer.getChildAt(0) as TextView
            val descView = textContainer.getChildAt(1) as TextView

            titleView.text = title
            descView.text = text
            if (icon != null) {
                iconView.visibility = View.VISIBLE
                iconView.setImageBitmap(icon)
            } else {
                iconView.visibility = if (title.isEmpty() && text.contains("Waiting")) View.GONE else View.INVISIBLE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(navReceiver)
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
            }
        } catch (e: Exception) {
            Log.e("HUD_EXT", "Error in onDestroy: ${e.message}")
        }
    }
}

@Composable
fun NavAppList(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(listOf<NavAppData>()) }

    LaunchedEffect(Unit) {
        while (true) {
            apps = getNavApps(context)
            delay(2000)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "HUD Extension",
                style = MaterialTheme.typography.headlineMedium
            )
        }
        items(apps) { app ->
            NavAppItem(app)
        }
    }
}

private fun getNavApps(context: Context): List<NavAppData> {
    val pm = context.packageManager
    val targetApps = listOf(
        "ru.yandex.yandexnavi" to "Yandex Navigator",
        "com.huawei.maps.app" to "Petal Maps"
    )

    val runningApps = mutableSetOf<String>()
    
    try {
        // Улучшенная проверка на запущенность через dumpsys activity
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys activity activities | grep -E 'ResumedActivity|mFocusedApp'"))
        val output = process.inputStream.bufferedReader().readText()
        
        targetApps.forEach { (pkg, _) ->
            if (output.contains(pkg)) runningApps.add(pkg)
        }
        
        // Дополнительная проверка через процессы, если dumpsys activity не дал результат
        if (runningApps.isEmpty()) {
            val process2 = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep -E 'yandexnavi|huawei.maps'"))
            val output2 = process2.inputStream.bufferedReader().readText()
            targetApps.forEach { (pkg, _) ->
                if (output2.contains(pkg)) runningApps.add(pkg)
            }
        }
    } catch (e: Exception) {
        Log.e("HUD_EXT", "Detection failed: ${e.message}")
    }

    return targetApps.map { (pkg, fallbackName) ->
        var isInstalled = false
        var name = fallbackName
        var icon: Drawable? = null
        
        try {
            val info = pm.getApplicationInfo(pkg, 0)
            name = pm.getApplicationLabel(info).toString()
            icon = pm.getApplicationIcon(info)
            isInstalled = true
        } catch (e: PackageManager.NameNotFoundException) {}

        NavAppData(
            name = name,
            packageName = pkg,
            icon = icon,
            isInstalled = isInstalled,
            isRunning = runningApps.contains(pkg)
        )
    }
}

@Composable
fun NavAppItem(app: NavAppData) {
    val grayscaleMatrix = remember {
        ColorMatrix().apply { setToSaturation(0f) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isRunning) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (app.isInstalled) 1f else 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(56.dp)) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon.toBitmap().asImageBitmap(),
                        contentDescription = app.name,
                        modifier = Modifier.fillMaxSize(),
                        colorFilter = if (!app.isInstalled) ColorFilter.colorMatrix(grayscaleMatrix) else null
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (app.isRunning) "Running on Display 0" else if (app.isInstalled) "Installed" else "Not installed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (app.isRunning) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }
            
            if (app.isRunning) {
                Text(
                    text = "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
