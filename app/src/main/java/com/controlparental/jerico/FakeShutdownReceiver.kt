package com.controlparental.jerico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent


class FakeShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_SHUTDOWN == intent.action) {
            val fakeShutdownIntent = Intent(context, FakeShutdownActivity::class.java)
            fakeShutdownIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(fakeShutdownIntent)
        }
    }
}

