package coredevices.coreapp.ui.screens

import coredevices.util.DeviceChoice

/** Stub builds pass false and offer only a watch. The open ring build passes true. */
fun visibleDeviceChoices(ringAvailable: Boolean): List<DeviceChoice> =
    if (ringAvailable) DeviceChoice.entries else listOf(DeviceChoice.Watch)

fun stageAfterDeviceChoice(choice: DeviceChoice): OnboardingStage = when (choice) {
    DeviceChoice.Watch, DeviceChoice.Both -> OnboardingStage.Permissions
    DeviceChoice.Index01 -> OnboardingStage.RingPermission
}

fun stageAfterPermissions(choice: DeviceChoice?): OnboardingStage = when (choice) {
    DeviceChoice.Index01 -> OnboardingStage.RingSetup
    else -> OnboardingStage.WatchappPrivacy
}

fun stageAfterWatchPrivacy(choice: DeviceChoice?): OnboardingStage = when (choice) {
    DeviceChoice.Both -> OnboardingStage.RingSetup
    else -> OnboardingStage.Done
}
