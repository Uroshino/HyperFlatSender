plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hyperflatsender"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hyperflatsender"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true   // strip unreferenced resources (Compose/datastore/nav/lifecycle); needs minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign release with the auto-generated debug key so `installRelease` works for local
            // perf testing without a keystore. Replace with a real signing config before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // generates BuildConfig.VERSION_NAME, shown in Settings
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.flatbuffers.java)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)

    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    debugImplementation(libs.compose.ui.tooling.preview)
}
