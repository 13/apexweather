# Apex Weather Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Native Android app "Apex Weather" for Dorf Tirol / Meran that blends seven regional forecast sources into a consensus forecast with an animated sky UI, source comparison, the official South Tyrol bulletin, and a home-screen widget.

**Architecture:** Single Gradle module `app`, package `it.apexweather`, layered packages `data` / `domain` / `ui` / `widget` / `work` / `di`. `domain` is pure Kotlin (JVM-testable): models, `ConsensusBlender`, sun phase, sky palettes. `data` holds three Retrofit clients, DTO→domain mappers, a Room cache, and one `WeatherRepository` exposing a `Flow<WeatherSnapshot>`. UI is Compose + Material 3 with a custom `SkyBackground`; widget is Glance; background refresh is WorkManager.

**Tech Stack:** Kotlin 2.4.10, AGP 9.3.2 (built-in Kotlin), Gradle 9.7.1, Jetpack Compose BOM 2026.08.00, Material 3, Hilt 2.60.1 (KSP), Retrofit 3.0.0 + OkHttp 5.5.0 + kotlinx.serialization 1.11.0, Room 2.8.4 (KSP), WorkManager 2.11.2, Glance 1.2.0, DataStore 1.2.1, Coil 3.6.2, Navigation Compose 2.10.0, JUnit 4, Robolectric 4.16.1, Compose UI test.

## Global Constraints

- Package / applicationId: `it.apexweather`. App name shown to users: `Apex Weather`.
- `minSdk = 31`, `compileSdk = 37` (newest libs require it), `targetSdk = 36`.
- Fixed location: Dorf Tirol, lat `46.691`, lon `11.155`, ISTAT `021101`, SIAG station `23200MS`, SIAG district `2`, zone `Europe/Rome`.
- Sources enum exactly: `SIAG_KMOS, GEOSPHERE_AROME, ICON_CH1, ICON_CH2, ICON_2I, ICON_D2, ECMWF`; regional = all but `ECMWF`.
- Consensus rules (spec §4.3): median temp/wind/precip, band = min..max, ECMWF only when < 2 regional sources at that hour, precip prob = max of model probs else `round(100 * count(precip > 0.1) / count)`, condition = majority vote with tie → more severe, `agreement = 1 - clamp(spread/6, 0, 1)`, single source → agreement 0.5.
- Stale thresholds: regional 6 h, SIAG KMOS 16 h (two runs per day), ECMWF 12 h. Station observation used as "now" only if fresher than 90 min. Auto-refresh on app open if cache older than 30 min. WorkManager period 60 min.
- Languages: German default, Italian, English. All user-facing strings in `res/values`, `values-it`, `values-en` (German in default `values`).
- Attribution text must appear on Home: "Daten: Landeswetterdienst Südtirol · GeoSphere Austria (CC BY 4.0) · MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo".
- Build machine: SDK at `/home/ben/Android/Sdk`, JDK `/usr/lib/jvm/java-21-openjdk`. `ANDROID_HOME` in the shell points at a broken SDK; always rely on `local.properties`.
- No `kapt`. KSP only. Do not apply `org.jetbrains.kotlin.android` to the app module (AGP 9 built-in Kotlin).
- Device tests run on the phone only: prefix every `connectedDebugAndroidTest` invocation with `ANDROID_SERIAL=RZCXA1ZEXJE` (the attached emulator fails with an unrelated `InputManager.getInstance` error). Any Compose test that renders `SkyBackground` (directly or via `MainActivity`) must set `rule.mainClock.autoAdvance = false` before `setContent`/launch and advance the clock manually, because the sky's frame loop never lets the test rule become idle.
- Commit after every task with a conventional-commit message. Test files live in `app/src/test` (JVM) and `app/src/androidTest` (device).

---

## File Structure

```
apexweather/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, local.properties, gradlew, gradle/wrapper/*
├── gradle/libs.versions.toml
└── app/
    ├── build.gradle.kts, proguard-rules.pro
    └── src/
        ├── main/AndroidManifest.xml
        ├── main/res/ (values*/strings.xml, drawable/ic_*.xml, xml/apex_widget_info.xml, mipmap icons)
        ├── main/kotlin/it/apexweather/
        │   ├── ApexApplication.kt                — Hilt app, WorkManager config, schedules refresh
        │   ├── MainActivity.kt                   — single activity, splash, sets content
        │   ├── di/AppModule.kt                   — Hilt: Json, OkHttp, Retrofit APIs, Room, Clock
        │   ├── domain/model/Models.kt            — Source, Condition, HourlyPoint, DailyPoint, SourceForecast, Bulletin*, StationObservation, SourceStatus, WeatherSnapshot, Consensus*
        │   ├── domain/model/Serializers.kt       — Instant/LocalDate kotlinx serializers
        │   ├── domain/DailyAggregator.kt         — hourly → daily aggregation (shared by mappers and blender)
        │   ├── domain/ConsensusBlender.kt
        │   ├── domain/SunPhase.kt                — SunPhase enum + SunPhaseCalculator
        │   ├── domain/SkyPalette.kt              — SkyPalette, ParticleKind, SkyPaletteSelector
        │   ├── domain/WmoCodes.kt                — WMO weather code → Condition
        │   ├── domain/SiagCodes.kt               — SIAG letter → Condition
        │   ├── data/remote/OpenMeteoApi.kt       — Retrofit interface + DTO + OpenMeteoMapper
        │   ├── data/remote/GeoSphereApi.kt       — Retrofit interface + DTO + GeoSphereMapper
        │   ├── data/remote/SiagApi.kt            — Retrofit interfaces (SiagApi, OdhApi) + DTOs + SiagMappers
        │   ├── data/local/AppDatabase.kt         — Room entities, DAO, database
        │   ├── data/WeatherRepository.kt
        │   ├── data/SettingsRepository.kt        — DataStore preferences
        │   ├── ui/theme/Theme.kt
        │   ├── ui/sky/SkyBackground.kt           — gradient, particles, ridge silhouette
        │   ├── ui/common/WeatherIcons.kt         — Condition → ImageVector, formatting helpers
        │   ├── ui/common/GlassCard.kt
        │   ├── ui/home/HomeViewModel.kt, HomeScreen.kt, HomeSections.kt
        │   ├── ui/compare/CompareViewModel.kt, CompareScreen.kt, MultiLineChart.kt
        │   ├── ui/bulletin/BulletinViewModel.kt, BulletinScreen.kt
        │   ├── ui/settings/SettingsSheet.kt
        │   ├── ui/navigation/AppNavigation.kt
        │   ├── widget/ApexWidget.kt, ApexWidgetReceiver.kt
        │   └── work/RefreshWorker.kt, RefreshScheduler.kt
        ├── test/kotlin/it/apexweather/...        — JVM tests, fixtures in test/resources/fixtures/
        └── androidTest/kotlin/it/apexweather/... — Compose UI tests
```

---

### Task 1: Project scaffold that builds and launches

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `gradle/libs.versions.toml`, `.gitignore`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/it/apexweather/ApexApplication.kt`, `app/src/main/kotlin/it/apexweather/MainActivity.kt`
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values-it/strings.xml`, `app/src/main/res/values-en/strings.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/drawable/ic_launcher_background.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/test/kotlin/it/apexweather/SmokeTest.kt`

**Interfaces:**
- Produces: Gradle project that `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest` succeed on. Package `it.apexweather`. `ApexApplication` annotated `@HiltAndroidApp`.

- [ ] **Step 1: Gradle wrapper and root files**

Run (copies the wrapper from a sibling project that uses the same Gradle version):

```bash
cd /home/ben/repo/apexweather
mkdir -p gradle/wrapper
cp /home/ben/repo/saymyname/gradlew /home/ben/repo/saymyname/gradlew.bat .
cp /home/ben/repo/saymyname/gradle/wrapper/gradle-wrapper.jar /home/ben/repo/saymyname/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
chmod +x gradlew
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```
Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip`

Create `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "apexweather"
include(":app")
```

Create `build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied to any module (AGP 9 built-in Kotlin), but pins the KGP version on the classpath.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

Create `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
org.gradle.java.home=/usr/lib/jvm/java-21-openjdk
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
android.onlyEnableUnitTestForTheTestedBuildType=false
```

Create `local.properties`:
```properties
sdk.dir=/home/ben/Android/Sdk
```

Create `.gitignore`:
```
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
/captures
.cxx/
```

- [ ] **Step 2: Version catalog**

Create `gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.3.2"
kotlin = "2.4.10"
ksp = "2.3.11"
hilt = "2.60.1"
hiltAndroidx = "1.4.0"
composeBom = "2026.08.00"
activityCompose = "1.13.0"
appcompat = "1.7.1"
lifecycle = "2.11.0"
navigation = "2.10.0"
room = "2.8.4"
work = "2.11.2"
glance = "1.2.0"
datastore = "1.2.1"
coreKtx = "1.19.0"
splashscreen = "1.2.0"
coroutines = "1.11.0"
serialization = "1.11.0"
retrofit = "3.0.0"
okhttp = "5.5.0"
coil = "3.6.2"
junit = "4.13.2"
robolectric = "4.16.1"
turbine = "1.2.0"
androidxTestJunit = "1.3.0"
androidxTestRunner = "1.6.2"

[libraries]
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
core-splashscreen = { group = "androidx.core", name = "core-splashscreen", version.ref = "splashscreen" }
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-android-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hiltAndroidx" }
hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hiltAndroidx" }
hilt-androidx-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hiltAndroidx" }
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
glance-appwidget = { group = "androidx.glance", name = "glance-appwidget", version.ref = "glance" }
glance-material3 = { group = "androidx.glance", name = "glance-material3", version.ref = "glance" }
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
coil-compose = { group = "io.coil-kt.coil3", name = "coil-compose", version.ref = "coil" }
coil-okhttp = { group = "io.coil-kt.coil3", name = "coil-network-okhttp", version.ref = "coil" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
androidx-test-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxTestJunit" }
androidx-test-runner = { group = "androidx.test", name = "runner", version.ref = "androidxTestRunner" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 3: App module build file**

Create `app/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "it.apexweather"
    compileSdk = 37

    defaultConfig {
        applicationId = "it.apexweather"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
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
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
```

Create `app/proguard-rules.pro`:
```
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class it.apexweather.**$$serializer { *; }
-keepclassmembers class it.apexweather.** { *** Companion; }
-keepclasseswithmembers class it.apexweather.** { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
```

- [ ] **Step 4: Manifest, Application, Activity, resources**

Create `app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <application
        android:name=".ApexApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.ApexWeather.Splash"
        android:localeConfig="@xml/locales_config">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|locale|layoutDirection"
            android:theme="@style/Theme.ApexWeather.Splash">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Per-app language persistence for API < 33 -->
        <service
            android:name="androidx.appcompat.app.AppLocalesMetadataHolderService"
            android:enabled="false"
            android:exported="false">
            <meta-data android:name="autoStoreLocales" android:value="true" />
        </service>

        <!-- WorkManager: disable default initializer, ApexApplication provides configuration -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

Create `app/src/main/res/xml/locales_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="de" />
    <locale android:name="it" />
    <locale android:name="en" />
</locale-config>
```

Create `app/src/main/kotlin/it/apexweather/ApexApplication.kt`:
```kotlin
package it.apexweather

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ApexApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

Create `app/src/main/kotlin/it/apexweather/MainActivity.kt` (placeholder UI, replaced in Task 14):
```kotlin
package it.apexweather

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme { Text("Apex Weather") }
        }
    }
}
```

Create `app/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ApexWeather" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="android:windowBackground">#FF0B1020</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
    <style name="Theme.ApexWeather.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">#FF0B1020</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <item name="postSplashScreenTheme">@style/Theme.ApexWeather</item>
    </style>
</resources>
```

Create `app/src/main/res/values/strings.xml` (German default; the full key set is added in Task 14, this task only needs `app_name`):
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Apex Weather</string>
</resources>
```
Create `app/src/main/res/values-it/strings.xml` and `app/src/main/res/values-en/strings.xml` with the same single `app_name` entry.

Create `app/src/main/res/drawable/ic_launcher_background.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#0B1020" android:pathData="M0,0h108v108H0z" />
    <path android:fillColor="#1E3A8A" android:pathData="M0,108 L0,60 Q54,20 108,60 L108,108 Z" />
</vector>
```

Create `app/src/main/res/drawable/ic_launcher_foreground.xml` (sun over a ridge):
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFB84D" android:pathData="M54,30 m-14,0 a14,14 0 1,1 28,0 a14,14 0 1,1 -28,0" />
    <path android:fillColor="#F1F5F9" android:pathData="M18,82 L38,50 L50,66 L62,44 L78,68 L90,82 Z" />
    <path android:fillColor="#94A3B8" android:pathData="M18,82 L38,50 L46,61 L34,82 Z" />
</vector>
```

Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 5: Smoke unit test**

Create `app/src/test/kotlin/it/apexweather/SmokeTest.kt`:
```kotlin
package it.apexweather

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun `build wiring works`() {
        assertEquals(4, 2 + 2)
    }
}
```

- [ ] **Step 6: Build, test, install**

Run:
```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. If the build complains that `org.jetbrains.kotlin.android` must not be applied, confirm `app/build.gradle.kts` has no `kotlin.android` alias. If Hilt reports a missing `HiltWorkerFactory` binding, the `hilt-work` + `hilt-androidx-compiler` KSP lines are missing.

Run:
```bash
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk && adb -s RZCXA1ZEXJE shell am start -n it.apexweather/.MainActivity
```
Expected: app opens on the phone showing "Apex Weather".

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: scaffold Apex Weather Android project (Compose, Hilt, Room, WorkManager, Glance)"
```

---

### Task 2: Domain models and ConsensusBlender

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/domain/model/Models.kt`
- Create: `app/src/main/kotlin/it/apexweather/domain/model/Serializers.kt`
- Create: `app/src/main/kotlin/it/apexweather/domain/DailyAggregator.kt`
- Create: `app/src/main/kotlin/it/apexweather/domain/ConsensusBlender.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/ConsensusBlenderTest.kt`, `app/src/test/kotlin/it/apexweather/domain/DailyAggregatorTest.kt`

**Interfaces:**
- Produces: everything in `Models.kt` below (used by every later task), `DailyAggregator.aggregate(hourly: List<HourlyPoint>, zone: ZoneId, sunTimes: Map<LocalDate, Pair<Instant?, Instant?>> = emptyMap()): List<DailyPoint>`, `ConsensusBlender(zone).blend(forecasts: Map<Source, SourceForecast>): ConsensusForecast`.

- [ ] **Step 1: Models**

Create `app/src/main/kotlin/it/apexweather/domain/model/Serializers.kt`:
```kotlin
package it.apexweather.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
```

Create `app/src/main/kotlin/it/apexweather/domain/model/Models.kt`:
```kotlin
package it.apexweather.domain.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

/** Forecast sources. [regional] sources always take part in the consensus; ECMWF only fills gaps. */
enum class Source(val displayName: String, val regional: Boolean) {
    SIAG_KMOS("Südtirol KMOS", true),
    GEOSPHERE_AROME("GeoSphere AROME", true),
    ICON_CH1("MeteoSwiss ICON-CH1", true),
    ICON_CH2("MeteoSwiss ICON-CH2", true),
    ICON_2I("ItaliaMeteo ICON-2I", true),
    ICON_D2("DWD ICON-D2", true),
    ECMWF("ECMWF IFS", false);

    val staleAfterHours: Int get() = if (regional) 6 else 12
}

/** Declaration order = severity order (used for tie-breaks and "worst of day"). */
enum class Condition {
    CLEAR, MOSTLY_CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN,
    SLEET, SNOW, HEAVY_SNOW, THUNDERSTORM;

    val isPrecipitation: Boolean get() = ordinal >= DRIZZLE.ordinal
}

@Serializable
data class HourlyPoint(
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val tempC: Double,
    val feelsLikeC: Double? = null,
    val precipMm: Double = 0.0,
    val precipProb: Int? = null,
    val windKmh: Double = 0.0,
    val gustKmh: Double? = null,
    val windDirDeg: Int? = null,
    val cloudPct: Int? = null,
    val humidityPct: Int? = null,
    val condition: Condition,
)

@Serializable
data class DailyPoint(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val minC: Double,
    val maxC: Double,
    val precipMm: Double,
    val condition: Condition,
    @Serializable(with = InstantSerializer::class) val sunrise: Instant? = null,
    @Serializable(with = InstantSerializer::class) val sunset: Instant? = null,
)

@Serializable
data class SourceForecast(
    val source: Source,
    @Serializable(with = InstantSerializer::class) val issuedAt: Instant,
    @Serializable(with = InstantSerializer::class) val fetchedAt: Instant,
    val hourly: List<HourlyPoint>,
    val daily: List<DailyPoint>,
)

@Serializable
data class BulletinCondition(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val title: String,
    val description: String,
    val temperatures: String?,
    val mapImageUrl: String?,
)

@Serializable
data class BulletinDay(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val code: String,
    val description: String,
    val iconUrl: String?,
    val minC: Double?,
    val maxC: Double?,
    val rainFromMm: Double?,
    val rainToMm: Double?,
    val thunderstormLevel: Int?,
)

@Serializable
data class Bulletin(
    val language: String,
    @Serializable(with = InstantSerializer::class) val issuedAt: Instant,
    val title: String,
    val evolution: String,
    val conditions: List<BulletinCondition>,
    val days: List<BulletinDay>,
)

@Serializable
data class StationObservation(
    val stationName: String,
    @Serializable(with = InstantSerializer::class) val time: Instant,
    val tempC: Double?,
    val humidityPct: Int?,
    val windKmh: Double?,
    val windDir: String?,
    val gustKmh: Double?,
    val precipMm: Double?,
    val pressureHpa: Double?,
)

sealed interface SourceStatus {
    data class Ok(val issuedAt: Instant) : SourceStatus
    data class Stale(val issuedAt: Instant) : SourceStatus
    data class Failed(val reason: String, val lastIssuedAt: Instant?) : SourceStatus
}

data class WeatherSnapshot(
    val forecasts: Map<Source, SourceForecast>,
    val bulletin: Bulletin?,
    val observation: StationObservation?,
    val status: Map<Source, SourceStatus>,
    val bulletinStatus: SourceStatus?,
    val observationStatus: SourceStatus?,
    val lastSuccessfulRefresh: Instant?,
    val lastRefreshFailed: Boolean,
) {
    val isEmpty: Boolean get() = forecasts.isEmpty() && bulletin == null && observation == null
    companion object {
        val EMPTY = WeatherSnapshot(emptyMap(), null, null, emptyMap(), null, null, null, false)
    }
}

data class ConsensusHour(
    val time: Instant,
    val tempC: Double,
    val tempMinC: Double,
    val tempMaxC: Double,
    val feelsLikeC: Double?,
    val precipMm: Double,
    val precipProb: Int,
    val windKmh: Double,
    val gustKmh: Double?,
    val condition: Condition,
    val agreement: Float,
    val sourceCount: Int,
    val perSource: Map<Source, HourlyPoint>,
)

data class ConsensusDay(
    val date: LocalDate,
    val minC: Double,
    val maxC: Double,
    val precipMm: Double,
    val condition: Condition,
    val agreement: Float,
    val sunrise: Instant?,
    val sunset: Instant?,
)

data class ConsensusForecast(val hourly: List<ConsensusHour>, val daily: List<ConsensusDay>) {
    companion object { val EMPTY = ConsensusForecast(emptyList(), emptyList()) }
}
```

- [ ] **Step 2: Failing tests for DailyAggregator and ConsensusBlender**

Create `app/src/test/kotlin/it/apexweather/domain/TestData.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import java.time.Instant
import java.time.ZoneId

val ROME: ZoneId = ZoneId.of("Europe/Rome")
val T0: Instant = Instant.parse("2026-09-08T00:00:00Z")

fun hour(i: Int): Instant = T0.plusSeconds(i * 3600L)

fun point(
    i: Int,
    temp: Double,
    precip: Double = 0.0,
    prob: Int? = null,
    wind: Double = 5.0,
    gust: Double? = null,
    condition: Condition = Condition.CLEAR,
) = HourlyPoint(
    time = hour(i), tempC = temp, precipMm = precip, precipProb = prob,
    windKmh = wind, gustKmh = gust, condition = condition,
)

fun forecast(source: Source, hourly: List<HourlyPoint>) = SourceForecast(
    source = source, issuedAt = T0, fetchedAt = T0, hourly = hourly, daily = emptyList(),
)
```

Create `app/src/test/kotlin/it/apexweather/domain/DailyAggregatorTest.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailyAggregatorTest {
    @Test
    fun `aggregates min max precip and daytime-worst condition per local day`() {
        // 2026-09-08T00:00Z is 02:00 local. Hours 0..23 UTC cover local 02:00 .. 01:00 next day.
        val hours = (0 until 24).map { i ->
            point(i, temp = 10.0 + i, precip = 0.5,
                condition = if (i == 22) Condition.THUNDERSTORM else Condition.CLEAR) // i=22 → 00:00 local next day
        }
        val days = DailyAggregator.aggregate(hours, ROME)
        assertEquals(2, days.size)
        val d0 = days[0]
        assertEquals(LocalDate.of(2026, 9, 8), d0.date)
        assertEquals(10.0, d0.minC, 0.0)
        assertEquals(31.0, d0.maxC, 0.0) // i=21 → 23:00 local
        assertEquals(11.0, d0.precipMm, 1e-9)
        assertEquals(Condition.CLEAR, d0.condition) // thunderstorm is on the next local day
        assertEquals(Condition.THUNDERSTORM, days[1].condition) // outside 06-22 but only hours → worst of all
    }

    @Test
    fun `daytime window wins over night hours when both exist`() {
        val hours = (0 until 24).map { i ->
            // local hour = i + 2; night hour 03:00 local (i=1) stormy, day hours rain at 12:00 local (i=10)
            val c = when (i) { 1 -> Condition.THUNDERSTORM; 10 -> Condition.RAIN; else -> Condition.CLEAR }
            point(i, temp = 15.0, condition = c)
        }
        val day = DailyAggregator.aggregate(hours, ROME).first { it.date == LocalDate.of(2026, 9, 8) }
        assertEquals(Condition.RAIN, day.condition)
    }
}
```

Create `app/src/test/kotlin/it/apexweather/domain/ConsensusBlenderTest.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsensusBlenderTest {
    private val blender = ConsensusBlender(ROME)

    @Test
    fun `median temperature and min max band from regional sources`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 14.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 12.0))),
        )
        val h = blender.blend(f).hourly.single()
        assertEquals(12.0, h.tempC, 0.0)
        assertEquals(10.0, h.tempMinC, 0.0)
        assertEquals(14.0, h.tempMaxC, 0.0)
        assertEquals(3, h.sourceCount)
    }

    @Test
    fun `even count uses mean of the two middle values`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 20.0))),
        )
        assertEquals(15.0, blender.blend(f).hourly.single().tempC, 0.0)
    }

    @Test
    fun `ECMWF excluded when two or more regional sources cover the hour`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 12.0))),
            Source.ECMWF to forecast(Source.ECMWF, listOf(point(0, 30.0))),
        )
        val h = blender.blend(f).hourly.single()
        assertEquals(11.0, h.tempC, 0.0)
        assertEquals(2, h.sourceCount)
    }

    @Test
    fun `ECMWF included when fewer than two regional sources cover the hour`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ECMWF to forecast(Source.ECMWF, listOf(point(0, 30.0), point(1, 31.0))),
        )
        val hours = blender.blend(f).hourly
        assertEquals(20.0, hours[0].tempC, 0.0)
        assertEquals(2, hours[0].sourceCount)
        assertEquals(31.0, hours[1].tempC, 0.0)
        assertEquals(1, hours[1].sourceCount)
    }

    @Test
    fun `precip probability is max of model probabilities`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, prob = 20))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, prob = 60))),
        )
        assertEquals(60, blender.blend(f).hourly.single().precipProb)
    }

    @Test
    fun `precip probability falls back to share of models with rain`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, precip = 0.5))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, precip = 0.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, precip = 1.2))),
        )
        assertEquals(67, blender.blend(f).hourly.single().precipProb)
    }

    @Test
    fun `condition majority vote with severity tie-break`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, condition = Condition.RAIN))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, condition = Condition.CLOUDY))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, condition = Condition.CLOUDY))),
            Source.ICON_CH2 to forecast(Source.ICON_CH2, listOf(point(0, 10.0, condition = Condition.RAIN))),
        )
        assertEquals(Condition.RAIN, blender.blend(f).hourly.single().condition)
    }

    @Test
    fun `agreement drops with spread and is 0_5 for a single source`() {
        val tight = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 11.5))),
        )
        assertEquals(0.75f, blender.blend(tight).hourly.single().agreement, 1e-6f)
        val wide = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 20.0))),
        )
        assertEquals(0f, blender.blend(wide).hourly.single().agreement, 1e-6f)
        val single = mapOf(Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0))))
        val h = blender.blend(single).hourly.single()
        assertEquals(0.5f, h.agreement, 1e-6f)
        assertEquals(h.tempC, h.tempMinC, 0.0)
        assertEquals(h.tempC, h.tempMaxC, 0.0)
    }

    @Test
    fun `gust is max, wind is median`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, listOf(point(0, 10.0, wind = 10.0, gust = 30.0))),
            Source.ICON_D2 to forecast(Source.ICON_D2, listOf(point(0, 10.0, wind = 20.0, gust = 50.0))),
            Source.ICON_2I to forecast(Source.ICON_2I, listOf(point(0, 10.0, wind = 12.0, gust = null))),
        )
        val h = blender.blend(f).hourly.single()
        assertEquals(12.0, h.windKmh, 0.0)
        assertEquals(50.0, h.gustKmh!!, 0.0)
    }

    @Test
    fun `daily is aggregated from consensus hourly`() {
        val f = mapOf(
            Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 24).map { point(it, 10.0 + it, precip = 1.0) }),
            Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 12.0 + it, precip = 3.0) }),
        )
        val days = blender.blend(f).daily
        assertTrue(days.isNotEmpty())
        val d0 = days[0]
        assertEquals(11.0, d0.minC, 0.0)
        assertEquals(32.0, d0.maxC, 0.0)
        assertEquals(44.0, d0.precipMm, 1e-9) // 22 local hours on 2026-09-08 × median 2.0
    }

    @Test
    fun `empty input yields empty consensus`() {
        val c = blender.blend(emptyMap())
        assertTrue(c.hourly.isEmpty())
        assertTrue(c.daily.isEmpty())
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.*' --console=plain 2>&1 | tail -15`
Expected: compilation failure, `Unresolved reference 'DailyAggregator'` / `'ConsensusBlender'`.

- [ ] **Step 4: Implement DailyAggregator and ConsensusBlender**

