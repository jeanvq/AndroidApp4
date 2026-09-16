plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.androidapp4"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.androidapp4"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Media3 ExoPlayer handles remote podcast audio streams more reliably than MediaPlayer.
    implementation("androidx.media3:media3-exoplayer:1.8.0")

    // Coil loads podcast artwork from the image URL returned by iTunes.
    implementation("io.coil-kt:coil:2.7.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}