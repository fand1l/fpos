plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Ключ підпису приходить лише із секретів CI. Без нього release підписується
// debug-ключем, щоб APK усе одно можна було поставити.
val releaseKeystore: String? = System.getenv("RADIOGUARD_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.fand1l.radioguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fand1l.radioguard"
        minSdk = 30
        targetSdk = 35
        versionCode = (System.getenv("RADIOGUARD_VERSION_CODE") ?: "1").toInt()
        versionName = "0.1"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RADIOGUARD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RADIOGUARD_KEY_ALIAS")
                keyPassword = System.getenv("RADIOGUARD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Без R8: Shizuku створює ShellService за іменем класу в окремому процесі,
            // а код лишається таким, яким його читають у репозиторії.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
}
