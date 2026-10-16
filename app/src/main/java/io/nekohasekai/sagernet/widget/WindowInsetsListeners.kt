package io.nekohasekai.sagernet.widget

import android.view.View
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.R

object ListListener : OnApplyWindowInsetsListener {
    override fun onApplyWindowInsets(view: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        var initialPaddingBottom = view.getTag(R.id.tag_initial_bottom_padding) as? Int
        if (initialPaddingBottom == null) {
            initialPaddingBottom = view.paddingBottom
            view.setTag(R.id.tag_initial_bottom_padding, initialPaddingBottom)
        }
        val bottomInset = insets.getInsets(
            WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
        ).bottom
        view.updatePadding(bottom = initialPaddingBottom + bottomInset)
        return insets
    }
}