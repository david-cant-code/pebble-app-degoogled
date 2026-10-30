package com.anopticlabs.gravel.notebook

import android.os.Build

/** The Notebook runs from API 28; below it the tab is absent and no reminder is re-armed. */
val isNotebookSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
