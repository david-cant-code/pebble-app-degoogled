package dev.gitlive.firebase

/**
 * Fork stub. This module replaces the dev.gitlive Firebase KMP artifacts
 * (firebase-auth, firebase-firestore) with inert implementations under the
 * same fully-qualified names, so upstream call sites compile unchanged while
 * no Firebase or GMS SDK exists in the dependency graph.
 *
 * The app never calls Firebase.initialize (the native SDK self-initialized
 * through the google-services Gradle plugin, which the fork also removes),
 * and upstream code only ever uses [Firebase] as the receiver for the
 * `auth` / `firestore` accessor extensions, so an empty object is the whole
 * surface needed here.
 */
object Firebase
