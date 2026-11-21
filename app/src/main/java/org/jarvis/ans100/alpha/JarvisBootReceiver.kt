package org.jarvis.ans100.alpha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores only previously user-enabled background presence or saved benchmark work. */
class JarvisBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        JarvisBackgroundService.restoreAfterBoot(context.applicationContext)
    }
}
