import java.util.Properties
import java.util.UUID

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.orbit.assistant"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    // arm64 only, on purpose: the virtual-display helper and the Python runtime
// are built for it, so a Chromebook cannot run this app. Lint's ChromeOS ABI
// warning is about that gap, not about a mistake.
lint {
    disable += "ChromeOsAbiSupport"
}

defaultConfig {
        applicationId = "com.orbit.assistant"
        minSdk = 34
        targetSdk = 34
        versionCode = 8
        versionName = "0.4.4"
        buildConfigField("int", "AGENT_PORT", System.getenv("ORBIT_PORT") ?: "8765")
        buildConfigField("String", "BROWSER_TOKEN", "\"${UUID.randomUUID()}\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    // A release APK is what a person can install from a download link: the
    // debug one is signed by a per-machine key and flagged debuggable. The
    // keystore and its password live in android-app/keystore.properties,
    // which is not in the repository (copy keystore.properties.example to create one).
    signingConfigs {
        create("release") {
            val props = rootProject.file("keystore.properties")
            if (props.exists()) {
                val values = Properties().apply {
                    props.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(values.getProperty("storeFile", "orbit-release.jks"))
                storePassword = values.getProperty("storePassword")
                keyAlias = values.getProperty("keyAlias", "orbit")
                keyPassword = values.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// `app/src/main/python/orbit` is a generated copy of ../../orbit. This task
// keeps it in sync on every build so shipped Python cannot drift from the
// audited source (Chaquopy has no Kotlin-DSL source-dir API).

chaquopy {
    defaultConfig {
        version = "3.13"
        // Do not hardcode a path here: whoever clones the repo has their own
        // python. local.properties (not in git) can point at one, and the
        // environment variable is the fallback. Chaquopy 17 requires the
        // build Python to match the app's Python major/minor version.
        val localPython = rootProject.file("local.properties").let { props ->
            if (props.exists()) {
                Properties().apply { props.inputStream().use { load(it) } }
                    .getProperty("python.path")
            } else {
                null
            }
        }
        buildPython(localPython ?: System.getenv("BUILD_PYTHON") ?: "python3.13")
        pip {
            install("requests")
            install("PyYAML")
            install("spake2")        // IRTF SPAKE2 (pure Python) for ADB pairing
            install("cryptography")  // AES-GCM / TLS primitives
        }
    }
}

val syncPythonSources by tasks.registering(Copy::class) {
    from(rootProject.file("../orbit")) {
        exclude("**/__pycache__/**", "**/tests/**", "**/*.pyc", "**/maps/**")
    }
    into(layout.projectDirectory.dir("src/main/python/orbit"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("PythonSources") }
    .configureEach { dependsOn(syncPythonSources) }

dependencies {
    // Document-start JavaScript: the only reliable moment to wrap fetch/XHR,
    // before the page's own scripts run. onPageStarted is too late for a page
    // that fetches during parse (measured on a search results page).
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.5")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
