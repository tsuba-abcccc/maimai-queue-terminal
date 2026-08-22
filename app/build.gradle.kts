plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val queueSyncUrl = providers.gradleProperty("QUEUE_SYNC_URL")
    .orElse(providers.environmentVariable("QUEUE_SYNC_URL"))
    // A public build must not silently point at a private/demo service.  A
    // connected terminal can still be provisioned explicitly with
    // -PQUEUE_SYNC_URL (or configured from the app settings).
    .orElse("")
val queueSyncToken = providers.gradleProperty("QUEUE_SYNC_TOKEN")
    .orElse(providers.environmentVariable("QUEUE_SYNC_TOKEN"))
    .orElse("")
val terminalBuildEnabled = providers.gradleProperty("ENABLE_TERMINAL_BUILD")
    .orElse(providers.environmentVariable("ENABLE_TERMINAL_BUILD"))
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val embedTerminalSyncConfig = providers.gradleProperty("EMBED_TERMINAL_SYNC_CONFIG")
    .orElse(providers.environmentVariable("EMBED_TERMINAL_SYNC_CONFIG"))
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
// Even a private Gradle user property must not accidentally put a URL or
// token into a public APK. Embedding is an explicit opt-in for controlled
// device builds; ordinary terminal builds are configured in the app.
val terminalQueueSyncUrl = if (terminalBuildEnabled.get() && embedTerminalSyncConfig.get()) {
    queueSyncUrl.get()
} else ""
val terminalQueueSyncToken = if (terminalBuildEnabled.get() && embedTerminalSyncConfig.get()) {
    queueSyncToken.get()
} else ""
val appVersionName = "0.12.3"

android {
    namespace = "com.abcccc.maimaiqueue"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.abcccc.maimaiqueue"
        minSdk = 29
        targetSdk = 36
        versionCode = 65
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "deployment"
    productFlavors {
        create("local") {
            dimension = "deployment"
            applicationIdSuffix = ".local"
            resValue("string", "app_name", "maimai Q 本地版")
            buildConfigField("Boolean", "CLOUD_SYNC_AVAILABLE", "false")
            buildConfigField("String", "QUEUE_SYNC_URL", "\"\"")
            buildConfigField("String", "QUEUE_SYNC_TOKEN", "\"\"")
        }
        create("terminal") {
            dimension = "deployment"
            resValue("string", "app_name", "maimai Q")
            buildConfigField("Boolean", "CLOUD_SYNC_AVAILABLE", "true")
            buildConfigField("String", "QUEUE_SYNC_URL", terminalQueueSyncUrl.asBuildConfigString())
            buildConfigField("String", "QUEUE_SYNC_TOKEN", terminalQueueSyncToken.asBuildConfigString())
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

androidComponents {
    beforeVariants(selector().withFlavor("deployment" to "terminal")) { variantBuilder ->
        variantBuilder.enable = terminalBuildEnabled.get()
    }
}

tasks.register<Copy>("packageLocalDebugApk") {
    dependsOn("assembleLocalDebug")
    from(layout.buildDirectory.file("outputs/apk/local/debug/app-local-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("output/apk"))
    rename("app-local-debug\\.apk", "maimai-Q-$appVersionName-local.apk")
}

tasks.register<Copy>("packageTerminalDebugApk") {
    dependsOn("assembleTerminalDebug")
    from(layout.buildDirectory.file("outputs/apk/terminal/debug/app-terminal-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("output/apk"))
    rename("app-terminal-debug\\.apk", "maimai-Q-$appVersionName-terminal.apk")
}

dependencies {
    implementation(project(":queue-core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
