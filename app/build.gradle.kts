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
val queueManagementUrl = providers.gradleProperty("QUEUE_MANAGEMENT_URL")
    .orElse(providers.environmentVariable("QUEUE_MANAGEMENT_URL"))
    .orElse("")
val queueManagementToken = providers.gradleProperty("QUEUE_MANAGEMENT_TOKEN")
    .orElse(providers.environmentVariable("QUEUE_MANAGEMENT_TOKEN"))
    .orElse("")
val managementBuildEnabled = providers.gradleProperty("ENABLE_MANAGEMENT_BUILD")
    .orElse(providers.environmentVariable("ENABLE_MANAGEMENT_BUILD"))
    .map { it.equals("true", ignoreCase = true) }
    .orElse(true)
val managementApiUrl = if (managementBuildEnabled.get()) queueManagementUrl.get() else ""
val managementApiToken = if (managementBuildEnabled.get()) queueManagementToken.get() else ""
val localAppVersionName = "0.13.1"
val terminalAppVersionName = "0.13.1"
val managementAppVersionName = "0.13.0"
val localVersionCode = 67
val terminalVersionCode = 67
val managementVersionCode = 66

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
        versionCode = localVersionCode
        versionName = localAppVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "deployment"
    productFlavors {
        create("local") {
            dimension = "deployment"
            applicationIdSuffix = ".local"
            versionCode = localVersionCode
            versionName = localAppVersionName
            resValue("string", "app_name", "maimai Q 本地版")
            buildConfigField("Boolean", "CLOUD_SYNC_AVAILABLE", "false")
            buildConfigField("String", "QUEUE_SYNC_URL", "\"\"")
            buildConfigField("String", "QUEUE_SYNC_TOKEN", "\"\"")
            buildConfigField("Boolean", "MANAGEMENT_APP", "false")
            buildConfigField("String", "MANAGEMENT_API_URL", "\"\"")
            buildConfigField("String", "MANAGEMENT_API_TOKEN", "\"\"")
        }
        create("terminal") {
            dimension = "deployment"
            versionCode = terminalVersionCode
            versionName = terminalAppVersionName
            resValue("string", "app_name", "maimai Q")
            buildConfigField("Boolean", "CLOUD_SYNC_AVAILABLE", "true")
            buildConfigField("String", "QUEUE_SYNC_URL", terminalQueueSyncUrl.asBuildConfigString())
            buildConfigField("String", "QUEUE_SYNC_TOKEN", terminalQueueSyncToken.asBuildConfigString())
            buildConfigField("Boolean", "MANAGEMENT_APP", "false")
            buildConfigField("String", "MANAGEMENT_API_URL", "\"\"")
            buildConfigField("String", "MANAGEMENT_API_TOKEN", "\"\"")
        }
        create("management") {
            dimension = "deployment"
            applicationIdSuffix = ".management"
            versionCode = managementVersionCode
            versionName = managementAppVersionName
            resValue("string", "app_name", "maimai Q 管理后台")
            buildConfigField("Boolean", "CLOUD_SYNC_AVAILABLE", "false")
            buildConfigField("String", "QUEUE_SYNC_URL", "\"\"")
            buildConfigField("String", "QUEUE_SYNC_TOKEN", "\"\"")
            buildConfigField("Boolean", "MANAGEMENT_APP", "true")
            buildConfigField("String", "MANAGEMENT_API_URL", managementApiUrl.asBuildConfigString())
            buildConfigField("String", "MANAGEMENT_API_TOKEN", managementApiToken.asBuildConfigString())
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
    beforeVariants(selector().withFlavor("deployment" to "management")) { variantBuilder ->
        variantBuilder.enable = managementBuildEnabled.get()
    }
}

tasks.register<Copy>("packageLocalDebugApk") {
    dependsOn("assembleLocalDebug")
    from(layout.buildDirectory.file("outputs/apk/local/debug/app-local-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("output/apk"))
    rename("app-local-debug\\.apk", "maimai-Q-$localAppVersionName-local.apk")
}

tasks.register<Copy>("packageTerminalDebugApk") {
    dependsOn("assembleTerminalDebug")
    from(layout.buildDirectory.file("outputs/apk/terminal/debug/app-terminal-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("output/apk"))
    rename("app-terminal-debug\\.apk", "maimai-Q-$terminalAppVersionName-terminal.apk")
}

tasks.register<Copy>("packageManagementDebugApk") {
    dependsOn("assembleManagementDebug")
    from(layout.buildDirectory.file("outputs/apk/management/debug/app-management-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("output/apk"))
    rename("app-management-debug\\.apk", "maimai-Q-$managementAppVersionName-management.apk")
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
