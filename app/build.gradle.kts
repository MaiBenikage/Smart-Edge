import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.imi.smartedge.sidebar.panel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imi.smartedge.sidebar.panel"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        // Release tags pass -PversionName=<tag without the leading v>
        // (release.yml); local and CI builds fall back to "1".
        versionName = providers.gradleProperty("versionName").getOrElse("1")

        // Audit/M: AGP 8.x deprecated `resConfigs("en", "es")` in favour of
        // the underlying DSL property `resourceConfigurations`. Same effect —
        // strip all non en/es resource locales from the APK so we ship the
        // smallest possible resources-only build.
        resourceConfigurations += listOf("en", "es")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }

        // Fail with a clear message instead of a bare `as String` cast
        // (which throws "null cannot be cast to non-null type String")
        // when the workflow writes an incomplete keystore.properties.
        fun key(name: String): String =
            keystoreProperties.getProperty(name)
                ?: error("keystore.properties is missing $name")

        signingConfigs {
            create("release") {
                keyAlias = key("KEY_ALIAS")
                keyPassword = key("KEY_PASSWORD")
                storeFile = file(key("STORE_FILE"))
                storePassword = key("STORE_PASSWORD")
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
            // Audit fix: getByName("release") threw at configuration time on
            // any machine without keystore.properties, breaking even
            // assembleDebug. findByName returns null when the keystore is
            // absent, so release builds are simply unsigned then instead.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
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

}

// `kotlinOptions` went away with the Kotlin plugin in AGP 9.0 (Kotlin
// support is now built into AGP); the JVM target is set through the
// compilerOptions DSL instead, which lives at the top level, not inside
// `android {}`. Target 17 (was 1.8): the JDK the build runs on and AGP 9's
// default — D8 desugars the bytecode for the minSdk 26 devices, so this
// does not change what runs on the phone.
// No project-wide deprecation suppression here: the per-call
// `@Suppress("DEPRECATION")` on AutomationManager.runShizuku is the
// proven narrow-scope fix instead.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
    implementation("com.github.skydoves:colorpickerview:2.5.0")
    // Shizuku 13.1.0 -> 13.1.5 (patch bump).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation(libs.junit)
    // Provide a real org.json on the JVM unit-test classpath to replace the
    // Android-stub `org.json` (which throws `RuntimeException("Stub!")` on use).
    // android.jar is on the unit-test compileClasspath but not at runtime.
    //
    // NB: this is Douglas Crockford's org.json:json (20231013), NOT the older
    // fork of org.json that Android bundles on device — the two differ in
    // edge cases (optString / null handling, number parsing), so a test can
    // pass on the JVM and behave differently on the phone. Keep fixtures to
    // behaviour both implementations share. The version itself is current
    // and contains the CVE-2023-5072 fix; it is test-only.
    testImplementation("org.json:json:20231013")
}
