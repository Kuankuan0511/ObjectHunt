import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.aai.steel.objecthunt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aai.steel.objecthunt"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Load API key from local.properties (not committed to git)
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { stream ->
                localProperties.load(stream)
            }
        }
        val museApiKey = localProperties.getProperty("muse.api.key", "")
        val museApiModel = localProperties.getProperty("muse.api.model", "muse-spark-1.1")
        
        // Create BuildConfig fields
        buildConfigField("String", "MUSE_API_KEY", "\"$museApiKey\"")
        buildConfigField("String", "MUSE_API_MODEL", "\"$museApiModel\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true  // Enable BuildConfig generation
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // ML Kit Image Labeling (keeping for fallback, optional)
    implementation(libs.mlkit.image.labeling)
    
    // HTTP Client for Model API
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // Retrofit for type-safe HTTP API
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)
    
    // Coroutines for async operations
    implementation(libs.kotlinx.coroutines.android)
    
    // Location - for getting current city
    implementation(libs.play.services.location)

    // Room - local database for saved pigeons (max 20)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Hilt - dependency injection
    implementation(libs.hilt.android)

    // Navigation - for saved list screen
    implementation(libs.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // KSP - Room + Hilt compilers
    ksp(libs.room.compiler)
    ksp(libs.hilt.compiler)
    // Force javapoet to fix hiltAggregateDepsDebug NoSuchMethodError canonicalName()
    ksp("com.squareup:javapoet:1.13.0")
}

// Fix javapoet version conflict: Room + Hilt both bring javapoet, Gradle picks older without canonicalName()
configurations.configureEach {
    resolutionStrategy {
        force("com.squareup:javapoet:1.13.0")
    }
}
