package com.mocklocation.lab

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

internal fun Activity.setLocationScreenContent(view: View) {
    if (Build.VERSION.SDK_INT < 35) {
        setContentView(view)
        return
    }
    val left = view.paddingLeft
    val top = view.paddingTop
    val right = view.paddingRight
    val bottom = view.paddingBottom
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
    ViewCompat.setOnApplyWindowInsetsListener(view) { content, insets ->
        val occupied = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        content.setPadding(left + occupied.left, top + occupied.top,
            right + occupied.right, bottom + occupied.bottom)
        insets
    }
    setContentView(view)
    ViewCompat.requestApplyInsets(view)
}
