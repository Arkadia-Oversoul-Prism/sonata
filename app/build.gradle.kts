plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
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

    // Room's schema history is a tracked artifact, not an implicit one: a narration
    // must stay attributable to the model that made it across app updates, and that
    // requires the schema to be versioned in the repository.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
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

    // Local-first persistence. The library, the narrations, the voice profiles and
    // the reading sessions all live here; there is no remote source.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Unit tests run on the JVM. The engine's logic is exercised through the
    // AudioPlayer / OfflineVoice / TtsTransport seams, so no Android framework
    // or Robolectric is required.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric runs Android framework code on the JVM. It is here to reproduce
    // device-only failures — the overlay service in particular, which has crashed
    // on the founder's device three times while every JVM test passed. Without it
    // the only way to test a Service is to have a device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // Room's own testing artifact, so the persistence invariants are exercised
    // against a real in-memory SQLite database rather than a fake.
    testImplementation(libs.androidx.room.testing)

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
