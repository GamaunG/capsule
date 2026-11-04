plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Short git commit hash shown in the About section; resolved at configuration
// time so the configuration cache stays valid.
val gitCommitHash: String =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().ifEmpty { "unknown" } }
        .orElse("unknown")
        .get()

// Release signing properties, resolved once at configuration time so task
// actions never touch Project at execution time (configuration-cache safe).
// Values may come from the command line (-P) or any gradle.properties file.
val releaseKeyAlias = providers.gradleProperty("capsule.release.keyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("capsule.release.keyPassword").orNull
val releaseStoreFile = providers.gradleProperty("capsule.release.storeFile").orNull
val releaseStorePassword = providers.gradleProperty("capsule.release.storePassword").orNull
val releaseSigningConfigured =
    listOf(releaseKeyAlias, releaseKeyPassword, releaseStoreFile, releaseStorePassword)
        .none { it.isNullOrBlank() }

android {
    namespace = "capsule.profile"
    compileSdk = 37

    defaultConfig {
        applicationId = "capsule.profile"
        // Android 8.0: notification channels, pinned shortcuts and the
        // unknown-sources app op are the effective floor of the feature set;
        // every pre-Oreo branch was untestable legacy.
        minSdk = 26
        targetSdk = 37
        versionCode = 20
        versionName = "0.2.0"

        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitCommitHash\"")
    }

    signingConfigs {
        // Public debug key committed to the repository. Debug builds only — never
        // use this key for release artifacts.
        create("debugPublic") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "capsule-debug"
            keyPassword = "android"
        }
        // Release key — properties and keystore stay outside the project tree:
        //   capsule.release.keyAlias / keyPassword / storePassword / storeFile
        // storeFile is resolved against the repository root, so a value like
        // "../keystores/capsule-release.jks" or an absolute path both work.
        create("release") {
            if (releaseKeyAlias != null && releaseKeyPassword != null &&
                releaseStoreFile != null && releaseStorePassword != null
            ) {
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugPublic")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Refuse to produce a release artifact that would end up unsigned or signed
// with a temporary key. The check runs at execution time, but reads only a
// task-local copy of the configuration-time Boolean, which keeps the
// configuration cache happy.
tasks
    .matching {
        it.name == "validateSigningRelease" ||
            it.name == "packageRelease" ||
            it.name == "bundleRelease"
    }.configureEach {
        val signingConfigured = releaseSigningConfigured
        doFirst {
            if (!signingConfigured) {
                throw GradleException(
                    "Release signing is not configured. Set the capsule.release.* " +
                        "gradle properties to a keystore outside the project tree.",
                )
            }
        }
    }

// AGP 9 built-in Kotlin: compilerOptions lives in a top-level kotlin {} block.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)

    testImplementation(libs.junit)
}
