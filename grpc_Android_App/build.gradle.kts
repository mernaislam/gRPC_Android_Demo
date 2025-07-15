// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false

    kotlin("jvm") version "1.9.25" apply false
    id("com.google.protobuf") version "0.9.4" apply false
    alias(libs.plugins.android.library) apply false
}