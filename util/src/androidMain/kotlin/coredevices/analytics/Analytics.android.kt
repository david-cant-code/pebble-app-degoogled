package coredevices.analytics

import PlatformContext

// This fork ships no analytics backend: Mixpanel is removed entirely, and the
// AnalyticsBackend consumers terminate here so upstream call sites stay wired.
fun createAndroidAnalytics(platformContext: PlatformContext): AnalyticsBackend = NoOpAnalyticsBackend

object NoOpAnalyticsBackend : AnalyticsBackend {
    override fun logEvent(name: String, parameters: Map<String, Any>?) {}

    override fun addGlobalProperty(name: String, value: String?) {}

    override fun setEnabled(enabled: Boolean) {}
}
