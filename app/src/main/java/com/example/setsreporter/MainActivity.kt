package com.example.setsreporter

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.setsreporter.ui.theme.SetsReporterTheme
import kotlin.math.abs
import kotlin.math.sqrt

enum class AutoCountSensitivity(
    val label: String,
    val requiredPutDownMillis: Long,
    val requiredPickupMillis: Long,
    val minPutDownMillis: Long,
    val maxTiltXY: Float,
    val stillMovement: Float,
    val pickupTiltXY: Float,
    val pickupMovement: Float,
    val requiredVerticalMoveMeters: Float,
) {
    LOW("低", 1300L, 650L, 1600L, 1.7f, 0.55f, 3.7f, 1.7f, 0.15f),
    MEDIUM("中", 1050L, 500L, 1300L, 2.1f, 0.7f, 4.3f, 2.1f, 0.12f),
    HIGH("高", 850L, 400L, 1000L, 2.5f, 0.9f, 5.0f, 2.5f, 0.10f),
}

object SetCounterStore {
    var count by mutableIntStateOf(0)
        private set
    var autoCountEnabled by mutableStateOf(false)
        private set
    var overlayEnabled by mutableStateOf(false)
        private set
    var fluidCloudEnabled by mutableStateOf(false)
        private set
    var debugStatusEnabled by mutableStateOf(false)
        private set
    var restDurationSeconds by mutableIntStateOf(60)
        private set
    var sensitivity by mutableStateOf(AutoCountSensitivity.MEDIUM)
        private set
    var restEndTimeMillis by mutableStateOf(0L)
        private set

    private val listeners = mutableSetOf<() -> Unit>()
    private var preferences: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("sets_reporter_settings", Context.MODE_PRIVATE)
        preferences = prefs
        autoCountEnabled = prefs.getBoolean("auto_count_enabled", autoCountEnabled)
        overlayEnabled = prefs.getBoolean("overlay_enabled", overlayEnabled)
        fluidCloudEnabled = prefs.getBoolean("fluid_cloud_enabled", fluidCloudEnabled)
        debugStatusEnabled = prefs.getBoolean("debug_status_enabled", debugStatusEnabled)
        restDurationSeconds = prefs.getInt("rest_duration_seconds", restDurationSeconds)
        sensitivity = runCatching {
            AutoCountSensitivity.valueOf(prefs.getString("sensitivity", sensitivity.name) ?: sensitivity.name)
        }.getOrDefault(AutoCountSensitivity.MEDIUM)
    }

    val isResting: Boolean
        get() = remainingRestMillis() > 0L

    fun remainingRestMillis(now: Long = System.currentTimeMillis()): Long = (restEndTimeMillis - now).coerceAtLeast(0L)

    fun increment(startRest: Boolean = false) {
        count++
        if (startRest && restDurationSeconds > 0) {
            restEndTimeMillis = System.currentTimeMillis() + restDurationSeconds * 1000L
        }
        notifyChanged()
    }

    fun decrement() {
        if (count > 0) count--
        notifyChanged()
    }

    fun reset() {
        count = 0
        endRest()
    }

    fun endRest() {
        restEndTimeMillis = 0L
        notifyChanged()
    }

    fun updateAutoCountEnabled(enabled: Boolean) {
        autoCountEnabled = enabled
        preferences?.edit()?.putBoolean("auto_count_enabled", enabled)?.apply()
        notifyChanged()
    }

    fun updateOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        preferences?.edit()?.putBoolean("overlay_enabled", enabled)?.apply()
        notifyChanged()
    }

    fun updateFluidCloudEnabled(enabled: Boolean) {
        fluidCloudEnabled = enabled
        preferences?.edit()?.putBoolean("fluid_cloud_enabled", enabled)?.apply()
        notifyChanged()
    }

    fun updateDebugStatusEnabled(enabled: Boolean) {
        debugStatusEnabled = enabled
        preferences?.edit()?.putBoolean("debug_status_enabled", enabled)?.apply()
        notifyChanged()
    }

    fun updateRestDurationSeconds(seconds: Int) {
        restDurationSeconds = seconds
        preferences?.edit()?.putInt("rest_duration_seconds", seconds)?.apply()
        if (seconds == 0) restEndTimeMillis = 0L
        notifyChanged()
    }

    fun updateSensitivity(newSensitivity: AutoCountSensitivity) {
        sensitivity = newSensitivity
        preferences?.edit()?.putString("sensitivity", newSensitivity.name)?.apply()
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }
}

