plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.handyvoice.v2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.handyvoice.v2"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.22"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Permanent shared signing key. Committed so every machine (and cloud
    // build) signs with the SAME certificate. Android only installs an in-app
    // update over the top when the new APK's signature matches the installed
    // one; a per-machine debug key breaks that ("App not installed as package
    // conflicts with an existing package"). This IS a standard Android *debug*
    // keystore (well-known creds android / androiddebugkey / android) — it is
    // an identity, not a security secret, so committing it is intentional.
    // Drop the keystore that signed your currently-installed build in at
    // app/signing/handy.keystore (see app/signing/README.md). If the file is
    // absent the build silently falls back to the per-machine debug key.
    val sharedKeystore = file("signing/handy.keystore")
    signingConfigs {
        if (sharedKeystore.exists()) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (sharedKeystore.exists()) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (sharedKeystore.exists()) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        // Needed so the in-app updater can read VERSION_NAME / VERSION_CODE
        // off BuildConfig to compare against the latest published release.
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// After a debug build, drop a version-named copy of the APK into the
// repo-root `apks/` folder so there's a stable, human-named installer to grab
// (e.g. to upload to Drive or sideload). The name tracks versionName, so bump
// versionName / versionCode in defaultConfig above to cut a new versioned file.
val copyDebugApkToRoot = tasks.register<Copy>("copyDebugApkToRoot") {
    val version = android.defaultConfig.versionName ?: "dev"
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("app-debug.apk")
    }
    into(rootProject.projectDir.parentFile.resolve("apks"))
    rename { "SmartDongV2-v$version-debug.apk" }
}

// Hook packageDebug (not assembleDebug): packageDebug is what actually writes
// the APK and is a dependency of both assembleDebug and installDebug, so the
// copy fires whichever entry point built the APK.
tasks.matching { it.name == "packageDebug" }.configureEach {
    finalizedBy(copyDebugApkToRoot)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("androidx.datastore:datastore-preferences:1.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
