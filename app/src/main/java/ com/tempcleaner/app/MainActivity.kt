package com.tempcleaner.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Prefs {
    private fun sp(c: Context) =
        c.getSharedPreferences("tc_prefs", Context.MODE_PRIVATE)

    fun autoEnabled(c: Context): Boolean = sp(c).getBoolean("auto", false)
    fun setAutoEnabled(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("auto", v).apply()
    }

    fun intervalHours(c: Context): Int = sp(c).getInt("hours", 24)
    fun setIntervalHours(c: Context, v: Int) {
        sp(c).edit().putInt("hours", v).apply()
    }

    fun deepScan(c: Context): Boolean = sp(c).getBoolean("deep", false)
    fun setDeepScan(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("deep", v).apply()
    }

    fun lastRun(c: Context): Long = sp(c).getLong("last", 0L)
    fun lastFreed(c: Context): Long = sp(c).getLong("freed", 0L)
    fun totalFreed(c: Context): Long = sp(c).getLong("total", 0L)

    fun record(c: Context, freed: Long) {
        sp(c).edit()
            .putLong("last", System.currentTimeMillis())
            .putLong("freed", freed)
            .putLong("total", totalFreed(c) + freed)
            .apply()
    }
}

data class CleanResult(val bytes: Long, val files: Int)

object Cleaner {

    private val TEMP_EXT = setOf("tmp", "temp", "log", "dmp", "chk")

    private const val MIN_AGE_MS = 24L * 60 * 60 * 1000

    fun hasDeepAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                c, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun run(c: Context, deep: Boolean): CleanResult {
        var bytes = 0L
        var count = 0

        val dirs = mutableListOf<File>()
        dirs.add(c.cacheDir)
        c.externalCacheDirs.filterNotNull().forEach { dirs.add(it) }

        for (d in dirs) {
            val r = wipeContents(d)
            bytes += r.bytes
            count += r.files
        }

        if (deep) {
            val r = sweepStorage()
            bytes += r.bytes
            count += r.files
        }
        return CleanResult(bytes, count)
    }

    private fun wipeContents(dir: File): CleanResult {
        if (!dir.exists()) return CleanResult(0, 0)
        var b = 0L
        var n = 0
        try {
            dir.walkBottomUp().forEach { f ->
                if (f == dir) return@forEach
                if (f.isFile) {
                    val s = f.length()
                    if (f.delete()) {
                        b += s
                        n++
                    }
                } else {
                    f.delete()
                }
            }
        } catch (_: SecurityException) {
        }
        return CleanResult(b, n)
    }

    private fun sweepStorage(): CleanResult {
        val root = Environment.getExternalStorageDirectory()
        val now = System.currentTimeMillis()
        var b = 0L
        var n = 0
        try {
            root.walkTopDown()
                .onEnter { it.name != "Android" || it.parentFile != root }
                .forEach { f ->
                    if (!f.isFile) return@forEach
                    val name = f.name.lowercase()
                    val inThumbs = f.parentFile?.name == ".thumbnails"
                    val isTemp = name.substringAfterLast('.', "") in TEMP_EXT ||
                        name.startsWith(".thumbdata")
                    val oldEnough = now - f.lastModified() > MIN_AGE_MS
                    if (inThumbs || (isTemp && oldEnough)) {
                        val s = f.length()
                        if (f.delete()) {
                            b += s
                            n++
                        }
                    }
                }
        } catch (_: SecurityException) {
        }
        return CleanResult(b, n)
    }
}

class CleanWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        val deep = Prefs.deepScan(c) && Cleaner.hasDeepAccess(c)
        val r = Cleaner.run(c, deep)
        Prefs.record(c, r.bytes)
        return Result.success()
    }
}

object Scheduler {
    private const val NAME = "auto_clean"

    fun apply(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (!Prefs.autoEnabled(c)) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<CleanWorker>(
            Prefs.intervalHours(c).toLong(), TimeUnit.HOURS
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

val Teal = Color(0xFF2F6F6B)
val TealLight = Color(0xFF4F8F86)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8E2),
    onPrimaryContainer = Color(0xFF1F4F4C),
    secondary = Color(0xFF8FB9A8),
    background = Color(0xFFF3F7F5),
    onBackground = Color(0xFF1E2B29),
    surface = Color.White,
    onSurface = Color(0xFF1E2B29),
    surfaceVariant = Color(0xFFE4ECE9),
    onSurfaceVariant = Color(0xFF51615E),
    outline = Color(0xFFB9C7C3),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FC4BC),
    onPrimary = Color(0xFF00201E),
    primaryContainer = Color(0xFF1F4F4C),
    onPrimaryContainer = Color(0xFFD5E8E2),
    secondary = Color(0xFF9BC4B3),
    background = Color(0xFF0E1817),
    onBackground = Color(0xFFE2ECE9),
    surface = Color(0xFF16231F),
    onSurface = Color(0xFFE2ECE9),
    surfaceVariant = Color(0xFF22332F),
    onSurfaceVariant = Color(0xFFA9BAB6),
    outline = Color(0xFF3B4D49),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}

class MainActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)

    private val legacyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Scheduler.apply(this)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HomeScreen(tick = tick, onRequestAccess = ::requestAccess)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

private val intervals = listOf(
    6 to "كل 6 ساعات",
    12 to "كل 12 ساعة",
    24 to "يوميًا",
    72 to "كل 3 أيام",
    168 to "أسبوعيًا",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(tick: Int, onRequestAccess: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var auto by remember { mutableStateOf(Prefs.autoEnabled(ctx)) }
    var hours by remember { mutableIntStateOf(Prefs.intervalHours(ctx)) }
    var deep by remember { mutableStateOf(Prefs.deepScan(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var lastFreed by remember { mutableLongStateOf(Prefs.lastFreed(ctx)) }
    var total by remember { mutableLongStateOf(Prefs.totalFreed(ctx)) }
    var lastRun by remember { mutableLongStateOf(Prefs.lastRun(ctx)) }
    val hasAccess = remember(tick) { Cleaner.hasDeepAccess(ctx) }

    LaunchedEffect(tick) {
        lastFreed = Prefs.lastFreed(ctx)
        total = Prefs.totalFreed(ctx)
        lastRun = Prefs.lastRun(ctx)
    }

    val lastRunText = if (lastRun == 0L) "لم يتم بعد"
    else SimpleDateFormat("d MMM، h:mm a", Locale("ar")).format(Date(lastRun))

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    "منظّف",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "تنظيف الملفات المؤقتة تلقائيًا",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Teal, TealLight)))
                    .padding(24.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("إجمالي المساحة المحرَّرة", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    Text(
                        Formatter.formatFileSize(ctx, total),
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column {
                            Text("آخر تنظيف", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Text(lastRunText, color = Color.White, fontSize = 14.sp)
                        }
                        Column {
                            Text("المحرَّر آخر مرة", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Text(Formatter.formatFileSize(ctx, lastFreed), color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            Cleaner.run(ctx, deep && Cleaner.hasDeepAccess(ctx))
                        }
                        Prefs.record(ctx, r.bytes)
                        lastFreed = Prefs.lastFreed(ctx)
                        total = Prefs.totalFreed(ctx)
                        lastRun = Prefs.lastRun(ctx)
                        busy = false
                        Toast.makeText(
                            ctx,
                            "تم تحرير ${Formatter.formatFileSize(ctx, r.bytes)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("تنظيف الآن", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            SectionCard {
                SettingRow(
                    title = "التنظيف التلقائي",
                    subtitle = "يشتغل لوحده في الخلفية حسب المدة اللي تختارها",
                    checked = auto,
                    onChange = {
                        auto = it
                        Prefs.setAutoEnabled(ctx, it)
                        Scheduler.apply(ctx)
                    }
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "المدة بين كل تنظيف",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    intervals.forEach { (h, label) ->
                        FilterChip(
                            selected = hours == h,
                            enabled = auto,
                            onClick = {
                                hours = h
                                Prefs.setIntervalHours(ctx, h)
                                Scheduler.apply(ctx)
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            SectionCard {
                SettingRow(
                    title = "تنظيف عميق",
                    subtitle = "يمسح ملفات .tmp و .log والصور المصغّرة من التخزين (الأقدم من 24 ساعة)",
                    checked = deep,
                    onChange = {
                        deep = it
                        Prefs.setDeepScan(ctx, it)
                    }
                )
                if (deep && !hasAccess) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onRequestAccess,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("منح صلاحية الوصول للملفات")
                    }
                }
            }

            SectionCard {
                val serviceOn = remember(tick) { CacheCleanerService.instance != null }
                Text(
                    "تنظيف كاش كل التطبيقات",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "يفتح شاشة كل تطبيق ويضغط مسح الذاكرة المؤقتة تلقائيًا. محتاج تفعيل خدمة إمكانية الوصول.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (!serviceOn) {
                    OutlinedButton(
                        onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("تفعيل الخدمة")
                    }
                } else if (AppsCleanState.running) {
                    Text(
                        "جاري التنظيف: ${AppsCleanState.done} من ${AppsCleanState.total}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (AppsCleanState.total == 0) 0f
                            else AppsCleanState.done.toFloat() / AppsCleanState.total
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { CacheCleanerService.instance?.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("إيقاف")
                    }
                } else {
                    Button(
                        onClick = { CacheCleanerService.instance?.startCleaning() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("ابدأ تنظيف كاش التطبيقات")
                    }
                    if (AppsCleanState.cleaned > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "آخر مرة: تم تنظيف ${AppsCleanState.cleaned} تطبيق",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                "ملاحظة: أندرويد لا يسمح لأي تطبيق بمسح ملفات تطبيقات أخرى مباشرةً، لذلك " +
                    "التطبيق ينظّف ملفاته المؤقتة، ولو فعّلت التنظيف العميق يمسح الملفات " +
                    "المؤقتة المتروكة في التخزين. وتنظيف كاش كل التطبيقات يتم عن طريق خدمة إمكانية الوصول.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
