plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

android {
    namespace = "com.github.lucasaguiar11.kotlin_baselime_logger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.github.lucasaguiar11.kotlin_baselime_logger"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "BASE_URL", "\"https://events.baselime.io\"")
        buildConfigField("String", "API_KEY", "\"811a26cb3387e1c47b85a5f4910ed7c6ddfb7e45\"") // This is a fake key hehehe
        buildConfigField("String", "SERVICE_NAME", "\"kotlin-baselime-logger\"")
        buildConfigField("String", "BUILD_TYPE", "\"dev\"")

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8

        isCoreLibraryDesugaringEnabled = true // Necessário para usar OpenTelemetry
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true

    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

//    implementation(libs.kotlin.baselime.logger)
    implementation(project(":kotlin-baselime-logger"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}