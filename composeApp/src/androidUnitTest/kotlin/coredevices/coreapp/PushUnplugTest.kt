package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PushUnplugTest {
    // The FCM push stack (kmpnotifier and its hard firebase-messaging
    // dependency) was deleted rather than seamed: push cannot degrade
    // gracefully, it either registers a token with Google or does not
    // exist. These probes fail the moment either artifact returns to the
    // app classpath, whatever the source tree looks like.
    @Test
    fun kmpnotifierIsAbsentFromTheClasspath() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.mmk.kmpnotifier.notification.NotifierManager")
        }
    }

    @Test
    fun firebaseMessagingIsAbsentFromTheClasspath() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.google.firebase.messaging.FirebaseMessaging")
        }
    }
}
