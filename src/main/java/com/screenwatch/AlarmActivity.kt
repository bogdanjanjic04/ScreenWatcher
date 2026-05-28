package com.screenwatch

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager

/**
 * Invisible activity launched whenever the alarm is active.
 * Has no UI — its only job is to intercept the volume-down key and stop the alarm.
 * Also finishes itself if the alarm is dismissed via the notification.
 */
class AlarmActivity : Activity() {

    companion object {
        const val ACTION_ALARM_DISMISSED = "com.screenwatch.ALARM_DISMISSED"
    }

    // Listens for the service telling us the alarm was stopped externally
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show even on the lock screen so the volume button works when phone is locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Fully transparent — nothing visible to the user
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // Listen for external dismiss (notification button, app button, etc.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, IntentFilter(ACTION_ALARM_DISMISSED),
                RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dismissReceiver, IntentFilter(ACTION_ALARM_DISMISSED))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                stopAlarmAndFinish()
            }
            // Consume both DOWN and UP so system doesn't also lower the volume
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun stopAlarmAndFinish() {
        val i = Intent(this, ScreenMonitorService::class.java).apply {
            action = ScreenMonitorService.ACTION_STOP_ALARM
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
        finish()
    }

    override fun onDestroy() {
        try { unregisterReceiver(dismissReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
