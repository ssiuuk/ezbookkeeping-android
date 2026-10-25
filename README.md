# ezBookkeeping for Android

An Android app for your own ezBookkeeping server. The screens you use every day are native Android screens: overview, transactions, adding and editing transactions, accounts and settings. Less used pages (reports, budgets, categories, tags, family books and so on) open the server's mobile web pages inside the app, already signed in. Nothing is stored by a third party: the app only talks to the servers you add.

You can add several servers and switch between them in Settings, for example a home server and a family one. On first launch the app asks for the server to connect to.

The app speaks the same languages as the server's web app. It follows the phone's language, or the one picked in **Settings → Language** (on Android 13 and later also in Android's own app language setting). Words the web app already has are copied from the server's `src/locales`; the rest were translated for the app.

The **Spending** home screen widget can be from one row by two columns up to four by four. One row shows the period's spending and an add button; two rows add income and two other periods; three and four rows add the latest transactions and buttons for income, transfers and Wallet purchases to review. Its settings (shown when it is added, or by touching and holding it on Android 12 and later) choose the period, the book and whether amounts are hidden. It refreshes every 30 minutes, whenever something changes in the app, and from its refresh button.

## Install

1. Every push to `main` builds the app with GitHub Actions (workflow "Build Android App", which can also be started by hand from the Actions tab).
2. On your phone, open the repository's **Releases** page, open **Android app (latest build)** and download `ezbookkeeping-android.apk`.
3. Open the downloaded file and allow your browser to install apps when Android asks.
4. Open ezBookkeeping, enter your server address (for example `https://money.example.com` or `http://192.168.1.10:8080`) and sign in. Use **Sign in on the web page** for single sign-on or to create an account.

To add or switch servers later, open **Settings → Manage servers**, or long-press the app icon and choose **Change server**.

Publishing a release (for example with the tag `v1.2.0`) builds the app from that tag, names the version after it (`1.2.0`) and attaches `ezbookkeeping-android.apk` to the release. For a release published before this, run the **Build Android App** workflow by hand with its tag as `release_tag`.

Pull requests that change the app publish a test build to the **Android app (preview of an open pull request)** release, along with screenshots of each screen taken on an emulator (`screenshots.zip`).

## Signing, so updates install over the old version

Android only installs an update when it is signed with the same key as the installed app. Without a key the workflow signs each build with a temporary one, so you have to uninstall the old app first (your data stays on the server; you only sign in again).

To keep one key, create it once on your computer:

```sh
keytool -genkeypair -v -keystore ezbookkeeping.jks -alias ezbookkeeping -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ezbookkeeping.jks   # on macOS: base64 -i ezbookkeeping.jks
```

Then add these repository secrets under Settings > Secrets and variables > Actions: `ANDROID_KEYSTORE_BASE64` (the base64 text), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`ezbookkeeping`) and `ANDROID_KEY_PASSWORD`. Keep the `.jks` file somewhere safe and never commit it.

## Testers through Firebase App Distribution

Publishing a release also sends the app to Firebase App Distribution, so testers get an email and install updates from the Firebase App Tester app. To turn it on:

1. In the [Firebase console](https://console.firebase.google.com), create a project and add an Android app with the package name `com.ssiuuk.ezbookkeeping`. Copy its App ID (`1:…:android:…`, under Project settings).
2. Under Release & Monitor > App Distribution, click Get started, then under Testers & Groups create a group with the alias `testers` and add the testers' email addresses.
3. In Google Cloud console > IAM & Admin > Service accounts (same project), create a service account with the **Firebase App Distribution Admin** role and download a JSON key for it.
4. In this repository under Settings > Secrets and variables > Actions, add the secret `FIREBASE_SERVICE_ACCOUNT` (the whole JSON file) and the variable `FIREBASE_APP_ID`. To send to other groups, add the variable `FIREBASE_TESTER_GROUPS` with their aliases separated by commas.

The release notes become the notes testers see. Until both settings exist the step is skipped.

## The server

The app talks to an ezBookkeeping server over its web API. Family books, projects and merchants need the server from [ssiuuk/ezbookkeeping](https://github.com/ssiuuk/ezbookkeeping); with other servers those fields are hidden.

## Screenshots in CI

The `screenshots` job in `.github/workflows/build.yml` builds the server named in `SERVER_REF` from ssiuuk/ezbookkeeping, fills it with demo data (`scripts/seed-test-server.sh`) and runs `ScreenshotTest` on an emulator, which signs in and walks through the main screens. It also runs nightly and whenever the server's `main` changes, and opens an issue if the app no longer works with the latest server.

## Build locally

With the Android SDK installed (Android Studio is the easiest way), run `./gradlew assembleRelease`. The app is at `app/build/outputs/apk/release/app-release.apk`.
