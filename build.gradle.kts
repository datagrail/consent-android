// Top-level build file
plugins {
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20" apply false
    id("org.jlleitschuh.gradle.ktlint") version "11.6.1" apply false
    id("com.vanniktech.maven.publish") version "0.28.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
