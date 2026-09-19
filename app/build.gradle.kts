plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

android {
    namespace = "com.metronearby"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.metronearby"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

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
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
// ---------------------------------------------------------------------------
// 单元测试覆盖率：本地度量用，不影响 APK 产物。
// AGP 的单元测试任务不是由 java 插件创建的，因此需要手动把 JaCoCo agent
// 挂到 Test 任务上，jacoco 插件才会产出 .exec 执行数据。
// ---------------------------------------------------------------------------
jacoco {
    toolVersion = "0.8.13"
}

tasks.withType<Test>().configureEach {
    // AGP 已经为单元测试任务注册了 JacocoTaskExtension，这里只保证 agent 处于启用状态。
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
        isEnabled = true
    }
}

// 单元测试覆盖率只统计"纯 JVM 可测"的逻辑层（domain / data / geo / location 业务类）。
// Compose UI、Activity 与 Android SDK 胶水类无法在本地 JVM 单测中执行，需要
// instrumentation 测试覆盖，因此不纳入本报告的统计口径。
val unitTestCoverageExcludes = listOf(
    "**/R.class",
    "**/R\$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "com/metronearby/ui/**",
    "com/metronearby/MainActivity*",
    "com/metronearby/ComposableSingletons*",
    "com/metronearby/location/AndroidLocationProvider*",
    "com/metronearby/data/source/AssetMetroDataSource*"
)

tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoUnitTestReport") {
    group = "verification"
    description = "生成 debug 单元测试覆盖率报告（逻辑层口径）"

    dependsOn(tasks.named("testDebugUnitTest"))

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(unitTestCoverageExcludes)
        }
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") }
    )
}

// 全量口径（含 UI 层），仅用于观察整体覆盖情况，不作为单元测试达标依据。
tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoFullReport") {
    group = "verification"
    description = "生成含 UI 层在内的整体覆盖率报告"

    dependsOn(tasks.named("testDebugUnitTest"))

    reports {
        xml.required.set(true)
        html.required.set(false)
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude("**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*")
        }
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") }
    )
}