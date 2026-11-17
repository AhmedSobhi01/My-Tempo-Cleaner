package com.tempcleaner.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AppsCleanState {
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var cleaned by mutableIntStateOf(0)
}

class CacheCleanerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<String> = emptyList()
    private var index = 0
    private var attempts = 0
    private var openedStorage = false
    private var cleanedThis = false
    private var cancelled = false

    companion object {
        @Volatile
        var instance: CacheCleanerService? = null

        private val CACHE = listOf(
            "clear cache",
            "مسح الذاكرة المؤقتة",
            "مسح ذاكرة التخزين المؤقت",
            "مسح التخزين المؤقت",
            "مسح الكاش",
            "حذف الذاكرة المؤقتة"
        )

        private val STORAGE = listOf(
            "storage & cache",
            "storage and cache",
            "storage",
            "التخزين والذاكرة المؤقتة",
            "مساحة التخزين",
            "التخزين"
        )

        private val DANGER = listOf(
            "clear storage", "clear data", "delete", "uninstall", "force stop",
            "مسح البيانات", "مسح التخزين", "مسح مساحة", "حذف",
            "إلغاء التثبيت", "فرض الإيقاف", "إزالة"
        )
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        cancel()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun startCleaning() {
        if (AppsCleanState.running) return
        val pm = packageManager
        val pkgs = pm.getInstalledApplications(0)
            .map { it.packageName }
            .filter { it != packageName && pm.getLaunchIntentForPackage(it) != null }
            .sorted()
        if (pkgs.isEmpty()) return
        queue = pkgs
        index = 0
        cancelled = false
        AppsCleanState.total = pkgs.size
        AppsCleanState.done = 0
        AppsCleanState.cleaned = 0
        AppsCleanState.running = true
        nextApp()
    }

    fun cancel() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        AppsCleanState.running = false
    }

    private fun nextApp() {
        if (cancelled || index >= queue.size) {
            stopRun()
            return
        }
        attempts = 0
        openedStorage = false
        cleanedThis = false
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + queue[index])
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            advance()
            return
        }
        handler.postDelayed({ tick() }, 1200L)
    }

    private fun tick() {
        if (cancelled) return
        attempts++
        val root = rootInActiveWindow
        val inSettings = root?.packageName?.toString()
            ?.contains("settings", ignoreCase = true) == true
        if (root != null && inSettings) {
            if (clickFirst(root, CACHE)) {
                cleanedThis = true
            } else if (!openedStorage && clickFirst(root, STORAGE)) {
                openedStorage = true
            }
        }
        if (cleanedThis) {
            AppsCleanState.cleaned++
            handler.postDelayed({ leave(3) }, 700L)
            return
        }
        if (attempts >= 8) {
            leave(3)
            return
        }
        handler.postDelayed({ tick() }, 900L)
    }

    private fun leave(times: Int) {
        if (cancelled) return
        if (times == 0) {
            advance()
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ leave(times - 1) }, 450L)
    }

    private fun advance() {
        index++
        AppsCleanState.done = index
        nextApp()
    }

    private fun stopRun() {
        AppsCleanState.running = false
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
        }
    }

    private fun isDanger(t: String): Boolean {
        if (t.contains("cache") || t.contains("مؤقت") || t.contains("كاش")) return false
        return DANGER.any { t.contains(it) }
    }

    private fun clickFirst(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
            for (n in nodes) {
                val text = (n.text ?: n.contentDescription)?.toString()?.lowercase() ?: continue
                if (isDanger(text)) continue
                var target: AccessibilityNodeInfo? = n
                var depth = 0
                while (target != null && !target.isClickable && depth < 4) {
                    target = target.parent
                    depth++
                }
                if (target != null && target.isClickable && target.isEnabled) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
            }
        }
        return false
    }
}
