import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "it.apexweather.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // A baseline profile is collected without root from API 33 on, where a release build is
        // profileable by default. Below that the generator needs a rooted device, which no phone
        // this app is developed against is.
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    testOptions.managedDevices.allDevices {
        // Only used when no phone is plugged in; the physical device is faster and is what the app
        // is actually verified on.
        create<ManagedVirtualDevice>("pixel6Api34") {
            device = "Pixel 6"
            apiLevel = 34
            systemImageSource = "aosp"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}

// Collect on whatever is attached; pass -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules
// only when narrowing a run down.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
