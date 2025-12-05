// app/build.gradle.kts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.zed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.zed"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        viewBinding = true
    }

    // ✅ THIS IS THE FIX
    packaging {
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.excludes += "META-INF/LICENSE"
        resources.excludes += "META-INF/LICENSE.txt"
        resources.excludes += "META-INF/NOTICE"
        resources.excludes += "META-INF/NOTICE.txt"
        resources.excludes += "META-INF/google-api-client.properties"

        // ADD THIS LINE TO SOLVE THE DUPLICATE FILE ERROR
        resources.excludes += "META-INF/INDEX.LIST"
    }
}

dependencies {

    // --- AndroidX and UI Libraries ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // --- Networking and Image Loading ---
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.coil)
    implementation(libs.volley)

    // --- ML Kit and Camera ---
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // --- Concurrency ---
    implementation(libs.kotlinx.coroutines.android)

    // --- Firebase and Google Services ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.google.play.services.auth)

    // --- Charting Library ---
    implementation(libs.mpandroidchart)

    // =================================================================================
    //  ✅ START: FINAL, COMPLETE GOOGLE API DEPENDENCIES
    // =================================================================================

    implementation(libs.google.api.services.sheets)
    implementation(libs.google.api.services.drive)
    implementation(libs.google.api.client.android)
    implementation(libs.google.http.client.gson)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.core.animation)
    implementation(libs.play.services.mlkit.barcode.scanning)
    implementation(libs.object1.detection)
    // ✅ ADD THIS LINE - THIS IS THE FIX FOR THE 'extensions' ERROR
    //implementation(libs.google.api.client.googleapis.extensions)

    // =================================================================================
    //  ✅ END: GOOGLE API LIBRARIES
    // =================================================================================

    // --- Testing Libraries ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
