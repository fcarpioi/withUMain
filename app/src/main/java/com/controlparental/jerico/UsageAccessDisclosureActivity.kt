package com.controlparental.jerico

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class UsageAccessDisclosureActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_usage_title)
            .setMessage(R.string.disclosure_usage_message)
            .setCancelable(false)
            .setPositiveButton(R.string.disclosure_open_settings) { _, _ ->
                ComplianceDisclosures.openUsageAccessSettings(this)
                finish()
            }
            .setNegativeButton(R.string.disclosure_decline) { _, _ ->
                finish()
            }
            .show()
    }
}
