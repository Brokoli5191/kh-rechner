# Building KH Rechner

This page is for people who want to change the code. If you only want the app, take the APK from
the [releases page](https://github.com/Brokoli5191/kh-rechner/releases/latest).

## Requirements

- JDK 17 or newer (CI uses 21)
- Android SDK. The build compiles against Android 17 (API 37.1) and fetches that platform itself
  once the SDK licences are accepted.

The Gradle wrapper downloads Gradle and all libraries.

## Build and test

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests and UI tests, all on the JVM
./gradlew lintDebug
```

The UI tests start the real app under Robolectric and click through it, so no emulator is needed.
The scanner, the network and AAPS are replaced by fakes in `TestApplication`.

To re-render the pictures in `docs/screenshots`:

```sh
./gradlew testDebugUnitTest -Pscreenshots
```

## Project layout

One module, `:app`, package `app.brokoli5191.khrechner`.

| Package | Contents |
|---|---|
| `domain` | The calculation, pure Kotlin. It uses exact fractions, which is how the app knows whether a displayed value was rounded. |
| `data` | DataStore with kotlinx.serialization, export and import, the Open Food Facts client, the barcode scanner and the AAPS hand-off. |
| `ui` | Compose, one package per screen, plus theme and shared components. |

`AppContainer` wires these together by hand.

The list behind "verwendete bibliotheken" is not maintained by hand. The AboutLibraries Gradle
plugin writes every dependency with its licence to `res/raw/aboutlibraries.json` during the build,
and `LibraryCatalog` reads that file.

## Releases

A release is a signed APK attached to a GitHub Release with generated notes. Version `1.2.3`
becomes version code `10203`. `.github/workflows/release.yml` builds it, and there are two ways to
start it:

- On GitHub: Actions → Release → Run workflow, on `main`, with the version number. The run creates
  the tag `v1.2.3` itself and refuses a version whose tag already exists.
- From a clone, by pushing the tag:

```sh
git tag v1.2.3
git push origin v1.2.3
```

### Signing

The workflow reads four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | alias of the key inside the keystore |
| `KEY_PASSWORD` | key password |

A fork needs its own. Create a keystore once and keep it safe: an update only installs over an
older version if both are signed with the same key.

```sh
keytool -genkeypair -v -keystore release.jks -alias khrechner \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks        # macOS: base64 -i release.jks
```

To sign on your own machine, set `KH_KEYSTORE_FILE`, `KH_KEYSTORE_PASSWORD`, `KH_KEY_ALIAS` and
`KH_KEY_PASSWORD` in the environment and run `./gradlew assembleRelease`. Without them the release
build comes out unsigned. Keystores and `local.properties` are in `.gitignore`.

## Export format

An export is a JSON file with a `schemaVersion` field, currently `1`, holding the saved
calculations and the cache of scanned products. Import refuses files from a newer schema version
with a message instead of guessing. Raise the number in `BackupCodec` whenever the format changes
in a way older versions cannot read.

## AAPS

`AndroidAapsHandoff` copies the rounded amount to the clipboard and starts AAPS through its launch
intent. It looks for the package `info.nightscout.androidaps`, the application id of the "full"
flavour in the AAPS build file, which is also listed under `<queries>` in the manifest.

There is no deeper integration because stock AAPS offers none that keeps the user in control. Its
exported receiver takes glucose data and SMS. The source of 3.2.0.4, 3.3.2.1, 3.4.2.6 and 4.0.0
has nothing that pre-fills the carbs dialog, and the Garmin HTTP endpoint writes carbs straight to
the database without a confirmation.
