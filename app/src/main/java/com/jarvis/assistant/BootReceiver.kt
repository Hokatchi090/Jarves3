package com.jarvis.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Re-schedule any persisted reminders here if you add
            // storage (e.g. SharedPreferences or Room) for them later.
        }
    }
}
