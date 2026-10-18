# ezBookkeeping for Android

An Android app for your own ezBookkeeping server. The screens you use every day are native Android screens: overview, transactions, adding and editing transactions, accounts and settings. Less used pages (reports, budgets, categories, tags, family books and so on) open the server's mobile web pages inside the app, already signed in. Nothing is stored by a third party: the app only talks to the servers you add.

You can add several servers and switch between them in Settings, for example a home server and a family one. On first launch the app asks for the server to connect to.

## Install

1. Every push to `main` that changes `android/` builds the app with GitHub Actions (workflow "Build Android App", which can also be started by hand from the Actions tab).
2. On your phone, open the repository's **Releases** page, open **Android app (latest build)** and download `ezbookkeeping-android.apk`.
3. Open the downloaded file and allow your browser to install apps when Android asks.
4. Open ezBookkeeping, enter your server address (for example `https://money.example.com` or `http://192.168.1.10:8080`) and sign in. Use **Sign in on the web page** for single sign-on or to create an account.

To add or switch servers later, open **Settings → Manage servers**, or long-press the app icon and choose **Change server**.

Pull requests that change the app publish a test build to the **Android app (preview of an open pull request)** release, along with screenshots of each screen taken on an emulator (`screenshots.zip`).

## Signing, so updates install over the old version

Android only installs an update when it is signed with the same key as the installed app. Without a key the workflow signs each build with a temporary one, so you have to uninstall the old app first (your data stays on the server; you only sign in again).

To keep one key, create it once on your computer:

```sh
keytool -genkeypair -v -keystore ezbookkeeping.jks -alias ezbookkeeping -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ezbookkeeping.jks   # on macOS: base64 -i ezbookkeeping.jks
```

Then add these repository secrets under Settings > Secrets and variables > Actions: `ANDROID_KEYSTORE_BASE64` (the base64 text), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`ezbookkeeping`) and `ANDROID_KEY_PASSWORD`. Keep the `.jks` file somewhere safe and never commit it.

## Screenshots in CI

The `screenshots` job in `.github/workflows/build-android.yml` starts an ezBookkeeping server, fills it with demo data (`scripts/seed-test-server.sh`) and runs `ScreenshotTest` on an emulator, which signs in and walks through the main screens.

## Build locally

With the Android SDK installed (Android Studio is the easiest way), run `./gradlew assembleRelease` in this folder. The app is at `app/build/outputs/apk/release/app-release.apk`.
