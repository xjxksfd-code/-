import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.autumn.douyin.liquidglass"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.autumn.douyin.liquidglass"
        minSdk = 33
        targetSdk = 35
        versionCode = 130
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Windows test workers on machines using GBK cannot load classes from this
// checkout's non-ASCII path. Mirror local outputs through java.io.tmpdir.
tasks.withType<Test>().configureEach {
    doFirst {
        val nonAsciiPaths = classpath.files.filter {
            it.absolutePath.any { char -> char.code > 127 }
        }
        if (nonAsciiPaths.isEmpty()) return@doFirst

        val mirrorRoot = File(
            System.getProperty("java.io.tmpdir"),
            "douyin-liquid-glass-gradle-test/$name",
        )
        if (mirrorRoot.absolutePath.any { char -> char.code > 127 }) return@doFirst
        mirrorRoot.deleteRecursively()
        mirrorRoot.mkdirs()

        classpath = files(classpath.files.mapIndexed { index, path ->
            if (!path.absolutePath.any { char -> char.code > 127 } || !path.exists()) {
                return@mapIndexed path
            }

            val suffix = when {
                path.isDirectory -> ".dir"
                path.extension.equals("jar", ignoreCase = true) -> ".jar"
                else -> ".file"
            }
            val mirroredPath = File(mirrorRoot, index.toString().padStart(3, '0') + suffix)
            if (path.isDirectory) {
                project.copy {
                    from(path)
                    into(mirroredPath)
                }
            } else {
                path.copyTo(mirroredPath, overwrite = true)
            }
            mirroredPath
        })
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.ui)

    compileOnly(files("libs/api-82.jar"))
}
