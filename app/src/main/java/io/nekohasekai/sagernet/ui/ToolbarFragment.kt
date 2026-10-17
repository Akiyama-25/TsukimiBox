package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.AppBarLayout
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
        toolbar.setNavigationOnClickListener {
            (activity as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        view.findViewById<AppBarLayout>(R.id.appbar)?.let { appbar ->
            ViewCompat.setOnApplyWindowInsetsListener(appbar) { v, insets ->
                var initialPaddingTop = v.getTag(R.id.tag_initial_top_padding) as? Int
                if (initialPaddingTop == null) {
                    initialPaddingTop = v.paddingTop
                    v.setTag(R.id.tag_initial_top_padding, initialPaddingTop)
                }
                val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.updatePadding(top = initialPaddingTop + top)
                insets
            }
        }
        ViewCompat.requestApplyInsets(view)
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
