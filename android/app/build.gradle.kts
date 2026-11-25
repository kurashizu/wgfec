import java.util.Properties

plugins { id("com.android.application") }

// Release signing: put the keystore details in android/keystore.properties (not committed):
//   storeFile=/path/to/release.jks   storePassword=...   keyAlias=...   keyPassword=...
val ksFile = rootProject.file("keystore.properties")
val ks = Properties().apply { if (ksFile.exists()) ksFile.inputStream().use { load(it) } }

android {
    namespace = "io.github.kurashizu.wgfec"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.kurashizu.wgfec"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.4.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    packaging { jniLibs { useLegacyPackaging = true } }   // speederv2 is run as a program, so it must be extracted to disk
    sourceSets["main"].jniLibs.srcDirs("../build/jniLibs")
    signingConfigs {
        if (ksFile.exists()) create("release") {
            storeFile = file(ks.getProperty("storeFile")); storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias"); keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildFeatures { viewBinding = true }
    buildTypes { release { isMinifyEnabled = false; if (ksFile.exists()) signingConfig = signingConfigs.getByName("release") } }
}

dependencies {
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
