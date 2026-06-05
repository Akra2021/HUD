package com.hud.extension

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

private const val APP_UI_SCALE = 0.7f
private const val HUD_SWITCH_SCALE = 0.6f
private const val APP_CARD_WIDTH_FRACTION = 0.5f
private val ReconnectButtonBlue = Color(0xFF42A5F5)
private val AppListIconSize = 39.dp

private fun Modifier.appCardWidth(): Modifier = fillMaxWidth(APP_CARD_WIDTH_FRACTION)

private fun Typography.scaleBy(factor: Float): Typography = copy(
    displayLarge = displayLarge.scaleBy(factor),
    displayMedium = displayMedium.scaleBy(factor),
    displaySmall = displaySmall.scaleBy(factor),
    headlineLarge = headlineLarge.scaleBy(factor),
    headlineMedium = headlineMedium.scaleBy(factor),
    headlineSmall = headlineSmall.scaleBy(factor),
    titleLarge = titleLarge.scaleBy(factor),
    titleMedium = titleMedium.scaleBy(factor),
    titleSmall = titleSmall.scaleBy(factor),
    bodyLarge = bodyLarge.scaleBy(factor),
    bodyMedium = bodyMedium.scaleBy(factor),
    bodySmall = bodySmall.scaleBy(factor),
    labelLarge = labelLarge.scaleBy(factor),
    labelMedium = labelMedium.scaleBy(factor),
    labelSmall = labelSmall.scaleBy(factor)
)

private fun TextStyle.scaleBy(factor: Float): TextStyle = copy(
    fontSize = fontSize * factor,
    lineHeight = lineHeight * factor
)

data class NavAppData(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
    val isInstalled: Boolean,
    val isRunning: Boolean
)

class MainActivity : ComponentActivity() {

    private var navOverlay: NavOverlayWindow? = null
    private var lastReconnectAt = 0L

    private val notificationListeningState = mutableStateOf(false)
    private val listenerConnectedState = mutableStateOf(false)
    private val notificationAccessGrantedState = mutableStateOf(false)
    private val selectedNavPackageState = mutableStateOf<String?>(null)

    override fun onResume() {
        super.onResume()
        HudPreferences.ensureDefaultSelection(this)
        refreshNotificationUiState()
        selectedNavPackageState.value = HudPreferences.getSelectedNavPackage(this)
        listenerConnectedState.value = NavEventHub.serviceConnected
        syncHudOutput()
        if (NotificationAccessHelper.isUserListeningEnabled(this)) {
            window.decorView.postDelayed({ refreshNavData() }, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        HudPreferences.ensureDefaultSelection(this)
        refreshNotificationUiState()
        selectedNavPackageState.value = HudPreferences.getSelectedNavPackage(this)
        attachNavPipeline()

        setContent {
            val notificationListening by notificationListeningState
            val listenerConnected by listenerConnectedState
            val notificationAccessGranted by notificationAccessGrantedState
            val selectedNavPackage by selectedNavPackageState

            MaterialTheme(
                typography = MaterialTheme.typography.scaleBy(APP_UI_SCALE)
            ) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavAppList(
                        modifier = Modifier.padding(innerPadding),
                        notificationListening = notificationListening,
                        listenerConnected = listenerConnected,
                        notificationAccessGranted = notificationAccessGranted,
                        selectedNavPackage = selectedNavPackage,
                        onNotificationListeningChange = ::onNotificationListeningToggle,
                        onSelectNavApp = ::onSelectNavApp,
                        onReconnect = { forceReconnectFromUi() },
                        onSyncHudOutput = { syncHudOutput() }
                    )
                }
            }
        }
    }

    private fun onSelectNavApp(packageName: String) {
        val normalized = HudPreferences.normalizeNavPackage(this, packageName) ?: packageName
        HudPreferences.setSelectedNavPackage(this, normalized)
        selectedNavPackageState.value = normalized
        if (NotificationAccessHelper.isUserListeningEnabled(this)) {
            syncHudOutput()
        }
    }

    fun syncNotificationUiState() {
        notificationAccessGrantedState.value = NotificationAccessHelper.isEnabled(this)
        notificationListeningState.value = NotificationAccessHelper.isUserListeningEnabled(this)
        refreshListenerConnectionState()
    }

    private fun refreshListenerConnectionState() {
        val hudOn = notificationListeningState.value && notificationAccessGrantedState.value
        listenerConnectedState.value = if (hudOn) NavEventHub.serviceConnected else false
    }

    private fun refreshNotificationUiState() = syncNotificationUiState()

