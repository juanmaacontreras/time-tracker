plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.bitacora.timer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bitacora.timer"
        minSdk = 26
        targetSdk = 34
        // La versión la inyecta el CI (número de commits, monotónico). Local: 1/dev.
        versionCode = (project.findProperty("vcode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("vname") as String?) ?: "dev"
    }

    // Firma fija (keystore commiteada) para que las actualizaciones se puedan instalar
    // una sobre otra: Android exige que todos los APK compartan la misma firma.
    signingConfigs {
        create("app") {
            storeFile = file("app.keystore")
            storePassword = "bitacora"
            keyAlias = "bitacora"
            keyPassword = "bitacora"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // BoM: fija versiones coherentes entre los artefactos de Firebase. Se eligió la
    // línea 33.1.x a propósito — las versiones más nuevas exigen compileSdk 35 y acá
    // estamos en 34. Si algún día se sube el compileSdk, se puede subir el BoM.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging")
}