object AutoCountSensorController : SensorEventListener {
    private enum class DetectionState {
        IDLE,
        MAYBE_PUT_DOWN,
        PUT_DOWN_CONFIRMED,
        MAYBE_PICKUP,
        RESTING,
    }

    private class MovementWindow(private val maxSize: Int) {
        private val values = ArrayDeque<Float>()
        var average = 0f
            private set
        var peak = 0f
            private set

        fun clear() {
            values.clear()
            average = 0f
            peak = 0f
        }

        fun add(value: Float) {
            values += value
            if (values.size > maxSize) values.removeFirst()
            average = values.average().toFloat()
            peak = values.maxOrNull() ?: 0f
        }
    }

    var debugStatusText by mutableStateOf("状态：IDLE")
        private set

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var gravitySensor: Sensor? = null
    private val activeClients = mutableSetOf<String>()

    private var state = DetectionState.IDLE
    private var stateStartTime = 0L
    private var confirmedPutDownTime = 0L
    private var lowX = 0f
    private var lowY = 0f
    private var lowZ = 0f
    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var hasGravitySample = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastRawZ = 0f
    private var lastSampleTimeMillis = 0L
    private var verticalVelocity = 0f
    private var verticalDisplacement = 0f
    private var hasLastSample = false
    private val movementWindow = MovementWindow(maxSize = 12)

