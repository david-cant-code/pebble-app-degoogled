package coredevices.util

import com.russhwolf.settings.Settings

enum class DeviceChoice {
    Watch,
    Index01,
    Both,
}

private const val ONBOARDING_DEVICE_KEY = "gravel.onboarding.device"

fun Settings.onboardingDeviceChoice(): DeviceChoice? = when (getStringOrNull(ONBOARDING_DEVICE_KEY)) {
    "watch" -> DeviceChoice.Watch
    "index01" -> DeviceChoice.Index01
    "both" -> DeviceChoice.Both
    else -> null
}

fun Settings.setOnboardingDeviceChoice(choice: DeviceChoice) {
    putString(
        ONBOARDING_DEVICE_KEY,
        when (choice) {
            DeviceChoice.Watch -> "watch"
            DeviceChoice.Index01 -> "index01"
            DeviceChoice.Both -> "both"
        },
    )
}

fun DeviceChoice.includesRing(): Boolean = this == DeviceChoice.Index01 || this == DeviceChoice.Both

/**
 * The open ring build always offers Add Index 01. The stub build has no
 * ring client, so the item stays off whatever the saved choice is.
 */
fun showAddIndexButton(ringClientPresent: Boolean): Boolean = ringClientPresent

/** The Index tab is present exactly when Enable Index Feed is on. */
fun showIndexFeedTab(enableIndex: Boolean): Boolean = enableIndex

/** Settings shows the row when this build has a ring client. */
fun indexFeedToggleVisible(ringClientPresent: Boolean): Boolean = ringClientPresent

/** Index 01 and Both turn the feed on during getting started. */
fun DeviceChoice.enablesIndexFeed(): Boolean = includesRing()

/** Saves the choice. Index 01 and Both turn the feed on; Watch leaves it as it is. */
fun Settings.recordOnboardingDeviceChoice(choice: DeviceChoice, enableIndexFeed: () -> Unit) {
    setOnboardingDeviceChoice(choice)
    if (choice.enablesIndexFeed()) enableIndexFeed()
}

/** Pairing from Devices promotes a watch-only or unset choice to Both. */
fun DeviceChoice?.afterRingPaired(): DeviceChoice = when (this) {
    DeviceChoice.Index01 -> DeviceChoice.Index01
    DeviceChoice.Both -> DeviceChoice.Both
    DeviceChoice.Watch, null -> DeviceChoice.Both
}
