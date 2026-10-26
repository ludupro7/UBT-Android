plugins {
    id("com.android.application")
}

android {
    namespace = "com.cue.amlogicburn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cue.amlogicburn"
        minSdk = 28
        targetSdk = 35
        versionCode = 11
        versionName = "0.2.0"
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val buildRust by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust native core for ARM32 and ARM64 Android."
    workingDir(rootProject.file("native"))
    commandLine(
        "cargo", "ndk",
        "-t", "armeabi-v7a",
        "-t", "arm64-v8a",
        "-o", project.file("src/main/jniLibs").absolutePath,
        "build", "--release"
    )
}

tasks.named("preBuild").configure {
    dependsOn(buildRust)
}
