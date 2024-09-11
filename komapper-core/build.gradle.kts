plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    // TODO: think again about the supported targets.
    iosArm64()
    androidNativeArm64()
    androidNativeX64()
    macosArm64()
    macosX64()
    linuxArm64()
    linuxX64()
    mingwX64()

    applyDefaultHierarchyTemplate()
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val kotlinCoroutinesVersion: String by project
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
            }
        }
    }
}