Create `app/src/main/kotlin/it/apexweather/domain/DailyAggregator.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.HourlyPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Aggregates hourly points into local calendar days. Shared by source mappers and the blender. */
object DailyAggregator {

    private const val DAY_START_HOUR = 6
    private const val DAY_END_HOUR = 22 // exclusive

    fun aggregate(
        hourly: List<HourlyPoint>,
        zone: ZoneId,
        sunTimes: Map<LocalDate, Pair<Instant?, Instant?>> = emptyMap(),
    ): List<DailyPoint> {
        if (hourly.isEmpty()) return emptyList()
        return hourly.groupBy { it.time.atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (date, points) ->
                DailyPoint(
                    date = date,
                    minC = points.minOf { it.tempC },
                    maxC = points.maxOf { it.tempC },
                    precipMm = points.sumOf { it.precipMm },
                    condition = worstCondition(points.map { it.time.atZone(zone).hour to it.condition }),
                    sunrise = sunTimes[date]?.first,
                    sunset = sunTimes[date]?.second,
                )
            }
    }

    /** Worst condition between 06:00 and 22:00 local; if no hours in that window, worst of all. */
    fun worstCondition(hourAndCondition: List<Pair<Int, Condition>>): Condition {
        val daytime = hourAndCondition.filter { it.first in DAY_START_HOUR until DAY_END_HOUR }
        val pool = if (daytime.isNotEmpty()) daytime else hourAndCondition
        return pool.maxOf { it.second }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/domain/ConsensusBlender.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

class ConsensusBlender(private val zone: ZoneId = ZoneId.of("Europe/Rome")) {

    fun blend(forecasts: Map<Source, SourceForecast>): ConsensusForecast {
        if (forecasts.isEmpty()) return ConsensusForecast.EMPTY

        // time → (source → point), times truncated to the hour
        val byTime = sortedMapOf<Instant, MutableMap<Source, HourlyPoint>>()
        forecasts.values.forEach { f ->
            f.hourly.forEach { p ->
                val t = p.time.truncatedTo(ChronoUnit.HOURS)
                byTime.getOrPut(t) { mutableMapOf() }[f.source] = p
            }
        }

        val hourly = byTime.mapNotNull { (time, bySource) ->
            val regional = bySource.filterKeys { it.regional }
            val contributing = if (regional.size >= 2) regional else bySource
            if (contributing.isEmpty()) return@mapNotNull null
            blendHour(time, contributing)
        }

        val sunTimes = forecasts.values.flatMap { it.daily }
            .filter { it.sunrise != null }
            .associate { it.date to (it.sunrise to it.sunset) }

        val daily = DailyAggregator.aggregate(
            hourly.map { h ->
                HourlyPoint(
                    time = h.time, tempC = h.tempC, precipMm = h.precipMm, precipProb = h.precipProb,
                    windKmh = h.windKmh, gustKmh = h.gustKmh, condition = h.condition,
                )
            },
            zone, sunTimes,
        ).map { d ->
            val agreements = hourly.filter { it.time.atZone(zone).toLocalDate() == d.date }.map { it.agreement }
            ConsensusDay(
                date = d.date, minC = d.minC, maxC = d.maxC, precipMm = d.precipMm,
                condition = d.condition, agreement = agreements.average().toFloat(),
                sunrise = d.sunrise, sunset = d.sunset,
            )
        }
        return ConsensusForecast(hourly, daily)
    }

    private fun blendHour(time: Instant, points: Map<Source, HourlyPoint>): ConsensusHour {
        val values = points.values
        val temps = values.map { it.tempC }
        val tMin = temps.min()
        val tMax = temps.max()
        val spread = tMax - tMin
        val agreement = if (values.size == 1) 0.5f else (1.0 - (spread / 6.0).coerceIn(0.0, 1.0)).toFloat()

        val probs = values.mapNotNull { it.precipProb }
        val precipProb = if (probs.isNotEmpty()) probs.max()
        else (100.0 * values.count { it.precipMm > 0.1 } / values.size).roundToInt()

        val feels = values.mapNotNull { it.feelsLikeC }
        val gusts = values.mapNotNull { it.gustKmh }

        return ConsensusHour(
            time = time,
            tempC = median(temps),
            tempMinC = tMin,
            tempMaxC = tMax,
            feelsLikeC = feels.takeIf { it.isNotEmpty() }?.let(::median),
            precipMm = median(values.map { it.precipMm }),
            precipProb = precipProb,
            windKmh = median(values.map { it.windKmh }),
            gustKmh = gusts.maxOrNull(),
            condition = voteCondition(values.map { it.condition }),
            agreement = agreement,
            sourceCount = values.size,
            perSource = points,
        )
    }

    companion object {
        fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }

        /** Majority vote; ties resolved toward the more severe condition. */
        fun voteCondition(conditions: List<Condition>): Condition =
            conditions.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<Condition, Int>> { it.value }.thenByDescending { it.key })
                .first().key
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.*' --console=plain 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`, 13 tests passed.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(domain): models, daily aggregation and consensus blender"
```

---

### Task 3: Sun phase, sky palettes, code tables

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/domain/SunPhase.kt`
- Create: `app/src/main/kotlin/it/apexweather/domain/SkyPalette.kt`
- Create: `app/src/main/kotlin/it/apexweather/domain/WmoCodes.kt`
- Create: `app/src/main/kotlin/it/apexweather/domain/SiagCodes.kt`
- Test: `app/src/test/kotlin/it/apexweather/domain/SunPhaseTest.kt`, `app/src/test/kotlin/it/apexweather/domain/SkyPaletteSelectorTest.kt`, `app/src/test/kotlin/it/apexweather/domain/CodeTablesTest.kt`

**Interfaces:**
- Produces: `enum SunPhase { NIGHT, DAWN, DAY, DUSK }`, `SunPhaseCalculator.phase(now: Instant, sunrise: Instant?, sunset: Instant?, zone: ZoneId): SunPhase`, `data class SkyPalette(top: Long, mid: Long, bottom: Long, accent: Long, particle: ParticleKind, density: Float, ridge: Long)`, `enum ParticleKind { NONE, STARS, CLOUDS, RAIN, SNOW, FOG, LIGHTNING }`, `SkyPaletteSelector.select(condition, phase, precipMm): SkyPalette`, `WmoCodes.toCondition(code: Int?): Condition`, `SiagCodes.toCondition(letter: String?): Condition`, `SiagCodes.iconUrl(letter: String): String`.

- [ ] **Step 1: Failing tests**

Create `app/src/test/kotlin/it/apexweather/domain/SunPhaseTest.kt`:
```kotlin
package it.apexweather.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SunPhaseTest {
    private val sunrise = Instant.parse("2026-09-08T04:44:00Z") // 06:44 local
    private val sunset = Instant.parse("2026-09-08T17:41:00Z")  // 19:41 local

    @Test fun night() = assertEquals(SunPhase.NIGHT, SunPhaseCalculator.phase(Instant.parse("2026-09-08T01:00:00Z"), sunrise, sunset, ROME))
    @Test fun dawn() = assertEquals(SunPhase.DAWN, SunPhaseCalculator.phase(Instant.parse("2026-09-08T04:30:00Z"), sunrise, sunset, ROME))
    @Test fun day() = assertEquals(SunPhase.DAY, SunPhaseCalculator.phase(Instant.parse("2026-09-08T10:00:00Z"), sunrise, sunset, ROME))
    @Test fun dusk() = assertEquals(SunPhase.DUSK, SunPhaseCalculator.phase(Instant.parse("2026-09-08T18:00:00Z"), sunrise, sunset, ROME))
    @Test fun `late evening is night`() = assertEquals(SunPhase.NIGHT, SunPhaseCalculator.phase(Instant.parse("2026-09-08T20:00:00Z"), sunrise, sunset, ROME))

    @Test fun `fallback without sun times uses 07 to 19 local`() {
        assertEquals(SunPhase.DAY, SunPhaseCalculator.phase(Instant.parse("2026-09-08T10:00:00Z"), null, null, ROME))
        assertEquals(SunPhase.NIGHT, SunPhaseCalculator.phase(Instant.parse("2026-09-08T22:00:00Z"), null, null, ROME))
    }
}
```

Create `app/src/test/kotlin/it/apexweather/domain/SkyPaletteSelectorTest.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPaletteSelectorTest {
    @Test fun `clear night has stars`() =
        assertEquals(ParticleKind.STARS, SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0).particle)

    @Test fun `rain has rain particles with density scaled by mm`() {
        val light = SkyPaletteSelector.select(Condition.RAIN, SunPhase.DAY, 0.5)
        val heavy = SkyPaletteSelector.select(Condition.HEAVY_RAIN, SunPhase.DAY, 8.0)
        assertEquals(ParticleKind.RAIN, light.particle)
        assertTrue(heavy.density > light.density)
        assertTrue(heavy.density <= 1f)
    }

    @Test fun `snow and thunderstorm map to their particle kinds`() {
        assertEquals(ParticleKind.SNOW, SkyPaletteSelector.select(Condition.SNOW, SunPhase.DAY, 1.0).particle)
        assertEquals(ParticleKind.LIGHTNING, SkyPaletteSelector.select(Condition.THUNDERSTORM, SunPhase.NIGHT, 3.0).particle)
        assertEquals(ParticleKind.FOG, SkyPaletteSelector.select(Condition.FOG, SunPhase.DAY, 0.0).particle)
    }

    @Test fun `phase changes clear palette`() {
        val day = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.DAY, 0.0)
        val dusk = SkyPaletteSelector.select(Condition.CLEAR, SunPhase.DUSK, 0.0)
        assertNotEquals(day.top, dusk.top)
    }
}
```

Create `app/src/test/kotlin/it/apexweather/domain/CodeTablesTest.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition
import org.junit.Assert.assertEquals
import org.junit.Test

class CodeTablesTest {
    @Test fun `wmo codes`() {
        assertEquals(Condition.CLEAR, WmoCodes.toCondition(0))
        assertEquals(Condition.PARTLY_CLOUDY, WmoCodes.toCondition(2))
        assertEquals(Condition.FOG, WmoCodes.toCondition(45))
        assertEquals(Condition.DRIZZLE, WmoCodes.toCondition(53))
        assertEquals(Condition.RAIN, WmoCodes.toCondition(61))
        assertEquals(Condition.HEAVY_RAIN, WmoCodes.toCondition(65))
        assertEquals(Condition.HEAVY_RAIN, WmoCodes.toCondition(82))
        assertEquals(Condition.SLEET, WmoCodes.toCondition(66))
        assertEquals(Condition.SNOW, WmoCodes.toCondition(71))
        assertEquals(Condition.HEAVY_SNOW, WmoCodes.toCondition(75))
        assertEquals(Condition.THUNDERSTORM, WmoCodes.toCondition(95))
        assertEquals(Condition.CLOUDY, WmoCodes.toCondition(null))
    }

    @Test fun `siag letters`() {
        assertEquals(Condition.CLEAR, SiagCodes.toCondition("a"))
        assertEquals(Condition.MOSTLY_CLEAR, SiagCodes.toCondition("b"))
        assertEquals(Condition.CLOUDY, SiagCodes.toCondition("e"))
        assertEquals(Condition.RAIN, SiagCodes.toCondition("f"))
        assertEquals(Condition.HEAVY_RAIN, SiagCodes.toCondition("i"))
        assertEquals(Condition.DRIZZLE, SiagCodes.toCondition("j"))
        assertEquals(Condition.SNOW, SiagCodes.toCondition("n"))
        assertEquals(Condition.HEAVY_SNOW, SiagCodes.toCondition("p"))
        assertEquals(Condition.SLEET, SiagCodes.toCondition("r"))
        assertEquals(Condition.FOG, SiagCodes.toCondition("t"))
        assertEquals(Condition.THUNDERSTORM, SiagCodes.toCondition("u"))
        assertEquals(Condition.THUNDERSTORM, SiagCodes.toCondition("z"))
        assertEquals(Condition.CLEAR, SiagCodes.toCondition("a_n")) // KMOS day/night suffix
        assertEquals(Condition.CLOUDY, SiagCodes.toCondition(null))
        assertEquals("https://api-weather.services.siag.it/api/v2/graphics/icons/HDimgsource/wetter/icon_7.png", SiagCodes.iconUrl("g"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.*' --console=plain 2>&1 | tail -10`
Expected: compilation failure (unresolved `SunPhase`, `SkyPaletteSelector`, `WmoCodes`, `SiagCodes`).

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/it/apexweather/domain/SunPhase.kt`:
```kotlin
package it.apexweather.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class SunPhase { NIGHT, DAWN, DAY, DUSK }

object SunPhaseCalculator {
    private val twilight: Duration = Duration.ofMinutes(45)

    fun phase(now: Instant, sunrise: Instant?, sunset: Instant?, zone: ZoneId): SunPhase {
        if (sunrise == null || sunset == null) {
            val h = now.atZone(zone).hour
            return if (h in 7 until 19) SunPhase.DAY else SunPhase.NIGHT
        }
        return when {
            now.isBefore(sunrise.minus(twilight)) -> SunPhase.NIGHT
            now.isBefore(sunrise.plus(twilight)) -> SunPhase.DAWN
            now.isBefore(sunset.minus(twilight)) -> SunPhase.DAY
            now.isBefore(sunset.plus(twilight)) -> SunPhase.DUSK
            else -> SunPhase.NIGHT
        }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/domain/SkyPalette.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition

enum class ParticleKind { NONE, STARS, CLOUDS, RAIN, SNOW, FOG, LIGHTNING }

/** Colors are ARGB longs so this stays free of Android/Compose dependencies. */
data class SkyPalette(
    val top: Long,
    val mid: Long,
    val bottom: Long,
    val accent: Long,
    val ridge: Long,
    val particle: ParticleKind,
    val density: Float,
)

object SkyPaletteSelector {

    private fun p(top: Long, mid: Long, bottom: Long, accent: Long, ridge: Long, particle: ParticleKind, density: Float = 0f) =
        SkyPalette(top, mid, bottom, accent, ridge, particle, density)

    fun select(condition: Condition, phase: SunPhase, precipMm: Double): SkyPalette {
        val precipDensity = (0.25 + precipMm / 6.0).coerceIn(0.25, 1.0).toFloat()
        return when (condition) {
            Condition.CLEAR, Condition.MOSTLY_CLEAR -> when (phase) {
                SunPhase.DAY -> p(0xFF1E63C9, 0xFF4F9BE8, 0xFFA9D6F5, 0xFFFFD166, 0xFF2E4A7A, ParticleKind.NONE)
                SunPhase.DAWN -> p(0xFF1B2C5C, 0xFFD97A5A, 0xFFF6C177, 0xFFFFB26B, 0xFF2A2F4A, ParticleKind.NONE)
                SunPhase.DUSK -> p(0xFF221B4E, 0xFF9B3F7A, 0xFFF0895A, 0xFFFF9E6B, 0xFF1F1B3A, ParticleKind.NONE)
                SunPhase.NIGHT -> p(0xFF05081A, 0xFF0F1B3D, 0xFF1F3160, 0xFFE9EDF7, 0xFF0A0F24, ParticleKind.STARS, 0.6f)
            }
            Condition.PARTLY_CLOUDY -> when (phase) {
                SunPhase.DAY -> p(0xFF2B5FA8, 0xFF5E93CF, 0xFFB8CFE6, 0xFFFFD166, 0xFF2F4468, ParticleKind.CLOUDS, 0.35f)
                SunPhase.DAWN, SunPhase.DUSK -> p(0xFF2A2650, 0xFF8A5C7A, 0xFFE4A07A, 0xFFFFB26B, 0xFF262640, ParticleKind.CLOUDS, 0.35f)
                SunPhase.NIGHT -> p(0xFF070B1E, 0xFF15213F, 0xFF2A3A5E, 0xFFDDE3F0, 0xFF0B1024, ParticleKind.STARS, 0.3f)
            }
            Condition.CLOUDY -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0B0F1C, 0xFF1C2333, 0xFF2E3648, 0xFFC9CFDB, 0xFF0D111C, ParticleKind.CLOUDS, 0.6f)
                else -> p(0xFF4A5568, 0xFF718096, 0xFFA0AEC0, 0xFFE2E8F0, 0xFF3A4352, ParticleKind.CLOUDS, 0.6f)
            }
            Condition.FOG -> p(0xFF6B7280, 0xFF9CA3AF, 0xFFD1D5DB, 0xFFF3F4F6, 0xFF5B6270, ParticleKind.FOG, 0.8f)
            Condition.DRIZZLE, Condition.RAIN -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0A0E1A, 0xFF141C2E, 0xFF20304A, 0xFF8FB3E8, 0xFF0B101C, ParticleKind.RAIN, precipDensity)
                else -> p(0xFF2F3E55, 0xFF4B5D7A, 0xFF7C8FA8, 0xFFA9C8F5, 0xFF26334A, ParticleKind.RAIN, precipDensity)
            }
            Condition.HEAVY_RAIN -> p(0xFF141B29, 0xFF243247, 0xFF3A4C66, 0xFF8FB3E8, 0xFF10161F, ParticleKind.RAIN, precipDensity.coerceAtLeast(0.7f))
            Condition.SLEET -> p(0xFF2E3A4E, 0xFF4E5D74, 0xFF8494AA, 0xFFD6E4F5, 0xFF26303F, ParticleKind.SNOW, precipDensity)
            Condition.SNOW -> when (phase) {
                SunPhase.NIGHT -> p(0xFF0E1526, 0xFF1F2B45, 0xFF3B4A68, 0xFFF1F5FF, 0xFF141C2E, ParticleKind.SNOW, precipDensity)
                else -> p(0xFF5B6B85, 0xFF8FA0BA, 0xFFD9E2EF, 0xFFFFFFFF, 0xFF4B5A73, ParticleKind.SNOW, precipDensity)
            }
            Condition.HEAVY_SNOW -> p(0xFF3D4A62, 0xFF6C7B95, 0xFFC5D0E0, 0xFFFFFFFF, 0xFF33405A, ParticleKind.SNOW, precipDensity.coerceAtLeast(0.7f))
            Condition.THUNDERSTORM -> p(0xFF0B0A1A, 0xFF1E1A3A, 0xFF2E2B52, 0xFFFFE28A, 0xFF0C0B1A, ParticleKind.LIGHTNING, precipDensity.coerceAtLeast(0.6f))
        }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/domain/WmoCodes.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition

/** WMO 4677 weather interpretation codes as used by Open-Meteo. */
object WmoCodes {
    fun toCondition(code: Int?): Condition = when (code) {
        0 -> Condition.CLEAR
        1 -> Condition.MOSTLY_CLEAR
        2 -> Condition.PARTLY_CLOUDY
        3 -> Condition.CLOUDY
        45, 48 -> Condition.FOG
        51, 53, 55, 56, 57 -> Condition.DRIZZLE
        61, 63, 80 -> Condition.RAIN
        65, 81, 82 -> Condition.HEAVY_RAIN
        66, 67 -> Condition.SLEET
        71, 73, 77, 85 -> Condition.SNOW
        75, 86 -> Condition.HEAVY_SNOW
        95, 96, 99 -> Condition.THUNDERSTORM
        else -> Condition.CLOUDY
    }
}
```

Create `app/src/main/kotlin/it/apexweather/domain/SiagCodes.kt`:
```kotlin
package it.apexweather.domain

import it.apexweather.domain.model.Condition

/**
 * Landeswetterdienst Südtirol symbol letters. Icon number = position in the alphabet (a=1 … z=26).
 * Table verified against the bulletin history and the icon set on 2026-09-08.
 */
object SiagCodes {
    private const val ICON_BASE = "https://api-weather.services.siag.it/api/v2/graphics/icons/HDimgsource/wetter/icon_"

    private val table: Map<Char, Condition> = mapOf(
        'a' to Condition.CLEAR,          // Wolkenlos
        'b' to Condition.MOSTLY_CLEAR,   // Heiter
        'c' to Condition.PARTLY_CLOUDY,  // Wolkig
        'd' to Condition.CLOUDY,         // Stark bewölkt
        'e' to Condition.CLOUDY,         // Bedeckt
        'f' to Condition.RAIN,           // Wolkig, mäßiger Regen
        'g' to Condition.HEAVY_RAIN,     // Wolkig, starker Regen
        'h' to Condition.RAIN,           // Bedeckt, mäßiger Regen
        'i' to Condition.HEAVY_RAIN,     // Bedeckt, starker Regen
        'j' to Condition.DRIZZLE,        // Bedeckt, leichter Regen
        'k' to Condition.MOSTLY_CLEAR,   // Durchscheinende Bewölkung
        'l' to Condition.SNOW,           // Wolkig, leichter Schneefall
        'm' to Condition.SNOW,           // Wolkig, mäßiger Schneefall
        'n' to Condition.SNOW,           // Bedeckt, leichter Schneefall
        'o' to Condition.SNOW,           // Bedeckt, mäßiger Schneefall
        'p' to Condition.HEAVY_SNOW,     // Bedeckt, starker Schneefall
        'q' to Condition.SLEET,          // Wolkig, Schneeregen
        'r' to Condition.SLEET,          // Bedeckt, Schneeregen
        's' to Condition.FOG,            // Hochnebel / Nebel mit Sonne
        't' to Condition.FOG,            // Talnebel
        'u' to Condition.THUNDERSTORM,   // Wolkig, Gewitter mit mäßigen Schauern
        'v' to Condition.THUNDERSTORM,   // Bedeckt, Gewitter mit starken Schauern
        'w' to Condition.THUNDERSTORM,   // Wolkig, Gewitter mit Schneeregen
        'x' to Condition.THUNDERSTORM,   // Bedeckt, Gewitter mit Schneeregen
        'y' to Condition.THUNDERSTORM,   // Wolkig, Gewitter mit Schnee
        'z' to Condition.THUNDERSTORM,   // Bedeckt, Gewitter mit Schnee
    )

    /** Accepts "g", "g_d", "g_n" (KMOS appends a day/night suffix). */
    fun toCondition(letter: String?): Condition {
        val c = letter?.trim()?.lowercase()?.firstOrNull() ?: return Condition.CLOUDY
        return table[c] ?: Condition.CLOUDY
    }

