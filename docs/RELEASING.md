# Release signing

Release APKs must never be signed with the public debug key. The release
signing properties live outside the project tree so no private key can be
committed by accident.

## One-time key creation

```
keytool -genkeypair -v \
  -keystore /path/OUTSIDE/the/project/capsule-release.jks \
  -alias capsule-release -keyalg RSA -keysize 4096 -validity 10000
```

## Gradle properties

Add these to `~/.gradle/gradle.properties` (or pass with `-P`):

```
capsule.release.storeFile=/path/OUTSIDE/the/project/capsule-release.jks
capsule.release.storePassword=<store password>
capsule.release.keyAlias=capsule-release
capsule.release.keyPassword=<key password>
```

`storeFile` is resolved against the repository root, so relative paths like
`../keystores/capsule-release.jks` also work.

## Building the release

```
./gradlew assembleRelease
```

- All four `capsule.release.*` properties set: the APK is signed.
- Only some of them set: the build fails fast with a clear error, so a typo
  can never produce an unsigned or wrongly signed artifact.
- None set: the build produces an **unsigned**
  `app/build/outputs/apk/release/app-release-unsigned.apk` — exactly what
  third-party reproducible-build verification consumes.

## Verifying a built APK

```
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

The printed SHA-256 digest should match the fingerprint published for your
distribution channel. Changing the signing key later means users must
uninstall before updating.

## Reproducible build verification

Anyone can rebuild a published release byte-for-byte from its sources:

```
tar xzf capsule-src.tar.gz && cd capsule
./gradlew :app:assembleRelease \
  -Pcapsule.gitCommitHash=<short hash of the release commit>
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

The digest must match the published one for the release. The required
environment: JDK 21 (Temurin), the Gradle/AGP/dependency versions pinned by
the wrapper and `gradle/libs.versions.toml`, and an Android SDK with
`platforms;android-37.0` installed.

To compare against a **signed** release APK instead of a published digest,
copy the signature over an unsigned rebuild and diff:

```
pip install apksigcopier
apksigcopier copy app-release.apk app-release-unsigned.apk re-signed.apk
cmp re-signed.apk app-release.apk && echo "reproducible"
```

Two properties of the build make this work:

- `vcsInfo { include = false }` — AGP >= 8.3 otherwise embeds the git
  revision in `META-INF/version-control-info.textproto`, so a git checkout
  and a source archive of the same commit would differ.
- `dependenciesInfo { includeInApk = false }` — keeps Play's SDK dependency
  blob out of the APK signing block.
