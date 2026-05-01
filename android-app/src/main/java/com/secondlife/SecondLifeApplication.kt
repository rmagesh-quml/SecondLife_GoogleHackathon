package com.secondlife

import android.app.Application
import android.util.Log
import com.secondlife.inference.InferenceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Application singleton — owns the ONE InferenceSession for the process lifetime.
 *
 * Why singleton? The LiteRT-LM Engine is 3.65 GB loaded into RAM.
 * If InferenceSession lived in the ViewModel it would be recreated on every
 * config change (screen rotation, etc.) → double-load → OOM crash.
 * Placing it here means it is created exactly once and never destroyed.
 */
class SecondLifeApplication : Application() {

    /** Shared by all Activities and ViewModels. Created once, never recreated. */
    lateinit var inferenceSession: InferenceSession
        private set

    // Long-lived scope tied to application lifetime, not any individual ViewModel.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ── 1. Log the previous crash (if any) so we can diagnose on next run ──
        logPreviousCrash()

        // ── 2. Install a crash handler that writes the stack trace to a file ───
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SECONDLIFE_CRASH", "UNCAUGHT EXCEPTION on thread $thread", throwable)
            runCatching {
                File(filesDir, "crash_log.txt")
                    .writeText("${System.currentTimeMillis()}\n${throwable.stackTraceToString()}")
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ── 3. Create session and start loading the model NOW on IO thread ─────
        inferenceSession = InferenceSession(applicationContext, resolveModelPath())
        appScope.launch {
            inferenceSession.initModel()
        }
    }

    private fun logPreviousCrash() {
        val crashFile = File(filesDir, "crash_log.txt")
        if (crashFile.exists()) {
            runCatching {
                val text = crashFile.readText()
                if (text.isNotBlank()) {
                    Log.e("SECONDLIFE_CRASH", "=== PREVIOUS SESSION CRASH ===\n$text")
                }
            }
            crashFile.delete() // Consume it so we don't re-log on every cold start
        }
    }

    private fun resolveModelPath(): String {
        val candidates = listOf(
            "/data/local/tmp/gemma-4-E4B-it.litertlm",
            "/sdcard/Download/models/gemma-4-E4B-it.litertlm",
            "/storage/emulated/0/Download/models/gemma-4-E4B-it.litertlm",
            "${filesDir.absolutePath}/gemma-4-E4B-it.litertlm",
        )
        return candidates.firstOrNull { File(it).exists() }
            ?: "${filesDir.absolutePath}/gemma-4-E4B-it.litertlm"
    }
}