    fun iconUrl(letter: String): String {
        val c = letter.trim().lowercase().first()
        return "$ICON_BASE${c - 'a' + 1}.png"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.domain.*' --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, all domain tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(domain): sun phase, sky palettes, WMO and SIAG code tables"
```

---

### Task 4: Fixtures, location constants, Open-Meteo DTO and mapper

**Files:**
- Create: `app/src/test/resources/fixtures/openmeteo.json`, `geosphere.json`, `siag_kmos.json`, `siag_stations.json`, `odh_weather_de.json`, `odh_district2_de.json`
- Create: `app/src/main/kotlin/it/apexweather/domain/DorfTirol.kt`
- Create: `app/src/main/kotlin/it/apexweather/data/remote/JsonHelpers.kt`
- Create: `app/src/main/kotlin/it/apexweather/data/remote/OpenMeteoApi.kt`
- Create: `app/src/test/kotlin/it/apexweather/Fixtures.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/remote/OpenMeteoMapperTest.kt`

**Interfaces:**
- Produces: `object DorfTirol { LAT, LON, ISTAT, STATION_CODE, DISTRICT_ID, ZONE }`, `interface OpenMeteoApi { suspend fun forecast(...): OpenMeteoResponse }`, `OpenMeteoMapper.map(resp: OpenMeteoResponse, fetchedAt: Instant): Map<Source, SourceForecast>`, `OpenMeteoMapper.MODELS: Map<Source, String>`, test helper `Fixtures.read(name): String` and `Fixtures.json: Json`.

- [ ] **Step 1: Capture fixtures from the live endpoints**

Run:
```bash
mkdir -p app/src/test/resources/fixtures && cd app/src/test/resources/fixtures
curl -sf 'https://api.open-meteo.com/v1/forecast?latitude=46.691&longitude=11.155&timezone=Europe%2FRome&forecast_days=7&models=meteoswiss_icon_ch1,meteoswiss_icon_ch2,italia_meteo_arpae_icon_2i,icon_d2,ecmwf_ifs025&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability,weather_code,cloud_cover,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,sunrise,sunset' -o openmeteo.json
curl -sf 'https://dataset.api.hub.geosphere.at/v1/timeseries/forecast/nwp-v1-1h-2500m?lat_lon=46.691,11.155&parameters=t2m,rr_acc,snow_acc,rh2m,u10m,v10m,ugust,vgust,tcc,sp,cape' -o geosphere.json
curl -sf 'https://api-weather.services.siag.it/api/v2/municipality/MunicipalityBulletin/021101' -o siag_kmos.json
curl -sf 'https://api-weather.services.siag.it/api/v2/station?categoryId=1&visibility=11' -o siag_stations.json
curl -sf 'https://tourism.opendatahub.com/v1/Weather?language=de' -o odh_weather_de.json
curl -sf 'https://tourism.opendatahub.com/v1/Weather/District/2?language=de' -o odh_district2_de.json
ls -la && python3 -c "import json,glob; [json.load(open(f)) for f in glob.glob('*.json')]; print('all valid JSON')"
```
Expected: six files, each > 1 KB, `all valid JSON`. (If an endpoint is down, retry later; do not hand-write fixtures.)

- [ ] **Step 2: Location constants and JSON helpers**

Create `app/src/main/kotlin/it/apexweather/domain/DorfTirol.kt`:
```kotlin
package it.apexweather.domain

import java.time.ZoneId

/** The one and only location of this app. */
object DorfTirol {
    const val NAME = "Dorf Tirol"
    const val LAT = 46.691
    const val LON = 11.155
    const val ISTAT = "021101"
    const val STATION_CODE = "23200MS"
    const val DISTRICT_ID = 2
    val ZONE: ZoneId = ZoneId.of("Europe/Rome")
}
```

Create `app/src/main/kotlin/it/apexweather/data/remote/JsonHelpers.kt`:
```kotlin
package it.apexweather.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun JsonObject.doubles(key: String): List<Double?> =
    this[key]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull } ?: emptyList()

internal fun JsonObject.ints(key: String): List<Int?> =
    this[key]?.jsonArray?.map { it.jsonPrimitive.intOrNull } ?: emptyList()

internal fun JsonObject.strings(key: String): List<String?> =
    this[key]?.jsonArray?.map { it.jsonPrimitive.contentOrNull } ?: emptyList()

/** "2026-09-08T06:00" (no offset) interpreted in [zone]. */
internal fun parseLocal(s: String, zone: ZoneId): Instant = LocalDateTime.parse(s).atZone(zone).toInstant()

/** "2026-09-08T06:00:00+02:00" or "2026-09-08T16:00+00:00". */
internal fun parseOffset(s: String): Instant = OffsetDateTime.parse(s).toInstant()

/** SIAG station strings: "31.1" or "--". */
internal fun String?.siagDouble(): Double? = this?.trim()?.takeIf { it != "--" && it.isNotEmpty() }?.toDoubleOrNull()
```

- [ ] **Step 3: Test helper and failing mapper test**

Create `app/src/test/kotlin/it/apexweather/Fixtures.kt`:
```kotlin
package it.apexweather

import kotlinx.serialization.json.Json

object Fixtures {
    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun read(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()
}
```

Create `app/src/test/kotlin/it/apexweather/data/remote/OpenMeteoMapperTest.kt`:
```kotlin
package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.WmoCodes
import it.apexweather.domain.model.Source
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class OpenMeteoMapperTest {
    private val raw = Fixtures.read("openmeteo.json")
    private val resp = Fixtures.json.decodeFromString(OpenMeteoResponse.serializer(), raw)
    private val rawObj = Fixtures.json.parseToJsonElement(raw).jsonObject
    private val fetchedAt = Instant.parse("2026-09-08T12:00:00Z")
    private val result = OpenMeteoMapper.map(resp, fetchedAt)

    @Test
    fun `maps all five models`() {
        assertEquals(setOf(Source.ICON_CH1, Source.ICON_CH2, Source.ICON_2I, Source.ICON_D2, Source.ECMWF), result.keys)
    }

    @Test
    fun `hourly values match raw arrays and skip null hours`() {
        val hourly = rawObj["hourly"]!!.jsonObject
        val rawTemps = hourly["temperature_2m_icon_d2"]!!.jsonArray
        val expectedCount = rawTemps.count { it.jsonPrimitive.content != "null" }
        val d2 = result.getValue(Source.ICON_D2)
        assertEquals(expectedCount, d2.hourly.size)
        val first = d2.hourly.first()
        assertEquals(rawTemps[0].jsonPrimitive.double, first.tempC, 0.0)
        assertEquals(hourly["wind_speed_10m_icon_d2"]!!.jsonArray[0].jsonPrimitive.double, first.windKmh, 0.0)
        assertEquals(WmoCodes.toCondition(hourly["weather_code_icon_d2"]!!.jsonArray[0].jsonPrimitive.int), first.condition)
        val firstTime = hourly["time"]!!.jsonArray[0].jsonPrimitive.content
        assertEquals(LocalDateTime.parse(firstTime).atZone(DorfTirol.ZONE).toInstant(), first.time)
    }

    @Test
    fun `daily carries sunrise and sunset and skips days without temperature`() {
        val ecmwf = result.getValue(Source.ECMWF)
        assertEquals(7, ecmwf.daily.size)
        assertNotNull(ecmwf.daily.first().sunrise)
        assertNotNull(ecmwf.daily.first().sunset)
        val ch1 = result.getValue(Source.ICON_CH1)
        assertTrue(ch1.daily.size < 7) // 48 h model → only the first 2-3 days have min/max
    }

    @Test
    fun `issuedAt equals fetchedAt because Open-Meteo has no run time`() {
        assertEquals(fetchedAt, result.getValue(Source.ICON_CH1).issuedAt)
    }

    @Test
    fun `missing precipitation probability stays null (ICON-2I)`() {
        assertTrue(result.getValue(Source.ICON_2I).hourly.all { it.precipProb == null })
    }
}
```

- [ ] **Step 4: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.OpenMeteoMapperTest' --console=plain 2>&1 | tail -10`
Expected: compilation failure, `Unresolved reference 'OpenMeteoResponse'`.

- [ ] **Step 5: Implement API, DTO, mapper**

Create `app/src/main/kotlin/it/apexweather/data/remote/OpenMeteoApi.kt`:
```kotlin
package it.apexweather.data.remote

import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.WmoCodes
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDate

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double = DorfTirol.LAT,
        @Query("longitude") longitude: Double = DorfTirol.LON,
        @Query("timezone") timezone: String = DorfTirol.ZONE.id,
        @Query("forecast_days") forecastDays: Int = 7,
        @Query("models") models: String = OpenMeteoMapper.MODELS.values.joinToString(","),
        @Query("hourly") hourly: String = OpenMeteoMapper.HOURLY_VARS,
        @Query("daily") daily: String = OpenMeteoMapper.DAILY_VARS,
    ): OpenMeteoResponse

    companion object { const val BASE_URL = "https://api.open-meteo.com/" }
}

@Serializable
data class OpenMeteoResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val hourly: JsonObject,
    val daily: JsonObject,
)

object OpenMeteoMapper {
    val MODELS: Map<Source, String> = mapOf(
        Source.ICON_CH1 to "meteoswiss_icon_ch1",
        Source.ICON_CH2 to "meteoswiss_icon_ch2",
        Source.ICON_2I to "italia_meteo_arpae_icon_2i",
        Source.ICON_D2 to "icon_d2",
        Source.ECMWF to "ecmwf_ifs025",
    )
    const val HOURLY_VARS = "temperature_2m,apparent_temperature,precipitation,precipitation_probability," +
        "weather_code,cloud_cover,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m"
    const val DAILY_VARS = "temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code,sunrise,sunset"

    fun map(resp: OpenMeteoResponse, fetchedAt: Instant): Map<Source, SourceForecast> {
        val zone = DorfTirol.ZONE
        val times = resp.hourly.strings("time").map { parseLocal(it!!, zone) }
        val dayDates = resp.daily.strings("time").map { LocalDate.parse(it!!) }

        return MODELS.mapNotNull { (source, key) ->
            val h = resp.hourly
            val temps = h.doubles("temperature_2m_$key")
            if (temps.isEmpty()) return@mapNotNull null
            val feels = h.doubles("apparent_temperature_$key")
            val precip = h.doubles("precipitation_$key")
            val prob = h.ints("precipitation_probability_$key")
            val code = h.ints("weather_code_$key")
            val cloud = h.ints("cloud_cover_$key")
            val hum = h.ints("relative_humidity_2m_$key")
            val wind = h.doubles("wind_speed_10m_$key")
            val gust = h.doubles("wind_gusts_10m_$key")
            val dir = h.ints("wind_direction_10m_$key")

            val hourly = times.indices.mapNotNull { i ->
                val t = temps.getOrNull(i) ?: return@mapNotNull null
                HourlyPoint(
                    time = times[i],
                    tempC = t,
                    feelsLikeC = feels.getOrNull(i),
                    precipMm = precip.getOrNull(i) ?: 0.0,
                    precipProb = prob.getOrNull(i),
                    windKmh = wind.getOrNull(i) ?: 0.0,
                    gustKmh = gust.getOrNull(i),
                    windDirDeg = dir.getOrNull(i),
                    cloudPct = cloud.getOrNull(i),
                    humidityPct = hum.getOrNull(i),
                    condition = WmoCodes.toCondition(code.getOrNull(i)),
                )
            }

            val d = resp.daily
            val tmax = d.doubles("temperature_2m_max_$key")
            val tmin = d.doubles("temperature_2m_min_$key")
            val psum = d.doubles("precipitation_sum_$key")
            val dcode = d.ints("weather_code_$key")
            val sunrise = d.strings("sunrise_$key")
            val sunset = d.strings("sunset_$key")
            val daily = dayDates.indices.mapNotNull { i ->
                val max = tmax.getOrNull(i) ?: return@mapNotNull null
                val min = tmin.getOrNull(i) ?: return@mapNotNull null
                DailyPoint(
                    date = dayDates[i],
                    minC = min,
                    maxC = max,
                    precipMm = psum.getOrNull(i) ?: 0.0,
                    condition = dcode.getOrNull(i)?.let(WmoCodes::toCondition) ?: run {
                        val hoursOfDay = hourly.filter { it.time.atZone(zone).toLocalDate() == dayDates[i] }
                            .map { it.time.atZone(zone).hour to it.condition }
                        if (hoursOfDay.isEmpty()) Condition.CLOUDY else DailyAggregator.worstCondition(hoursOfDay)
                    },
                    sunrise = sunrise.getOrNull(i)?.let { parseLocal(it, zone) },
                    sunset = sunset.getOrNull(i)?.let { parseLocal(it, zone) },
                )
            }
            source to SourceForecast(source, issuedAt = fetchedAt, fetchedAt = fetchedAt, hourly = hourly, daily = daily)
        }.toMap()
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.OpenMeteoMapperTest' --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(data): Open-Meteo multi-model client, mapper and recorded fixtures"
```

---

### Task 5: GeoSphere AROME DTO and mapper

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/remote/GeoSphereApi.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/remote/GeoSphereMapperTest.kt`

**Interfaces:**
- Produces: `interface GeoSphereApi { suspend fun forecast(...): GeoSphereResponse }`, `GeoSphereMapper.map(resp, fetchedAt): SourceForecast` (source `GEOSPHERE_AROME`), `GeoSphereMapper.condition(precipMm, snowMm, tcc, tempC, cape): Condition`, `GeoSphereMapper.windFromUV(u, v): Pair<Double /*km/h*/, Int /*deg from*/>`.

- [ ] **Step 1: Failing tests**

Create `app/src/test/kotlin/it/apexweather/data/remote/GeoSphereMapperTest.kt`:
```kotlin
package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

class GeoSphereMapperTest {
    private val resp = Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json"))
    private val fetchedAt = Instant.parse("2026-09-08T14:00:00Z")
    private val fc = GeoSphereMapper.map(resp, fetchedAt)
    private val params = resp.features.first().properties.parameters

    @Test
    fun `source issuedAt and count`() {
        assertEquals(Source.GEOSPHERE_AROME, fc.source)
        assertEquals(OffsetDateTime.parse(resp.referenceTime).toInstant(), fc.issuedAt)
        assertEquals(resp.timestamps.size, fc.hourly.size)
        assertTrue(fc.hourly.size >= 48)
    }

    @Test
    fun `precipitation is the hourly difference of the accumulated series`() {
        val acc = params.getValue("rr_acc").data
        assertEquals(acc[0]!!, fc.hourly[0].precipMm, 1e-9)
        assertEquals(acc[3]!! - acc[2]!!, fc.hourly[3].precipMm, 1e-9)
        assertTrue(fc.hourly.all { it.precipMm >= 0.0 })
    }

    @Test
    fun `wind converted from u v components`() {
        val (speed, dir) = GeoSphereMapper.windFromUV(u = 0.0, v = -5.0) // blowing toward south = from north
        assertEquals(18.0, speed, 1e-9)
        assertEquals(0, dir)
        val (_, west) = GeoSphereMapper.windFromUV(u = 5.0, v = 0.0) // toward east = from west
        assertEquals(270, west)
    }

    @Test
    fun `condition derivation`() {
        assertEquals(Condition.CLEAR, GeoSphereMapper.condition(0.0, 0.0, tcc = 0.1, tempC = 20.0, cape = 0.0))
        assertEquals(Condition.PARTLY_CLOUDY, GeoSphereMapper.condition(0.0, 0.0, tcc = 0.5, tempC = 20.0, cape = 0.0))
        assertEquals(Condition.CLOUDY, GeoSphereMapper.condition(0.05, 0.0, tcc = 0.95, tempC = 20.0, cape = 0.0))
        assertEquals(Condition.DRIZZLE, GeoSphereMapper.condition(0.3, 0.0, tcc = 1.0, tempC = 15.0, cape = 0.0))
        assertEquals(Condition.RAIN, GeoSphereMapper.condition(2.0, 0.0, tcc = 1.0, tempC = 15.0, cape = 0.0))
        assertEquals(Condition.HEAVY_RAIN, GeoSphereMapper.condition(5.0, 0.0, tcc = 1.0, tempC = 15.0, cape = 100.0))
        assertEquals(Condition.THUNDERSTORM, GeoSphereMapper.condition(3.0, 0.0, tcc = 1.0, tempC = 25.0, cape = 800.0))
        assertEquals(Condition.SNOW, GeoSphereMapper.condition(1.0, 0.9, tcc = 1.0, tempC = -2.0, cape = 0.0))
        assertEquals(Condition.HEAVY_SNOW, GeoSphereMapper.condition(3.0, 2.8, tcc = 1.0, tempC = -2.0, cape = 0.0))
        assertEquals(Condition.SLEET, GeoSphereMapper.condition(2.0, 0.8, tcc = 1.0, tempC = 1.0, cape = 0.0))
    }

