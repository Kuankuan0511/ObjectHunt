// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
}

// FIX: hiltAggregateDepsDebug is a Gradle plugin task that runs on the buildscript classpath,
// not KSP or implementation classpath. It needs JavaPoet 1.13.0 or newer.
// Adding ksp("javapoet") or implementation("javapoet") doesn't affect plugin classpath.
buildscript {
    dependencies {
        classpath("com.squareup:javapoet:1.13.0")
    }
}
