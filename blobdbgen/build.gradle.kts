import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":blobannotations"))
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.google.devtools.ksp:symbol-processing-api:2.1.20-1.0.32")
    implementation("com.squareup:kotlinpoet:2.1.0")
    implementation("com.squareup:kotlinpoet-ksp:2.1.0")
}

// Fork: no jvmToolchain pin (see blobannotations/build.gradle.kts). Kotlin and
// Java compile to the same 17 target so the JVM-target consistency check passes
// on any JDK 17+.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.valueOf("JVM_${libs.versions.jvm.toolchain.get()}"))
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvm.toolchain.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.toolchain.get())
}
