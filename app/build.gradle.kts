plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.dnsbunker.android"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "org.dnsbunker.android"
        minSdk = 21
        targetSdk = 36
        versionCode = 300
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystore = providers.environmentVariable("DNSBUNKER_KEYSTORE").orNull
    if (!releaseKeystore.isNullOrBlank()) {
        signingConfigs.create("production") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("DNSBUNKER_STORE_PASSWORD").orNull
                ?: error("DNSBUNKER_STORE_PASSWORD is required")
            keyAlias = providers.environmentVariable("DNSBUNKER_KEY_ALIAS").getOrElse("dnsbunker-release")
            keyPassword = providers.environmentVariable("DNSBUNKER_KEY_PASSWORD").orNull
                ?: error("DNSBUNKER_KEY_PASSWORD is required")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug { versionNameSuffix = "-dev" }
        release {
            isDebuggable = false
            isJniDebuggable = false
            if (!releaseKeystore.isNullOrBlank()) signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs { keepDebugSymbols += "**/*.so" }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/dnsbunkerJni"))
}

val nativeOutput = layout.buildDirectory.dir("generated/dnsbunkerJni")
val androidNdk = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
val compileDnsBunkerNative by tasks.registering(Exec::class) {
    val sources = rootProject.layout.projectDirectory.dir("native")
    inputs.dir(sources)
    inputs.property("ndkVersion", android.ndkVersion)
    outputs.dir(nativeOutput)
    doFirst { environment("ANDROID_NDK_HOME", androidNdk.get()) }
    commandLine("bash", sources.file("build-native.sh").asFile.absolutePath, nativeOutput.get().asFile.absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(compileDnsBunkerNative)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