    @Test
    fun `daily aggregated from hourly`() {
        assertTrue(fc.daily.size >= 2)
        assertTrue(fc.daily.all { it.maxC >= it.minC })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.GeoSphereMapperTest' --console=plain 2>&1 | tail -10`
Expected: compilation failure, `Unresolved reference 'GeoSphereResponse'`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/it/apexweather/data/remote/GeoSphereApi.kt`:
```kotlin
package it.apexweather.data.remote

import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface GeoSphereApi {
    @GET("v1/timeseries/forecast/nwp-v1-1h-2500m")
    suspend fun forecast(
        @Query("lat_lon") latLon: String = "${DorfTirol.LAT},${DorfTirol.LON}",
        @Query("parameters") parameters: String = GeoSphereMapper.PARAMS,
    ): GeoSphereResponse

    companion object { const val BASE_URL = "https://dataset.api.hub.geosphere.at/" }
}

@Serializable
data class GeoSphereResponse(
    @SerialName("reference_time") val referenceTime: String,
    val timestamps: List<String>,
    val features: List<GeoSphereFeature>,
)

@Serializable data class GeoSphereFeature(val properties: GeoSphereProperties)
@Serializable data class GeoSphereProperties(val parameters: Map<String, GeoSphereParam>)
@Serializable data class GeoSphereParam(val unit: String? = null, val data: List<Double?>)

object GeoSphereMapper {
    const val PARAMS = "t2m,rr_acc,snow_acc,rh2m,u10m,v10m,ugust,vgust,tcc,sp,cape"

    fun map(resp: GeoSphereResponse, fetchedAt: Instant): SourceForecast {
        val zone = DorfTirol.ZONE
        val p = resp.features.firstOrNull()?.properties?.parameters ?: error("GeoSphere: no features")
        fun series(name: String): List<Double?> = p[name]?.data ?: emptyList()
        val t2m = series("t2m").ifEmpty { error("GeoSphere: missing t2m") }
        val rrAcc = series("rr_acc")
        val snowAcc = series("snow_acc")
        val rh = series("rh2m")
        val u = series("u10m"); val v = series("v10m")
        val ug = series("ugust"); val vg = series("vgust")
        val tcc = series("tcc")
        val cape = series("cape")
        val times = resp.timestamps.map(::parseOffset)

        val hourly = times.indices.mapNotNull { i ->
            val temp = t2m.getOrNull(i) ?: return@mapNotNull null
            val precip = hourlyFromAccumulated(rrAcc, i)
            val snow = hourlyFromAccumulated(snowAcc, i)
            val (wind, dir) = if (u.getOrNull(i) != null && v.getOrNull(i) != null) windFromUV(u[i]!!, v[i]!!) else 0.0 to null
            val gust = if (ug.getOrNull(i) != null && vg.getOrNull(i) != null) windFromUV(ug[i]!!, vg[i]!!).first else null
            val cloud = tcc.getOrNull(i) ?: 0.5
            HourlyPoint(
                time = times[i],
                tempC = temp,
                precipMm = precip,
                windKmh = wind,
                gustKmh = gust,
                windDirDeg = dir,
                cloudPct = (cloud * 100).roundToInt().coerceIn(0, 100),
                humidityPct = rh.getOrNull(i)?.roundToInt()?.coerceIn(0, 100),
                condition = condition(precip, snow, cloud, temp, cape.getOrNull(i) ?: 0.0),
            )
        }
        return SourceForecast(
            source = Source.GEOSPHERE_AROME,
            issuedAt = parseOffset(resp.referenceTime),
            fetchedAt = fetchedAt,
            hourly = hourly,
            daily = DailyAggregator.aggregate(hourly, zone),
        )
    }

    private fun hourlyFromAccumulated(acc: List<Double?>, i: Int): Double {
        val cur = acc.getOrNull(i) ?: return 0.0
        val prev = if (i == 0) 0.0 else (acc.getOrNull(i - 1) ?: 0.0)
        return (cur - prev).coerceAtLeast(0.0)
    }

    /** u = eastward, v = northward component in m/s. Returns speed in km/h and meteorological "from" direction. */
    fun windFromUV(u: Double, v: Double): Pair<Double, Int> {
        val speedKmh = sqrt(u * u + v * v) * 3.6
        val dirFrom = (Math.toDegrees(atan2(-u, -v)) + 360.0) % 360.0
        return speedKmh to dirFrom.roundToInt() % 360
    }

    fun condition(precipMm: Double, snowMm: Double, tcc: Double, tempC: Double, cape: Double): Condition {
        if (precipMm >= 0.1) {
            val snowShare = if (precipMm > 0) snowMm / precipMm else 0.0
            val frozen = snowShare > 0.5 || tempC < 0.5
            return when {
                frozen -> if (precipMm >= 2.5) Condition.HEAVY_SNOW else Condition.SNOW
                snowShare > 0.2 -> Condition.SLEET
                cape >= 500.0 && precipMm >= 0.5 -> Condition.THUNDERSTORM
                precipMm < 0.5 -> Condition.DRIZZLE
                precipMm < 4.0 -> Condition.RAIN
                else -> Condition.HEAVY_RAIN
            }
        }
        return when {
            tcc < 0.15 -> Condition.CLEAR
            tcc < 0.40 -> Condition.MOSTLY_CLEAR
            tcc < 0.70 -> Condition.PARTLY_CLOUDY
            else -> Condition.CLOUDY
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.GeoSphereMapperTest' --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(data): GeoSphere AROME client and mapper with derived conditions"
```

---

### Task 6: SIAG clients: KMOS forecast, bulletin, live station

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/remote/SiagApi.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/remote/SiagMappersTest.kt`

**Interfaces:**
- Produces: `interface SiagApi { suspend fun municipality(istat: String): KmosResponse; suspend fun stations(categoryId: Int = 1, visibility: Int = 11): SiagStationsResponse }`, `interface OdhApi { suspend fun weather(language: String): OdhWeatherResponse; suspend fun district(id: Int = 2, language: String): OdhDistrictResponse }`, `SiagMappers.mapKmos(resp, fetchedAt): SourceForecast`, `SiagMappers.mapBulletin(weather, district, language): Bulletin`, `SiagMappers.mapObservation(resp): StationObservation?`.

- [ ] **Step 1: Failing tests**

Create `app/src/test/kotlin/it/apexweather/data/remote/SiagMappersTest.kt`:
```kotlin
package it.apexweather.data.remote

import it.apexweather.Fixtures
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.SiagCodes
import it.apexweather.domain.model.Source
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime

class SiagMappersTest {
    private val fetchedAt = Instant.parse("2026-09-08T14:00:00Z")

    @Test
    fun `kmos maps 3-hourly points with symbol conditions and third of 3h precipitation`() {
        val resp = Fixtures.json.decodeFromString(KmosResponse.serializer(), Fixtures.read("siag_kmos.json"))
        val fc = SiagMappers.mapKmos(resp, fetchedAt)
        assertEquals(Source.SIAG_KMOS, fc.source)
        val temp3 = resp.municipality.temp3!!.data
        assertEquals(temp3.size, fc.hourly.size)
        assertEquals(OffsetDateTime.parse(temp3[0].date).toInstant(), fc.hourly[0].time)
        assertEquals(temp3[0].value!!.double, fc.hourly[0].tempC, 0.0)
        val sym0 = resp.municipality.symbols3!!.data[0].value!!.contentOrNull
        assertEquals(SiagCodes.toCondition(sym0), fc.hourly[0].condition)
        val prec0 = resp.municipality.precSum3!!.data[0].value!!.double
        assertEquals(prec0 / 3.0, fc.hourly[0].precipMm, 1e-9)
        assertEquals(resp.municipality.tempMax24!!.data.size, fc.daily.size)
        assertEquals(OffsetDateTime.parse(resp.info.currentModelRun!!).toInstant(), fc.issuedAt)
    }

    @Test
    fun `bulletin merges evolution text, conditions and district days`() {
        val w = Fixtures.json.decodeFromString(OdhWeatherResponse.serializer(), Fixtures.read("odh_weather_de.json"))
        val d = Fixtures.json.decodeFromString(OdhDistrictResponse.serializer(), Fixtures.read("odh_district2_de.json"))
        val b = SiagMappers.mapBulletin(w, d, "de")
        assertEquals("de", b.language)
        assertEquals(w.evolutionTitle, b.title)
        assertEquals(LocalDateTime.parse(w.date).atZone(DorfTirol.ZONE).toInstant(), b.issuedAt)
        assertEquals(w.conditions.size, b.conditions.size)
        assertEquals(d.forecast.size, b.days.size)
        val day0 = b.days[0]
        assertEquals(d.forecast[0].weatherCode, day0.code)
        assertEquals(d.forecast[0].maxTemp, day0.maxC)
        assertNotNull(day0.iconUrl)
    }

    @Test
    fun `observation picks the Meran station and converts units`() {
        val resp = Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        val obs = SiagMappers.mapObservation(resp)!!
        val row = resp.rows.first { it.code == DorfTirol.STATION_CODE }
        assertEquals(row.name, obs.stationName)
        assertEquals(row.t.siagDouble(), obs.tempC)
        row.ff.siagDouble()?.let { assertEquals(it * 3.6, obs.windKmh!!, 0.051) } ?: assertNull(obs.windKmh) // km/h rounded to 1 decimal
        assertEquals(LocalDateTime.parse(row.lastUpdated).atZone(DorfTirol.ZONE).toInstant(), obs.time)
    }

    @Test
    fun `observation is null when station missing`() {
        assertNull(SiagMappers.mapObservation(SiagStationsResponse(rows = emptyList())))
    }

    @Test
    fun `dash strings parse to null`() {
        assertNull("--".siagDouble())
        assertEquals(4.3, "4.3".siagDouble()!!, 0.0)
        assertTrue(null.siagDouble() == null)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.SiagMappersTest' --console=plain 2>&1 | tail -10`
Expected: compilation failure, `Unresolved reference 'KmosResponse'`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/it/apexweather/data/remote/SiagApi.kt`:
```kotlin
package it.apexweather.data.remote

import it.apexweather.domain.DorfTirol
import it.apexweather.domain.SiagCodes
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.BulletinCondition
import it.apexweather.domain.model.BulletinDay
import it.apexweather.domain.model.DailyPoint
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.StationObservation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** api-weather.services.siag.it — the backend of the official "Wetter Südtirol" app. */
interface SiagApi {
    @GET("api/v2/municipality/MunicipalityBulletin/{istat}")
    suspend fun municipality(@Path("istat") istat: String = DorfTirol.ISTAT): KmosResponse

    @GET("api/v2/station")
    suspend fun stations(@Query("categoryId") categoryId: Int = 1, @Query("visibility") visibility: Int = 11): SiagStationsResponse

    companion object { const val BASE_URL = "https://api-weather.services.siag.it/" }
}

/** tourism.opendatahub.com — open-data mirror of the province bulletin. */
interface OdhApi {
    @GET("v1/Weather")
    suspend fun weather(@Query("language") language: String): OdhWeatherResponse

    @GET("v1/Weather/District/{id}")
    suspend fun district(@Path("id") id: Int = DorfTirol.DISTRICT_ID, @Query("language") language: String): OdhDistrictResponse

    companion object { const val BASE_URL = "https://tourism.opendatahub.com/" }
}

// ---- KMOS municipality forecast DTOs ----

@Serializable data class KmosResponse(val info: KmosInfo = KmosInfo(), val municipality: KmosMunicipality)
@Serializable data class KmosInfo(val model: String? = null, val currentModelRun: String? = null, val fileCreationDate: String? = null)
@Serializable
data class KmosMunicipality(
    val code: String? = null,
    val nameDe: String? = null,
    val tempMin24: KmosSeries? = null,
    val tempMax24: KmosSeries? = null,
    val temp3: KmosSeries? = null,
    val precProb3: KmosSeries? = null,
    val precSum3: KmosSeries? = null,
    val symbols3: KmosSeries? = null,
    val symbols24: KmosSeries? = null,
    val precSum24: KmosSeries? = null,
    val precProb24: KmosSeries? = null,
)
@Serializable data class KmosSeries(val unit: String? = null, val data: List<KmosPoint> = emptyList())
@Serializable data class KmosPoint(val date: String, val value: JsonPrimitive? = null)

// ---- Open Data Hub bulletin DTOs (keys are PascalCase; lowercase duplicates are ignored) ----

@Serializable
data class OdhWeatherResponse(
    @SerialName("Date") val date: String,
    @SerialName("EvolutionTitle") val evolutionTitle: String? = null,
    @SerialName("Evolution") val evolution: String? = null,
    @SerialName("Conditions") val conditions: List<OdhCondition> = emptyList(),
)
@Serializable
data class OdhCondition(
    @SerialName("Date") val date: String,
    @SerialName("Title") val title: String? = null,
    @SerialName("WeatherDesc") val weatherDesc: String? = null,
    @SerialName("Temperatures") val temperatures: String? = null,
    @SerialName("WeatherImgUrl") val weatherImgUrl: String? = null,
)
@Serializable
data class OdhDistrictResponse(
    @SerialName("DistrictName") val districtName: String? = null,
    @SerialName("BezirksForecast") val forecast: List<OdhDistrictDay> = emptyList(),
)
@Serializable
data class OdhDistrictDay(
    @SerialName("Date") val date: String,
    @SerialName("WeatherCode") val weatherCode: String? = null,
    @SerialName("WeatherDesc") val weatherDesc: String? = null,
    @SerialName("WeatherImgUrl") val weatherImgUrl: String? = null,
    @SerialName("MaxTemp") val maxTemp: Double? = null,
    @SerialName("MinTemp") val minTemp: Double? = null,
    @SerialName("RainFrom") val rainFrom: Double? = null,
    @SerialName("RainTo") val rainTo: Double? = null,
    @SerialName("Thunderstorm") val thunderstorm: Int? = null,
)

// ---- Live station DTOs ----

@Serializable data class SiagStationsResponse(val rows: List<SiagStationRow> = emptyList())
@Serializable
data class SiagStationRow(
    val code: String? = null,
    val name: String? = null,
    val t: String? = null,
    val rh: String? = null,
    val p: String? = null,
    val ff: String? = null,
    val dd: String? = null,
    val wMax: String? = null,
    val n: String? = null,
    val lastUpdated: String? = null,
)

object SiagMappers {

    fun mapKmos(resp: KmosResponse, fetchedAt: Instant): SourceForecast {
        val m = resp.municipality
        val temps = m.temp3?.data?.takeIf { it.isNotEmpty() } ?: error("KMOS: missing temp3")
        val precByDate = m.precSum3?.data?.associate { it.date to it.value?.doubleOrNull } ?: emptyMap()
        val probByDate = m.precProb3?.data?.associate { it.date to it.value?.intOrNull } ?: emptyMap()
        val symByDate = m.symbols3?.data?.associate { it.date to it.value?.contentOrNull } ?: emptyMap()

        val hourly = temps.mapNotNull { pt ->
            val t = pt.value?.doubleOrNull ?: return@mapNotNull null
            HourlyPoint(
                time = parseOffset(pt.date),
                tempC = t,
                precipMm = (precByDate[pt.date] ?: 0.0) / 3.0, // 3-hour sum spread as an hourly rate
                precipProb = probByDate[pt.date],
                windKmh = 0.0,
                condition = SiagCodes.toCondition(symByDate[pt.date]),
            )
        }

        val minByDate = m.tempMin24?.data?.associate { it.date to it.value?.doubleOrNull } ?: emptyMap()
        val precDayByDate = m.precSum24?.data?.associate { it.date to it.value?.doubleOrNull } ?: emptyMap()
        val symDayByDate = m.symbols24?.data?.associate { it.date to it.value?.contentOrNull } ?: emptyMap()
        val daily = m.tempMax24?.data.orEmpty().mapNotNull { pt ->
            val max = pt.value?.doubleOrNull ?: return@mapNotNull null
            val min = minByDate[pt.date] ?: return@mapNotNull null
            DailyPoint(
                date = parseOffset(pt.date).atZone(DorfTirol.ZONE).toLocalDate(),
                minC = min,
                maxC = max,
                precipMm = precDayByDate[pt.date] ?: 0.0,
                condition = SiagCodes.toCondition(symDayByDate[pt.date]),
            )
        }

        return SourceForecast(
            source = Source.SIAG_KMOS,
            issuedAt = resp.info.currentModelRun?.let { runCatching { parseOffset(it) }.getOrNull() } ?: fetchedAt,
            fetchedAt = fetchedAt,
            hourly = hourly,
            daily = daily,
        )
    }

    fun mapBulletin(weather: OdhWeatherResponse, district: OdhDistrictResponse, language: String): Bulletin {
        val zone = DorfTirol.ZONE
        return Bulletin(
            language = language,
            issuedAt = LocalDateTime.parse(weather.date).atZone(zone).toInstant(),
            title = weather.evolutionTitle.orEmpty(),
            evolution = weather.evolution.orEmpty().replace("\r\n", "\n"),
            conditions = weather.conditions.map { c ->
                BulletinCondition(
                    date = LocalDateTime.parse(c.date).toLocalDate(),
                    title = c.title.orEmpty(),
                    description = c.weatherDesc.orEmpty(),
                    temperatures = c.temperatures,
                    mapImageUrl = c.weatherImgUrl,
                )
            },
            days = district.forecast.map { d ->
                BulletinDay(
                    date = LocalDateTime.parse(d.date).toLocalDate(),
                    code = d.weatherCode.orEmpty(),
                    description = d.weatherDesc.orEmpty(),
                    iconUrl = d.weatherImgUrl ?: d.weatherCode?.let(SiagCodes::iconUrl),
                    minC = d.minTemp,
                    maxC = d.maxTemp,
                    rainFromMm = d.rainFrom,
                    rainToMm = d.rainTo,
                    thunderstormLevel = d.thunderstorm,
                )
            },
        )
    }

    fun mapObservation(resp: SiagStationsResponse): StationObservation? {
        val row = resp.rows.firstOrNull { it.code == DorfTirol.STATION_CODE } ?: return null
        val updated = row.lastUpdated ?: return null
        fun msToKmh(v: Double?) = v?.let { (it * 3.6 * 10).roundToInt() / 10.0 }
        return StationObservation(
            stationName = row.name ?: "Meran",
            time = LocalDateTime.parse(updated).atZone(DorfTirol.ZONE).toInstant(),
            tempC = row.t.siagDouble(),
            humidityPct = row.rh.siagDouble()?.roundToInt(),
            windKmh = msToKmh(row.ff.siagDouble()),
            windDir = row.dd?.takeIf { it != "--" },
            gustKmh = msToKmh(row.wMax.siagDouble()),
            precipMm = row.n.siagDouble(),
            pressureHpa = row.p.siagDouble(),
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.remote.*' --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, all remote mapper tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(data): SIAG KMOS forecast, bulletin and live station clients"
```

---

### Task 7: Room cache

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/local/AppDatabase.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/local/WeatherDaoTest.kt`

**Interfaces:**
- Produces: entities `SourceForecastEntity(source: String, json: String?, issuedAtMs: Long?, fetchedAtMs: Long?, lastError: String?, lastErrorAtMs: Long?)`, `BulletinEntity(language, json, fetchedAtMs, lastError, lastErrorAtMs)`, `ObservationEntity(id = 0, json, fetchedAtMs, lastError, lastErrorAtMs)`, `RefreshMetaEntity(id = 0, lastSuccessMs: Long?, lastAttemptMs: Long?, lastAttemptFailed: Boolean)`; `WeatherDao` with `forecasts(): Flow<List<SourceForecastEntity>>`, `forecastOnce(source)`, `upsertForecast`, `bulletin(language): Flow<BulletinEntity?>`, `bulletinOnce`, `upsertBulletin`, `observation(): Flow<ObservationEntity?>`, `observationOnce`, `upsertObservation`, `meta(): Flow<RefreshMetaEntity?>`, `upsertMeta`; `AppDatabase.build(context)` and `AppDatabase.inMemory(context)`.

- [ ] **Step 1: Failing DAO test (Robolectric)**

Create `app/src/test/kotlin/it/apexweather/data/local/WeatherDaoTest.kt`:
```kotlin
package it.apexweather.data.local

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: WeatherDao

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.weatherDao()
    }

    @After fun tearDown() = db.close()

    @Test
    fun `upsert replaces forecast row and flow emits it`() = runTest {
        dao.upsertForecast(SourceForecastEntity("ICON_D2", "{}", 1L, 2L, null, null))
        dao.upsertForecast(SourceForecastEntity("ICON_D2", "{\"a\":1}", 3L, 4L, null, null))
        val rows = dao.forecasts().first()
        assertEquals(1, rows.size)
        assertEquals("{\"a\":1}", rows[0].json)
        assertEquals(3L, rows[0].issuedAtMs)
    }

    @Test
    fun `bulletin is keyed by language`() = runTest {
        dao.upsertBulletin(BulletinEntity("de", "{}", 1L, null, null))
        assertEquals("{}", dao.bulletin("de").first()?.json)
        assertNull(dao.bulletin("it").first())
    }

    @Test
    fun `meta and observation singletons`() = runTest {
        assertNull(dao.meta().first())
        dao.upsertMeta(RefreshMetaEntity(lastSuccessMs = 5L, lastAttemptMs = 6L, lastAttemptFailed = false))
        assertEquals(5L, dao.meta().first()?.lastSuccessMs)
        dao.upsertObservation(ObservationEntity(json = "{}", fetchedAtMs = 1L, lastError = null, lastErrorAtMs = null))
        assertEquals("{}", dao.observation().first()?.json)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.local.*' --console=plain 2>&1 | tail -10`
Expected: compilation failure, `Unresolved reference 'AppDatabase'`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/it/apexweather/data/local/AppDatabase.kt`:
```kotlin
package it.apexweather.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "source_forecast")
data class SourceForecastEntity(
    @PrimaryKey val source: String,
    val json: String?,
    val issuedAtMs: Long?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

@Entity(tableName = "bulletin")
data class BulletinEntity(
    @PrimaryKey val language: String,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

@Entity(tableName = "observation")
data class ObservationEntity(
    @PrimaryKey val id: Int = 0,
    val json: String?,
    val fetchedAtMs: Long?,
    val lastError: String?,
    val lastErrorAtMs: Long?,
)

@Entity(tableName = "refresh_meta")
data class RefreshMetaEntity(
    @PrimaryKey val id: Int = 0,
    val lastSuccessMs: Long?,
    val lastAttemptMs: Long?,
    val lastAttemptFailed: Boolean,
)

@Dao
interface WeatherDao {
    @Query("SELECT * FROM source_forecast") fun forecasts(): Flow<List<SourceForecastEntity>>
    @Query("SELECT * FROM source_forecast WHERE source = :source") suspend fun forecastOnce(source: String): SourceForecastEntity?
    @Upsert suspend fun upsertForecast(entity: SourceForecastEntity)

    @Query("SELECT * FROM bulletin WHERE language = :language") fun bulletin(language: String): Flow<BulletinEntity?>
    @Query("SELECT * FROM bulletin WHERE language = :language") suspend fun bulletinOnce(language: String): BulletinEntity?
    @Upsert suspend fun upsertBulletin(entity: BulletinEntity)

    @Query("SELECT * FROM observation WHERE id = 0") fun observation(): Flow<ObservationEntity?>
    @Query("SELECT * FROM observation WHERE id = 0") suspend fun observationOnce(): ObservationEntity?
    @Upsert suspend fun upsertObservation(entity: ObservationEntity)

    @Query("SELECT * FROM refresh_meta WHERE id = 0") fun meta(): Flow<RefreshMetaEntity?>
    @Upsert suspend fun upsertMeta(entity: RefreshMetaEntity)
}

@Database(
    entities = [SourceForecastEntity::class, BulletinEntity::class, ObservationEntity::class, RefreshMetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun weatherDao(): WeatherDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "apexweather.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.local.*' --console=plain 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`, 3 tests pass. If Robolectric fails to download an android-all jar, run once with network and retry.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(data): Room cache for source forecasts, bulletin, observation and refresh meta"
```

---

### Task 8: WeatherRepository with parallel refresh and status logic

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt`

**Interfaces:**
- Consumes: DAO + entities (Task 7), APIs + mappers (Tasks 4–6), models (Task 2).
- Produces: `class WeatherRepository(dao, openMeteo, geoSphere, siag, odh, json, clock)` with `fun snapshot(language: String): Flow<WeatherSnapshot>` and `suspend fun refresh(language: String): RefreshResult`; `data class RefreshResult(val succeeded: List<String>, val failed: Map<String, String>) { val allFailed }`.

- [ ] **Step 1: Failing repository test**

Create `app/src/test/kotlin/it/apexweather/data/WeatherRepositoryTest.kt`:
```kotlin
package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.Fixtures
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.GeoSphereResponse
import it.apexweather.data.remote.KmosResponse
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OdhDistrictResponse
import it.apexweather.data.remote.OdhWeatherResponse
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.OpenMeteoResponse
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SiagStationsResponse
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherRepositoryTest {

    private class FakeOpenMeteo(var fail: Boolean = false) : OpenMeteoApi {
        override suspend fun forecast(latitude: Double, longitude: Double, timezone: String, forecastDays: Int, models: String, hourly: String, daily: String): OpenMeteoResponse {
            if (fail) throw IOException("open-meteo down")
            return Fixtures.json.decodeFromString(OpenMeteoResponse.serializer(), Fixtures.read("openmeteo.json"))
        }
    }
    private class FakeGeoSphere(var fail: Boolean = false) : GeoSphereApi {
        override suspend fun forecast(latLon: String, parameters: String): GeoSphereResponse {
            if (fail) throw IOException("geosphere down")
            return Fixtures.json.decodeFromString(GeoSphereResponse.serializer(), Fixtures.read("geosphere.json"))
        }
    }
    private class FakeSiag(var fail: Boolean = false) : SiagApi {
        override suspend fun municipality(istat: String): KmosResponse {
            if (fail) throw IOException("siag down")
            return Fixtures.json.decodeFromString(KmosResponse.serializer(), Fixtures.read("siag_kmos.json"))
        }
        override suspend fun stations(categoryId: Int, visibility: Int): SiagStationsResponse {
            if (fail) throw IOException("siag down")
            return Fixtures.json.decodeFromString(SiagStationsResponse.serializer(), Fixtures.read("siag_stations.json"))
        }
    }
    private class FakeOdh : OdhApi {
        override suspend fun weather(language: String) =
            Fixtures.json.decodeFromString(OdhWeatherResponse.serializer(), Fixtures.read("odh_weather_de.json"))
        override suspend fun district(id: Int, language: String) =
            Fixtures.json.decodeFromString(OdhDistrictResponse.serializer(), Fixtures.read("odh_district2_de.json"))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    private lateinit var db: AppDatabase
    private val openMeteo = FakeOpenMeteo()
    private val geoSphere = FakeGeoSphere()
    private val siag = FakeSiag()
    private val clock = MutableClock(Instant.parse("2026-09-08T14:00:00Z"))
    private lateinit var repo: WeatherRepository

    @Before fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repo = WeatherRepository(db.weatherDao(), openMeteo, geoSphere, siag, FakeOdh(), Fixtures.json, clock)
    }

    @After fun tearDown() = db.close()

    @Test
    fun `empty snapshot before any refresh`() = runTest {
        val s = repo.snapshot("de").first()
        assertTrue(s.isEmpty)
    }

    @Test
    fun `refresh fills all seven sources, bulletin and observation`() = runTest {
        val result = repo.refresh("de")
        assertTrue(result.failed.isEmpty())
        val s = repo.snapshot("de").first()
        assertEquals(Source.entries.toSet(), s.forecasts.keys)
        assertNotNull(s.bulletin)
        assertNotNull(s.observation)
        assertTrue(s.status.values.all { it is SourceStatus.Ok })
        assertEquals(clock.now, s.lastSuccessfulRefresh)
        assertFalse(s.lastRefreshFailed)
    }

    @Test
    fun `a failing source keeps its cached data and reports Failed`() = runTest {
        repo.refresh("de")
        geoSphere.fail = true
        clock.now = clock.now.plus(Duration.ofMinutes(30))
        val result = repo.refresh("de")
        assertEquals(setOf("GEOSPHERE_AROME"), result.failed.keys)
        val s = repo.snapshot("de").first()
        assertTrue(s.forecasts.containsKey(Source.GEOSPHERE_AROME))
        val st = s.status.getValue(Source.GEOSPHERE_AROME)
        assertTrue(st is SourceStatus.Failed)
        assertNotNull((st as SourceStatus.Failed).lastIssuedAt)
        assertTrue(s.status.getValue(Source.ICON_D2) is SourceStatus.Ok)
        assertFalse(s.lastRefreshFailed) // partial success
    }

    @Test
    fun `all sources failing marks the refresh failed but keeps data`() = runTest {
        repo.refresh("de")
        openMeteo.fail = true; geoSphere.fail = true; siag.fail = true
        val result = repo.refresh("de")
        assertTrue(result.failed.size >= 3)
        val s = repo.snapshot("de").first()
        assertEquals(Source.entries.toSet(), s.forecasts.keys)
        assertNotNull(s.bulletin) // ODH still works
    }

    @Test
    fun `stale after threshold`() = runTest {
        repo.refresh("de")
        clock.now = clock.now.plus(Duration.ofHours(7))
        val s = repo.snapshot("de").first()
        assertTrue(s.status.getValue(Source.ICON_D2) is SourceStatus.Stale)
        assertTrue(s.status.getValue(Source.ECMWF) is SourceStatus.Ok) // 12 h threshold
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WeatherRepositoryTest' --console=plain 2>&1 | tail -10`
Expected: compilation failure, `Unresolved reference 'WeatherRepository'`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/it/apexweather/data/WeatherRepository.kt`:
```kotlin
package it.apexweather.data

import it.apexweather.data.local.BulletinEntity
import it.apexweather.data.local.ObservationEntity
import it.apexweather.data.local.RefreshMetaEntity
import it.apexweather.data.local.SourceForecastEntity
import it.apexweather.data.local.WeatherDao
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.GeoSphereMapper
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.OpenMeteoMapper
import it.apexweather.data.remote.SiagApi
import it.apexweather.data.remote.SiagMappers
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.domain.DorfTirol
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class RefreshResult(val succeeded: List<String>, val failed: Map<String, String>) {
    val allFailed: Boolean get() = succeeded.isEmpty() && failed.isNotEmpty()
}

@Singleton
class WeatherRepository @Inject constructor(
    private val dao: WeatherDao,
    private val openMeteo: OpenMeteoApi,
    private val geoSphere: GeoSphereApi,
    private val siag: SiagApi,
    private val odh: OdhApi,
    private val json: Json,
    private val clock: Clock,
) {
    fun snapshot(language: String): Flow<WeatherSnapshot> =
        combine(dao.forecasts(), dao.bulletin(language), dao.observation(), dao.meta()) { rows, bulletinRow, obsRow, meta ->
            val now = clock.instant()
            val forecasts = mutableMapOf<Source, SourceForecast>()
            val status = mutableMapOf<Source, SourceStatus>()
            rows.forEach { row ->
                val source = runCatching { Source.valueOf(row.source) }.getOrNull() ?: return@forEach
                val fc = row.json?.let { runCatching { json.decodeFromString(SourceForecast.serializer(), it) }.getOrNull() }
                if (fc != null) forecasts[source] = fc
                status[source] = statusOf(
                    hasData = fc != null, issuedAt = fc?.issuedAt, fetchedAtMs = row.fetchedAtMs,
                    lastError = row.lastError, lastErrorAtMs = row.lastErrorAtMs,
                    staleAfter = Duration.ofHours(source.staleAfterHours.toLong()), now = now,
                )
            }
            val bulletin = bulletinRow?.json?.let { runCatching { json.decodeFromString(Bulletin.serializer(), it) }.getOrNull() }
            val observation = obsRow?.json?.let { runCatching { json.decodeFromString(StationObservation.serializer(), it) }.getOrNull() }
            WeatherSnapshot(
                forecasts = forecasts,
                bulletin = bulletin,
                observation = observation,
                status = status,
                bulletinStatus = bulletinRow?.let {
                    statusOf(bulletin != null, bulletin?.issuedAt, it.fetchedAtMs, it.lastError, it.lastErrorAtMs, Duration.ofHours(24), now)
                },
                observationStatus = obsRow?.let {
                    statusOf(observation != null, observation?.time, it.fetchedAtMs, it.lastError, it.lastErrorAtMs, Duration.ofMinutes(90), now)
                },
                lastSuccessfulRefresh = meta?.lastSuccessMs?.let(Instant::ofEpochMilli),
                lastRefreshFailed = meta?.lastAttemptFailed ?: false,
            )
        }

    /** Fetches every source in parallel; a failure in one never affects the others. */
    suspend fun refresh(language: String): RefreshResult {
        val now = clock.instant()
        val succeeded = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()

        suspend fun <T> attempt(name: String, block: suspend () -> T): T? =
            runCatching { block() }
                .onSuccess { succeeded += name }
                .onFailure { failed[name] = it.message ?: it.javaClass.simpleName }
                .getOrNull()

        supervisorScope {
            val om = async {
                val mapped = attempt("OPEN_METEO") { OpenMeteoMapper.map(openMeteo.forecast(), now) }
                OpenMeteoMapper.MODELS.keys.forEach { source ->
                    storeForecast(source, mapped?.get(source), failed["OPEN_METEO"], now)
                }
            }
            val gs = async {
                val fc = attempt("GEOSPHERE_AROME") { GeoSphereMapper.map(geoSphere.forecast(), now) }
                storeForecast(Source.GEOSPHERE_AROME, fc, failed["GEOSPHERE_AROME"], now)
            }
            val km = async {
                val fc = attempt("SIAG_KMOS") { SiagMappers.mapKmos(siag.municipality(), now) }
                storeForecast(Source.SIAG_KMOS, fc, failed["SIAG_KMOS"], now)
            }
            val bl = async {
                val b = attempt("SIAG_BULLETIN") { SiagMappers.mapBulletin(odh.weather(language), odh.district(language = language), language) }
                val prev = dao.bulletinOnce(language)
                dao.upsertBulletin(
                    if (b != null) BulletinEntity(language, json.encodeToString(Bulletin.serializer(), b), now.toEpochMilli(), null, null)
                    else BulletinEntity(language, prev?.json, prev?.fetchedAtMs, failed["SIAG_BULLETIN"], now.toEpochMilli())
                )
            }
            val ob = async {
                val o = attempt("SIAG_STATION") { SiagMappers.mapObservation(siag.stations()) ?: error("station ${DorfTirol.STATION_CODE} not in response") }
                val prev = dao.observationOnce()
                dao.upsertObservation(
                    if (o != null) ObservationEntity(0, json.encodeToString(StationObservation.serializer(), o), now.toEpochMilli(), null, null)
                    else ObservationEntity(0, prev?.json, prev?.fetchedAtMs, failed["SIAG_STATION"], now.toEpochMilli())
                )
            }
            om.await(); gs.await(); km.await(); bl.await(); ob.await()
        }

        val result = RefreshResult(succeeded, failed)
        dao.upsertMeta(
            RefreshMetaEntity(
                lastSuccessMs = if (succeeded.isNotEmpty()) now.toEpochMilli() else previousSuccessMs(),
                lastAttemptMs = now.toEpochMilli(),
                lastAttemptFailed = result.allFailed,
            )
        )
        return result
    }

    private suspend fun previousSuccessMs(): Long? = dao.meta().first()?.lastSuccessMs

    private suspend fun storeForecast(source: Source, fc: SourceForecast?, error: String?, now: Instant) {
        val prev = dao.forecastOnce(source.name)
        dao.upsertForecast(
            if (fc != null) SourceForecastEntity(
                source = source.name,
                json = json.encodeToString(SourceForecast.serializer(), fc),
                issuedAtMs = fc.issuedAt.toEpochMilli(),
                fetchedAtMs = now.toEpochMilli(),
                lastError = null,
                lastErrorAtMs = null,
            ) else SourceForecastEntity(
                source = source.name,
                json = prev?.json,
                issuedAtMs = prev?.issuedAtMs,
                fetchedAtMs = prev?.fetchedAtMs,
                lastError = error ?: "unknown error",
                lastErrorAtMs = now.toEpochMilli(),
            )
        )
    }

    companion object {
        fun statusOf(
            hasData: Boolean, issuedAt: Instant?, fetchedAtMs: Long?,
            lastError: String?, lastErrorAtMs: Long?, staleAfter: Duration, now: Instant,
        ): SourceStatus {
            if (!hasData) return SourceStatus.Failed(lastError ?: "no data", null)
            val failedAfterFetch = lastErrorAtMs != null && lastErrorAtMs > (fetchedAtMs ?: 0L)
            if (failedAfterFetch) return SourceStatus.Failed(lastError ?: "error", issuedAt)
            val issued = issuedAt ?: return SourceStatus.Failed("no timestamp", null)
            return if (Duration.between(issued, now) > staleAfter) SourceStatus.Stale(issued) else SourceStatus.Ok(issued)
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.WeatherRepositoryTest' --console=plain 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(data): WeatherRepository with parallel refresh, cache-preserving failures and status"
```

---

### Task 9: Settings (DataStore) and Hilt wiring

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt`
- Create: `app/src/main/kotlin/it/apexweather/di/AppModule.kt`
- Test: `app/src/test/kotlin/it/apexweather/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `enum LanguageSetting(tag: String?) { SYSTEM, DE, IT, EN }`, `enum WindUnit { KMH, MS }`, `enum CompareVariable { TEMPERATURE, PRECIPITATION, WIND }`, `data class AppSettings(language, windUnit, animations, compareSources: Set<Source>, compareVariable)` with `fun bulletinLanguage(systemTag: String): String`, `class SettingsRepository(context)` with `val settings: Flow<AppSettings>` and `suspend fun setLanguage / setWindUnit / setAnimations / setCompareSources / setCompareVariable`. Hilt module providing `Json`, `OkHttpClient`, the four APIs, `AppDatabase`, `WeatherDao`, `Clock`, `ConsensusBlender`.

- [ ] **Step 1: Failing settings test**

Create `app/src/test/kotlin/it/apexweather/data/SettingsRepositoryTest.kt`:
```kotlin
package it.apexweather.data

import androidx.test.core.app.ApplicationProvider
import it.apexweather.domain.model.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {
    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `defaults`() = runTest {
        val s = repo.settings.first()
        assertEquals(LanguageSetting.SYSTEM, s.language)
        assertEquals(WindUnit.KMH, s.windUnit)
        assertTrue(s.animations)
        assertEquals(Source.entries.toSet(), s.compareSources)
        assertEquals(CompareVariable.TEMPERATURE, s.compareVariable)
    }

    @Test
    fun `bulletin language resolves system locale and falls back to German`() {
        val s = AppSettings()
        assertEquals("it", s.bulletinLanguage("it-IT"))
        assertEquals("en", s.bulletinLanguage("en-US"))
        assertEquals("de", s.bulletinLanguage("fr-FR"))
        assertEquals("en", s.copy(language = LanguageSetting.EN).bulletinLanguage("de-DE"))
    }

    @Test
    fun `writes round-trip`() = runTest {
        repo.setLanguage(LanguageSetting.IT)
        repo.setCompareSources(setOf(Source.ICON_D2))
        repo.setCompareVariable(CompareVariable.WIND)
        repo.setAnimations(false)
        val s = repo.settings.first()
        assertEquals(LanguageSetting.IT, s.language)
        assertEquals(setOf(Source.ICON_D2), s.compareSources)
        assertEquals(CompareVariable.WIND, s.compareVariable)
        assertEquals(false, s.animations)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.data.SettingsRepositoryTest' --console=plain 2>&1 | tail -10`
Expected: compilation failure, `Unresolved reference 'SettingsRepository'`.

- [ ] **Step 3: Implement settings and DI**

Create `app/src/main/kotlin/it/apexweather/data/SettingsRepository.kt`:
```kotlin
package it.apexweather.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.domain.model.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class LanguageSetting(val tag: String?) { SYSTEM(null), DE("de"), IT("it"), EN("en") }
enum class WindUnit { KMH, MS }
enum class CompareVariable { TEMPERATURE, PRECIPITATION, WIND }

data class AppSettings(
    val language: LanguageSetting = LanguageSetting.SYSTEM,
    val windUnit: WindUnit = WindUnit.KMH,
    val animations: Boolean = true,
    val compareSources: Set<Source> = Source.entries.toSet(),
    val compareVariable: CompareVariable = CompareVariable.TEMPERATURE,
) {
    /** Language used for the SIAG bulletin: explicit setting, else system language, else German. */
    fun bulletinLanguage(systemTag: String): String {
        language.tag?.let { return it }
        val lang = systemTag.substringBefore('-').lowercase()
        return if (lang in setOf("de", "it", "en")) lang else "de"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val language = stringPreferencesKey("language")
        val windUnit = stringPreferencesKey("wind_unit")
        val animations = booleanPreferencesKey("animations")
        val compareSources = stringSetPreferencesKey("compare_sources")
        val compareVariable = stringPreferencesKey("compare_variable")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            language = p[Keys.language]?.let { runCatching { LanguageSetting.valueOf(it) }.getOrNull() } ?: LanguageSetting.SYSTEM,
            windUnit = p[Keys.windUnit]?.let { runCatching { WindUnit.valueOf(it) }.getOrNull() } ?: WindUnit.KMH,
            animations = p[Keys.animations] ?: true,
            compareSources = p[Keys.compareSources]?.mapNotNull { runCatching { Source.valueOf(it) }.getOrNull() }?.toSet()
                ?: Source.entries.toSet(),
            compareVariable = p[Keys.compareVariable]?.let { runCatching { CompareVariable.valueOf(it) }.getOrNull() }
                ?: CompareVariable.TEMPERATURE,
        )
    }

    suspend fun setLanguage(v: LanguageSetting) = context.settingsStore.edit { it[Keys.language] = v.name }
    suspend fun setWindUnit(v: WindUnit) = context.settingsStore.edit { it[Keys.windUnit] = v.name }
    suspend fun setAnimations(v: Boolean) = context.settingsStore.edit { it[Keys.animations] = v }
    suspend fun setCompareSources(v: Set<Source>) = context.settingsStore.edit { it[Keys.compareSources] = v.map { s -> s.name }.toSet() }
    suspend fun setCompareVariable(v: CompareVariable) = context.settingsStore.edit { it[Keys.compareVariable] = v.name }
}
```

Create `app/src/main/kotlin/it/apexweather/di/AppModule.kt`:
```kotlin
package it.apexweather.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import it.apexweather.BuildConfig
import it.apexweather.data.local.AppDatabase
import it.apexweather.data.local.WeatherDao
import it.apexweather.data.remote.GeoSphereApi
import it.apexweather.data.remote.OdhApi
import it.apexweather.data.remote.OpenMeteoApi
import it.apexweather.data.remote.SiagApi
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DorfTirol
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "ApexWeather/${BuildConfig.VERSION_NAME} (Android)").build())
        }
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
        }
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton fun openMeteo(c: OkHttpClient, j: Json): OpenMeteoApi = retrofit(OpenMeteoApi.BASE_URL, c, j).create(OpenMeteoApi::class.java)
    @Provides @Singleton fun geoSphere(c: OkHttpClient, j: Json): GeoSphereApi = retrofit(GeoSphereApi.BASE_URL, c, j).create(GeoSphereApi::class.java)
    @Provides @Singleton fun siag(c: OkHttpClient, j: Json): SiagApi = retrofit(SiagApi.BASE_URL, c, j).create(SiagApi::class.java)
    @Provides @Singleton fun odh(c: OkHttpClient, j: Json): OdhApi = retrofit(OdhApi.BASE_URL, c, j).create(OdhApi::class.java)

    @Provides @Singleton fun database(@ApplicationContext ctx: Context): AppDatabase = AppDatabase.build(ctx)
    @Provides fun dao(db: AppDatabase): WeatherDao = db.weatherDao()
    @Provides @Singleton fun clock(): Clock = Clock.systemUTC()
    @Provides @Singleton fun blender(): ConsensusBlender = ConsensusBlender(DorfTirol.ZONE)
}
```

- [ ] **Step 4: Run tests and a full debug build (verifies Hilt graph)**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`, all unit tests pass, APK builds (Hilt validates the graph at compile time).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: settings store and Hilt module for network, database and domain services"
```

---

### Task 10: UI foundation: theme, icons, glass cards, animated sky

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/common/WeatherIcons.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/common/GlassCard.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/common/Format.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/sky/SkyBackground.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/sky/Particles.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/common/FormatTest.kt`, `app/src/androidTest/kotlin/it/apexweather/ui/sky/SkyBackgroundTest.kt`

**Interfaces:**
- Produces: `ApexTheme(content)`, `Color.fromArgb(Long)`, `Condition.icon(phase: SunPhase): ImageVector`, `Condition.label(): String` (resource lookup, `@Composable`), `GlassCard(modifier, content)`, `SkyBackground(palette: SkyPalette, animationsEnabled: Boolean, modifier)`, `Format.temp(Double): String` ("21°"), `Format.tempDecimal(Double)` ("21.4°"), `Format.wind(kmh: Double, unit: WindUnit): String`, `Format.mm(Double): String`, `Format.hour(Instant, ZoneId): String` ("14"), `Format.time(Instant, ZoneId): String` ("14:20"), `Format.weekday(LocalDate, Locale): String`.

- [ ] **Step 1: Failing format test**

Create `app/src/test/kotlin/it/apexweather/ui/common/FormatTest.kt`:
```kotlin
package it.apexweather.ui.common

import it.apexweather.data.WindUnit
import it.apexweather.domain.DorfTirol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class FormatTest {
    @Test fun temp() { assertEquals("21°", Format.temp(21.4)); assertEquals("-3°", Format.temp(-2.6)); assertEquals("0°", Format.temp(-0.2)) }
    @Test fun tempDecimal() = assertEquals("21.4°", Format.tempDecimal(21.44))
    @Test fun wind() { assertEquals("12 km/h", Format.wind(12.3, WindUnit.KMH)); assertEquals("3.4 m/s", Format.wind(12.3, WindUnit.MS)) }
    @Test fun mm() { assertEquals("0 mm", Format.mm(0.04)); assertEquals("0.3 mm", Format.mm(0.3)); assertEquals("12 mm", Format.mm(12.4)) }
    @Test fun hour() = assertEquals("14", Format.hour(Instant.parse("2026-09-08T12:00:00Z"), DorfTirol.ZONE))
    @Test fun time() = assertEquals("14:20", Format.time(Instant.parse("2026-09-08T12:20:00Z"), DorfTirol.ZONE))
    @Test fun weekday() = assertEquals("Di.", Format.weekday(LocalDate.of(2026, 9, 8), Locale.GERMAN))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.common.FormatTest' --console=plain 2>&1 | tail -8`
Expected: compilation failure, `Unresolved reference 'Format'`.

- [ ] **Step 3: Implement foundation**

Create `app/src/main/kotlin/it/apexweather/ui/common/Format.kt`:
```kotlin
package it.apexweather.ui.common

import it.apexweather.data.WindUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {
    fun temp(c: Double): String {
        val r = c.roundToInt()
        return if (r == 0) "0°" else "$r°"
    }
    fun tempDecimal(c: Double): String = String.format(Locale.ROOT, "%.1f°", c)
    fun wind(kmh: Double, unit: WindUnit): String = when (unit) {
        WindUnit.KMH -> "${kmh.roundToInt()} km/h"
        WindUnit.MS -> String.format(Locale.ROOT, "%.1f m/s", kmh / 3.6)
    }
    fun mm(mm: Double): String = when {
        abs(mm) < 0.05 -> "0 mm"
        mm < 10 -> String.format(Locale.ROOT, "%.1f mm", mm)
        else -> "${mm.roundToInt()} mm"
    }
    fun hour(t: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH").format(t.atZone(zone))
    fun time(t: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm").format(t.atZone(zone))
    fun weekday(d: LocalDate, locale: Locale): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    fun dayMonth(d: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("d. MMM", locale).format(d)
}
```

Create `app/src/main/kotlin/it/apexweather/ui/theme/Theme.kt`:
```kotlin
package it.apexweather.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Color.Companion.fromArgb(argb: Long): Color = Color(argb.toInt())

val ApexColors = darkColorScheme(
    primary = Color(0xFFFFD166),
    onPrimary = Color(0xFF1A1200),
    secondary = Color(0xFF9CC9FF),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFF4F6FB),
    surface = Color(0xFF121A2E),
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = Color(0x1AFFFFFF),
    onSurfaceVariant = Color(0xCCFFFFFF),
    outline = Color(0x33FFFFFF),
)

val ApexTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 112.sp, lineHeight = 112.sp, letterSpacing = (-4).sp, fontFeatureSettings = "tnum"),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium),
)

val ApexShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun ApexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ApexColors, typography = ApexTypography, shapes = ApexShapes, content = content)
}
```

Create `app/src/main/kotlin/it/apexweather/ui/common/WeatherIcons.kt`:
```kotlin
package it.apexweather.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import it.apexweather.R
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition

fun Condition.icon(phase: SunPhase = SunPhase.DAY): ImageVector = when (this) {
    Condition.CLEAR -> if (phase == SunPhase.NIGHT) Icons.Rounded.NightsStay else Icons.Rounded.WbSunny
    Condition.MOSTLY_CLEAR -> if (phase == SunPhase.NIGHT) Icons.Rounded.NightsStay else Icons.Rounded.WbSunny
    Condition.PARTLY_CLOUDY -> Icons.Rounded.WbCloudy
    Condition.CLOUDY -> Icons.Rounded.Cloud
    Condition.FOG -> Icons.Rounded.Dehaze
    Condition.DRIZZLE -> Icons.Rounded.Grain
    Condition.RAIN -> Icons.Rounded.Opacity
    Condition.HEAVY_RAIN -> Icons.Rounded.Umbrella
    Condition.SLEET, Condition.SNOW, Condition.HEAVY_SNOW -> Icons.Rounded.AcUnit
    Condition.THUNDERSTORM -> Icons.Rounded.Bolt
}

@Composable
fun Condition.label(): String = stringResource(
    when (this) {
        Condition.CLEAR -> R.string.cond_clear
        Condition.MOSTLY_CLEAR -> R.string.cond_mostly_clear
        Condition.PARTLY_CLOUDY -> R.string.cond_partly_cloudy
        Condition.CLOUDY -> R.string.cond_cloudy
        Condition.FOG -> R.string.cond_fog
        Condition.DRIZZLE -> R.string.cond_drizzle
        Condition.RAIN -> R.string.cond_rain
        Condition.HEAVY_RAIN -> R.string.cond_heavy_rain
        Condition.SLEET -> R.string.cond_sleet
        Condition.SNOW -> R.string.cond_snow
        Condition.HEAVY_SNOW -> R.string.cond_heavy_snow
        Condition.THUNDERSTORM -> R.string.cond_thunderstorm
    }
)
```

Add these strings now to `app/src/main/res/values/strings.xml` (German), `values-it/strings.xml`, `values-en/strings.xml` (the complete string set arrives in Task 14; these are needed to compile this task):
```xml
    <!-- values (de) -->
    <string name="cond_clear">Klar</string>
    <string name="cond_mostly_clear">Heiter</string>
    <string name="cond_partly_cloudy">Wolkig</string>
    <string name="cond_cloudy">Bedeckt</string>
    <string name="cond_fog">Nebel</string>
    <string name="cond_drizzle">Leichter Regen</string>
    <string name="cond_rain">Regen</string>
    <string name="cond_heavy_rain">Starker Regen</string>
    <string name="cond_sleet">Schneeregen</string>
    <string name="cond_snow">Schnee</string>
    <string name="cond_heavy_snow">Starker Schneefall</string>
    <string name="cond_thunderstorm">Gewitter</string>
```
```xml
    <!-- values-it -->
    <string name="cond_clear">Sereno</string>
    <string name="cond_mostly_clear">Poco nuvoloso</string>
    <string name="cond_partly_cloudy">Nuvoloso</string>
    <string name="cond_cloudy">Coperto</string>
    <string name="cond_fog">Nebbia</string>
    <string name="cond_drizzle">Pioggia debole</string>
    <string name="cond_rain">Pioggia</string>
    <string name="cond_heavy_rain">Pioggia forte</string>
    <string name="cond_sleet">Nevischio</string>
    <string name="cond_snow">Neve</string>
    <string name="cond_heavy_snow">Neve forte</string>
    <string name="cond_thunderstorm">Temporale</string>
```
```xml
    <!-- values-en -->
    <string name="cond_clear">Clear</string>
    <string name="cond_mostly_clear">Mostly clear</string>
    <string name="cond_partly_cloudy">Partly cloudy</string>
    <string name="cond_cloudy">Cloudy</string>
    <string name="cond_fog">Fog</string>
    <string name="cond_drizzle">Drizzle</string>
    <string name="cond_rain">Rain</string>
    <string name="cond_heavy_rain">Heavy rain</string>
    <string name="cond_sleet">Sleet</string>
    <string name="cond_snow">Snow</string>
    <string name="cond_heavy_snow">Heavy snow</string>
    <string name="cond_thunderstorm">Thunderstorm</string>
```

Create `app/src/main/kotlin/it/apexweather/ui/common/GlassCard.kt`:
```kotlin
package it.apexweather.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Translucent "frosted" card that lets the sky show through. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f))))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape)
            .padding(16.dp),
        content = content,
    )
}
```

Create `app/src/main/kotlin/it/apexweather/ui/sky/Particles.kt`:
```kotlin
package it.apexweather.ui.sky

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import it.apexweather.domain.ParticleKind
import kotlin.math.sin
import kotlin.random.Random

/** One particle in normalized [0,1] space. */
class Particle(var x: Float, var y: Float, val size: Float, val speed: Float, val seed: Float)

class ParticleSystem(val kind: ParticleKind, density: Float, private val random: Random = Random(42)) {
    val particles: List<Particle>
    var lightningFlash = 0f // 0..1, decays each frame
    private var nextStrikeAt = 2f
    private var elapsed = 0f

    init {
        val count = when (kind) {
            ParticleKind.NONE -> 0
            ParticleKind.STARS -> (90 * density).toInt().coerceAtLeast(20)
            ParticleKind.CLOUDS -> (5 * density).toInt().coerceAtLeast(2)
            ParticleKind.RAIN -> (180 * density).toInt().coerceAtLeast(30)
            ParticleKind.SNOW -> (120 * density).toInt().coerceAtLeast(25)
            ParticleKind.FOG -> 4
            ParticleKind.LIGHTNING -> (160 * density).toInt().coerceAtLeast(40)
        }
        particles = List(count) { newParticle(kind, random, spawnAnywhere = true) }
    }

    private fun newParticle(kind: ParticleKind, r: Random, spawnAnywhere: Boolean): Particle {
        val y = if (spawnAnywhere) r.nextFloat() else -0.05f
        return when (kind) {
            ParticleKind.STARS -> Particle(r.nextFloat(), r.nextFloat() * 0.6f, 0.6f + r.nextFloat() * 1.6f, 0f, r.nextFloat() * 6.28f)
            ParticleKind.CLOUDS -> Particle(if (spawnAnywhere) r.nextFloat() else -0.3f, 0.05f + r.nextFloat() * 0.35f, 0.25f + r.nextFloat() * 0.25f, 0.004f + r.nextFloat() * 0.006f, r.nextFloat())
            ParticleKind.RAIN, ParticleKind.LIGHTNING -> Particle(r.nextFloat(), y, 8f + r.nextFloat() * 14f, 0.9f + r.nextFloat() * 0.6f, r.nextFloat())
            ParticleKind.SNOW -> Particle(r.nextFloat(), y, 1.5f + r.nextFloat() * 3f, 0.06f + r.nextFloat() * 0.08f, r.nextFloat() * 6.28f)
            ParticleKind.FOG -> Particle(r.nextFloat(), 0.3f + r.nextFloat() * 0.6f, 0.5f + r.nextFloat() * 0.5f, 0.01f + r.nextFloat() * 0.01f, r.nextFloat())
            ParticleKind.NONE -> Particle(0f, 0f, 0f, 0f, 0f)
        }
    }

    /** Advance by [dt] seconds. */
    fun step(dt: Float) {
        elapsed += dt
        for (p in particles) {
            when (kind) {
                ParticleKind.RAIN, ParticleKind.LIGHTNING -> { p.y += p.speed * dt; p.x -= 0.08f * dt; if (p.y > 1.05f) { p.y = -0.05f; p.x = random.nextFloat() } }
                ParticleKind.SNOW -> { p.y += p.speed * dt; p.x += sin(elapsed * 0.8f + p.seed) * 0.02f * dt; if (p.y > 1.05f) { p.y = -0.05f; p.x = random.nextFloat() } }
                ParticleKind.CLOUDS, ParticleKind.FOG -> { p.x += p.speed * dt; if (p.x > 1.3f) p.x = -0.3f }
                ParticleKind.STARS, ParticleKind.NONE -> Unit
            }
        }
        if (kind == ParticleKind.LIGHTNING) {
            lightningFlash = (lightningFlash - dt * 2.5f).coerceAtLeast(0f)
            if (elapsed >= nextStrikeAt) { lightningFlash = 1f; nextStrikeAt = elapsed + 3f + random.nextFloat() * 6f }
        }
    }

    fun draw(scope: DrawScope, accent: Color) = with(scope) {
        val w = size.width; val h = size.height
        when (kind) {
            ParticleKind.STARS -> particles.forEach { p ->
                val twinkle = 0.55f + 0.45f * sin(elapsed * 1.5f + p.seed)
                drawCircle(accent.copy(alpha = 0.9f * twinkle), radius = p.size, center = Offset(p.x * w, p.y * h))
            }
            ParticleKind.CLOUDS -> particles.forEach { p ->
                val cw = p.size * w; val ch = cw * 0.35f
                val c = Color.White.copy(alpha = 0.10f)
                drawOval(c, topLeft = Offset(p.x * w - cw / 2, p.y * h - ch / 2), size = Size(cw, ch))
                drawOval(c, topLeft = Offset(p.x * w - cw * 0.3f, p.y * h - ch * 0.9f), size = Size(cw * 0.6f, ch * 1.2f))
            }
            ParticleKind.RAIN, ParticleKind.LIGHTNING -> {
                particles.forEach { p ->
                    val x = p.x * w; val y = p.y * h
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(x, y), Offset(x - p.size * 0.15f, y + p.size), strokeWidth = 1.2f)
                }
                if (lightningFlash > 0f) drawRect(Color.White.copy(alpha = 0.55f * lightningFlash))
            }
            ParticleKind.SNOW -> particles.forEach { p ->
                drawCircle(Color.White.copy(alpha = 0.85f), radius = p.size, center = Offset(p.x * w, p.y * h))
            }
            ParticleKind.FOG -> particles.forEach { p ->
                val fw = p.size * w * 1.6f; val fh = h * 0.18f
                drawOval(Color.White.copy(alpha = 0.12f), topLeft = Offset(p.x * w - fw / 2, p.y * h - fh / 2), size = Size(fw, fh))
            }
            ParticleKind.NONE -> Unit
        }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/ui/sky/SkyBackground.kt`:
```kotlin
package it.apexweather.ui.sky

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import it.apexweather.domain.SkyPalette
import it.apexweather.ui.theme.fromArgb

/**
 * Full-screen animated sky: crossfading gradient, weather particles, ridge silhouette.
 * Particles only run while the lifecycle is RESUMED and [animationsEnabled] is true.
 */
@Composable
fun SkyBackground(palette: SkyPalette, animationsEnabled: Boolean, modifier: Modifier = Modifier) {
    val spec = tween<Color>(1500)
    val top by animateColorAsState(Color.fromArgb(palette.top), spec, label = "top")
    val mid by animateColorAsState(Color.fromArgb(palette.mid), spec, label = "mid")
    val bottom by animateColorAsState(Color.fromArgb(palette.bottom), spec, label = "bottom")
    val ridge by animateColorAsState(Color.fromArgb(palette.ridge), spec, label = "ridge")
    val accent = Color.fromArgb(palette.accent)

    val system = remember(palette.particle, palette.density) { ParticleSystem(palette.particle, palette.density) }
    var frame by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    if (animationsEnabled) {
        LaunchedEffect(system, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var last = 0L
                while (true) {
                    withFrameNanos { now ->
                        val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                        last = now
                        system.step(dt)
                        frame = now
                    }
                }
            }
        }
    }

    Box(modifier.fillMaxSize().testTag("sky").background(Brush.verticalGradient(listOf(top, mid, bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val f = frame // read state so the canvas redraws every frame
            system.draw(this, accent)
            drawRidge(ridge)
        }
    }
}

/** Stylized Meran valley ridge (Ifinger / Mutspitze) at the bottom of the screen. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRidge(color: Color) {
    val w = size.width; val h = size.height
    val pts = listOf(0f to 0.86f, 0.08f to 0.80f, 0.16f to 0.84f, 0.26f to 0.74f, 0.33f to 0.79f, 0.42f to 0.70f,
        0.50f to 0.76f, 0.58f to 0.72f, 0.66f to 0.81f, 0.74f to 0.77f, 0.84f to 0.85f, 0.92f to 0.82f, 1f to 0.88f)
    val path = Path().apply {
        moveTo(0f, h)
        pts.forEach { (x, y) -> lineTo(x * w, y * h) }
        lineTo(w, h); close()
    }
    drawPath(path, color.copy(alpha = 0.85f))
    val back = Path().apply {
        moveTo(0f, h)
        listOf(0f to 0.78f, 0.12f to 0.70f, 0.22f to 0.75f, 0.36f to 0.62f, 0.48f to 0.69f, 0.60f to 0.60f, 0.72f to 0.68f, 0.86f to 0.64f, 1f to 0.74f)
            .forEach { (x, y) -> lineTo(x * w, y * h) }
        lineTo(w, h); close()
    }
    drawPath(back, color.copy(alpha = 0.45f))
}
```

- [ ] **Step 4: Compose UI smoke test for the sky**

Create `app/src/androidTest/kotlin/it/apexweather/ui/sky/SkyBackgroundTest.kt`:
```kotlin
package it.apexweather.ui.sky

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.domain.SkyPaletteSelector
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import it.apexweather.ui.theme.ApexTheme
import org.junit.Rule
import org.junit.Test

class SkyBackgroundTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun rendersEveryParticleKindWithoutCrashing() {
        val palettes = listOf(
            SkyPaletteSelector.select(Condition.CLEAR, SunPhase.NIGHT, 0.0),
            SkyPaletteSelector.select(Condition.RAIN, SunPhase.DAY, 3.0),
            SkyPaletteSelector.select(Condition.SNOW, SunPhase.DUSK, 2.0),
            SkyPaletteSelector.select(Condition.THUNDERSTORM, SunPhase.NIGHT, 5.0),
            SkyPaletteSelector.select(Condition.FOG, SunPhase.DAWN, 0.0),
            SkyPaletteSelector.select(Condition.PARTLY_CLOUDY, SunPhase.DAY, 0.0),
        )
        val current = mutableStateOf(palettes.first())
        rule.setContent { ApexTheme { SkyBackground(palette = current.value, animationsEnabled = true) } }
        palettes.forEach { p ->
            rule.runOnIdle { current.value = p }
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithTag("sky").assertIsDisplayed()
        }
    }
}
```

- [ ] **Step 5: Run unit tests, build, run the device test**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.common.FormatTest' :app:assembleDebug --console=plain 2>&1 | tail -10
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.sky.SkyBackgroundTest --console=plain 2>&1 | tail -10
```
Expected: both `BUILD SUCCESSFUL`. (If both the phone and the emulator are attached, Gradle runs on both; that is fine.)

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(ui): theme, weather icons, glass cards and animated sky background"
```

---

### Task 11: Home screen (state builder, ViewModel, sections, detail sheet)

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/home/HomeState.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/home/HomeViewModel.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/home/HomeStateBuilderTest.kt`, `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: `WeatherRepository.snapshot/refresh`, `SettingsRepository.settings`, `ConsensusBlender`, `SkyPaletteSelector`, `SunPhaseCalculator`, `Format`, `GlassCard`, `Condition.icon/label`.
- Produces: `data class HomeUiState(...)` (below), `HomeStateBuilder.build(snapshot, settings, consensus, now): HomeUiState`, `HomeViewModel` (`state: StateFlow<HomeUiState>`, `refresh()`), `HomeScreen(viewModel, onOpenBulletin)`, `HomeContent(state, onRefresh, onOpenBulletin)`. Test tags: `hero_temp`, `hourly_strip`, `hour_column_<index>`, `hour_detail_sheet`, `daily_list`, `offline_banner`, `empty_state`, `agreement_badge`.

- [ ] **Step 1: Failing state builder test**

Create `app/src/test/kotlin/it/apexweather/ui/home/HomeStateBuilderTest.kt`:
```kotlin
package it.apexweather.ui.home

import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.ParticleKind
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.point
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class HomeStateBuilderTest {
    private val blender = ConsensusBlender()
    private val forecasts = mapOf(
        Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 48).map { point(it, 10.0 + it % 10, condition = Condition.RAIN) }),
        Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 48).map { point(it, 12.0 + it % 10, condition = Condition.RAIN) }),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = forecasts)
    private val consensus = blender.blend(forecasts)

    @Test
    fun `hero uses consensus hour when no fresh observation`() {
        val s = HomeStateBuilder.build(snapshot, AppSettings(), consensus, now = hour(3).plusSeconds(600))
        assertEquals(consensus.hourly[3].tempC, s.heroTempC!!, 0.0)
        assertEquals(Condition.RAIN, s.heroCondition)
        assertEquals(ParticleKind.RAIN, s.palette.particle)
        assertEquals(1.0, s.bandHalfWidth!!, 0.0)
        assertFalse(s.isEmpty)
    }

    @Test
    fun `fresh observation overrides hero temperature but not condition`() {
        val obs = StationObservation("Meran", hour(3), tempC = 25.5, humidityPct = 40, windKmh = 5.0, windDir = "W", gustKmh = null, precipMm = 0.0, pressureHpa = 1010.0)
        val s = HomeStateBuilder.build(snapshot.copy(observation = obs), AppSettings(), consensus, now = hour(3).plusSeconds(600))
        assertEquals(25.5, s.heroTempC!!, 0.0)
        assertEquals(Condition.RAIN, s.heroCondition)
        assertTrue(s.observation != null)
    }

    @Test
    fun `stale observation is ignored`() {
        val obs = StationObservation("Meran", hour(0), tempC = 25.5, null, null, null, null, null, null)
        val s = HomeStateBuilder.build(snapshot.copy(observation = obs), AppSettings(), consensus, now = hour(0).plus(Duration.ofMinutes(91)))
        assertNull(s.observation)
        assertEquals(consensus.hourly[1].tempC, s.heroTempC!!, 0.0)
    }

    @Test
    fun `upcoming hours start at the current hour and cap at 48`() {
        val s = HomeStateBuilder.build(snapshot, AppSettings(), consensus, now = hour(5).plusSeconds(1))
        assertEquals(hour(5), s.upcomingHours.first().time)
        assertTrue(s.upcomingHours.size <= 48)
    }

    @Test
    fun `empty snapshot gives empty state with default palette`() {
        val s = HomeStateBuilder.build(WeatherSnapshot.EMPTY, AppSettings(), consensus = ConsensusForecast.EMPTY, now = hour(0))
        assertTrue(s.isEmpty)
        assertNull(s.heroTempC)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.home.HomeStateBuilderTest' --console=plain 2>&1 | tail -8`
Expected: compilation failure, `Unresolved reference 'HomeStateBuilder'`.

- [ ] **Step 3: Implement state and ViewModel**

Create `app/src/main/kotlin/it/apexweather/ui/home/HomeState.kt`:
```kotlin
package it.apexweather.ui.home

import it.apexweather.data.AppSettings
import it.apexweather.domain.SkyPalette
import it.apexweather.domain.SkyPaletteSelector
import it.apexweather.domain.SunPhase
import it.apexweather.domain.SunPhaseCalculator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.domain.model.StationObservation
import it.apexweather.domain.model.WeatherSnapshot
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val isEmpty: Boolean = true,
    val now: Instant = Instant.EPOCH,
    val phase: SunPhase = SunPhase.DAY,
    val sunrise: Instant? = null,
    val sunset: Instant? = null,
    val palette: SkyPalette = SkyPaletteSelector.select(Condition.PARTLY_CLOUDY, SunPhase.DAY, 0.0),
    val heroTempC: Double? = null,
    val heroFeelsLikeC: Double? = null,
    val heroCondition: Condition = Condition.PARTLY_CLOUDY,
    val bandHalfWidth: Double? = null,
    val currentHour: ConsensusHour? = null,
    val observation: StationObservation? = null,
    val upcomingHours: List<ConsensusHour> = emptyList(),
    val days: List<ConsensusDay> = emptyList(),
    val bulletin: Bulletin? = null,
    val updatedAt: Instant? = null,
    val offline: Boolean = false,
    val settings: AppSettings = AppSettings(),
) {
    /** Sun phase at an arbitrary instant, using today's sunrise/sunset (fallback 07–19 local). */
    fun phaseAt(t: Instant): SunPhase = SunPhaseCalculator.phase(t, sunrise, sunset, DorfTirol.ZONE)
}

object HomeStateBuilder {
    private val OBSERVATION_MAX_AGE: Duration = Duration.ofMinutes(90)

    fun build(snapshot: WeatherSnapshot, settings: AppSettings, consensus: ConsensusForecast, now: Instant): HomeUiState {
        val thisHour = now.truncatedTo(ChronoUnit.HOURS)
        val upcoming = consensus.hourly.filter { !it.time.isBefore(thisHour) }.take(48)
        val current = upcoming.firstOrNull()
        val today = consensus.daily.firstOrNull { it.date == now.atZone(DorfTirol.ZONE).toLocalDate() }
        val phase = SunPhaseCalculator.phase(now, today?.sunrise, today?.sunset, DorfTirol.ZONE)
        val obs = snapshot.observation?.takeIf { Duration.between(it.time, now) <= OBSERVATION_MAX_AGE && it.tempC != null }
        val heroCondition = current?.condition ?: Condition.PARTLY_CLOUDY
        val isEmpty = current == null && obs == null && snapshot.bulletin == null
        return HomeUiState(
            loading = false,
            isEmpty = isEmpty,
            now = now,
            phase = phase,
            sunrise = today?.sunrise,
            sunset = today?.sunset,
            palette = SkyPaletteSelector.select(heroCondition, phase, current?.precipMm ?: 0.0),
            heroTempC = obs?.tempC ?: current?.tempC,
            heroFeelsLikeC = current?.feelsLikeC,
            heroCondition = heroCondition,
            bandHalfWidth = current?.let { (it.tempMaxC - it.tempMinC) / 2.0 },
            currentHour = current,
            observation = obs,
            upcomingHours = upcoming,
            days = consensus.daily.filter { !it.date.isBefore(now.atZone(DorfTirol.ZONE).toLocalDate()) }.take(7),
            bulletin = snapshot.bulletin,
            updatedAt = snapshot.lastSuccessfulRefresh,
            offline = snapshot.lastRefreshFailed,
            settings = settings,
        )
    }
}
```

Create `app/src/main/kotlin/it/apexweather/ui/home/HomeViewModel.kt`:
```kotlin
package it.apexweather.ui.home

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val blender: ConsensusBlender,
    private val clock: Clock,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val minuteTick = flow { while (true) { emit(Unit); delay(60_000) } }

    private val snapshotWithSettings = settingsRepository.settings.flatMapLatest { s ->
        repository.snapshot(s.bulletinLanguage(Locale.getDefault().toLanguageTag())).map { s to it }
    }

    val state: StateFlow<HomeUiState> = combine(snapshotWithSettings, refreshing, minuteTick) { (settings, snapshot), isRefreshing, _ ->
        HomeStateBuilder.build(snapshot, settings, blender.blend(snapshot.forecasts), clock.instant()).copy(refreshing = isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            val (settings, snapshot) = snapshotWithSettings.first()
            val age = snapshot.lastSuccessfulRefresh?.let { Duration.between(it, clock.instant()) }
            if (age == null || age > Duration.ofMinutes(30)) doRefresh(settings.bulletinLanguage(Locale.getDefault().toLanguageTag()))
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            doRefresh(settings.bulletinLanguage(Locale.getDefault().toLanguageTag()))
        }
    }

    private suspend fun doRefresh(language: String) {
        if (refreshing.value) return
        refreshing.value = true
        try {
            repository.refresh(language)
            runCatching { ApexWidget().updateAll(context) }
        } finally {
            refreshing.value = false
        }
    }
}
```
`ApexWidget` is created in Task 16; until then create a placeholder `app/src/main/kotlin/it/apexweather/widget/ApexWidget.kt`:
```kotlin
package it.apexweather.widget

import androidx.glance.GlanceId
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

class ApexWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Text("Apex Weather") }
    }
}
```

- [ ] **Step 4: Run the state builder test**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.home.HomeStateBuilderTest' --console=plain 2>&1 | tail -8`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 5: Home screen composables**

Create `app/src/main/kotlin/it/apexweather/ui/home/HomeSections.kt`:
```kotlin
package it.apexweather.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.ConsensusDay
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.icon
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb
import kotlin.math.roundToInt

@Composable
fun HeroSection(state: HomeUiState, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.Start) {
        Text(DorfTirol.NAME, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
        Text(
            text = state.heroTempC?.let(Format::temp) ?: "–",
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
            modifier = Modifier.testTag("hero_temp"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(state.heroCondition.icon(state.phase), contentDescription = null, tint = Color.fromArgb(state.palette.accent), modifier = Modifier.size(22.dp))
            Text(state.heroCondition.label(), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.heroFeelsLikeC?.let { Text(stringResource(R.string.feels_like, Format.temp(it)), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f)) }
            state.bandHalfWidth?.let { AgreementBadge(it, state.currentHour?.agreement ?: 0.5f) }
        }
        Spacer(Modifier.height(4.dp))
        val source = state.observation?.let { stringResource(R.string.now_from_station, it.stationName, Format.time(it.time, DorfTirol.ZONE)) }
            ?: stringResource(R.string.now_from_consensus, state.currentHour?.sourceCount ?: 0)
        Text(source, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
        state.updatedAt?.let {
            Text(stringResource(R.string.updated_at, Format.time(it, DorfTirol.ZONE)), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun AgreementBadge(halfWidth: Double, agreement: Float) {
    val color = when {
        agreement >= 0.75f -> Color(0xFF7CE0A5)
        agreement >= 0.45f -> Color(0xFFFFD166)
        else -> Color(0xFFFF8A80)
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp).testTag("agreement_badge"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text("±${halfWidth.roundToInt()}°", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private val HourColumnWidth = 58.dp

@Composable
fun HourlySection(hours: List<ConsensusHour>, phaseAt: (java.time.Instant) -> SunPhase, accent: Color, onHourClick: (Int) -> Unit) {
    if (hours.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.section_hourly), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        val scroll = rememberScrollState()
        Column(Modifier.horizontalScroll(scroll).testTag("hourly_strip")) {
            TemperatureCurve(hours, accent, Modifier.width(HourColumnWidth * hours.size).height(90.dp))
            Row {
                hours.forEachIndexed { i, h ->
                    Column(
                        Modifier.width(HourColumnWidth).clickable { onHourClick(i) }.padding(vertical = 6.dp).testTag("hour_column_$i"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (i == 0) stringResource(R.string.now) else Format.hour(h.time, DorfTirol.ZONE), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                        Spacer(Modifier.height(6.dp))
                        Icon(h.condition.icon(phaseAt(h.time)), null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(Format.temp(h.tempC), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        PrecipBar(h.precipMm, h.precipProb)
                    }
                }
            }
        }
    }
}

@Composable
private fun PrecipBar(mm: Double, prob: Int) {
    val heightFraction = (mm / 5.0).coerceIn(0.0, 1.0).toFloat()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(18.dp).height(22.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.width(18.dp).height((22 * heightFraction).dp.coerceAtLeast(if (mm > 0.05) 2.dp else 0.dp)).clip(RoundedCornerShape(3.dp)).background(Color(0xFF8FB3E8)))
        }
        Text(if (prob > 0) "$prob%" else "", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color(0xFFB9D2F5))
    }
}

/** Consensus temperature line with translucent min/max band. */
@Composable
fun TemperatureCurve(hours: List<ConsensusHour>, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (hours.size < 2) return@Canvas
        val minT = hours.minOf { it.tempMinC } - 1
        val maxT = hours.maxOf { it.tempMaxC } + 1
        val colW = size.width / hours.size
        fun x(i: Int) = colW * i + colW / 2
        fun y(t: Double) = (size.height - 10f) - ((t - minT) / (maxT - minT)).toFloat() * (size.height - 20f)

        val band = Path().apply {
            moveTo(x(0), y(hours[0].tempMaxC))
            hours.forEachIndexed { i, h -> lineTo(x(i), y(h.tempMaxC)) }
            for (i in hours.indices.reversed()) lineTo(x(i), y(hours[i].tempMinC))
            close()
        }
        drawPath(band, Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.05f))))

        val line = Path().apply {
            moveTo(x(0), y(hours[0].tempC))
            for (i in 1 until hours.size) {
                val x0 = x(i - 1); val y0 = y(hours[i - 1].tempC); val x1 = x(i); val y1 = y(hours[i].tempC)
                cubicTo(x0 + colW / 2, y0, x1 - colW / 2, y1, x1, y1)
            }
        }
        drawPath(line, Color.White, style = Stroke(width = 3f))
        drawCircle(accent, radius = 5f, center = Offset(x(0), y(hours[0].tempC)))
    }
}

@Composable
fun DailySection(days: List<ConsensusDay>, phase: SunPhase, accent: Color) {
    if (days.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val globalMin = days.minOf { it.minC }
    val globalMax = days.maxOf { it.maxC }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("daily_list")) {
        Text(stringResource(R.string.section_daily), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        days.forEachIndexed { i, d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (i == 0) stringResource(R.string.today) else Format.weekday(d.date, locale),
                    style = MaterialTheme.typography.bodyMedium, color = Color.White, modifier = Modifier.width(52.dp),
                )
                Icon(d.condition.icon(SunPhase.DAY), null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (d.precipMm >= 0.5) Format.mm(d.precipMm) else "", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5), modifier = Modifier.width(48.dp))
                Text(Format.temp(d.minC), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.width(36.dp))
                RangeBar(d.minC, d.maxC, globalMin, globalMax, accent, Modifier.weight(1f).height(6.dp))
                Spacer(Modifier.width(8.dp))
                Text(Format.temp(d.maxC), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp))
                AgreementDot(d.agreement)
            }
        }
    }
}

@Composable
private fun RangeBar(min: Double, max: Double, gMin: Double, gMax: Double, accent: Color, modifier: Modifier) {
    Canvas(modifier) {
        val span = (gMax - gMin).coerceAtLeast(1.0)
        val x0 = ((min - gMin) / span * size.width).toFloat()
        val x1 = ((max - gMin) / span * size.width).toFloat()
        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF8FB3E8), accent), startX = x0, endX = x1),
            topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(size.height), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
        )
    }
}

@Composable
private fun AgreementDot(agreement: Float) {
    val color = when { agreement >= 0.75f -> Color(0xFF7CE0A5); agreement >= 0.45f -> Color(0xFFFFD166); else -> Color(0xFFFF8A80) }
    Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun BulletinTeaser(bulletin: Bulletin, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onClick).testTag("bulletin_teaser")) {
        Text(stringResource(R.string.section_bulletin), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        Text(bulletin.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(bulletin.evolution.substringBefore('\n').take(180), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 3)
    }
}

@Composable
fun AttributionFooter() {
    Text(
        stringResource(R.string.attribution),
        style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
    )
}
```

Create `app/src/main/kotlin/it/apexweather/ui/home/HomeScreen.kt`:
```kotlin
package it.apexweather.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.ConsensusHour
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.label
import it.apexweather.ui.theme.fromArgb

@Composable
fun HomeScreen(onOpenBulletin: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(state = state, onRefresh = viewModel::refresh, onOpenBulletin = onOpenBulletin)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(state: HomeUiState, onRefresh: () -> Unit, onOpenBulletin: () -> Unit) {
    var selectedHour by remember { mutableIntStateOf(-1) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(state.isEmpty) { if (!state.isEmpty) appeared = true }
    val accent = Color.fromArgb(state.palette.accent)
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize())
            state.isEmpty -> EmptyState(onRefresh, Modifier.fillMaxSize())
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topInset + 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.offline) item { OfflineBanner(state) }
                item { HeroSection(state) }
                item {
                    AnimatedVisibility(appeared, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 4 }) {
                        HourlySection(state.upcomingHours, state::phaseAt, accent) { selectedHour = it }
                    }
                }
                item {
                    AnimatedVisibility(appeared, enter = fadeIn(tween(600, 100)) + slideInVertically(tween(600, 100)) { it / 4 }) {
                        DailySection(state.days, state.phase, accent)
                    }
                }
                state.bulletin?.let { b ->
                    item {
                        AnimatedVisibility(appeared, enter = fadeIn(tween(700, 200)) + slideInVertically(tween(700, 200)) { it / 4 }) {
                            BulletinTeaser(b, onOpenBulletin)
                        }
                    }
                }
                item { AttributionFooter() }
            }
        }
    }

    val hour = state.upcomingHours.getOrNull(selectedHour)
    if (hour != null) {
        ModalBottomSheet(onDismissRequest = { selectedHour = -1 }, containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("hour_detail_sheet")) {
            HourDetail(hour, state)
        }
    }
}

@Composable
private fun OfflineBanner(state: HomeUiState) {
    val text = state.updatedAt?.let { stringResource(R.string.offline_banner, Format.time(it, DorfTirol.ZONE)) } ?: stringResource(R.string.offline_banner_no_time)
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = Color.White,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x66FF8A80)).padding(10.dp).testTag("offline_banner"),
    )
}

@Composable
private fun EmptyState(onRetry: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(32.dp).testTag("empty_state"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun HourDetail(hour: ConsensusHour, state: HomeUiState) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).padding(bottom = 32.dp)) {
        Text("${Format.time(hour.time, DorfTirol.ZONE)} · ${hour.condition.label()}", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.hour_summary, Format.temp(hour.tempC), Format.temp(hour.tempMinC), Format.temp(hour.tempMaxC), Format.mm(hour.precipMm), hour.precipProb, Format.wind(hour.windKmh, state.settings.windUnit)),
            style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.per_source), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(6.dp))
        hour.perSource.entries.sortedBy { it.key.ordinal }.forEach { (source, p) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(source.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Text("${Format.tempDecimal(p.tempC)}  ${Format.mm(p.precipMm)}  ${Format.wind(p.windKmh, state.settings.windUnit)}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}
```

Add these strings (de / it / en) to the three `strings.xml` files:
```xml
    <!-- de -->
    <string name="feels_like">Gefühlt %1$s</string>
    <string name="now_from_station">Jetzt: Station %1$s, %2$s</string>
    <string name="now_from_consensus">Konsens aus %1$d Modellen</string>
    <string name="updated_at">Aktualisiert %1$s</string>
    <string name="section_hourly">NÄCHSTE 48 STUNDEN</string>
    <string name="section_daily">7 TAGE</string>
    <string name="section_bulletin">LANDESWETTERDIENST</string>
    <string name="now">Jetzt</string>
    <string name="today">Heute</string>
    <string name="offline_banner">Daten von %1$s · offline</string>
    <string name="offline_banner_no_time">Keine Verbindung</string>
    <string name="empty_title">Noch keine Daten</string>
    <string name="empty_body">Apex Weather braucht einmal Internet, um die Vorhersagen für Dorf Tirol zu laden.</string>
    <string name="retry">Erneut versuchen</string>
    <string name="hour_summary">%1$s (%2$s bis %3$s) · %4$s · %5$d %% · Wind %6$s</string>
    <string name="per_source">PRO QUELLE</string>
    <string name="attribution">Daten: Landeswetterdienst Südtirol · GeoSphere Austria (CC BY 4.0) · MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo</string>
```
```xml
    <!-- it -->
    <string name="feels_like">Percepita %1$s</string>
    <string name="now_from_station">Ora: stazione %1$s, %2$s</string>
    <string name="now_from_consensus">Consenso di %1$d modelli</string>
    <string name="updated_at">Aggiornato %1$s</string>
    <string name="section_hourly">PROSSIME 48 ORE</string>
    <string name="section_daily">7 GIORNI</string>
    <string name="section_bulletin">SERVIZIO METEO PROVINCIALE</string>
    <string name="now">Ora</string>
    <string name="today">Oggi</string>
    <string name="offline_banner">Dati delle %1$s · offline</string>
    <string name="offline_banner_no_time">Nessuna connessione</string>
    <string name="empty_title">Ancora nessun dato</string>
    <string name="empty_body">Apex Weather ha bisogno di internet una volta per caricare le previsioni per Tirolo.</string>
    <string name="retry">Riprova</string>
    <string name="hour_summary">%1$s (da %2$s a %3$s) · %4$s · %5$d %% · vento %6$s</string>
    <string name="per_source">PER FONTE</string>
    <string name="attribution">Dati: Servizio Meteo Alto Adige · GeoSphere Austria (CC BY 4.0) · MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo</string>
```
```xml
    <!-- en -->
    <string name="feels_like">Feels like %1$s</string>
    <string name="now_from_station">Now: station %1$s, %2$s</string>
    <string name="now_from_consensus">Consensus of %1$d models</string>
    <string name="updated_at">Updated %1$s</string>
    <string name="section_hourly">NEXT 48 HOURS</string>
    <string name="section_daily">7 DAYS</string>
    <string name="section_bulletin">SOUTH TYROL WEATHER SERVICE</string>
    <string name="now">Now</string>
    <string name="today">Today</string>
    <string name="offline_banner">Data from %1$s · offline</string>
    <string name="offline_banner_no_time">No connection</string>
    <string name="empty_title">No data yet</string>
    <string name="empty_body">Apex Weather needs internet once to load the forecasts for Dorf Tirol.</string>
    <string name="retry">Retry</string>
    <string name="hour_summary">%1$s (%2$s to %3$s) · %4$s · %5$d %% · wind %6$s</string>
    <string name="per_source">PER SOURCE</string>
    <string name="attribution">Data: Landeswetterdienst Südtirol · GeoSphere Austria (CC BY 4.0) · MeteoSwiss, DWD, ARPAE, ECMWF via Open-Meteo</string>
```

Temporarily wire `MainActivity` to render Home so the task can be seen on the device (Task 14 replaces this):
```kotlin
setContent {
    ApexTheme {
        val vm: HomeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize()) {
            SkyBackground(state.palette, state.settings.animations)
            HomeScreen(onOpenBulletin = {})
        }
    }
}
```
(with the corresponding imports: `androidx.compose.foundation.layout.Box`, `fillMaxSize`, `Modifier`, `getValue`, `collectAsStateWithLifecycle`, `ApexTheme`, `SkyBackground`, `HomeScreen`, `HomeViewModel`).

- [ ] **Step 6: Compose UI test for Home**

Create `app/src/androidTest/kotlin/it/apexweather/ui/home/HomeScreenTest.kt`:
```kotlin
package it.apexweather.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.theme.ApexTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private fun fc(source: Source, offset: Double) = SourceForecast(
        source, t0, t0,
        hourly = (0 until 48).map { HourlyPoint(t0.plusSeconds(it * 3600L), 15.0 + offset + it % 8, precipMm = if (it % 5 == 0) 1.0 else 0.0, windKmh = 6.0, condition = Condition.PARTLY_CLOUDY) },
        daily = emptyList(),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0), Source.ICON_D2 to fc(Source.ICON_D2, 2.0)))
    private val state = HomeStateBuilder.build(snapshot, AppSettings(), ConsensusBlender().blend(snapshot.forecasts), t0.plusSeconds(60))

    @Test
    fun heroShowsConsensusTemperature() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("hero_temp").assertIsDisplayed().assertTextContains("16°")
        rule.onNodeWithTag("agreement_badge").assertIsDisplayed()
    }

    @Test
    fun hourlyStripScrollsAndOpensDetailSheet() {
        rule.setContent { ApexTheme { HomeContent(state, onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("hourly_strip").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("hour_column_0").performClick()
        rule.onNodeWithTag("hour_detail_sheet").assertIsDisplayed()
    }

    @Test
    fun offlineBannerAndEmptyState() {
        rule.setContent { ApexTheme { HomeContent(state.copy(offline = true, updatedAt = t0), onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("offline_banner").assertIsDisplayed()
    }

    @Test
    fun emptyStateShowsRetry() {
        rule.setContent { ApexTheme { HomeContent(HomeUiState(loading = false, isEmpty = true), onRefresh = {}, onOpenBulletin = {}) } }
        rule.onNodeWithTag("empty_state").assertIsDisplayed()
    }
}
```
- [ ] **Step 7: Build, run tests, look at it on the phone**

Run:
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -10
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest --console=plain 2>&1 | tail -10
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk && adb -s RZCXA1ZEXJE shell am start -n it.apexweather/.MainActivity
sleep 8 && adb -s RZCXA1ZEXJE exec-out screencap -p > /tmp/apex_home.png
```
Expected: builds succeed, 4 UI tests pass, the phone shows the animated sky, hero temperature, hourly strip and daily list with real data. Inspect `/tmp/apex_home.png` (Read tool) and fix obvious layout problems before committing.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat(ui): home screen with hero, hourly curve, daily list, bulletin teaser and hour detail"
```

---

### Task 12: Compare screen (multi-line chart, day table, source status)

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/common/SourceColors.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/compare/MultiLineChart.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/compare/CompareScreen.kt`
- Test: `app/src/test/kotlin/it/apexweather/ui/compare/CompareStateBuilderTest.kt`, `app/src/androidTest/kotlin/it/apexweather/ui/compare/CompareScreenTest.kt`

**Interfaces:**
- Produces: `SourceColors.of(source): Color`, `data class CompareUiState(...)`, `CompareStateBuilder.build(snapshot, settings, consensus, now): CompareUiState`, `CompareViewModel` (`state`, `toggleSource(Source)`, `setVariable(CompareVariable)`), `CompareScreen(viewModel)`, `CompareContent(state, onToggleSource, onVariable)`. Test tags: `compare_chart`, `chip_<SOURCE>`, `variable_<VARIABLE>`, `day_table`, `status_list`.

- [ ] **Step 1: Failing state builder test**

Create `app/src/test/kotlin/it/apexweather/ui/compare/CompareStateBuilderTest.kt`:
```kotlin
package it.apexweather.ui.compare

import it.apexweather.data.AppSettings
import it.apexweather.data.CompareVariable
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.point
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompareStateBuilderTest {
    private val forecasts = mapOf(
        Source.ICON_CH1 to forecast(Source.ICON_CH1, (0 until 72).map { point(it, 10.0, precip = 1.0, wind = 10.0) }),
        Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 72).map { point(it, 14.0, precip = 3.0, wind = 20.0) }),
        Source.ECMWF to forecast(Source.ECMWF, (0 until 168).map { point(it, 12.0) }),
    )
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = forecasts, status = forecasts.keys.associateWith { SourceStatus.Ok(hour(0)) })
    private val consensus = ConsensusBlender().blend(forecasts)

    @Test
    fun `series limited to selected sources and 72h window from now`() {
        val settings = AppSettings(compareSources = setOf(Source.ICON_CH1, Source.ECMWF))
        val s = CompareStateBuilder.build(snapshot, settings, consensus, hour(2).plusSeconds(1))
        assertEquals(setOf(Source.ICON_CH1, Source.ECMWF), s.series.keys)
        assertEquals(hour(2), s.series.getValue(Source.ICON_CH1).first().time)
        assertTrue(s.series.getValue(Source.ECMWF).size <= 72)
        assertEquals(hour(2), s.consensusLine.first().time)
    }

    @Test
    fun `variable picks the right value`() {
        val temp = CompareStateBuilder.build(snapshot, AppSettings(compareVariable = CompareVariable.TEMPERATURE), consensus, hour(0))
        val wind = CompareStateBuilder.build(snapshot, AppSettings(compareVariable = CompareVariable.WIND), consensus, hour(0))
        val precip = CompareStateBuilder.build(snapshot, AppSettings(compareVariable = CompareVariable.PRECIPITATION), consensus, hour(0))
        assertEquals(14.0, temp.series.getValue(Source.ICON_D2).first().value, 0.0)
        assertEquals(20.0, wind.series.getValue(Source.ICON_D2).first().value, 0.0)
        assertEquals(3.0, precip.series.getValue(Source.ICON_D2).first().value, 0.0)
    }

    @Test
    fun `day table has a row per consensus day and a cell per source`() {
        val s = CompareStateBuilder.build(snapshot, AppSettings(), consensus, hour(0))
        assertEquals(consensus.daily.size, s.dayRows.size)
        val row0 = s.dayRows.first()
        assertTrue(row0.cells.containsKey(Source.ICON_D2))
        assertEquals(14.0, row0.cells.getValue(Source.ICON_D2).maxC, 0.0)
        assertEquals(3, s.statuses.size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.compare.*' --console=plain 2>&1 | tail -8`
Expected: compilation failure, `Unresolved reference 'CompareStateBuilder'`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/it/apexweather/ui/common/SourceColors.kt`:
```kotlin
package it.apexweather.ui.common

import androidx.compose.ui.graphics.Color
import it.apexweather.domain.model.Source

object SourceColors {
    fun of(source: Source): Color = when (source) {
        Source.SIAG_KMOS -> Color(0xFFFF8A65)
        Source.GEOSPHERE_AROME -> Color(0xFFEF5DA8)
        Source.ICON_CH1 -> Color(0xFF7CE0A5)
        Source.ICON_CH2 -> Color(0xFF3FBF8F)
        Source.ICON_2I -> Color(0xFF4FC3F7)
        Source.ICON_D2 -> Color(0xFFFFD166)
        Source.ECMWF -> Color(0xFFB39DDB)
    }
    val consensus: Color = Color.White
}
```

Create `app/src/main/kotlin/it/apexweather/ui/compare/CompareViewModel.kt`:
```kotlin
package it.apexweather.ui.compare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.AppSettings
import it.apexweather.data.CompareVariable
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DailyAggregator
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.ConsensusForecast
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.domain.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

data class SeriesPoint(val time: Instant, val value: Double)
data class BandPoint(val time: Instant, val min: Double, val max: Double)
data class DayCell(val minC: Double, val maxC: Double, val precipMm: Double)
data class DayRow(val date: LocalDate, val consensus: DayCell, val cells: Map<Source, DayCell>)

data class CompareUiState(
    val loading: Boolean = true,
    val variable: CompareVariable = CompareVariable.TEMPERATURE,
    val selected: Set<Source> = Source.entries.toSet(),
    val series: Map<Source, List<SeriesPoint>> = emptyMap(),
    val consensusLine: List<SeriesPoint> = emptyList(),
    val band: List<BandPoint> = emptyList(),
    val dayRows: List<DayRow> = emptyList(),
    val statuses: Map<Source, SourceStatus> = emptyMap(),
    val settings: AppSettings = AppSettings(),
    val now: Instant = Instant.EPOCH,
)

object CompareStateBuilder {
    private const val WINDOW_HOURS = 72L

    fun build(snapshot: WeatherSnapshot, settings: AppSettings, consensus: ConsensusForecast, now: Instant): CompareUiState {
        val from = now.truncatedTo(ChronoUnit.HOURS)
        val to = from.plus(WINDOW_HOURS, ChronoUnit.HOURS)
        fun HourlyPoint.value() = when (settings.compareVariable) {
            CompareVariable.TEMPERATURE -> tempC
            CompareVariable.PRECIPITATION -> precipMm
            CompareVariable.WIND -> windKmh
        }
        val series = snapshot.forecasts
            .filterKeys { it in settings.compareSources }
            .mapValues { (_, fc) -> fc.hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) }.map { SeriesPoint(it.time, it.value()) } }
            .filterValues { it.isNotEmpty() }
        val window = consensus.hourly.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
        val consensusLine = window.map { h ->
            SeriesPoint(h.time, when (settings.compareVariable) {
                CompareVariable.TEMPERATURE -> h.tempC
                CompareVariable.PRECIPITATION -> h.precipMm
                CompareVariable.WIND -> h.windKmh
            })
        }
        val band = if (settings.compareVariable == CompareVariable.TEMPERATURE) window.map { BandPoint(it.time, it.tempMinC, it.tempMaxC) } else emptyList()

        val perSourceDaily = snapshot.forecasts.mapValues { (_, fc) ->
            (fc.daily.takeIf { it.isNotEmpty() } ?: DailyAggregator.aggregate(fc.hourly, DorfTirol.ZONE)).associateBy { it.date }
        }
        val dayRows = consensus.daily.map { d ->
            DayRow(
                date = d.date,
                consensus = DayCell(d.minC, d.maxC, d.precipMm),
                cells = perSourceDaily.mapNotNull { (src, byDate) -> byDate[d.date]?.let { src to DayCell(it.minC, it.maxC, it.precipMm) } }.toMap(),
            )
        }
        return CompareUiState(
            loading = false, variable = settings.compareVariable, selected = settings.compareSources,
            series = series, consensusLine = consensusLine, band = band, dayRows = dayRows,
            statuses = snapshot.status, settings = settings, now = now,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompareViewModel @Inject constructor(
    private val repository: WeatherRepository,
    private val settingsRepository: SettingsRepository,
    private val blender: ConsensusBlender,
    private val clock: Clock,
) : ViewModel() {
    val state: StateFlow<CompareUiState> = settingsRepository.settings.flatMapLatest { s ->
        repository.snapshot(s.bulletinLanguage(Locale.getDefault().toLanguageTag())).map { snap ->
            CompareStateBuilder.build(snap, s, blender.blend(snap.forecasts), clock.instant())
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CompareUiState())

    fun toggleSource(source: Source) = viewModelScope.launch {
        val current = settingsRepository.settings.first().compareSources
        settingsRepository.setCompareSources(if (source in current) current - source else current + source)
    }

    fun setVariable(v: CompareVariable) = viewModelScope.launch { settingsRepository.setCompareVariable(v) }
}
```

Create `app/src/main/kotlin/it/apexweather/ui/compare/MultiLineChart.kt`:
```kotlin
package it.apexweather.ui.compare

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Source
import it.apexweather.ui.common.SourceColors
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor

/** Lines per source, thick white consensus line, optional shaded band. X axis = time over the 72 h window. */
@Composable
fun MultiLineChart(
    series: Map<Source, List<SeriesPoint>>,
    consensus: List<SeriesPoint>,
    band: List<BandPoint>,
    from: Instant,
    hours: Long,
    unitLabel: String,
    modifier: Modifier = Modifier,
) {
    val labelPaint = android.graphics.Paint().apply { color = android.graphics.Color.argb(160, 255, 255, 255); textSize = 28f; isAntiAlias = true }
    Canvas(modifier.testTag("compare_chart")) {
        val all = series.values.flatten().map { it.value } + consensus.map { it.value } + band.flatMap { listOf(it.min, it.max) }
        if (all.isEmpty()) return@Canvas
        val lo = floor(all.min() - 1)
        val hi = ceil(all.max() + 1).coerceAtLeast(lo + 2)
        val left = 64f; val right = size.width - 12f; val top = 12f; val bottom = size.height - 36f
        val spanMs = hours * 3_600_000.0
        fun x(t: Instant) = left + ((t.toEpochMilli() - from.toEpochMilli()) / spanMs * (right - left)).toFloat()
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo) * (bottom - top)).toFloat()

        // grid + y labels
        val steps = 4
        for (s in 0..steps) {
            val v = lo + (hi - lo) * s / steps
            drawLine(Color.White.copy(alpha = 0.10f), Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("${v.toInt()}$unitLabel", 4f, y(v) + 10f, labelPaint)
        }
        // x labels every 12 h
        var t = from
        while (!t.isAfter(from.plusSeconds(hours * 3600))) {
            val z = t.atZone(DorfTirol.ZONE)
            val label = if (z.hour == 0) z.dayOfWeek.name.take(2) else "${z.hour}h"
            drawContext.canvas.nativeCanvas.drawText(label, x(t) - 12f, size.height - 8f, labelPaint)
            drawLine(Color.White.copy(alpha = 0.06f), Offset(x(t), top), Offset(x(t), bottom), strokeWidth = 1f)
            t = t.plusSeconds(12 * 3600)
        }

        if (band.size >= 2) {
            val p = Path().apply {
                moveTo(x(band[0].time), y(band[0].max))
                band.forEach { lineTo(x(it.time), y(it.max)) }
                band.reversed().forEach { lineTo(x(it.time), y(it.min)) }
                close()
            }
            drawPath(p, Color.White.copy(alpha = 0.10f))
        }
        series.forEach { (source, pts) ->
            if (pts.size < 2) return@forEach
            val p = Path().apply { moveTo(x(pts[0].time), y(pts[0].value)); pts.drop(1).forEach { lineTo(x(it.time), y(it.value)) } }
            drawPath(p, SourceColors.of(source), style = Stroke(width = 2.5f))
        }
        if (consensus.size >= 2) {
            val p = Path().apply { moveTo(x(consensus[0].time), y(consensus[0].value)); consensus.drop(1).forEach { lineTo(x(it.time), y(it.value)) } }
            drawPath(p, SourceColors.consensus, style = Stroke(width = 5f))
        }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/ui/compare/CompareScreen.kt`:
```kotlin
package it.apexweather.ui.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.R
import it.apexweather.data.CompareVariable
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceStatus
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard
import it.apexweather.ui.common.SourceColors
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Composable
fun CompareScreen(viewModel: CompareViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompareContent(state, viewModel::toggleSource, viewModel::setVariable)
}

@Composable
fun CompareContent(state: CompareUiState, onToggleSource: (Source) -> Unit, onVariable: (CompareVariable) -> Unit) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topInset + 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp))
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                CompareVariable.entries.forEachIndexed { i, v ->
                    SegmentedButton(
                        selected = state.variable == v, onClick = { onVariable(v) },
                        shape = SegmentedButtonDefaults.itemShape(i, CompareVariable.entries.size),
                        modifier = Modifier.testTag("variable_${v.name}"),
                    ) { Text(variableLabel(v)) }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                MultiLineChart(
                    series = state.series, consensus = state.consensusLine, band = state.band,
                    from = state.now.truncatedTo(ChronoUnit.HOURS), hours = 72,
                    unitLabel = when (state.variable) { CompareVariable.TEMPERATURE -> "°"; CompareVariable.PRECIPITATION -> "mm"; CompareVariable.WIND -> "" },
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Source.entries.forEach { s ->
                        val on = s in state.selected
                        FilterChip(
                            selected = on, onClick = { onToggleSource(s) },
                            label = { Text(s.displayName) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(SourceColors.of(s))) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SourceColors.of(s).copy(alpha = 0.25f), labelColor = Color.White, selectedLabelColor = Color.White),
                            modifier = Modifier.testTag("chip_${s.name}"),
                        )
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("day_table")) {
                Text(stringResource(R.string.compare_table), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                val scroll = rememberScrollState()
                Column(Modifier.horizontalScroll(scroll)) {
                    Row {
                        Text("", Modifier.width(52.dp))
                        Text(stringResource(R.string.consensus), Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                        state.selected.sortedBy { it.ordinal }.forEach { s -> Text(s.displayName.substringAfter(' ').take(9), Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, color = SourceColors.of(s)) }
                    }
                    state.dayRows.forEach { row ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Format.weekday(row.date, locale), Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            DayCellText(row.consensus, deviation = 0.0, bold = true)
                            state.selected.sortedBy { it.ordinal }.forEach { s ->
                                val c = row.cells[s]
                                if (c == null) Text("–", Modifier.width(84.dp), color = Color.White.copy(alpha = 0.4f))
                                else DayCellText(c, deviation = abs(c.maxC - row.consensus.maxC), bold = false)
                            }
                        }
                    }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("status_list")) {
                Text(stringResource(R.string.compare_status), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                Source.entries.forEach { s ->
                    val st = state.statuses[s]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(SourceColors.of(s)))
                            Text(s.displayName, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                        Text(statusText(st), style = MaterialTheme.typography.labelSmall, color = statusColor(st))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCellText(c: DayCell, deviation: Double, bold: Boolean) {
    val tint = when { deviation < 1.0 -> Color.Transparent; deviation < 3.0 -> Color(0x33FFD166); else -> Color(0x40FF8A80) }
    Column(Modifier.width(84.dp).background(tint).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text("${Format.temp(c.minC)} / ${Format.temp(c.maxC)}", style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
        Text(Format.mm(c.precipMm), style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5))
    }
}

@Composable
private fun variableLabel(v: CompareVariable) = stringResource(
    when (v) { CompareVariable.TEMPERATURE -> R.string.var_temperature; CompareVariable.PRECIPITATION -> R.string.var_precipitation; CompareVariable.WIND -> R.string.var_wind }
)

@Composable
private fun statusText(st: SourceStatus?): String = when (st) {
    null -> stringResource(R.string.status_none)
    is SourceStatus.Ok -> stringResource(R.string.status_ok, Format.time(st.issuedAt, DorfTirol.ZONE))
    is SourceStatus.Stale -> stringResource(R.string.status_stale, Format.time(st.issuedAt, DorfTirol.ZONE))
    is SourceStatus.Failed -> stringResource(R.string.status_failed, st.reason.take(40))
}

private fun statusColor(st: SourceStatus?): Color = when (st) {
    is SourceStatus.Ok -> Color(0xFF7CE0A5)
    is SourceStatus.Stale -> Color(0xFFFFD166)
    else -> Color(0xFFFF8A80)
}
```
Add strings (de / it / en):
```xml
    <!-- de -->
    <string name="compare_title">Quellen im Vergleich</string>
    <string name="compare_table">TAGESWERTE JE QUELLE (MIN / MAX, NIEDERSCHLAG)</string>
    <string name="compare_status">QUELLENSTATUS</string>
    <string name="consensus">Konsens</string>
    <string name="var_temperature">Temperatur</string>
    <string name="var_precipitation">Niederschlag</string>
    <string name="var_wind">Wind</string>
    <string name="status_none">nie geladen</string>
    <string name="status_ok">Lauf %1$s</string>
    <string name="status_stale">veraltet (%1$s)</string>
    <string name="status_failed">Fehler: %1$s</string>
```
```xml
    <!-- it -->
    <string name="compare_title">Confronto fonti</string>
    <string name="compare_table">VALORI GIORNALIERI PER FONTE (MIN / MAX, PRECIPITAZIONI)</string>
    <string name="compare_status">STATO FONTI</string>
    <string name="consensus">Consenso</string>
    <string name="var_temperature">Temperatura</string>
    <string name="var_precipitation">Precipitazioni</string>
    <string name="var_wind">Vento</string>
    <string name="status_none">mai caricato</string>
    <string name="status_ok">run %1$s</string>
    <string name="status_stale">obsoleto (%1$s)</string>
    <string name="status_failed">errore: %1$s</string>
```
```xml
    <!-- en -->
    <string name="compare_title">Source comparison</string>
    <string name="compare_table">DAILY VALUES PER SOURCE (MIN / MAX, PRECIPITATION)</string>
    <string name="compare_status">SOURCE STATUS</string>
    <string name="consensus">Consensus</string>
    <string name="var_temperature">Temperature</string>
    <string name="var_precipitation">Precipitation</string>
    <string name="var_wind">Wind</string>
    <string name="status_none">never loaded</string>
    <string name="status_ok">run %1$s</string>
    <string name="status_stale">stale (%1$s)</string>
    <string name="status_failed">error: %1$s</string>
```

- [ ] **Step 4: Compose UI test**

Create `app/src/androidTest/kotlin/it/apexweather/ui/compare/CompareScreenTest.kt`:
```kotlin
package it.apexweather.ui.compare

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import it.apexweather.data.AppSettings
import it.apexweather.data.CompareVariable
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.HourlyPoint
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.SourceForecast
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.theme.ApexTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CompareScreenTest {
    @get:Rule val rule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-08T10:00:00Z")
    private fun fc(s: Source, off: Double) = SourceForecast(s, t0, t0, (0 until 72).map { HourlyPoint(t0.plusSeconds(it * 3600L), 10.0 + off, condition = Condition.CLEAR) }, emptyList())
    private val snapshot = WeatherSnapshot.EMPTY.copy(forecasts = mapOf(Source.ICON_CH1 to fc(Source.ICON_CH1, 0.0), Source.ICON_D2 to fc(Source.ICON_D2, 3.0)))
    private val state = CompareStateBuilder.build(snapshot, AppSettings(), ConsensusBlender().blend(snapshot.forecasts), t0)

    @Test
    fun chartTableAndStatusRender() {
        rule.setContent { ApexTheme { CompareContent(state, {}, {}) } }
        rule.onNodeWithTag("compare_chart").assertIsDisplayed()
        rule.onNodeWithTag("chip_ICON_D2").assertIsDisplayed()
    }

    @Test
    fun chipAndVariableCallbacksFire() {
        var toggled: Source? = null
        var variable: CompareVariable? = null
        rule.setContent { ApexTheme { CompareContent(state, { toggled = it }, { variable = it }) } }
        rule.onNodeWithTag("chip_ICON_D2").performClick()
        rule.onNodeWithTag("variable_WIND").performClick()
        assertEquals(Source.ICON_D2, toggled)
        assertEquals(CompareVariable.WIND, variable)
    }
}
```

- [ ] **Step 5: Run tests**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests 'it.apexweather.ui.compare.*' :app:assembleDebug --console=plain 2>&1 | tail -8
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.compare.CompareScreenTest --console=plain 2>&1 | tail -8
```
Expected: `BUILD SUCCESSFUL` for both.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(ui): compare screen with per-source chart, day table and source status"
```

---

### Task 13: Bulletin screen

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/bulletin/BulletinViewModel.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/bulletin/BulletinScreen.kt`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/bulletin/BulletinScreenTest.kt`

**Interfaces:**
- Produces: `data class BulletinUiState(loading, bulletin: Bulletin?, status: SourceStatus?)`, `BulletinViewModel` (`state`), `BulletinScreen(viewModel)`, `BulletinContent(state)`. Test tags: `bulletin_text`, `bulletin_day_<index>`, `bulletin_empty`.

- [ ] **Step 1: Implement ViewModel and screen**

Create `app/src/main/kotlin/it/apexweather/ui/bulletin/BulletinViewModel.kt`:
```kotlin
package it.apexweather.ui.bulletin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.SourceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

data class BulletinUiState(val loading: Boolean = true, val bulletin: Bulletin? = null, val status: SourceStatus? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BulletinViewModel @Inject constructor(repository: WeatherRepository, settingsRepository: SettingsRepository) : ViewModel() {
    val state: StateFlow<BulletinUiState> = settingsRepository.settings.flatMapLatest { s ->
        repository.snapshot(s.bulletinLanguage(Locale.getDefault().toLanguageTag())).map { BulletinUiState(false, it.bulletin, it.bulletinStatus) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BulletinUiState())
}
```

Create `app/src/main/kotlin/it/apexweather/ui/bulletin/BulletinScreen.kt`:
```kotlin
package it.apexweather.ui.bulletin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import it.apexweather.R
import it.apexweather.domain.DorfTirol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.GlassCard

@Composable
fun BulletinScreen(viewModel: BulletinViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BulletinContent(state)
}

@Composable
fun BulletinContent(state: BulletinUiState) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val locale = LocalConfiguration.current.locales[0]
    val b = state.bulletin
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topInset + 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(stringResource(R.string.bulletin_title), style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp)) }
        if (b == null) {
            item { Text(stringResource(R.string.bulletin_empty), color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(24.dp).testTag("bulletin_empty")) }
            return@LazyColumn
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(b.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.bulletin_issued, Format.time(b.issuedAt, DorfTirol.ZONE)), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                Spacer(Modifier.height(10.dp))
                Text(b.evolution, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f), modifier = Modifier.testTag("bulletin_text"))
            }
        }
        if (b.days.isNotEmpty()) item {
            Text(stringResource(R.string.bulletin_district), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(b.days) { i, d ->
                    GlassCard(Modifier.width(150.dp).testTag("bulletin_day_$i")) {
                        Text(Format.weekday(d.date, locale) + " " + Format.dayMonth(d.date, locale), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            d.iconUrl?.let { AsyncImage(model = it, contentDescription = d.description, modifier = Modifier.size(44.dp)) }
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(listOfNotNull(d.minC?.let(Format::temp), d.maxC?.let(Format::temp)).joinToString(" / "), style = MaterialTheme.typography.titleMedium, color = Color.White)
                                if (d.rainToMm != null && d.rainToMm > 0) Text("${d.rainFromMm?.toInt() ?: 0}–${d.rainToMm.toInt()} mm", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9D2F5))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(d.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 3)
                    }
                }
            }
        }
        itemsIndexed(b.conditions) { _, c ->
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(Format.weekday(c.date, locale) + " · " + c.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(c.description, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                c.temperatures?.let { Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.75f)) }
                c.mapImageUrl?.let {
                    Spacer(Modifier.height(10.dp))
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)))
                }
            }
        }
    }
}
```

Add strings (de / it / en):
```xml
    <!-- de -->
    <string name="bulletin_title">Wetterbericht Südtirol</string>
    <string name="bulletin_empty">Kein Wetterbericht geladen.</string>
    <string name="bulletin_issued">Ausgegeben %1$s · Landeswetterdienst</string>
    <string name="bulletin_district">BURGGRAFENAMT – MERAN UND UMGEBUNG</string>
```
```xml
    <!-- it -->
    <string name="bulletin_title">Bollettino Alto Adige</string>
    <string name="bulletin_empty">Nessun bollettino caricato.</string>
    <string name="bulletin_issued">Emesso alle %1$s · Servizio Meteo provinciale</string>
    <string name="bulletin_district">BURGRAVIATO – MERANO E DINTORNI</string>
```
```xml
    <!-- en -->
    <string name="bulletin_title">South Tyrol bulletin</string>
    <string name="bulletin_empty">No bulletin loaded.</string>
    <string name="bulletin_issued">Issued %1$s · South Tyrol weather service</string>
    <string name="bulletin_district">BURGGRAFENAMT – MERAN AND SURROUNDINGS</string>
```

- [ ] **Step 2: UI test**

Create `app/src/androidTest/kotlin/it/apexweather/ui/bulletin/BulletinScreenTest.kt`:
```kotlin
package it.apexweather.ui.bulletin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import it.apexweather.domain.model.Bulletin
import it.apexweather.domain.model.BulletinDay
import it.apexweather.ui.theme.ApexTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class BulletinScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun rendersTextAndDays() {
        val b = Bulletin("de", Instant.now(), "Sonnig", "Viel Sonne.\nMorgen Regen.", emptyList(),
            listOf(BulletinDay(LocalDate.now(), "b", "Heiter", null, 12.0, 25.0, 0.0, 2.0, 0)))
        rule.setContent { ApexTheme { BulletinContent(BulletinUiState(false, b, null)) } }
        rule.onNodeWithTag("bulletin_text").assertIsDisplayed()
        rule.onNodeWithTag("bulletin_day_0").assertIsDisplayed()
    }

    @Test
    fun emptyState() {
        rule.setContent { ApexTheme { BulletinContent(BulletinUiState(false, null, null)) } }
        rule.onNodeWithTag("bulletin_empty").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: Build and run**

Run:
```bash
./gradlew :app:assembleDebug --console=plain 2>&1 | tail -6
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.bulletin.BulletinScreenTest --console=plain 2>&1 | tail -6
```
Expected: `BUILD SUCCESSFUL` for both.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(ui): bulletin screen with district days and map images"
```

---

### Task 14: Navigation, settings sheet, language switching, final MainActivity

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/sky/SkyViewModel.kt`
- Create: `app/src/main/kotlin/it/apexweather/ui/settings/SettingsSheet.kt`
- Modify: `app/src/main/kotlin/it/apexweather/MainActivity.kt`
- Modify: the three `strings.xml`
- Test: `app/src/androidTest/kotlin/it/apexweather/ui/navigation/NavigationTest.kt`

**Interfaces:**
- Produces: routes `HomeRoute`, `CompareRoute`, `BulletinRoute` (`@Serializable object`), `ApexApp()` root composable, `SkyViewModel` (`state: StateFlow<SkyUiState(palette, animations)>`), `SettingsSheet(settings, onLanguage, onWindUnit, onAnimations, onRefresh, onDismiss)`. Test tags: `nav_home`, `nav_compare`, `nav_bulletin`, `settings_button`, `settings_sheet`.

- [ ] **Step 1: Sky ViewModel, navigation, settings**

Create `app/src/main/kotlin/it/apexweather/ui/sky/SkyViewModel.kt`:
```kotlin
package it.apexweather.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.SkyPalette
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.util.Locale
import javax.inject.Inject

data class SkyUiState(val palette: SkyPalette = HomeUiState().palette, val animations: Boolean = true)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SkyViewModel @Inject constructor(
    repository: WeatherRepository,
    settingsRepository: SettingsRepository,
    blender: ConsensusBlender,
    clock: Clock,
) : ViewModel() {
    private val tick = flow { while (true) { emit(Unit); delay(60_000) } }
    val state: StateFlow<SkyUiState> = combine(
        settingsRepository.settings.flatMapLatest { s ->
            repository.snapshot(s.bulletinLanguage(Locale.getDefault().toLanguageTag())).map { s to it }
        },
        tick,
    ) { (settings, snapshot), _ ->
        val home = HomeStateBuilder.build(snapshot, settings, blender.blend(snapshot.forecasts), clock.instant())
        SkyUiState(home.palette, settings.animations)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkyUiState())
}
```

Create `app/src/main/kotlin/it/apexweather/ui/settings/SettingsSheet.kt`:
```kotlin
package it.apexweather.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.apexweather.BuildConfig
import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.data.LanguageSetting
import it.apexweather.data.WindUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onLanguage: (LanguageSetting) -> Unit,
    onWindUnit: (WindUnit) -> Unit,
    onAnimations: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("settings_sheet")) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)

            Text(stringResource(R.string.setting_language), style = MaterialTheme.typography.labelSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LanguageSetting.entries.forEachIndexed { i, l ->
                    SegmentedButton(selected = settings.language == l, onClick = { onLanguage(l) }, shape = SegmentedButtonDefaults.itemShape(i, LanguageSetting.entries.size)) {
                        Text(when (l) { LanguageSetting.SYSTEM -> stringResource(R.string.lang_system); LanguageSetting.DE -> "DE"; LanguageSetting.IT -> "IT"; LanguageSetting.EN -> "EN" })
                    }
                }
            }

            Text(stringResource(R.string.setting_wind), style = MaterialTheme.typography.labelSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WindUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(selected = settings.windUnit == u, onClick = { onWindUnit(u) }, shape = SegmentedButtonDefaults.itemShape(i, WindUnit.entries.size)) {
                        Text(if (u == WindUnit.KMH) "km/h" else "m/s")
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.setting_animations), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = settings.animations, onCheckedChange = onAnimations)
            }

            Button(onClick = { onRefresh(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh_now)) }

            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.about, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.attribution), style = MaterialTheme.typography.labelSmall)
        }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/ui/navigation/AppNavigation.kt`:
```kotlin
package it.apexweather.ui.navigation

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StackedLineChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.apexweather.R
import it.apexweather.data.LanguageSetting
import it.apexweather.ui.bulletin.BulletinScreen
import it.apexweather.ui.compare.CompareScreen
import it.apexweather.ui.home.HomeScreen
import it.apexweather.ui.home.HomeViewModel
import it.apexweather.ui.settings.SettingsSheet
import it.apexweather.ui.settings.SettingsViewModel
import it.apexweather.ui.sky.SkyBackground
import it.apexweather.ui.sky.SkyViewModel
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object CompareRoute
@Serializable object BulletinRoute

private data class NavItem(val route: Any, val tag: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun ApexApp() {
    val nav = rememberNavController()
    val skyVm: SkyViewModel = hiltViewModel()
    val sky by skyVm.state.collectAsStateWithLifecycle()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val homeVm: HomeViewModel = hiltViewModel()
    var settingsOpen by remember { mutableStateOf(false) }
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination

    Box(Modifier.fillMaxSize()) {
        SkyBackground(sky.palette, sky.animations)
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0x59000000), tonalElevation = 0.dp) {
                    val items = listOf(
                        NavItem(HomeRoute, "home", R.string.nav_home, Icons.Rounded.Home),
                        NavItem(CompareRoute, "compare", R.string.nav_compare, Icons.Rounded.StackedLineChart),
                        NavItem(BulletinRoute, "bulletin", R.string.nav_bulletin, Icons.Rounded.Article),
                    )
                    items.forEach { item ->
                        val selected = dest?.hasRoute(item.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.navigate(item.route) { popUpTo(HomeRoute) { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(item.icon, null) },
                            label = { Text(stringResource(item.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Color(0x33FFFFFF), unselectedIconColor = Color(0xAAFFFFFF), unselectedTextColor = Color(0xAAFFFFFF)),
                            modifier = Modifier.testTag("nav_${item.tag}"),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                composable<HomeRoute> { HomeScreen(onOpenBulletin = { nav.navigate(BulletinRoute) { launchSingleTop = true } }, viewModel = homeVm) }
                composable<CompareRoute> { CompareScreen() }
                composable<BulletinRoute> { BulletinScreen() }
            }
        }
        IconButton(onClick = { settingsOpen = true }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).testTag("settings_button")) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White.copy(alpha = 0.85f))
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = settings,
            onLanguage = { l ->
                settingsVm.setLanguage(l)
                AppCompatDelegate.setApplicationLocales(l.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList())
            },
            onWindUnit = settingsVm::setWindUnit,
            onAnimations = settingsVm::setAnimations,
            onRefresh = homeVm::refresh,
            onDismiss = { settingsOpen = false },
        )
    }
}
```
Create `app/src/main/kotlin/it/apexweather/ui/settings/SettingsViewModel.kt`:
```kotlin
package it.apexweather.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.apexweather.data.AppSettings
import it.apexweather.data.LanguageSetting
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WindUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    fun setLanguage(l: LanguageSetting) = viewModelScope.launch { repo.setLanguage(l) }
    fun setWindUnit(u: WindUnit) = viewModelScope.launch { repo.setWindUnit(u) }
    fun setAnimations(b: Boolean) = viewModelScope.launch { repo.setAnimations(b) }
}
```

Replace `MainActivity.kt` entirely:
```kotlin
package it.apexweather

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import it.apexweather.ui.navigation.ApexApp
import it.apexweather.ui.theme.ApexTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ApexTheme { ApexApp() } }
    }
}
```

Add the remaining strings (de / it / en):
```xml
    <!-- de -->
    <string name="nav_home">Heute</string>
    <string name="nav_compare">Vergleich</string>
    <string name="nav_bulletin">Bericht</string>
    <string name="settings">Einstellungen</string>
    <string name="setting_language">SPRACHE</string>
    <string name="lang_system">System</string>
    <string name="setting_wind">WINDEINHEIT</string>
    <string name="setting_animations">Himmel animieren</string>
    <string name="refresh_now">Jetzt aktualisieren</string>
    <string name="about">Apex Weather %1$s · Dorf Tirol / Meran · Konsens aus 7 Wettermodellen</string>
```
```xml
    <!-- it -->
    <string name="nav_home">Oggi</string>
    <string name="nav_compare">Confronto</string>
    <string name="nav_bulletin">Bollettino</string>
    <string name="settings">Impostazioni</string>
    <string name="setting_language">LINGUA</string>
    <string name="lang_system">Sistema</string>
    <string name="setting_wind">UNITÀ VENTO</string>
    <string name="setting_animations">Anima il cielo</string>
    <string name="refresh_now">Aggiorna ora</string>
    <string name="about">Apex Weather %1$s · Tirolo / Merano · consenso di 7 modelli meteo</string>
```
```xml
    <!-- en -->
    <string name="nav_home">Today</string>
    <string name="nav_compare">Compare</string>
    <string name="nav_bulletin">Bulletin</string>
    <string name="settings">Settings</string>
    <string name="setting_language">LANGUAGE</string>
    <string name="lang_system">System</string>
    <string name="setting_wind">WIND UNIT</string>
    <string name="setting_animations">Animate the sky</string>
    <string name="refresh_now">Refresh now</string>
    <string name="about">Apex Weather %1$s · Dorf Tirol / Meran · consensus of 7 weather models</string>
```

- [ ] **Step 2: Navigation UI test (Hilt instrumented)**

Create `app/src/androidTest/kotlin/it/apexweather/HiltTestRunner.kt`:
```kotlin
package it.apexweather

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```
In `app/build.gradle.kts` set `testInstrumentationRunner = "it.apexweather.HiltTestRunner"` and add:
```kotlin
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
```

Create `app/src/androidTest/kotlin/it/apexweather/ui/navigation/NavigationTest.kt`:
```kotlin
package it.apexweather.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import it.apexweather.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class NavigationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() {
        hilt.inject()
        rule.mainClock.autoAdvance = false // the sky's frame loop never idles
    }

    private fun settle() = rule.mainClock.advanceTimeBy(1_000)

    @Test
    fun bottomBarSwitchesScreensAndSettingsOpens() {
        settle()
        rule.onNodeWithTag("nav_compare").performClick(); settle()
        rule.onNodeWithTag("compare_chart").assertIsDisplayed()
        rule.onNodeWithTag("nav_bulletin").performClick(); settle()
        rule.onNodeWithTag("nav_home").performClick(); settle()
        rule.onNodeWithTag("settings_button").performClick(); settle()
        rule.onNodeWithTag("settings_sheet").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: Build, run all device tests, install, screenshots of all three tabs**

Run:
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain 2>&1 | tail -8
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest --console=plain 2>&1 | tail -12
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk && adb -s RZCXA1ZEXJE shell am start -n it.apexweather/.MainActivity
sleep 8 && adb -s RZCXA1ZEXJE exec-out screencap -p > /tmp/apex_home.png
adb -s RZCXA1ZEXJE shell input tap 540 2250 && sleep 2 && adb -s RZCXA1ZEXJE exec-out screencap -p > /tmp/apex_compare.png
```
Expected: builds and all instrumented tests pass. Look at both screenshots with the Read tool; the bottom bar should be visible over the sky and the settings gear at the top right. If the compare tab tap coordinate misses on the A34 (1080×2340), tap at y≈2280.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(ui): navigation shell, shared sky, settings sheet and language switching"
```

---

### Task 15: Background refresh with WorkManager

**Files:**
- Create: `app/src/main/kotlin/it/apexweather/work/RefreshWorker.kt`
- Create: `app/src/main/kotlin/it/apexweather/work/RefreshScheduler.kt`
- Modify: `app/src/main/kotlin/it/apexweather/ApexApplication.kt`
- Test: `app/src/test/kotlin/it/apexweather/work/RefreshSchedulerTest.kt`

**Interfaces:**
- Produces: `RefreshWorker` (`@HiltWorker`, `CoroutineWorker`), `RefreshScheduler.ensureScheduled(context)`, `RefreshScheduler.refreshNow(context)`, constants `RefreshScheduler.PERIODIC_NAME = "apex_refresh"`, `RefreshScheduler.ONESHOT_NAME = "apex_refresh_now"`.

- [ ] **Step 1: Failing scheduler test**

Create `app/src/test/kotlin/it/apexweather/work/RefreshSchedulerTest.kt`:
```kotlin
package it.apexweather.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `ensureScheduled enqueues exactly one periodic job and is idempotent`() {
        RefreshScheduler.ensureScheduled(context)
        RefreshScheduler.ensureScheduled(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RefreshScheduler.PERIODIC_NAME).get()
        assertEquals(1, infos.size)
        assertTrue(infos[0].state == WorkInfo.State.ENQUEUED || infos[0].state == WorkInfo.State.RUNNING)
    }

    @Test
    fun `refreshNow enqueues a one-shot job`() {
        RefreshScheduler.refreshNow(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RefreshScheduler.ONESHOT_NAME).get()
        assertEquals(1, infos.size)
    }
}
```
Add to `gradle/libs.versions.toml` under `[libraries]`: `work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }`, and to `app/build.gradle.kts`: `testImplementation(libs.work.testing)`.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.work.*' --console=plain 2>&1 | tail -8`
Expected: compilation failure, `Unresolved reference 'RefreshScheduler'`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/it/apexweather/work/RefreshWorker.kt`:
```kotlin
package it.apexweather.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.widget.ApexWidget
import kotlinx.coroutines.flow.first
import java.util.Locale

@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: WeatherRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val language = settings.settings.first().bulletinLanguage(Locale.getDefault().toLanguageTag())
        val result = runCatching { repository.refresh(language) }.getOrNull()
        runCatching { ApexWidget().updateAll(applicationContext) }
        return when {
            result == null -> Result.retry()
            result.allFailed -> if (runAttemptCount < 3) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/work/RefreshScheduler.kt`:
```kotlin
package it.apexweather.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    const val PERIODIC_NAME = "apex_refresh"
    const val ONESHOT_NAME = "apex_refresh_now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(60, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
```

Schedule from the application (not the activity, so Hilt instrumented tests with `HiltTestApplication` never touch WorkManager). Replace `ApexApplication.kt`:
```kotlin
package it.apexweather

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import it.apexweather.work.RefreshScheduler
import javax.inject.Inject

@HiltAndroidApp
class ApexApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        RefreshScheduler.ensureScheduled(this)
    }
}
```

- [ ] **Step 4: Run tests and verify the periodic job on the phone**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests 'it.apexweather.work.*' :app:assembleDebug --console=plain 2>&1 | tail -8
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk && adb -s RZCXA1ZEXJE shell am start -n it.apexweather/.MainActivity && sleep 5
adb -s RZCXA1ZEXJE shell dumpsys jobscheduler | grep -A2 "it.apexweather" | head -20
```
Expected: 2 unit tests pass; `dumpsys jobscheduler` lists a job for `it.apexweather` (WorkManager schedules through JobScheduler).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(work): hourly background refresh with WorkManager and Hilt worker"
```

---

### Task 16: Glance home-screen widget

**Files:**
- Replace: `app/src/main/kotlin/it/apexweather/widget/ApexWidget.kt`
- Create: `app/src/main/kotlin/it/apexweather/widget/ApexWidgetReceiver.kt`
- Create: `app/src/main/kotlin/it/apexweather/widget/WidgetState.kt`
- Create: `app/src/main/res/xml/apex_widget_info.xml`
- Create: `app/src/main/res/drawable/ic_wx_sun.xml`, `ic_wx_moon.xml`, `ic_wx_cloud.xml`, `ic_wx_rain.xml`, `ic_wx_snow.xml`, `ic_wx_storm.xml`, `ic_wx_fog.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `strings.xml` ×3
- Test: `app/src/test/kotlin/it/apexweather/widget/WidgetStateTest.kt`

**Interfaces:**
- Produces: `data class WidgetState(tempText, conditionRes: Int, iconRes: Int, hours: List<WidgetHour(label, tempText, iconRes)>, updatedText, topColor: Long, bottomColor: Long, hasData)`, `WidgetStateBuilder.build(home: HomeUiState, zone): WidgetState`, `Condition.widgetIcon(phase): Int` (drawable id), `ApexWidget`, `ApexWidgetReceiver`.

- [ ] **Step 1: Failing state test**

Create `app/src/test/kotlin/it/apexweather/widget/WidgetStateTest.kt`:
```kotlin
package it.apexweather.widget

import it.apexweather.R
import it.apexweather.data.AppSettings
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DorfTirol
import it.apexweather.domain.forecast
import it.apexweather.domain.hour
import it.apexweather.domain.point
import it.apexweather.domain.model.Condition
import it.apexweather.domain.model.Source
import it.apexweather.domain.model.WeatherSnapshot
import it.apexweather.ui.home.HomeStateBuilder
import it.apexweather.ui.home.HomeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateTest {
    @Test
    fun `builds temperature, six upcoming hours and icons`() {
        val f = mapOf(Source.ICON_D2 to forecast(Source.ICON_D2, (0 until 24).map { point(it, 20.0 + it, condition = if (it < 3) Condition.RAIN else Condition.CLEAR) }))
        val home = HomeStateBuilder.build(WeatherSnapshot.EMPTY.copy(forecasts = f), AppSettings(), ConsensusBlender().blend(f), hour(0).plusSeconds(30))
        val w = WidgetStateBuilder.build(home, DorfTirol.ZONE)
        assertTrue(w.hasData)
        assertEquals("20°", w.tempText)
        assertEquals(R.drawable.ic_wx_rain, w.iconRes)
        assertEquals(6, w.hours.size)
        assertEquals("03", w.hours[0].label) // hour(1) = 03:00 local
        assertEquals(R.drawable.ic_wx_moon, w.hours[2].iconRes) // hour(3) = 05:00 local, clear → night icon
        assertEquals(R.drawable.ic_wx_sun, w.hours[5].iconRes) // hour(6) = 08:00 local, clear → day icon
    }

    @Test
    fun `empty home gives no-data widget`() {
        val w = WidgetStateBuilder.build(HomeUiState(loading = false, isEmpty = true), DorfTirol.ZONE)
        assertFalse(w.hasData)
        assertEquals("–", w.tempText)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests 'it.apexweather.widget.*' --console=plain 2>&1 | tail -8`
Expected: compilation failure, `Unresolved reference 'WidgetStateBuilder'` / `ic_wx_rain`.

- [ ] **Step 3: Vector drawables**

Create the seven icons under `app/src/main/res/drawable/` (24×24 viewport, white fill, tinted at runtime):

`ic_wx_sun.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,7a5,5 0 1,0 0,10a5,5 0 1,0 0,-10z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M11,1h2v3h-2zM11,20h2v3h-2zM1,11h3v2H1zM20,11h3v2h-3zM3.5,4.9l1.4,-1.4l2.1,2.1l-1.4,1.4zM17,18.4l1.4,-1.4l2.1,2.1l-1.4,1.4zM3.5,19.1l2.1,-2.1l1.4,1.4l-2.1,2.1zM17,5.6l2.1,-2.1l1.4,1.4l-2.1,2.1z" />
</vector>
```
`ic_wx_moon.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M14,2a9,9 0 1,0 8,13.2A8,8 0 0,1 14,2z" />
</vector>
```
`ic_wx_cloud.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M19,18H6a4,4 0 0,1 -0.5,-7.97A6,6 0 0,1 17,8h1a5,5 0 0,1 1,9.9z" />
</vector>
```
`ic_wx_rain.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M19,15H6a4,4 0 0,1 -0.5,-7.97A6,6 0 0,1 17,5h1a5,5 0 0,1 1,9.9z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M8,17l-1.5,3.5h2zM12,17l-1.5,3.5h2zM16,17l-1.5,3.5h2z" />
</vector>
```
`ic_wx_snow.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M19,14H6a4,4 0 0,1 -0.5,-7.97A6,6 0 0,1 17,4h1a5,5 0 0,1 1,9.9z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M7,17.5a1.2,1.2 0 1,0 0,2.4a1.2,1.2 0 1,0 0,-2.4zM12,17.5a1.2,1.2 0 1,0 0,2.4a1.2,1.2 0 1,0 0,-2.4zM17,17.5a1.2,1.2 0 1,0 0,2.4a1.2,1.2 0 1,0 0,-2.4z" />
</vector>
```
`ic_wx_storm.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M19,14H6a4,4 0 0,1 -0.5,-7.97A6,6 0 0,1 17,4h1a5,5 0 0,1 1,9.9z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M13,15l-4,5h3l-1,4l4,-5h-3z" />
</vector>
```
`ic_wx_fog.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M3,9h18v2H3zM5,13h14v2H5zM3,17h18v2H3z" />
</vector>
```

- [ ] **Step 4: Widget state, widget, receiver, manifest**

Create `app/src/main/kotlin/it/apexweather/widget/WidgetState.kt`:
```kotlin
package it.apexweather.widget

import it.apexweather.R
import it.apexweather.domain.SunPhase
import it.apexweather.domain.model.Condition
import it.apexweather.ui.common.Format
import it.apexweather.ui.home.HomeUiState
import java.time.ZoneId

data class WidgetHour(val label: String, val tempText: String, val iconRes: Int)

data class WidgetState(
    val hasData: Boolean,
    val tempText: String,
    val conditionRes: Int,
    val iconRes: Int,
    val hours: List<WidgetHour>,
    val updatedText: String,
    val topColor: Long,
    val bottomColor: Long,
)

fun Condition.widgetIcon(phase: SunPhase): Int = when (this) {
    Condition.CLEAR, Condition.MOSTLY_CLEAR -> if (phase == SunPhase.NIGHT) R.drawable.ic_wx_moon else R.drawable.ic_wx_sun
    Condition.PARTLY_CLOUDY, Condition.CLOUDY -> R.drawable.ic_wx_cloud
    Condition.FOG -> R.drawable.ic_wx_fog
    Condition.DRIZZLE, Condition.RAIN, Condition.HEAVY_RAIN -> R.drawable.ic_wx_rain
    Condition.SLEET, Condition.SNOW, Condition.HEAVY_SNOW -> R.drawable.ic_wx_snow
    Condition.THUNDERSTORM -> R.drawable.ic_wx_storm
}

fun Condition.labelRes(): Int = when (this) {
    Condition.CLEAR -> R.string.cond_clear
    Condition.MOSTLY_CLEAR -> R.string.cond_mostly_clear
    Condition.PARTLY_CLOUDY -> R.string.cond_partly_cloudy
    Condition.CLOUDY -> R.string.cond_cloudy
    Condition.FOG -> R.string.cond_fog
    Condition.DRIZZLE -> R.string.cond_drizzle
    Condition.RAIN -> R.string.cond_rain
    Condition.HEAVY_RAIN -> R.string.cond_heavy_rain
    Condition.SLEET -> R.string.cond_sleet
    Condition.SNOW -> R.string.cond_snow
    Condition.HEAVY_SNOW -> R.string.cond_heavy_snow
    Condition.THUNDERSTORM -> R.string.cond_thunderstorm
}

object WidgetStateBuilder {
    fun build(home: HomeUiState, zone: ZoneId): WidgetState = WidgetState(
        hasData = !home.isEmpty && home.heroTempC != null,
        tempText = home.heroTempC?.let(Format::temp) ?: "–",
        conditionRes = home.heroCondition.labelRes(),
        iconRes = home.heroCondition.widgetIcon(home.phase),
        hours = home.upcomingHours.drop(1).take(6).map { h ->
            WidgetHour(Format.hour(h.time, zone), Format.temp(h.tempC), h.condition.widgetIcon(home.phaseAt(h.time)))
        },
        updatedText = home.updatedAt?.let { Format.time(it, zone) } ?: "",
        topColor = home.palette.top,
        bottomColor = home.palette.bottom,
    )
}
```

Replace `app/src/main/kotlin/it/apexweather/widget/ApexWidget.kt`:
```kotlin
package it.apexweather.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import it.apexweather.MainActivity
import it.apexweather.R
import it.apexweather.data.SettingsRepository
import it.apexweather.data.WeatherRepository
import it.apexweather.domain.ConsensusBlender
import it.apexweather.domain.DorfTirol
import it.apexweather.ui.home.HomeStateBuilder
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): WeatherRepository
    fun settings(): SettingsRepository
    fun blender(): ConsensusBlender
    fun clock(): Clock
}

class ApexWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val settings = ep.settings().settings.first()
        val snapshot = ep.repository().snapshot(settings.bulletinLanguage(Locale.getDefault().toLanguageTag())).first()
        val home = HomeStateBuilder.build(snapshot, settings, ep.blender().blend(snapshot.forecasts), ep.clock().instant())
        val state = WidgetStateBuilder.build(home, DorfTirol.ZONE)
        val background = gradientBitmap(state.topColor, state.bottomColor)

        provideContent { WidgetContent(state, background) }
    }

    private fun gradientBitmap(top: Long, bottom: Long): Bitmap {
        val bmp = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 128f, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 64f, 128f, paint)
        return bmp
    }

    companion object {
        val SMALL = androidx.compose.ui.unit.DpSize(110.dp, 50.dp)
        val MEDIUM = androidx.compose.ui.unit.DpSize(250.dp, 110.dp)
    }
}

@Composable
private fun WidgetContent(state: WidgetState, background: Bitmap) {
    val size = LocalSize.current
    val white = ColorProvider(Color.White)
    Box(GlanceModifier.fillMaxSize().cornerRadius(24.dp).clickable(actionStartActivity<MainActivity>())) {
        Image(ImageProvider(background), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = GlanceModifier.fillMaxSize())
        Column(GlanceModifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(state.iconRes), contentDescription = null, modifier = GlanceModifier.size(28.dp))
                Spacer(GlanceModifier.width(8.dp))
                Text(state.tempText, style = TextStyle(color = white, fontSize = 30.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Text(DorfTirol.NAME, style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    Text(androidx.glance.LocalContext.current.getString(state.conditionRes), style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.8f)), fontSize = 12.sp))
                }
            }
            if (size.height >= ApexWidget.MEDIUM.height && state.hours.isNotEmpty()) {
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    state.hours.forEach { h ->
                        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(h.label, style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.75f)), fontSize = 11.sp))
                            Image(ImageProvider(h.iconRes), contentDescription = null, modifier = GlanceModifier.size(18.dp))
                            Text(h.tempText, style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        }
                    }
                }
            }
        }
    }
}
```

Create `app/src/main/kotlin/it/apexweather/widget/ApexWidgetReceiver.kt`:
```kotlin
package it.apexweather.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class ApexWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ApexWidget()
}
```

Create `app/src/main/res/xml/apex_widget_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="110dp"
    android:minHeight="50dp"
    android:targetCellWidth="2"
    android:targetCellHeight="1"
    android:maxResizeWidth="400dp"
    android:maxResizeHeight="200dp"
    android:resizeMode="horizontal|vertical"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen"
    android:description="@string/widget_description" />
```

Add to `AndroidManifest.xml` inside `<application>`:
```xml
        <receiver
            android:name=".widget.ApexWidgetReceiver"
            android:exported="true"
            android:label="@string/app_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/apex_widget_info" />
        </receiver>
```

Add strings: `widget_description` = de "Aktuelle Temperatur und die nächsten Stunden für Dorf Tirol" / it "Temperatura attuale e prossime ore per Tirolo" / en "Current temperature and next hours for Dorf Tirol".

- [ ] **Step 5: Run tests, install, add the widget on the phone**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests 'it.apexweather.widget.*' :app:assembleDebug --console=plain 2>&1 | tail -8
adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: 2 tests pass, build succeeds. Then add the widget manually on the phone (long-press home screen → Widgets → Apex Weather) in both sizes and confirm it shows the temperature and, in the larger size, six hours. Take a screenshot: `adb -s RZCXA1ZEXJE exec-out screencap -p > /tmp/apex_widget.png` and inspect it.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(widget): Glance home-screen widget in two sizes with sky gradient"
```

---

### Task 17: Final verification, release build, project docs

**Files:**
- Create: `CLAUDE.md`, `README.md`
- Modify: anything the checks below reveal

- [ ] **Step 1: Full test run and release build**

Run:
```bash
./gradlew clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain 2>&1 | tail -15
ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest --console=plain 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL` for all; the release APK builds with R8 (unsigned is fine). If R8 strips a kotlinx.serialization class, add the class to `proguard-rules.pro` with `-keep class it.apexweather.data.remote.** { *; }`.

- [ ] **Step 2: Manual acceptance on the phone**

Install the debug build and verify each item; fix and re-run tests for anything failing:
1. Cold start shows cached data instantly (kill the app with `adb shell am force-stop it.apexweather`, disable Wi-Fi and mobile data, reopen: the offline banner appears with the last update time and all sections still render).
2. Re-enable network, pull to refresh: the banner disappears, the update time changes.
3. Compare tab: toggling chips removes lines; the variable switch changes the axis unit; the status list shows "Lauf HH:MM" for every source.
4. Bulletin tab: German text, six district day cards, map images load.
5. Settings: switching language to IT recreates the activity in Italian and the bulletin text is Italian after refresh; switching animations off stops the particles.
6. Widget updates after an in-app refresh.
7. `adb shell dumpsys jobscheduler | grep it.apexweather` shows the periodic job.

- [ ] **Step 3: Write CLAUDE.md**

Create `CLAUDE.md`:
```markdown
# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

- Build/install: `./gradlew :app:assembleDebug` then `adb -s RZCXA1ZEXJE install -r app/build/outputs/apk/debug/app-debug.apk`
- JVM tests: `./gradlew :app:testDebugUnitTest` (single class: `--tests 'it.apexweather.domain.ConsensusBlenderTest'`)
- Device tests: `ANDROID_SERIAL=RZCXA1ZEXJE ./gradlew :app:connectedDebugAndroidTest` (single class: `-Pandroid.testInstrumentationRunnerArguments.class=it.apexweather.ui.home.HomeScreenTest`)
- Toolchain is pinned in `gradle.properties` (`org.gradle.java.home` = JDK 21) and `local.properties` (`sdk.dir`); the shell's `ANDROID_HOME` points at an incomplete SDK, ignore it.
- AGP 9 built-in Kotlin: never apply `org.jetbrains.kotlin.android` in `app/build.gradle.kts`; KSP only, no kapt.

## Architecture

Single module, package `it.apexweather`, fixed location Dorf Tirol (constants in `domain/DorfTirol.kt`).

- `domain/` is pure Kotlin. `ConsensusBlender` turns `Map<Source, SourceForecast>` into `ConsensusForecast` (median, min/max band, ECMWF only where < 2 regional sources, majority-vote condition). `DailyAggregator` is the single place hourly → daily happens. `SkyPaletteSelector` + `SunPhaseCalculator` drive the UI colours.
- `data/remote/` has one file per upstream: Open-Meteo (5 models in one call, dynamic JSON keys read via `JsonObject`), GeoSphere AROME (condition derived from cloud/precip/CAPE, precipitation is a diff of the accumulated series), SIAG (KMOS municipality forecast, Open Data Hub bulletin, live station). Each mapper is tested against recorded fixtures in `app/src/test/resources/fixtures/`; re-record with the curl commands in `docs/superpowers/plans/2026-09-08-apexweather.md` Task 4 when an upstream changes.
- `data/WeatherRepository` fetches all sources in a `supervisorScope`, writes each into Room independently, and keeps old JSON when a source fails (`SourceStatus.Failed` carries `lastIssuedAt`). UI always renders whatever is cached.
- `ui/home/HomeStateBuilder` is the pure function that decides hero values (station observation wins if < 90 min old), palette, and the 48 h / 7 d windows; the widget (`widget/WidgetStateBuilder`) and `SkyViewModel` reuse it.
- Background refresh: `work/RefreshWorker` (Hilt worker, hourly, network constraint) → repository → `ApexWidget().updateAll`.

## Conventions

- Screens are split into `XScreen` (ViewModel wiring) and `XContent(state, callbacks)`; UI tests drive `XContent` with hand-built states.
- Strings live in `values` (German, default), `values-it`, `values-en`; add every new key to all three.
- Source colours are in `ui/common/SourceColors.kt`; SIAG letter codes in `domain/SiagCodes.kt`.
```

- [ ] **Step 4: Short README and commit**

Create `README.md` with the app name, one paragraph, the source list with attribution links, and the build commands (copy from CLAUDE.md). Then:
```bash
git add -A && git commit -m "docs: CLAUDE.md and README, release build verified"
```

---

## Self-review notes (already applied)

- Spec coverage: §3 sources → Tasks 4–6; §4.2 repository → Task 8; §4.3 blender → Task 2; §4.4 sky → Tasks 3, 10; §5 screens → Tasks 11–14; §6 widget/worker → Tasks 15–16; §7 error states → Tasks 8, 11; §8 tests → every task; §9 build env → Task 1.
- Deviation from spec §4.4: cards use a translucent gradient + border instead of a real backdrop `RenderEffect` blur. Compose has no stable backdrop blur; a follow-up can add the `haze` library.
- Deviation from spec §6: the widget background is a runtime-generated gradient bitmap (Glance has no gradient brush), which matches the intent.
