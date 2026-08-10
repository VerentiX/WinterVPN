package com.v2ray.ang.util

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in failure breadcrumbs: RAM ring + sparse disk writes on lifecycle/errors only.
 * No continuous logcat capture, no timers — safe for battery when left off (default).
 */
object FailureLogRecorder {
    private const val DIR_NAME = "failure_logs"
    private const val BREADCRUMB_FILE = "breadcrumbs.log"
    private const val FLAG_FILE = "session_unclean"
    private const val MAX_MEMORY_LINES = 140
    private const val MAX_BREADCRUMB_BYTES = 96 * 1024
    private const val MAX_REPORTS = 5

    private val memory = ConcurrentLinkedDeque<String>()
    private val handlerInstalled = AtomicBoolean(false)
    private val diskLock = Any()

    @Volatile
    private var app: Application? = null

    @Volatile
    private var enabledCache: Boolean? = null

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun init(application: Application) {
        app = application
        if (!isEnabled()) return
        installHandlerIfNeeded()
        recoverUncleanSession("startup")
    }

    fun refreshEnabled() {
        enabledCache = null
        if (isEnabled()) {
            installHandlerIfNeeded()
            app?.let { recoverUncleanSession("pref_enabled") }
        }
    }

    fun isEnabled(): Boolean {
        enabledCache?.let { return it }
        val value = MmkvManager.decodeSettingsBool(AppConfig.PREF_FAILURE_LOG_ENABLED, false)
        enabledCache = value
        return value
    }

    /** Lightweight breadcrumb. Disk I/O only for significant lifecycle/error lines. */
    fun breadcrumb(message: String, forceDisk: Boolean = false) {
        if (!isEnabled()) return
        val line = "${timestamp()} ${processTag()} $message"
        memory.addFirst(line)
        while (memory.size > MAX_MEMORY_LINES) {
            memory.pollLast()
        }
        if (forceDisk || isSignificant(message)) {
            appendDisk(line)
        }
    }

    fun markSessionActive(reason: String = "core_started") {
        if (!isEnabled()) return
        writeUncleanFlag(true)
        breadcrumb("SESSION_ACTIVE reason=$reason", forceDisk = true)
    }

    fun markSessionClean(reason: String = "core_stopped") {
        if (!isEnabled()) return
        breadcrumb("SESSION_CLEAN reason=$reason", forceDisk = true)
        writeUncleanFlag(false)
    }

    fun recordFailure(reason: String, throwable: Throwable? = null) {
        if (!isEnabled()) return
        breadcrumb("FAILURE reason=$reason", forceDisk = true)
        writeReport(reason, throwable)
        writeUncleanFlag(false)
    }

    fun reportCount(context: Context = requireContext()): Int =
        reportFiles(context).size

    fun latestReportText(context: Context = requireContext()): String {
        val latest = reportFiles(context).firstOrNull()
            ?: return "Нет сохранённых логов падений."
        return buildString {
            appendLine(latest.name)
            appendLine(latest.readText())
        }
    }

    fun exportLatest(context: Context): File? {
        val latest = reportFiles(context).firstOrNull() ?: return null
        val shareDir = File(context.cacheDir, "shared_logs").apply { mkdirs() }
        shareDir.listFiles()?.forEach { it.delete() }
        val out = File(shareDir, latest.name)
        latest.copyTo(out, overwrite = true)
        return out
    }

    fun clear(context: Context = requireContext()) {
        synchronized(diskLock) {
            dir(context).listFiles()?.forEach { it.delete() }
        }
        memory.clear()
    }

    fun summary(context: Context = requireContext()): String {
        val reports = reportFiles(context)
        val breadcrumbSize = breadcrumbFile(context).takeIf { it.exists() }?.length() ?: 0L
        return buildString {
            appendLine("Отчётов: ${reports.size} (макс. $MAX_REPORTS)")
            appendLine("Хлебные крошки: ${breadcrumbSize} байт")
            if (reports.isEmpty()) {
                appendLine()
                append("Падений ещё не записано. При сбое рядом сохранятся последние события VPN.")
            } else {
                appendLine()
                reports.take(5).forEach { file ->
                    appendLine("• ${file.name} (${file.length()} байт)")
                }
            }
        }
    }

