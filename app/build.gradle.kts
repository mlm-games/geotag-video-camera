import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.app.geotagvideocamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.app.geotagvideocamera"
        minSdk = 24
        targetSdk = 35
        versionCode = 130
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Enable reproducible builds
//        setProperty("android.enableR8.fullMode", "true")
//        setProperty("android.injected.testOnly", "false")
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }


    // Add support for architecture-specific builds
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as BaseVariantOutputImpl
            val abiName = output.filters.find { it.filterType == "ABI" }?.identifier

            if (abiName != null) {
                val baseVersionCode = variant.versionCode
                val abiVersionCode = when (abiName) {
                    "x86" -> baseVersionCode - 3
                    "x86_64" -> baseVersionCode - 2
                    "armeabi-v7a" -> baseVersionCode - 1
                    "arm64-v8a" -> baseVersionCode
                    else -> baseVersionCode
                }

                (output as ApkVariantOutputImpl).versionCodeOverride = abiVersionCode
                output.outputFileName = ("geotag_camera-${variant.versionName}-${abiName}.apk")
            }
        }
    }



    // For reproducible builds
    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // For reproducible builds
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }

        debug {
            // For reproducible builds in debug mode
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // For reproducible builds
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // For reproducible builds
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
        }
    }

    // For reproducible builds - set fixed timestamps
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// For reproducible builds
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

dependencies {
    // Core libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Camera
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    // Location (MicroG coz FDroid)
    implementation(libs.microg.location)

    // UI components
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.exifinterface)

    // QR code scanning
    // implementation(libs.core)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    // Debug tools
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // For Java 8+ APIs on older Android versions
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
