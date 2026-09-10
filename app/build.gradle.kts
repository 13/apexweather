import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

// The release workflow stamps the git tag in with -PapexVersionName / -PapexVersionCode.
// Kept level with the newest published release, so a local build does not claim to be older than
// what is on GitHub and the updater does not offer a version the developer already has.
val apexVersionName: String = providers.gradleProperty("apexVersionName").getOrElse("0.7.1")
val apexVersionCode: Int = providers.gradleProperty("apexVersionCode").map(String::toInt).getOrElse(701)

// A real signing key, when one exists: environment variables on CI, or an
// untracked keystore/keystore.properties locally. Without either, both build
// types fall back to the repo keystore below.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// CI exports these even when the secret is unset, so an empty value means "absent".
fun signingSecret(envName: String, propName: String): String? =
    (System.getenv(envName) ?: keystoreProps.getProperty(propName))?.takeIf { it.isNotBlank() }

val releaseStoreFile: String? = signingSecret("APEX_KEYSTORE_FILE", "storeFile")

// Which commit a build came from, for bug reports. Deliberately the commit's own hash and date
// rather than the wall clock: a build timestamp would change on every single build and force
// BuildConfig, and everything that reads it, to recompile each time.
fun git(vararg args: String): String? = runCatching {
    ProcessBuilder("git", *args).directory(rootDir).start()
        .inputStream.bufferedReader().readText().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

val gitHash: String = git("rev-parse", "--short", "HEAD") ?: "unknown"
val gitDate: String = git("show", "-s", "--format=%cs", "HEAD") ?: "unknown"

// Names the outputs ApexWeather-<variant>.apk instead of app-<variant>.apk.
base {
    archivesName.set("ApexWeather")
}

android {
    namespace = "it.apexweather"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.apexweather"
        minSdk = 31
        targetSdk = 36
        versionCode = apexVersionCode
        versionName = apexVersionName
        testInstrumentationRunner = "it.apexweather.HiltTestRunner"

        // Where the in-app updater looks for releases. Here rather than in Kotlin so the repo is
        // named once, beside the version it is compared against.
        buildConfigField("String", "UPDATE_REPO", "\"13/apexweather\"")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "GIT_DATE", "\"$gitDate\"")
    }

    signingConfigs {
        // Repo-local keystore holding the standard Android debug credentials, so it
        // is not a secret. Copied from Apex Maps, which signs this way on purpose:
        // every machine and both build types sign identically, so a debug build
        // updates a sideloaded release build in place instead of failing on a
        // signature mismatch. It is not a distribution key — anyone can produce an
        // APK with this signature, so Play Store builds need a real one.
        getByName("debug") {
            storeFile = rootProject.file("tools/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // v1 (JAR) signing only matters below Android 7; minSdk is 31.
            enableV1Signing = false
        }
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingSecret("APEX_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingSecret("APEX_KEY_ALIAS", "keyAlias")
                keyPassword = signingSecret("APEX_KEY_PASSWORD", "keyPassword")
                enableV1Signing = false
            }
        }
    }

    lint {
        // CI runs this, so a warning has to be worth failing a build over.
        warningsAsErrors = true
        abortOnError = true
        sarifReport = true
        disable += setOf(
            // Dependency and toolchain upgrades are a decision to take deliberately, on a day set
            // aside for it. A new release upstream is not a defect in this code.
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            // targetSdk trails compileSdk on purpose; see the comment on targetSdk above.
            "OldTargetApi",
            // Lint calls mipmap-anydpi-v26 unnecessary at minSdk 31, but dropping the qualifier
            // makes aapt2 fail with "resource mipmap/ic_launcher not found". Tried, reverted.
            "ObsoleteSdkInt",
            // Resource shrinking is left off: Glance selects its generated layouts at runtime, and
            // nothing here verifies a shrunk widget on a device. Revisit with a placed widget to
            // test against.
            "NotShrinkingResources",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // The instrumented tests read the same recorded fixtures as the JVM tests. One of them,
        // MeteoAlarmMapper, can only be trusted once it has run on a device: Android's XML parser
        // rejects a configuration the JVM's accepts, and that difference reached a phone once.
        getByName("androidTest") { resources.srcDir("src/test/resources") }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Roborazzi needs real pixels out of Robolectric; the default legacy mode draws nothing,
            // and a golden image of an empty canvas would pass forever.
            all { it.systemProperty("robolectric.graphicsMode", "NATIVE") }
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    // Installs the recorded baseline profile on first run; without it the profile in the APK is inert.
    implementation(libs.profileinstaller)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    baselineProfile(project(":baselineprofile"))

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // GrantPermissionRule, for the notification tests: POST_NOTIFICATIONS is a runtime permission.
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}
