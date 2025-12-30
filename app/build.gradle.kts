plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.tutorial"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.tutorial"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86")
            abiFilters.add("x86_64")
        }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"

    }
    androidResources {
        noCompress.add("tflite")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.okhttp)

    // The new core runtime (replaces org.tensorflow:tensorflow-lite)
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    // The new GPU delegate (replaces org.tensorflow:tensorflow-lite-gpu)
    implementation("com.google.ai.edge.litert:litert-gpu:1.0.1")
    // The new GPU API (contains the modern GpuDelegateFactory)
    implementation("com.google.ai.edge.litert:litert-gpu-api:1.0.1")


//    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") // Useful for TensorImage

    implementation("org.yaml:snakeyaml:2.0")
}
