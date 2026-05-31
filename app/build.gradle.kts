import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

val buildTime: String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z (z)").apply {
        timeZone = TimeZone.getDefault()
    }.format(Date())

val gitBranch: String =
    try {
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
    alias(libs.plugins.spotless)
}

data class GoBridgeTarget(
    val abi: String,
    val goArch: String,
    val goArm: String? = null,
    val clangTriple: String,
)

val vpnBridgeTargets =
    listOf(
        GoBridgeTarget("arm64-v8a", "arm64", clangTriple = "aarch64-linux-android33-clang"),
        GoBridgeTarget("armeabi-v7a", "arm", "7", "armv7a-linux-androideabi33-clang"),
        GoBridgeTarget("x86_64", "amd64", clangTriple = "x86_64-linux-android33-clang"),
        GoBridgeTarget("x86", "386", clangTriple = "i686-linux-android33-clang"),
    )

val goToolchainVersion = "1.24.1"
val goToolchainArchive = "go${goToolchainVersion}.linux-amd64.tar.gz"
val goToolchainUrl = "https://go.dev/dl/$goToolchainArchive"
val goToolchainRootDir =
    providers
        .environmentVariable("FLOWGATE_GO_TOOLCHAIN_DIR")
        .map { file(it) }
        .orElse(layout.buildDirectory.dir("tools/go/$goToolchainVersion").map { it.asFile })
val goBinary = goToolchainRootDir.map { it.resolve("bin/go") }
val androidNdkVersion = "r27c"
val androidNdkArchive = "android-ndk-$androidNdkVersion-linux.zip"
val androidNdkUrl = "https://dl.google.com/android/repository/$androidNdkArchive"
val androidNdkRootDir =
    providers
        .environmentVariable("FLOWGATE_ANDROID_NDK_DIR")
        .map { file(it) }
        .orElse(layout.buildDirectory.dir("tools/android-ndk/$androidNdkVersion").map { it.asFile })
val vpnBridgeSourceDir = layout.projectDirectory.dir("src/main/go/vpnbridge")
val vpnBridgeAssetDir = layout.buildDirectory.dir("generated/assets/vpnbridge")
val ensureGoToolchain =
    tasks.register<Exec>("ensureGoToolchain") {
        outputs.dir(goToolchainRootDir)
        onlyIf { !goBinary.get().exists() }
        val toolchainRoot = goToolchainRootDir.get()
        toolchainRoot.parentFile.mkdirs()
        commandLine(
            "bash",
            "-lc",
            """
            set -euo pipefail
            tmpdir=${'$'}(mktemp -d)
            trap 'rm -rf "${'$'}tmpdir"' EXIT
            curl -L "$goToolchainUrl" -o "${'$'}tmpdir/$goToolchainArchive"
            rm -rf "${toolchainRoot.absolutePath}"
            mkdir -p "${toolchainRoot.parentFile.absolutePath}"
            tar -C "${toolchainRoot.parentFile.absolutePath}" -xzf "${'$'}tmpdir/$goToolchainArchive"
            mv "${toolchainRoot.parentFile.absolutePath}/go" "${toolchainRoot.absolutePath}"
            """.trimIndent(),
        )
    }