    fun hasAccelerometer(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    fun setClientActive(context: Context, client: String, active: Boolean) {
        if (active) {
            activeClients += client
        } else {
            activeClients -= client
        }
        refresh(context)
    }

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val manager = (sensorManager ?: appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .also { sensorManager = it }
        val accelerometerSensor = accelerometer ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            .also { accelerometer = it }
        val gravity = gravitySensor ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            .also { gravitySensor = it }

        manager.unregisterListener(this)
        if (SetCounterStore.autoCountEnabled && activeClients.isNotEmpty() && accelerometerSensor != null) {
            gravity?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            manager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            resetDetectionState()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!SetCounterStore.autoCountEnabled) return

        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            gravityX = event.values[0]
            gravityY = event.values[1]
            gravityZ = event.values[2]
            hasGravitySample = true
            return
        }
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        if (SetCounterStore.isResting) {
            transitionTo(DetectionState.RESTING, now)
            debugStatusText = "状态：RESTING，剩余 ${((SetCounterStore.remainingRestMillis() + 999L) / 1000L)}s"
            return
        }
        if (state == DetectionState.RESTING) {
            resetDetectionState(now)
        }

        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = event.values[2]
        applyLowPass(rawX, rawY, rawZ)
        val poseX = if (hasGravitySample) gravityX else lowX
        val poseY = if (hasGravitySample) gravityY else lowY
        val poseZ = if (hasGravitySample) gravityZ else lowZ

        val rawMagnitude = sqrt(rawX * rawX + rawY * rawY + rawZ * rawZ)
        val filteredXy = sqrt(poseX * poseX + poseY * poseY)
        val rawMovement = if (hasLastSample) {
            abs(rawX - lastRawX) + abs(rawY - lastRawY) + abs(rawZ - lastRawZ)
        } else {
            Float.MAX_VALUE
        }
        movementWindow.add(rawMovement)
        updateVerticalDisplacement(now, rawX, rawY, rawZ, poseX, poseY, poseZ)
        lastRawX = rawX
        lastRawY = rawY
        lastRawZ = rawZ
        lastSampleTimeMillis = now
        hasLastSample = true

        val sensitivity = SetCounterStore.sensitivity
        val isMagnitudeNormal = rawMagnitude in 8.4f..11.1f
        val isHorizontallyPlaced = abs(poseZ) > 9.15f && filteredXy < sensitivity.maxTiltXY
        val isStill = movementWindow.average < sensitivity.stillMovement && movementWindow.peak < sensitivity.stillMovement * 2.2f
        val movedDownEnough = verticalDisplacement <= -sensitivity.requiredVerticalMoveMeters
        val movedUpEnough = verticalDisplacement >= sensitivity.requiredVerticalMoveMeters
        val putDownCandidate = isMagnitudeNormal && isHorizontallyPlaced && isStill && movedDownEnough
        val postureChangedForPickup = abs(poseZ) < 8.0f || filteredXy > sensitivity.pickupTiltXY
        val pickupCandidate = postureChangedForPickup &&
            movementWindow.average > sensitivity.stillMovement &&
            movementWindow.peak > sensitivity.pickupMovement &&
            movedUpEnough

        if (SetCounterStore.debugStatusEnabled) {
            debugStatusText = "状态：$state | 水平=$isHorizontallyPlaced 静止=$isStill 下移=$movedDownEnough 上移=$movedUpEnough 平均移动=${"%.2f".format(movementWindow.average)} 峰值=${"%.2f".format(movementWindow.peak)}"
        }

        when (state) {
            DetectionState.IDLE -> {
                if (putDownCandidate) transitionTo(DetectionState.MAYBE_PUT_DOWN, now)
            }
            DetectionState.MAYBE_PUT_DOWN -> {
                when {
                    !putDownCandidate -> transitionTo(DetectionState.IDLE, now)
                    now - stateStartTime >= sensitivity.requiredPutDownMillis -> {
                        confirmedPutDownTime = now
                        resetVerticalMotion()
                        transitionTo(DetectionState.PUT_DOWN_CONFIRMED, now)
                    }
                }
            }
            DetectionState.PUT_DOWN_CONFIRMED -> {
                if (pickupCandidate && now - confirmedPutDownTime >= sensitivity.minPutDownMillis) {
                    transitionTo(DetectionState.MAYBE_PICKUP, now)
                }
            }
            DetectionState.MAYBE_PICKUP -> {
                when {
                    putDownCandidate -> {
                        resetVerticalMotion()
                        transitionTo(DetectionState.PUT_DOWN_CONFIRMED, now)
                    }
                    !pickupCandidate -> transitionTo(DetectionState.PUT_DOWN_CONFIRMED, now)
                    now - stateStartTime >= sensitivity.requiredPickupMillis -> {
                        SetCounterStore.increment(startRest = true)
                        transitionTo(DetectionState.RESTING, now)
                    }
                }
            }
            DetectionState.RESTING -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun applyLowPass(rawX: Float, rawY: Float, rawZ: Float) {
        val alpha = 0.12f
        if (!hasLastSample) {
            lowX = rawX
            lowY = rawY
            lowZ = rawZ
        } else {
            lowX += alpha * (rawX - lowX)
            lowY += alpha * (rawY - lowY)
            lowZ += alpha * (rawZ - lowZ)
        }
    }

    private fun updateVerticalDisplacement(
        now: Long,
        rawX: Float,
        rawY: Float,
        rawZ: Float,
        poseX: Float,
        poseY: Float,
        poseZ: Float,
    ) {
        if (!hasLastSample || lastSampleTimeMillis == 0L) return
        val dt = ((now - lastSampleTimeMillis) / 1000f).coerceIn(0.005f, 0.08f)
        val gravityMagnitude = sqrt(poseX * poseX + poseY * poseY + poseZ * poseZ).coerceAtLeast(0.1f)
        val unitX = poseX / gravityMagnitude
        val unitY = poseY / gravityMagnitude
        val unitZ = poseZ / gravityMagnitude
        val linearX = rawX - poseX
        val linearY = rawY - poseY
        val linearZ = rawZ - poseZ
        val verticalAcceleration = linearX * unitX + linearY * unitY + linearZ * unitZ
        if (abs(verticalAcceleration) < 0.25f) {
            verticalVelocity *= 0.82f
        } else {
            verticalVelocity = (verticalVelocity + verticalAcceleration * dt).coerceIn(-1.2f, 1.2f)
            verticalDisplacement = (verticalDisplacement + verticalVelocity * dt).coerceIn(-0.5f, 0.5f)
        }
    }

    private fun resetVerticalMotion() {
        verticalVelocity = 0f
        verticalDisplacement = 0f
    }

    private fun transitionTo(newState: DetectionState, now: Long) {
        if (state != newState) {
            state = newState
            stateStartTime = now
            if (SetCounterStore.debugStatusEnabled) {
                debugStatusText = "状态：$newState"
            }
        }
    }

    private fun resetDetectionState(now: Long = System.currentTimeMillis()) {
        state = DetectionState.IDLE
        stateStartTime = now
        confirmedPutDownTime = 0L
        lowX = 0f
        lowY = 0f
        lowZ = 0f
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        hasGravitySample = false
        debugStatusText = "状态：IDLE"
        lastRawX = 0f
        lastRawY = 0f
        lastRawZ = 0f
        lastSampleTimeMillis = 0L
        resetVerticalMotion()
        movementWindow.clear()
        hasLastSample = false
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SetCounterStore.init(this)
        if (SetCounterStore.overlayEnabled && Settings.canDrawOverlays(this)) {
            startService(Intent(this, CounterOverlayService::class.java))
        }
        if (SetCounterStore.fluidCloudEnabled) {
            FluidCloudNotifier.showOrUpdate(this)
        }

        setContent {
            SetsReporterTheme {
                DisposableEffect(SetCounterStore.autoCountEnabled) {
                    AutoCountSensorController.refresh(this@MainActivity)
                    onDispose { }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetReporterScreen(
                        hasAccelerometer = AutoCountSensorController.hasAccelerometer(this@MainActivity),
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AutoCountSensorController.setClientActive(this, "activity", true)
    }

    override fun onPause() {
        AutoCountSensorController.setClientActive(this, "activity", false)
        super.onPause()
    }
}

@Composable
fun SetReporterScreen(
    hasAccelerometer: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val count = SetCounterStore.count
    val autoEnabled = SetCounterStore.autoCountEnabled
    val overlayEnabled = SetCounterStore.overlayEnabled
    val fluidCloudEnabled = SetCounterStore.fluidCloudEnabled
    val debugStatusEnabled = SetCounterStore.debugStatusEnabled
    val debugStatusText = AutoCountSensorController.debugStatusText
    val restDurationSeconds = SetCounterStore.restDurationSeconds
    val sensitivity = SetCounterStore.sensitivity
    var showCustomRestDialog by remember { mutableStateOf(false) }
    var customRestText by remember { mutableStateOf(restDurationSeconds.takeIf { it > 0 }?.toString().orEmpty()) }
    val presetRestOptions = listOf(0, 30, 60, 90, 120, 180)
    val customRestOption = -1

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (debugStatusEnabled) {
            Text(
                text = debugStatusText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            )
        }
        Text(
            text = "Sets Reporter",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "已完成组数", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = count.toString(),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.Center) {
                    Button(onClick = { SetCounterStore.decrement() }) { Text("-1") }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { SetCounterStore.reset() }) { Text("清零") }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { SetCounterStore.increment() }) { Text("+1") }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        SettingRow(
            title = "自动计数",
            subtitle = if (hasAccelerometer) "手机放下后再拿起，组数自动 +1" else "当前设备没有加速度计",
            checked = autoEnabled,
            enabled = hasAccelerometer,
            onCheckedChange = {
                SetCounterStore.updateAutoCountEnabled(it)
                AutoCountSensorController.refresh(context)
            }
        )
        SettingRow(
            title = "状态显示",
            subtitle = "调试用：在页面顶部显示自动计数状态和判定数据",
            checked = debugStatusEnabled,
            onCheckedChange = { SetCounterStore.updateDebugStatusEnabled(it) },
        )
        SettingRow(
            title = "悬浮窗",
            subtitle = "圆形半透明计数球，点击后展开 +1、-1、清零",
            checked = overlayEnabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (Settings.canDrawOverlays(context)) {
                        SetCounterStore.updateOverlayEnabled(true)
                        context.startService(Intent(context, CounterOverlayService::class.java))
                        AutoCountSensorController.refresh(context)
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            "package:${context.packageName}".toUri()
                        )
                        context.startActivity(intent)
                    }
                } else {
                    SetCounterStore.updateOverlayEnabled(false)
                    context.stopService(Intent(context, CounterOverlayService::class.java))
                    AutoCountSensorController.refresh(context)
                }
            }
        )
        OptionRow(
            title = "组间休息时间",
            subtitle = "计数 +1 后暂停自动识别，悬浮窗显示倒计时",
            options = presetRestOptions + customRestOption,
            selected = if (restDurationSeconds in presetRestOptions) restDurationSeconds else customRestOption,
            label = {
                when (it) {
                    0 -> "关闭"
                    customRestOption -> "自定义"
                    else -> "${it}秒"
                }
            },
            onSelected = {
                if (it == customRestOption) {
                    customRestText = restDurationSeconds.takeIf { seconds -> seconds > 0 }?.toString().orEmpty()
                    showCustomRestDialog = true
                } else {
                    SetCounterStore.updateRestDurationSeconds(it)
                }
            },
        )
        if (showCustomRestDialog) {
            AlertDialog(
                onDismissRequest = { showCustomRestDialog = false },
                title = { Text("自定义休息时间") },
                text = {
                    OutlinedTextField(
                        value = customRestText,
                        onValueChange = { input ->
                            customRestText = input.filter { char -> char.isDigit() }.take(4)
                        },
                        label = { Text("秒") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val seconds = customRestText.toIntOrNull()?.coerceIn(0, 3600) ?: 0
                            SetCounterStore.updateRestDurationSeconds(seconds)
                            showCustomRestDialog = false
                        },
                    ) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCustomRestDialog = false }) {
                        Text("取消")
                    }
                },
            )
        }
        OptionRow(
            title = "自动计数灵敏度",
            subtitle = "低更稳，高更灵敏",
            options = AutoCountSensitivity.entries.toList(),
            selected = sensitivity,
            label = { it.label },
            onSelected = { SetCounterStore.updateSensitivity(it) }
        )
//        SettingRow(
//            title = "ColorOS 流体云",
//            subtitle = "兼容模式：通过常驻通知同步组数并提供相同操作",
//            checked = fluidCloudEnabled,
//            onCheckedChange = { enabled ->
//                if (enabled) {
//                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
//                        (context as? MainActivity)?.requestPermissions(
//                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
//                            1001
//                        )
//                    }
//                    SetCounterStore.updateFluidCloudEnabled(true)
//                    FluidCloudNotifier.showOrUpdate(context)
//                } else {
//                    SetCounterStore.updateFluidCloudEnabled(false)
//                    FluidCloudNotifier.cancel(context)
//                }
//            }
//        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun <T> OptionRow(
    title: String,
    subtitle: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(6.dp))
        options.chunked(4).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = spacedBy(6.dp),
            ) {
                rowOptions.forEach { option ->
                    val buttonModifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                    if (option == selected) {
                        Button(
                            onClick = { onSelected(option) },
                            modifier = buttonModifier,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(text = label(option), fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelected(option) },
                            modifier = buttonModifier,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text(text = label(option), fontSize = 12.sp)
                        }
                    }
                }
                repeat(4 - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

class CounterOverlayService : android.app.Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var countText: TextView? = null

    private var restText: TextView? = null
    private var restEndButton: TextView? = null
    private var expanded = false

    private val collapsedSize by lazy { dp(52) }
    private val touchSlop by lazy { dp(6) }
    private val updateListener = { updateCountText() }
    private val restTicker = object : Runnable {
        override fun run() {
            updateCountText()
            if (SetCounterStore.isResting) {
                overlayView?.postDelayed(this, 1000L)
            }
        }
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var lastTapTimeMillis = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        SetCounterStore.addListener(updateListener)
        AutoCountSensorController.setClientActive(this, "overlay", true)
        overlayView?.post(restTicker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlayView == null) showOverlay()
        SetCounterStore.updateOverlayEnabled(true)
        return START_STICKY
    }

    override fun onDestroy() {
        AutoCountSensorController.setClientActive(this, "overlay", false)
        overlayView?.removeCallbacks(restTicker)
        SetCounterStore.removeListener(updateListener)
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        SetCounterStore.updateOverlayEnabled(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = circleBackground()
        }
        countText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        restText = TextView(this).apply {
            textSize = 10f
            setTextColor(0xFFFFD54F.toInt())
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        restEndButton = TextView(this).apply {
            text = "长按结束休息"
            textSize = 10f
            setTextColor(0xFFFFD54F.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        container.addView(
            countText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        )
        container.addView(
            restText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                rightMargin = dp(7)
                topMargin = -dp(18)
            }
        )
        container.addView(restEndButton)
        container.setOnLongClickListener {
            SetCounterStore.endRest()
            AutoCountSensorController.refresh(this)
            updateCountText()
            true
        }
        container.setOnTouchListener { _, event -> handleTouch(event) }

        val params = WindowManager.LayoutParams(
            collapsedSize,
            collapsedSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - collapsedSize - dp(20)
            y = dp(120)
        }

        overlayView = container
        windowManager.addView(container, params)
        updateCountText()
    }

    private fun handleTouch(event: android.view.MotionEvent): Boolean {
        val view = overlayView ?: return false
        val params = view.layoutParams as WindowManager.LayoutParams
        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = params.x
                startY = params.y
                dragging = false
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    if (expanded) collapse()
                }
                if (dragging) {
                    params.x = (startX + dx).coerceIn(0, screenWidth() - params.width)
                    params.y = (startY + dy).coerceIn(0, screenHeight() - params.height)
                    windowManager.updateViewLayout(view, params)
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTimeMillis <= 350L) {
                        SetCounterStore.reset()
                        lastTapTimeMillis = 0L
                    } else {
                        SetCounterStore.increment()
                        lastTapTimeMillis = now
                    }
                    collapse()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private fun collapse() {
        expanded = false
        overlayView?.background = circleBackground()
        restEndButton?.visibility = if (SetCounterStore.isResting) View.VISIBLE else View.GONE
        countText?.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        updateOverlaySize(collapsedSize, collapsedSize)
    }

    private fun updateOverlaySize(width: Int, height: Int) {
        val view = overlayView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.width = width
        params.height = height
        params.x = params.x.coerceIn(0, screenWidth() - width)
        params.y = params.y.coerceIn(0, screenHeight() - height)
        windowManager.updateViewLayout(view, params)
    }

    private fun updateCountText() {
        countText?.text = SetCounterStore.count.toString()
        val remainingSeconds = ((SetCounterStore.remainingRestMillis() + 999L) / 1000L).toInt()
        if (remainingSeconds > 0) {
            overlayView?.removeCallbacks(restTicker)
            overlayView?.postDelayed(restTicker, 1000L)
            restText?.visibility = View.VISIBLE
            restText?.text = if (remainingSeconds >= 60) {
                "%d:%02d".format(remainingSeconds / 60, remainingSeconds % 60)
            } else {
                "${remainingSeconds}s"
            }
        } else {
            restText?.visibility = View.GONE
            restText?.text = ""
        }
        restEndButton?.visibility = if (SetCounterStore.isResting) View.VISIBLE else View.GONE
    }

    private fun circleBackground() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x88222222.toInt())
        setStroke(dp(1), 0x55FFFFFF)
    }

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

object FluidCloudNotifier {
    private const val CHANNEL_ID = "sets_reporter_fluid_cloud"
    private const val NOTIFICATION_ID = 2001
    private const val ACTION_INCREMENT = "com.example.setsreporter.action.INCREMENT"
    private const val ACTION_DECREMENT = "com.example.setsreporter.action.DECREMENT"
    private const val ACTION_RESET = "com.example.setsreporter.action.RESET"

    private var appContext: Context? = null
    private var listenerRegistered = false
    private val updateListener: () -> Unit = {
        val context = appContext
        if (context != null) {
            showOrUpdate(context)
        }
    }

    @SuppressLint("MissingPermission")
    fun showOrUpdate(context: Context) {
        val safeContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(safeContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        appContext = safeContext
        createChannel(safeContext)
        if (!listenerRegistered) {
            SetCounterStore.addListener(updateListener)
            listenerRegistered = true
        }

        val notification = NotificationCompat.Builder(safeContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("已完成组数：${SetCounterStore.count}")
            .setContentText("点击操作按钮可 +1、-1 或清零")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "-1", actionPendingIntent(safeContext, ACTION_DECREMENT, 1))
            .addAction(0, "清零", actionPendingIntent(safeContext, ACTION_RESET, 2))
            .addAction(0, "+1", actionPendingIntent(safeContext, ACTION_INCREMENT, 3))
            .build()

        NotificationManagerCompat.from(safeContext).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        if (listenerRegistered) {
            SetCounterStore.removeListener(updateListener)
            listenerRegistered = false
        }
        appContext = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    internal fun handleAction(context: Context, action: String?) {
        when (action) {
            ACTION_INCREMENT -> SetCounterStore.increment()
            ACTION_DECREMENT -> SetCounterStore.decrement()
            ACTION_RESET -> SetCounterStore.reset()
        }
        if (SetCounterStore.fluidCloudEnabled) showOrUpdate(context)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ColorOS 流体云",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "显示健身组数并提供快捷操作"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, FluidCloudActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class FluidCloudActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FluidCloudNotifier.handleAction(context.applicationContext, intent.action)
    }
}

@Preview(showBackground = true)
@Composable
fun SetReporterPreview() {
    SetsReporterTheme {
        SetReporterScreen(hasAccelerometer = true)
    }
}
