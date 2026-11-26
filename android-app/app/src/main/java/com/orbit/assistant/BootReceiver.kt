package com.orbit.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        runCatching { context.startForegroundService(Intent(context, AgentService::class.java)) }
        runCatching { context.startForegroundService(Intent(context, DiyaWakeService::class.java)) }
    }
}