val ensureAndroidNdk =
    tasks.register<Exec>("ensureAndroidNdk") {
        outputs.dir(androidNdkRootDir)
        val ndkRoot = androidNdkRootDir.get()
        onlyIf { !ndkRoot.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/clang").exists() }
        ndkRoot.parentFile.mkdirs()
        commandLine(
            "bash",
            "-lc",
            """
            set -euo pipefail
            tmpdir=${'$'}(mktemp -d)
            trap 'rm -rf "${'$'}tmpdir"' EXIT
            curl -L "$androidNdkUrl" -o "${'$'}tmpdir/$androidNdkArchive"
            rm -rf "${ndkRoot.absolutePath}"
            mkdir -p "${ndkRoot.parentFile.absolutePath}"
            unzip -q "${'$'}tmpdir/$androidNdkArchive" -d "${ndkRoot.parentFile.absolutePath}"
            mv "${ndkRoot.parentFile.absolutePath}/android-ndk-$androidNdkVersion" "${ndkRoot.absolutePath}"
            """.trimIndent(),
        )
    }
val buildVpnBridgeBinaries =
    tasks.register<Exec>("buildVpnBridgeBinaries") {
        dependsOn(ensureGoToolchain)
        dependsOn(ensureAndroidNdk)
        inputs.dir(vpnBridgeSourceDir)
        inputs.property("goToolchainVersion", goToolchainVersion)
        inputs.property("androidNdkVersion", androidNdkVersion)
        outputs.dir(vpnBridgeAssetDir)
        val outputRoot = vpnBridgeAssetDir.get().asFile
        onlyIf {
            vpnBridgeTargets.any { target ->
                !outputRoot.resolve("vpnbridge/${target.abi}/vpnbridge").exists()
            }
        }
        outputRoot.mkdirs()
        val ndkBinDir = androidNdkRootDir.get().resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")
        val buildScript =
            buildString {
                appendLine("set -euo pipefail")
                appendLine("cd '${vpnBridgeSourceDir.asFile.absolutePath}'")
                appendLine("'${goBinary.get().absolutePath}' mod tidy")
                vpnBridgeTargets.forEach { target ->
                    appendLine("mkdir -p '${outputRoot.resolve("vpnbridge/${target.abi}").absolutePath}'")
                    append("GOOS=android GOARCH=${target.goArch} CGO_ENABLED=1 CC='${ndkBinDir.resolve(target.clangTriple).absolutePath}' ")
                    if (target.goArm != null) {
                        append("GOARM=${target.goArm} ")
                    }
                    appendLine(
                        "'${goBinary.get().absolutePath}' build -trimpath -o '${outputRoot.resolve("vpnbridge/${target.abi}/vpnbridge").absolutePath}' .",
                    )
                }
            }
        commandLine("bash", "-lc", buildScript)
    }

val mihomoVersion = "1.19.25"
val mihomoJniLibDir = layout.buildDirectory.dir("generated/jniLibs/mihomo")

data class MihomoTarget(val abi: String, val archiveName: String)

val mihomoTargets =
    listOf(
        MihomoTarget(
            abi = "arm64-v8a",
            archiveName = "mihomo-android-arm64-v8-v$mihomoVersion.gz",
        ),
    )

val prepareMihomoLibraries =
    tasks.register<Exec>("prepareMihomoLibraries") {
        val outDir = mihomoJniLibDir.get().asFile
        outputs.dir(outDir)
        onlyIf {
            mihomoTargets.any { target ->
                !outDir.resolve("${target.abi}/libmihomo.so").exists()
            }
        }
        val buildScript =
            buildString {
                appendLine("set -euo pipefail")
                appendLine("tmpdir=\$(mktemp -d)")
                appendLine("trap 'rm -rf \"\$tmpdir\"' EXIT")
                mihomoTargets.forEach { target ->
                    val abiDir = outDir.resolve(target.abi)
                    appendLine("mkdir -p '${abiDir.absolutePath}'")
                    appendLine(
                        "curl -fsSL 'https://github.com/MetaCubeX/mihomo/releases/download/v$mihomoVersion/${target.archiveName}' -o \"\$tmpdir/${target.archiveName}\"",
                    )
                    appendLine(
                        "gunzip -c \"\$tmpdir/${target.archiveName}\" > '${abiDir.resolve("libmihomo.so").absolutePath}'",
                    )
                }
            }
        commandLine("bash", "-lc", buildScript)
    }

android {
    namespace = "info.loveyu.mfca"
    compileSdk = 36

    defaultConfig {
        applicationId = "info.loveyu.mfca"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = (project.findProperty("versionName") as String?) ?: ""

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
            versionNameSuffix = "debug.$timestamp.$gitBranch"

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
                "proguard-rules.pro",
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("main").assets.srcDir(vpnBridgeAssetDir.get().asFile)
    sourceSets.getByName("main").jniLibs.srcDir(mihomoJniLibDir.get().asFile)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

spotless {
    kotlin {
        ktfmt().kotlinlangStyle()
        ktlint()
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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.nanohttpd)
    implementation(libs.syEngine)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.paho.mqtt)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

afterEvaluate {
    val unitTestTask = tasks.named<Test>("testDebugUnitTest").get()
    tasks.register<Test>("generateConfigDoc") {
        group = "documentation"
        description = "Generate config schema Markdown to src/main/assets/config_schema.md"
        testClassesDirs = unitTestTask.testClassesDirs
        classpath = unitTestTask.classpath
        dependsOn("compileDebugUnitTestKotlin")
        filter {
            includeTestsMatching("info.loveyu.mfca.config.schema.GenerateConfigDocTest")
        }
        systemProperty("generateConfigDoc", "true")
        outputs.upToDateWhen { false }
    }

    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn(buildVpnBridgeBinaries)
    }

    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
        dependsOn(prepareMihomoLibraries)
    }
}
