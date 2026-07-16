import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.imi.smartedge.sidebar.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.imi.smartedge.sidebar.panel"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "1.5.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Audit/M: AGP 8.x deprecated `resConfigs("en", "es")` in favour of
        // the underlying DSL property `resourceConfigurations`. Same effect —
        // strip all non en/es resource locales from the APK so we ship the
        // smallest possible resources-only build.
        resourceConfigurations += listOf("en", "es")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))

        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties["KEY_ALIAS"] as String
                keyPassword = keystoreProperties["KEY_PASSWORD"] as String
                storeFile = file(keystoreProperties["STORE_FILE"] as String)
                storePassword = keystoreProperties["STORE_PASSWORD"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        // No project-wide deprecation suppression here. The Kotlin compiler
        // bundled with this Gradle wrapper rejected both
        // `-Xsuppress-deprecated-warnings` and
        // `-Xsuppress-deprecated-warnings-time=N` with "not supported by this
        // version of the compiler" — verified empirically against a Kotlin
        // 1.9.24 plugin. The per-call `@Suppress("DEPRECATION")` on
        // AutomationManager.runShizuku is the proven narrow-scope fix instead.
        // Revisit when we bump Kotlin past whichever minor release
        // recognises the flag.
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.glide)
    implementation("com.github.skydoves:colorpickerview:2.3.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    // Shizuku bumped 12.1.0 -> 13.1.0: api gains proper @Nullable annotations
    // on `newProcess(String[], String[], String)`; provider packaging unchanged.
    implementation("dev.rikka.shizuku:api:13.1.0")
    implementation("dev.rikka.shizuku:provider:13.1.0")

    testImplementation(libs.junit)
    // Provide a real org.json on the JVM unit-test classpath to replace the
    // Android-stub `org.json` (which throws `RuntimeException("Stub!")` on use).
    // android.jar is on the unit-test compileClasspath but not at runtime.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
