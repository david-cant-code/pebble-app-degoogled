# Keep class names and all members for the packets package
-keepnames class io.rebble.libpebblecommon.packets.** { *; }
-keep class io.rebble.libpebblecommon.notification.NotificationDecision { *; }

# Ktor
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Gravel: overrides a hidden framework callback that nothing in the app calls, so R8 would drop or rename it
-keepclassmembers class io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerCallback {
    public void onConnectionUpdated(android.bluetooth.BluetoothDevice, int, int, int, int);
}
