plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("com.google.devtools.ksp")
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions.jvmTarget = "1.8" }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyVersion()}")
            }
        }
        val commonTest by getting { dependencies { implementation(kotlin("test")) } }
        val androidMain by getting {
            dependencies { api("com.tencent.kuikly-open:core-render-android:${Version.getKuiklyVersion()}") }
        }
        val androidUnitTest by getting { dependencies { implementation(kotlin("test-junit")) } }
    }
}

ksp { arg("pageName", "") }
dependencies { add("kspAndroid", "com.tencent.kuikly-open:core-ksp:${Version.getKuiklyVersion()}") }

android {
    namespace = "com.example.navi_app.navi_app_shared"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    sourceSets.named("main") { assets.srcDirs("src/commonMain/assets") }
}
