import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

val buildTime: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z (z)").apply {
    timeZone = TimeZone.getDefault()
}.format(Date())

val gitBranch: String = try {
    // Check if HEAD is on a tag
    val tagProcess = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--exact-match", "HEAD"))
    tagProcess.waitFor()
    if (tagProcess.exitValue() == 0) {
        val tagName = tagProcess.inputStream.bufferedReader().readText().trim()
        "tag-$tagName"
    } else {
        val process = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--abbrev-ref", "HEAD"))
        process.waitFor()
        val ref = process.inputStream.bufferedReader().readText().trim()
        if (ref == "HEAD") {
            // Detached HEAD, use short commit hash
            val hashProcess = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
            hashProcess.waitFor()
            hashProcess.inputStream.bufferedReader().readText().trim()
        } else {
            ref
        }
    }
} catch (e: Exception) {
    "unknown"
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "info.loveyu.mfca"
    compileSdk = 36

    defaultConfig {
        applicationId = "info.loveyu.mfca"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = (project.findProperty("versionName") as String?) ?: "DEBUG"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))

                storeFile = file("${keystoreProperties["STORE_FILE"]}")
                storePassword = keystoreProperties["STORE_PASSWORD"].toString()
                keyAlias = keystoreProperties["KEY_ALIAS"].toString()
                keyPassword = keystoreProperties["KEY_PASSWORD"].toString()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            val dateFormat = SimpleDateFormat("yyMMddHHmm")
            val timestamp = dateFormat.format(Date())
            versionNameSuffix = "-debug.$timestamp.$gitBranch"

            isDebuggable = true
            isMinifyEnabled = false

            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.nanohttpd)
    implementation(libs.syEngine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.paho.mqtt)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