    private fun installHandlerIfNeeded() {
        if (!handlerInstalled.compareAndSet(false, true)) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (isEnabled()) {
                    recordFailure("uncaught:${thread.name}", throwable)
                }
            } catch (_: Exception) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun recoverUncleanSession(source: String) {
        val context = app ?: return
        if (!uncleanFlag(context).exists()) return
        breadcrumb("RECOVER_UNCLEAN_SESSION source=$source", forceDisk = true)
        writeReport("native_or_killed_process", detail = "Previous VPN session ended without clean stop")
        writeUncleanFlag(false)
    }

    private fun isSignificant(message: String): Boolean {
        val m = message.lowercase(Locale.US)
        if (m.contains("probe failed") || m.contains("probeinterval") || m.contains("screen=")) {
            return false
        }
        if (m.contains("network capabilities=")) {
            return false
        }
        return m.contains("session_") ||
            m.contains("startcore") ||
            m.contains("stopcore") ||
            m.contains("service ") ||
            m.contains("reconnect") ||
            m.contains("reload") ||
            m.contains("transport changed") ||
            m.contains("network lost") ||
            m.contains("network blocked") ||
            m.contains("recreating tun") ||
            m.contains("failure") ||
            m.contains("failed") ||
            m.contains("exception") ||
            m.contains("error") ||
            m.contains("revoke") ||
            m.contains("destroyed") ||
            m.contains("daemon_unreachable") ||
            m.contains("hard recovery") ||
            m.contains("rolling back")
    }

    private fun writeReport(reason: String, throwable: Throwable? = null, detail: String? = null) {
        val context = app ?: return
        synchronized(diskLock) {
            val dir = dir(context).apply { mkdirs() }
            val name = "failure_${fileTimestamp()}.txt"
            val report = File(dir, name)
            val body = buildString {
                appendLine("Winter failure log")
                appendLine("time=${timestamp()}")
                appendLine("reason=$reason")
                appendLine("process=${processTag()}")
                appendLine("pid=${Process.myPid()}")
                appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("flavor=${BuildConfig.FLAVOR}")
                appendLine("sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
                if (!detail.isNullOrBlank()) {
                    appendLine("detail=$detail")
                }
                if (throwable != null) {
                    appendLine()
                    appendLine("--- exception ---")
                    appendLine(stackTrace(throwable))
                }
                appendLine()
                appendLine("--- recent events (newest first) ---")
                memory.forEach { appendLine(it) }
                appendLine()
                appendLine("--- breadcrumbs on disk ---")
                val crumbs = breadcrumbFile(context)
                if (crumbs.exists()) {
                    append(crumbs.readText())
                } else {
                    appendLine("(empty)")
                }
            }
            report.writeText(body)
            trimReports(dir)
        }
    }

    private fun appendDisk(line: String) {
        val context = app ?: return
        synchronized(diskLock) {
            val file = breadcrumbFile(context).also { it.parentFile?.mkdirs() }
            file.appendText(line + "\n")
            if (file.length() > MAX_BREADCRUMB_BYTES) {
                val keep = file.readText().takeLast(MAX_BREADCRUMB_BYTES / 2)
                file.writeText(keep)
            }
        }
    }

    private fun writeUncleanFlag(unclean: Boolean) {
        val context = app ?: return
        synchronized(diskLock) {
            val flag = uncleanFlag(context).also { it.parentFile?.mkdirs() }
            if (unclean) {
                flag.writeText(timestamp())
            } else if (flag.exists()) {
                flag.delete()
            }
        }
    }

    private fun reportFiles(context: Context): List<File> {
        val dir = dir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("failure_") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    private fun trimReports(dir: File) {
        val reports = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("failure_") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        reports.drop(MAX_REPORTS).forEach { it.delete() }
    }

    private fun dir(context: Context) = File(context.filesDir, DIR_NAME)
    private fun breadcrumbFile(context: Context) = File(dir(context), BREADCRUMB_FILE)
    private fun uncleanFlag(context: Context) = File(dir(context), FLAG_FILE)

    private fun requireContext(): Context =
        app ?: error("FailureLogRecorder not initialized")

    private fun processTag(): String {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            app?.packageName ?: "unknown"
        }
        return when {
            name.endsWith(":RunSoLibV2RayDaemon") -> "vpn"
            name.endsWith(":bg") -> "bg"
            else -> "ui"
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun stackTrace(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
