package com.adamgreloch.cryptjournal

import android.content.Intent
import androidx.core.app.ActivityCompat.startActivityForResult
import androidx.fragment.app.FragmentActivity

const val CREATE_FILE = 1

fun createFile(activity: FragmentActivity) {
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/gpg-encrypted"
        putExtra(Intent.EXTRA_TITLE, "file")
    }
    startActivityForResult(activity, intent, CREATE_FILE, null)
}