    private fun attachNavPipeline() {
        NavEventHub.setNavConsumer { update -> handleNavUpdate(update) }
        NavEventHub.setConnectionConsumer { connected ->
            if (NotificationAccessHelper.isUserListeningEnabled(this@MainActivity) &&
                NotificationAccessHelper.isEnabled(this@MainActivity)
            ) {
                listenerConnectedState.value = connected
            } else {
                listenerConnectedState.value = false
            }
        }
    }

    private fun detachNavPipeline() {
        NavEventHub.setNavConsumer(null)
        NavEventHub.setConnectionConsumer(null)
    }

    private fun handleNavUpdate(update: NavEventHub.NavUpdate) {
        if (!NotificationAccessHelper.isUserListeningEnabled(this)) return

        listenerConnectedState.value = true
        if (navOverlay == null && Settings.canDrawOverlays(this)) {
            ensureOverlay()
        }

        if (update.clear) {
            navOverlay?.showWaiting()
            return
        }

        val guidance = update.guidance ?: return
        if (guidance.isPlaceholder) return
        HudLog.i("overlay update: '${guidance.instruction}'")
        navOverlay?.updateGuidance(guidance)
    }

    fun syncHudOutput() {
        if (!NotificationAccessHelper.isUserListeningEnabled(this)) {
            dismissOverlay()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            dismissOverlay()
            return
        }

        ensureOverlay()
        HudRefreshScheduler.start(this)
        refreshNavData()
        if (!NavEventHub.serviceConnected || !HUDNotificationListenerService.isRunning()) {
            maybeReconnect()
        }
    }

    private fun refreshNavData() {
        if (!NotificationAccessHelper.isUserListeningEnabled(this)) return
        HUDNotificationListenerService.refreshActiveNotifications("syncHudOutput")
        val guidance = NavEventHub.lastGuidance
        if (guidance != null && guidance.hasDisplayableContent()) {
            navOverlay?.updateGuidance(guidance)
        } else {
            navOverlay?.showWaiting()
        }
    }

    fun dismissOverlay() {
        HudRefreshScheduler.stop()
        navOverlay?.dismiss()
        navOverlay = null
        NavOverlayHolder.dismiss()
        NavEventHub.resetLastGuidance()
        HudLog.i("overlay dismissed (HUD off or no permission)")
    }

    fun ensureOverlay() {
        if (!NotificationAccessHelper.isUserListeningEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) return
        if (navOverlay != null) {
            NavOverlayHolder.attach(navOverlay!!)
            return
        }
        try {
            navOverlay = NavOverlayWindow(this).also { overlay ->
                overlay.show()
                NavOverlayHolder.attach(overlay)
            }
            Log.d("HUD_EXT", "Overlay shown")
        } catch (e: Exception) {
            Log.e("HUD_EXT", "Overlay failed: ${e.message}")
        }
    }

    fun maybeReconnect() {
        if (!NotificationAccessHelper.isUserListeningEnabled(this)) return
        if (!NotificationAccessHelper.isEnabled(this)) return
        val now = System.currentTimeMillis()
        val needsImmediate = !NavEventHub.serviceConnected
        val minInterval = if (needsImmediate) 1500L else 4000L
        if (now - lastReconnectAt < minInterval) return
        lastReconnectAt = now
        HudLog.i("maybeReconnect immediate=$needsImmediate")
        NotificationAccessHelper.forceReconnect(this)
    }

    fun forceReconnectFromUi() {
        if (!NotificationAccessHelper.isEnabled(this)) {
            Toast.makeText(this, R.string.toast_enable_access_first, Toast.LENGTH_LONG).show()
            NotificationAccessHelper.openAccessSettings(this)
            return
        }
        lastReconnectAt = System.currentTimeMillis()
        listenerConnectedState.value = false
        NotificationAccessHelper.forceReconnect(this)
        Toast.makeText(this, R.string.toast_reconnecting, Toast.LENGTH_SHORT).show()
    }

