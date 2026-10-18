# Working on this repository

This is the Android app for Sam's ezBookkeeping server, which lives in a separate repository:
**https://github.com/ssiuuk/ezbookkeeping** (a fork of mayswind/ezbookkeeping with family books,
projects, merchants and other additions). The app shares no code with the server; it only calls the
server's HTTP API (`/api/v1/...`), the same one the web app uses.

## What links the two repositories

- `SERVER_REF` names the server branch, tag or commit this version of the app is tested against
  (normally `main`). The `screenshots` job in `.github/workflows/build.yml` checks that server out,
  builds it, seeds it with `scripts/seed-test-server.sh` and runs `ScreenshotTest` on an emulator.
- The server repository sends a `server-updated` repository dispatch when its `main` changes, and the
  job also runs nightly. When either run fails, it opens (or comments on) the issue
  "The app test fails against the latest server".
- The job can be started by hand from the Actions tab with any `server_ref`.

## Changes that need both sides

When a feature needs a server change (a new API field or endpoint) as well as an app change:

1. Make the server change in ssiuuk/ezbookkeeping on its own branch and open its pull request there.
2. In this repository, set `SERVER_REF` to that server branch in the app's pull request, so CI tests
   the two together.
3. After the server pull request is merged, set `SERVER_REF` back to `main` before merging the app one.

Keep the app working against servers without the fork's additions where it is cheap to do so: the
app hides books, projects and merchants when those endpoints answer 404.

When an API the app calls changes on the server (request or response fields in `pkg/models`, routes
in `cmd/webserver.go`), update `app/src/main/java/com/ssiuuk/ezbookkeeping/data/` here to match.

## Building and checking

- Builds only run in GitHub Actions in the cloud sessions used so far (Google's Maven repository is
  blocked there). A quick local check is `javac` against an `android-all` jar from Maven Central;
  errors about AndroidX classes are expected there and only matter if they are not "cannot find symbol".
- Pull requests from this repository publish a test APK and `screenshots.zip` to the
  `android-preview` release; `main` publishes `ezbookkeeping-android.apk` to `android-latest`.
