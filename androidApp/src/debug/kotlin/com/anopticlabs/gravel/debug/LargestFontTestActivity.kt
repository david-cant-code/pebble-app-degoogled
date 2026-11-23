package com.anopticlabs.gravel.debug

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity

/** The font scale the drawn-text font check draws at. */
const val LARGEST_FONT_SCALE = 2f

/** The drawn-text tests' host activity at [LARGEST_FONT_SCALE], dialogs opened from it included. */
class LargestFontTestActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(Configuration().apply { fontScale = LARGEST_FONT_SCALE })
    }
}
