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

val androidNdkVersion = "r27c"
val androidNdkArchive = "android-ndk-$androidNdkVersion-linux.zip"
val androidNdkUrl = "https://dl.google.com/android/repository/$androidNdkArchive"
val androidNdkRootDir =
    providers
        .environmentVariable("FLOWGATE_ANDROID_NDK_DIR")
        .map { file(it) }
        .orElse(layout.buildDirectory.dir("tools/android-ndk/$androidNdkVersion").map { it.asFile })
val vpnBridgeJniLibDir = layout.buildDirectory.dir("generated/jniLibs/vpnbridge")
val socks5TunnelDir = layout.projectDirectory.dir("src/main/c/hev-socks5-tunnel")
val vpnBridgeWrapperDir = layout.projectDirectory.dir("src/main/c/vpnbridge")
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
        dependsOn(ensureAndroidNdk)
        inputs.dir(socks5TunnelDir)
        inputs.dir(vpnBridgeWrapperDir)
        outputs.dir(vpnBridgeJniLibDir)
        val outputRoot = vpnBridgeJniLibDir.get().asFile
        val ndkRoot = androidNdkRootDir.get()
        val hevDir = socks5TunnelDir.asFile.absolutePath
        val wrapperDir = vpnBridgeWrapperDir.asFile.absolutePath
        onlyIf {
            !outputRoot.resolve("arm64-v8a/libvpnbridge.so").exists()
        }
        // A shell helper function to compile sources preserving directory structure
        // so that files with the same basename (e.g. lwip mem.c) don't collide.
        val compileHelper = """
            compile_to_objdir() {
              local cc="${'$'}1" flags="${'$'}2" inc="${'$'}3" objdir="${'$'}4" src="${'$'}5"
              local relpath="${'$'}{src#${'$'}HEV/}"
              local objfile="${'$'}objdir/${'$'}{relpath%.*}.o"
              mkdir -p "${'$'}(dirname "${'$'}objfile")"
              ${'$'}cc ${'$'}flags ${'$'}inc -c "${'$'}src" -o "${'$'}objfile"
            }
            make_static_lib() {
              local ar="${'$'}1" libpath="${'$'}2" objdir="${'$'}3"
              ${'$'}ar rcs "${'$'}libpath" ${'$'}(find "${'$'}objdir" -name '*.o')
            }
        """.trimIndent()
        val buildScript =
            buildString {
                appendLine("set -euo pipefail")
                appendLine("NDK_BIN='${ndkRoot.absolutePath}/toolchains/llvm/prebuilt/linux-x86_64/bin'")
                appendLine("HEV='$hevDir'")
                appendLine("OUT='${outputRoot.absolutePath}'")
                appendLine(compileHelper)
                // Common include paths and flags
                appendLine("COMMON_INC=\"-I\$HEV/src -I\$HEV/src/misc -I\$HEV/src/core/include")
                appendLine("  -I\$HEV/third-part/yaml/src")
                appendLine("  -I\$HEV/third-part/lwip/src/include -I\$HEV/third-part/lwip/src/ports/include")
                appendLine("  -I\$HEV/third-part/hev-task-system/include -I\$HEV/third-part/hev-task-system/src")
                appendLine("  -I\$HEV/include\"")
                appendLine("COMMON_FLAGS=\"-O3 -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY\"")
                // Per-ABI build
                val targets =
                    listOf(
                        Triple("arm64-v8a", "aarch64-linux-android33-clang", ""),
                        Triple("x86_64", "x86_64-linux-android33-clang", ""),
                    )
                for ((abi, triple, extraFlags) in targets) {
                    val cc = "\$NDK_BIN/$triple"
                    val ar = "\$NDK_BIN/llvm-ar"
                    appendLine("echo '=== Building VPN bridge for $abi ==='")
                    appendLine("ABI_OUT=\"\$OUT/$abi\"")
                    appendLine("OBJDIR=\"\$ABI_OUT/obj\"")
                    appendLine("mkdir -p \"\$ABI_OUT\"")
                    // yaml
                    appendLine("YAML_FLAGS=\"-DYAML_VERSION_MAJOR=0 -DYAML_VERSION_MINOR=2 -DYAML_VERSION_PATCH=5 -DYAML_VERSION_STRING=\\\"0.2.5\\\"\"")
                    appendLine("for f in \$(find \"\$HEV/third-part/yaml/src\" -name '*.c'); do")
                    appendLine("  compile_to_objdir $cc \"\$COMMON_FLAGS \$YAML_FLAGS\" \"\$COMMON_INC\" \"\$OBJDIR/yaml\" \"\$f\"")
                    appendLine("done")
                    appendLine("make_static_lib $ar \"\$OBJDIR/libyaml.a\" \"\$OBJDIR/yaml\"")
                    // lwip
                    appendLine("for f in \$(find \"\$HEV/third-part/lwip/src\" -name '*.c'); do")
                    appendLine("  compile_to_objdir $cc \"\$COMMON_FLAGS\" \"\$COMMON_INC\" \"\$OBJDIR/lwip\" \"\$f\"")
                    appendLine("done")
                    appendLine("make_static_lib $ar \"\$OBJDIR/liblwip.a\" \"\$OBJDIR/lwip\"")
                    // hev-task-system
                    appendLine("TASK_FLAGS=\"-fvisibility=hidden -DENABLE_STACK_OVERFLOW_DETECTION -DENABLE_MEMALLOC_SLICE -DENABLE_IO_SPLICE_SYSCALL -DCONFIG_STACK_BACKEND=STACK_MMAP -DCONFIG_STACK_OVERFLOW_DETECTION=1 -DCONFIG_MEMALLOC_SLICE_ALIGN=16 -DCONFIG_MEMALLOC_SLICE_MAX_SIZE=4096 -DCONFIG_MEMALLOC_SLICE_MAX_COUNT=1000 -DCONFIG_SCHED_CLOCK=CLOCK_NONE\"")
                    appendLine("for f in \$(find \"\$HEV/third-part/hev-task-system/src\" -name '*.c'); do")
                    appendLine("  compile_to_objdir $cc \"\$COMMON_FLAGS $extraFlags \$TASK_FLAGS\" \"\$COMMON_INC\" \"\$OBJDIR/task\" \"\$f\"")
                    appendLine("done")
                    appendLine("for f in \$(find \"\$HEV/third-part/hev-task-system/src\" -name '*.S'); do")
                    appendLine("  compile_to_objdir $cc \"$extraFlags\" \"\$COMMON_INC\" \"\$OBJDIR/task\" \"\$f\"")
                    appendLine("done")
                    appendLine("make_static_lib $ar \"\$OBJDIR/libhev-task-system.a\" \"\$OBJDIR/task\"")
                    // hev-socks5-tunnel
                    appendLine("for f in \$(find \"\$HEV/src\" -name '*.c'); do")
                    appendLine("  compile_to_objdir $cc \"\$COMMON_FLAGS $extraFlags\" \"\$COMMON_INC\" \"\$OBJDIR/tunnel\" \"\$f\"")
                    appendLine("done")
                    // wrapper
                    appendLine("mkdir -p \"\$OBJDIR/wrapper\"")
                    appendLine("$cc \$COMMON_FLAGS $extraFlags \$COMMON_INC -c '$wrapperDir/main.c' -o \"\$OBJDIR/wrapper/main.o\"")
                    // Link
                    appendLine(
                        "$cc -Wl,-z,max-page-size=16384 -o \"\$ABI_OUT/libvpnbridge.so\" \"\$OBJDIR/wrapper/main.o\" \$(find \"\$OBJDIR/tunnel\" -name '*.o') \"\$OBJDIR/libyaml.a\" \"\$OBJDIR/liblwip.a\" \"\$OBJDIR/libhev-task-system.a\"",
                    )
                    appendLine("\$NDK_BIN/llvm-strip \"\$ABI_OUT/libvpnbridge.so\"")
                }
            }
        commandLine("bash", "-lc", buildScript)
    }

