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

The build fails with a clear error when the signing properties are missing:
an unsigned or debug-signed artifact can never ship by accident.

## Verifying a built APK

```
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

The printed SHA-256 digest should match the fingerprint published for your
distribution channel. Changing the signing key later means users must
uninstall before updating.
