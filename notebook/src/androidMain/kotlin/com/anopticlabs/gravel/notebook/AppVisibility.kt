package com.anopticlabs.gravel.notebook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Whether any of the app's activities is started, and resumed; registered with the Application before any activity runs. */
class AppVisibility : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)
    private val resumed = AtomicInteger(0)
    private val resumedListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val backgroundedListeners = CopyOnWriteArrayList<() -> Unit>()

    val isBackgrounded: Boolean get() = started.get() == 0

    val isResumed: Boolean get() = resumed.get() > 0

    /** Calls [listener] with [isResumed] now, then on the main thread each time it changes. */
    fun addResumedListener(listener: (Boolean) -> Unit) {
        resumedListeners += listener
        listener(isResumed)
    }

    /** Calls [listener] on the main thread each time no activity is left started. */
    fun addBackgroundedListener(listener: () -> Unit) {
        backgroundedListeners += listener
    }

    override fun onActivityStarted(activity: Activity) = onStarted()

    override fun onActivityStopped(activity: Activity) = onStopped()

    override fun onActivityResumed(activity: Activity) = onResumed()

    override fun onActivityPaused(activity: Activity) = onPaused()

    internal fun onStarted() {
        started.incrementAndGet()
    }

    internal fun onStopped() {
        if (started.getAndUpdate { (it - 1).coerceAtLeast(0) } == 1) backgroundedListeners.forEach { it() }
    }

    internal fun onResumed() {
        if (resumed.incrementAndGet() == 1) resumedListeners.forEach { it(true) }
    }

    internal fun onPaused() {
        if (resumed.getAndUpdate { (it - 1).coerceAtLeast(0) } == 1) resumedListeners.forEach { it(false) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
