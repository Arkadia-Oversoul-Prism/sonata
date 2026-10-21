plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.arkadia.sonata"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arkadia.sonata"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // android.util.Log is a stub on the JVM; returning defaults lets the
            // engine's logic be tested without Robolectric. This does not make any
            // Android framework *behaviour* available — the tests exercise the
            // AudioPlayer / OfflineVoice / TtsTransport seams instead.
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // MediaSessionCompat + MediaStyle notification
    implementation(libs.androidx.media)

    // Unit tests run on the JVM. The engine's logic is exercised through the
    // AudioPlayer / OfflineVoice / TtsTransport seams, so no Android framework
    // or Robolectric is required.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests run on a device/emulator. They are the only surface that
    // can exercise the real platform TextToSpeech engine and the real OkHttp
    // stack, which is where the readiness and cleartext defects actually appeared.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
