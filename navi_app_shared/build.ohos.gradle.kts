plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
}

kotlin {
    ohosArm64 { binaries.sharedLib {} }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.tencent.kuikly-open:core:${Version.getKuiklyOhosVersion()}")
                implementation("com.tencent.kuikly-open:core-annotations:${Version.getKuiklyOhosVersion()}")
            }
        }
        val commonTest by getting { dependencies { implementation(kotlin("test")) } }
    }
}

ksp { arg("pageName", "") }
dependencies { add("kspOhosArm64", "com.tencent.kuikly-open:core-ksp:${Version.getKuiklyOhosVersion()}") }
