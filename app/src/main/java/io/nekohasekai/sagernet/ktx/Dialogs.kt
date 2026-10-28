package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Context.alert(text: String): AlertDialog {
    return MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .create()
}

fun Fragment.alert(text: String) = requireContext().alert(text)

fun AlertDialog.tryToShow() {
    try {
        val activity = context.findActivity() ?: return
        if (!activity.isFinishing && !activity.isDestroyed) {
            show()
        }
    } catch (e: Exception) {
        Logs.e(e)
    }
}
