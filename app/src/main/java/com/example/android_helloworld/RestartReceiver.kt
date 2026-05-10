package com.example.android_helloworld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("RestartReceiver", "Received restart broadcast — starting RestartService.")
        val service = Intent(context, RestartService::class.java)
        context.startForegroundService(service)
    }
}