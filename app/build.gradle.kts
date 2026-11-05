plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Short git commit hash shown in the About section; resolved at configuration
// time so the configuration cache stays valid. Can be overridden via
// -Pcapsule.gitCommitHash when building from a source archive without git history.
// An empty -P value is treated as absent (Provider.map to null), so a stray
// `-Pcapsule.gitCommitHash=` can never blank the field.
val gitCommitHash: String =
    providers
        .gradleProperty("capsule.gitCommitHash")
        .map { it.trim().ifEmpty { null } }
        .orElse(
            providers
                .exec {
                    commandLine("git", "rev-parse", "--short=7", "HEAD")
                    isIgnoreExitValue = true
                }.standardOutput.asText
                .map { it.trim().ifEmpty { "unknown" } },
        ).orElse("unknown")
        .get()

// Release signing properties, resolved once at configuration time so task
// actions never touch Project at execution time (configuration-cache safe).
// Values may come from the command line (-P) or any gradle.properties file.
val releaseKeyAlias = providers.gradleProperty("capsule.release.keyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("capsule.release.keyPassword").orNull
val releaseStoreFile = providers.gradleProperty("capsule.release.storeFile").orNull
val releaseStorePassword = providers.gradleProperty("capsule.release.storePassword").orNull

val releaseProps = listOf(releaseKeyAlias, releaseKeyPassword, releaseStoreFile, releaseStorePassword)
val hasAnyReleaseProps = releaseProps.any { !it.isNullOrBlank() }
val hasAllReleaseProps = releaseProps.all { !it.isNullOrBlank() }
val resolvedKeystoreFile = releaseStoreFile?.let { rootProject.file(it) }

// Fail fast if signing was attempted but configured incorrectly (protects against typos).
// If no signing properties are provided at all, the build cleanly falls back to unsigned
// output, enabling third-party reproducible build verifications.
if (hasAnyReleaseProps) {
    if (!hasAllReleaseProps) {
        throw GradleException(
            "Incomplete release signing configuration. All capsule.release.* properties must be provided.",
        )
    }
    if (resolvedKeystoreFile?.exists() != true) {
        throw GradleException(
            "Release keystore file not found at: ${resolvedKeystoreFile?.absolutePath}",
        )
    }
}

val isReleaseSigningConfigured = hasAllReleaseProps && resolvedKeystoreFile?.exists() == true

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
        if (isReleaseSigningConfigured) {
            create("release") {
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeFile = resolvedKeystoreFile
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
            // Signs the APK if the keystore is available locally; otherwise leaves it
            // unsigned so reproducible builds can be verified with apksigcopier.
            signingConfig = if (isReleaseSigningConfigured) signingConfigs.getByName("release") else null
            // AGP >= 8.3 embeds git revision info into
            // META-INF/version-control-info.textproto by default, which makes a
            // build from a git checkout differ from the same sources built from a
            // source archive. Keep both byte-identical; the release commit hash is
            // already surfaced through BuildConfig.GIT_COMMIT_HASH above.
            vcsInfo {
                include = false
            }
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

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
