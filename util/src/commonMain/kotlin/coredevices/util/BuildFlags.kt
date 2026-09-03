package coredevices.util

/**
 * True only in a debuggable build of the app. Gate for test hooks that
 * must never act in a release: the settings that drive them are only
 * offered when this is true, and the code that honours them checks it
 * again, because debug and release installs share an application id and
 * a persisted debug flag could otherwise outlive the build that set it.
 * Fails closed: any doubt reads as "not a debug build".
 */
expect fun isDebugBuild(): Boolean