    private fun onNotificationListeningToggle(enabled: Boolean) {
        try {
            if (enabled) {
                notificationListeningState.value = true
                HudPreferences.ensureDefaultSelection(this)
                selectedNavPackageState.value = HudPreferences.getSelectedNavPackage(this)
                requestHudPermissionsIfNeeded()
                when (NotificationAccessHelper.enable(this)) {
                    NotificationAccessHelper.EnableResult.OPENED_SETTINGS -> {
                        Toast.makeText(
                            this,
                            R.string.toast_enable_notification_access,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    NotificationAccessHelper.EnableResult.REBOUND -> {
                        Toast.makeText(
                            this,
                            R.string.toast_hud_enabled,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                syncHudOutput()
                refreshListenerConnectionState()
                window.decorView.postDelayed({
                    refreshNavData()
                    refreshListenerConnectionState()
                }, 1200)
            } else {
                notificationListeningState.value = false
                listenerConnectedState.value = false
                HudOutputController.stop(this)
                dismissOverlay()
                Toast.makeText(
                    this,
                    R.string.toast_hud_disabled,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e("HUD_EXT", "Toggle failed: ${e.message}", e)
            refreshNotificationUiState()
            Toast.makeText(this, R.string.toast_toggle_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestHudPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Запрос выполняется из Compose launcher при отображении списка.
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            detachNavPipeline()
            if (!NotificationAccessHelper.isUserListeningEnabled(this)) {
                dismissOverlay()
            }
        } catch (e: Exception) {
            Log.e("HUD_EXT", "Error in onDestroy: ${e.message}")
        }
    }
}

@Composable
fun NavAppList(
    modifier: Modifier = Modifier,
    notificationListening: Boolean,
    listenerConnected: Boolean,
    notificationAccessGranted: Boolean,
    selectedNavPackage: String?,
    onNotificationListeningChange: (Boolean) -> Unit,
    onSelectNavApp: (String) -> Unit,
    onReconnect: () -> Unit = {},
    onSyncHudOutput: () -> Unit = {}
) {
    val context = LocalContext.current
    var hasNavFeed by remember { mutableStateOf(NavEventHub.hasLiveNavFeed()) }
    var apps by remember { mutableStateOf(listOf<NavAppData>()) }
    var notificationsGranted by remember {
        mutableStateOf(hasPostNotificationsPermission(context))
    }
    var previousSystemAccess by remember {
        mutableStateOf(NotificationAccessHelper.isEnabled(context))
    }
    var overlayGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
    }

    LaunchedEffect(overlayGranted, notificationListening) {
        if (notificationListening && overlayGranted) onSyncHudOutput()
        if (!notificationListening) {
            (context as? MainActivity)?.dismissOverlay()
        }
    }

    LaunchedEffect(notificationListening, notificationAccessGranted) {
        if (notificationListening && notificationAccessGranted) {
            (context as? MainActivity)?.maybeReconnect()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPostNotificationsPermission(context)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            apps = getNavApps(context)
            notificationsGranted = hasPostNotificationsPermission(context)
            val systemAccess = NotificationAccessHelper.isEnabled(context)
            overlayGranted = Settings.canDrawOverlays(context)
            hasNavFeed = NavEventHub.hasLiveNavFeed()
            (context as? MainActivity)?.syncNotificationUiState()

            if (systemAccess && !previousSystemAccess &&
                NotificationAccessHelper.isUserListeningEnabled(context)
            ) {
                (context as? MainActivity)?.maybeReconnect()
            }
            previousSystemAccess = systemAccess

            delay(1000)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "HUD Extension",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        item {
            NotificationAccessCard(
                listening = notificationListening,
                systemAccessGranted = notificationAccessGranted,
                listenerConnected = listenerConnected,
                selectedNavPackage = selectedNavPackage,
                onListeningChange = onNotificationListeningChange,
                onReconnect = onReconnect,
                hasNavFeed = hasNavFeed
            )
        }

        if (notificationListening && (!notificationsGranted || !overlayGranted)) {
            item {
                PermissionsSection(
                    notificationsGranted = notificationsGranted,
                    overlayGranted = overlayGranted,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onOpenOverlay = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
            }
        }

        items(apps) { app ->
            NavAppItem(
                app = app,
                selected = HudPreferences.isSameNavSource(selectedNavPackage, app.packageName),
                hudEnabled = notificationListening,
                onSelect = { onSelectNavApp(app.packageName) }
            )
        }

    }
}

@Composable
private fun NotificationAccessCard(
    listening: Boolean,
    systemAccessGranted: Boolean,
    listenerConnected: Boolean,
    selectedNavPackage: String?,
    onListeningChange: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    hasNavFeed: Boolean
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.appCardWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notification to HUD",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = when {
                            !listening ->
                                context.getString(R.string.status_off)
                            listening && !systemAccessGranted ->
                                context.getString(R.string.status_grant_access)
                            listening && hasNavFeed && selectedNavPackage != null ->
                                context.getString(R.string.status_active, appLabel(context, selectedNavPackage))
                            listening && listenerConnected && systemAccessGranted ->
                                context.getString(R.string.status_waiting_route)
                            listening && systemAccessGranted ->
                                context.getString(R.string.status_connecting)
                            else ->
                                context.getString(R.string.status_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
                Switch(
                    modifier = Modifier.scale(HUD_SWITCH_SCALE),
                    checked = listening,
                    onCheckedChange = onListeningChange
                )
            }
            if (listening && systemAccessGranted) {
                Button(
                    onClick = onReconnect,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReconnectButtonBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = context.getString(
                            if (listenerConnected) R.string.btn_reconnect else R.string.btn_connect_service
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

private fun appLabel(context: Context, packageName: String): String = when {
    packageName.contains("yandexnavi") -> context.getString(R.string.app_yandex_navigator)
    packageName.contains("huawei.maps") -> context.getString(R.string.app_petal_maps)
    else -> packageName
}

@Composable
private fun PermissionsSection(
    notificationsGranted: Boolean,
    overlayGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    Card(
        modifier = Modifier.appCardWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val context = LocalContext.current
            Text(
                text = context.getString(R.string.permissions_required),
                style = MaterialTheme.typography.titleMedium
            )

            PermissionRow(
                label = context.getString(R.string.permission_notifications),
                granted = notificationsGranted,
                buttonText = context.getString(R.string.permission_allow),
                onClick = onRequestNotifications
            )

            PermissionRow(
                label = context.getString(R.string.permission_overlay),
                granted = overlayGranted,
                buttonText = context.getString(R.string.permission_open_settings),
                onClick = onOpenOverlay
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (granted) {
                    LocalContext.current.getString(R.string.permission_granted)
                } else {
                    LocalContext.current.getString(R.string.permission_not_granted)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }

        if (!granted) {
            Button(onClick = onClick) {
                Text(buttonText)
            }
        }
    }
}

private fun hasPostNotificationsPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun getNavApps(context: Context): List<NavAppData> {
    val pm = context.packageManager
    val petalPackage = HudPreferences.resolvePetalPackage(context)
    val targetApps = buildList {
        add("ru.yandex.yandexnavi" to context.getString(R.string.app_yandex_navigator))
        add(petalPackage to context.getString(R.string.app_petal_maps))
    }

    val runningApps = mutableSetOf<String>()

    try {
        val process = Runtime.getRuntime().exec(
            arrayOf("sh", "-c", "dumpsys activity activities | grep -E 'mResumedActivity|mFocusedApp'")
        )
        val output = process.inputStream.bufferedReader().readText()
        if (output.contains("yandexnavi")) runningApps.add("ru.yandex.yandexnavi")
        if (output.contains("huawei.maps")) {
            HudPreferences.petalPackages.forEach { pkg ->
                if (output.contains(pkg)) runningApps.add(pkg)
            }
        }
        if (runningApps.isEmpty()) {
            val process2 = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A | grep -E 'yandexnavi|huawei.maps'"))
            val output2 = process2.inputStream.bufferedReader().readText()
            if (output2.contains("yandexnavi")) runningApps.add("ru.yandex.yandexnavi")
            HudPreferences.petalPackages.forEach { pkg ->
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

        if (HudPreferences.isPetalPackage(pkg)) {
            isInstalled = HudPreferences.isAnyPetalInstalled(context)
            if (isInstalled) {
                try {
                    val info = pm.getApplicationInfo(petalPackage, 0)
                    name = pm.getApplicationLabel(info).toString()
                    icon = pm.getApplicationIcon(info)
                } catch (_: PackageManager.NameNotFoundException) {}
            }
        } else {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                name = pm.getApplicationLabel(info).toString()
                icon = pm.getApplicationIcon(info)
                isInstalled = true
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        val isRunning = when {
            pkg == "ru.yandex.yandexnavi" -> runningApps.contains(pkg)
            HudPreferences.isPetalPackage(pkg) -> HudPreferences.isPetalRunning(runningApps)
            else -> runningApps.contains(pkg)
        }

        NavAppData(
            name = name,
            packageName = if (HudPreferences.isPetalPackage(pkg)) petalPackage else pkg,
            icon = icon,
            isInstalled = isInstalled,
            isRunning = isRunning
        )
    }
}

@Composable
fun NavAppItem(
    app: NavAppData,
    selected: Boolean,
    hudEnabled: Boolean,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    val grayscaleMatrix = remember {
        ColorMatrix().apply { setToSaturation(0f) }
    }

    Card(
        modifier = Modifier.appCardWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected && hudEnabled -> MaterialTheme.colorScheme.primaryContainer
                app.isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (app.isInstalled) 1f else 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(AppListIconSize)) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon.toBitmap().asImageBitmap(),
                        contentDescription = app.name,
                        modifier = Modifier.fillMaxSize(),
                        colorFilter = if (!app.isInstalled) ColorFilter.colorMatrix(grayscaleMatrix) else null
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when {
                        selected && hudEnabled -> context.getString(R.string.nav_hud_source)
                        app.isRunning -> context.getString(R.string.nav_running_display0)
                        app.isInstalled -> context.getString(R.string.nav_installed)
                        else -> context.getString(R.string.nav_not_installed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected && hudEnabled || app.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Gray
                    }
                )
            }

            Checkbox(
                checked = selected,
                onCheckedChange = { checked ->
                    if (checked && hudEnabled) onSelect()
                },
                enabled = app.isInstalled && hudEnabled
            )
        }
    }
}
