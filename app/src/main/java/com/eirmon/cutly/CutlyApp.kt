package com.eirmon.cutly

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Exists for one thing: keeping the last crash on disk so a user can paste it into a bug report.
 *
 * No crash reporting service, because the app's promise is that nothing leaves the phone unless
 * the user sends it. The Settings screen offers the log to the clipboard; the user does the rest.
 */
class CutlyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                crashFile(this).writeText(
                    buildString {
                        append("Cutly ").append(BuildConfig.VERSION_NAME).append(" (")
                        append(BuildConfig.VERSION_CODE).append(") on thread ").append(thread.name)
                        append('\n')
                        append(StringWriter().also { throwable.printStackTrace(PrintWriter(it)) })
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private fun crashFile(context: Context) = File(context.filesDir, "last-crash.txt")

        /** The last crash report, or null when there has never been one. */
        fun lastCrash(context: Context): String? =
            crashFile(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }
}
