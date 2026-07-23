plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val queueSyncUrl = providers.gradleProperty("QUEUE_SYNC_URL")
    .orElse(providers.environmentVariable("QUEUE_SYNC_URL"))
    .orElse("https://abcccc.top/api/queue-status")
val queueSyncToken = providers.gradleProperty("QUEUE_SYNC_TOKEN")
    .orElse(providers.environmentVariable("QUEUE_SYNC_TOKEN"))
    .orElse("")

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
        versionCode = 15
        versionName = "0.2.13"

        buildConfigField("String", "QUEUE_SYNC_URL", queueSyncUrl.get().asBuildConfigString())
        buildConfigField("String", "QUEUE_SYNC_TOKEN", queueSyncToken.get().asBuildConfigString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
