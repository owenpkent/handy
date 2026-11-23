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
        versionCode = 24
        versionName = "0.24"

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
        unitTests.all { test ->
            // JUnit Platform: the existing JUnit 4 tests run through the vintage
            // engine, and Jazzer's @FuzzTest (src/test/.../fuzz) through Jupiter.
            test.useJUnitPlatform()

            // Fuzzing is opt-in: with JAZZER_FUZZ unset the fuzz tests only replay
            // their seed corpus, so an ordinary `gradlew test` stays fast. With
            // JAZZER_FUZZ=1 each target fuzzes for its @FuzzTest maxDuration, and
            // Jazzer fuzzes only one target per JVM, so every test class gets its
            // own JVM in that mode or the second target is silently skipped.
            // A fuzz campaign is also never up to date and never cached: JAZZER_FUZZ
            // is not a task input, so Gradle would otherwise answer a fuzz request
            // with the last seed replay (UP-TO-DATE) and no fuzzer would run.
            System.getenv("JAZZER_FUZZ")?.let {
                test.environment("JAZZER_FUZZ", it)
                test.setForkEvery(1)
                test.doNotTrackState("a fuzz campaign is open-ended; never reuse a previous result")
            }
            // Jazzer attaches its agent at runtime; JDK 21+ warns unless this is set.
            test.jvmArgs("-XX:+EnableDynamicAgentLoading")
        }
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
    // JUnit Platform runs both: the JUnit 4 tests above (vintage engine) and the
    // Jazzer fuzz targets (Jupiter). Jazzer is the JVM libFuzzer; see
    // .github/workflows/nightly-fuzz.yml.
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("com.code-intelligence:jazzer-junit:0.30.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// Lock what ships: the debug and release runtime classpaths, in
// app/gradle.lockfile, so a build is reproducible and OSV-Scanner
// (.github/workflows/osv.yml) has the full list of artifacts that reach a
// phone. Test and lint configurations are left unlocked on purpose: their
// tooling pulls its own, often old, libraries that never ship, and a
// vulnerability scan of them is noise. After changing a dependency, update with:
// ./gradlew :app:dependencies --write-locks
configurations.configureEach {
    if (name.endsWith("RuntimeClasspath") && !name.contains("Test")) {
        resolutionStrategy.activateDependencyLocking()
    }
}