val udp2rawVersion = (project.findProperty("udp2rawVersion") as String?) ?: "v2026.05.31-android.1"
val udp2rawJniLibDir = layout.buildDirectory.dir("generated/jniLibs/udp2raw")
val udp2rawAbis = listOf("arm64-v8a", "x86_64")

val prepareUdp2RawLibraries =
    tasks.register<Exec>("prepareUdp2RawLibraries") {
        val outDir = udp2rawJniLibDir.get().asFile
        outputs.dir(outDir)
        inputs.property("udp2rawVersion", udp2rawVersion)
        onlyIf {
            udp2rawAbis.any { abi ->
                !outDir.resolve("$abi/libudp2raw.so").exists()
            }
        }
        val releaseZipUrl =
            "https://github.com/loveyu/udp2raw/releases/download/$udp2rawVersion/udp2raw-android-jniLibs.zip"
        val buildScript =
            buildString {
                appendLine("set -euo pipefail")
                appendLine("tmpdir=\$(mktemp -d)")
                appendLine("trap 'rm -rf \"\$tmpdir\"' EXIT")
                appendLine("curl -fsSL --retry 5 --retry-delay 10 --retry-all-errors '$releaseZipUrl' -o \"\$tmpdir/udp2raw-android-jniLibs.zip\"")
                appendLine("rm -rf '${outDir.absolutePath}'")
                appendLine("mkdir -p '${outDir.absolutePath}'")
                appendLine("unzip -q \"\$tmpdir/udp2raw-android-jniLibs.zip\" -d \"\$tmpdir/unpack\"")
                appendLine("cp -R \"\$tmpdir/unpack/jniLibs/.\" '${outDir.absolutePath}'")
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
    // libudp2raw_plugin.so is NOT bundled in the APK — it is distributed as a plugin
    // and installed at runtime via PluginManager into the app's private files directory.
    sourceSets.getByName("main").jniLibs.srcDir(vpnBridgeJniLibDir.get().asFile)
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

    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
        // prepareUdp2RawLibraries is no longer part of the build — libudp2raw_plugin.so
        // is installed at runtime as a plugin, not bundled in the APK.
        dependsOn(buildVpnBridgeBinaries)
    }
}
