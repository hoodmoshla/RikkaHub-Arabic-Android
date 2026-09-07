# RikkaHub Arabic

RikkaHub Arabic is an independent Android distribution based on `rikkahub/rikkahub`. It uses the stable application ID `me.rerere.rikkahub.arabic`, so it can be installed beside the official RikkaHub application. The application ID must not be changed after the first public release.

## Language architecture

Arabic is a real app language exposed in **Settings → Preferences → General → App language**. The selector includes system default, English, Simplified Chinese, Traditional Chinese, Japanese, Korean, Russian, and Arabic. The choice is persisted locally and applied before the activity receives its base context. Android's normal resource resolution then supplies `values-ar`, and `android:supportsRtl="true"` plus the locale configuration supplies right-to-left layout behavior. Selecting system default clears the app override and follows the device locale.

The complete Arabic resource is stored in `app/src/main/res/values-ar/strings.xml`. The maintenance helper `tools/translate_android_strings.py` can translate newly added base strings while checking keys and placeholders. Its local cache is ignored and must never be committed.

## Releases and updates

The `sync-and-release.yml` workflow checks out Git submodules recursively, fetches `rikkahub/rikkahub`, merges `upstream/master`, validates Arabic resource coverage, builds a signed APK, and creates a GitHub Release only when the merge and build succeed. A merge conflict, missing Arabic key, missing signing secret, missing Google Services secret, or build failure stops the workflow without publishing an APK. Manual `workflow_dispatch` always performs a build; scheduled runs build only when upstream changed.

The recursive submodule checkout is required. The earlier `surfaceContainerLowest` and `primaryFixed` compiler errors were not caused by Arabic code or dependency-version changes: the `material3` module imports `DynamicScheme` from the official `material-color-utilities` Git submodule, and an ordinary checkout had left that submodule empty. Initializing the pinned upstream submodule commit `6fd88eb3e95ba1d457842e2a2bf847d06b3a018a` restored the API and produced a successful debug build.

The app applies Google Services and Firebase Analytics/Crashlytics plugins, so a release build requires a Firebase Android configuration for the independent application ID. From the Firebase Console, create or select a project, add an Android app with package name `me.rerere.rikkahub.arabic`, download its `google-services.json`, and add the file contents manually to the repository Actions secret named `GOOGLE_SERVICES_JSON`. Do not commit the file. The workflow checks that this secret is non-empty, writes it only for the build, and removes it in an `always` cleanup step.

Before the first manual release, add these encrypted repository secrets under **Settings → Secrets and variables → Actions**: `ARABIC_RELEASE_KEYSTORE_B64`, `ARABIC_RELEASE_STORE_PASSWORD`, `ARABIC_RELEASE_KEY_ALIAS`, `ARABIC_RELEASE_KEY_PASSWORD`, and `GOOGLE_SERVICES_JSON`. The workflow intentionally fails with an explicit missing-secret message rather than printing or exposing secret values.

The application update checker reads the latest release from this repository's GitHub Releases API. APK assets are offered through the existing update card and downloaded with Android DownloadManager. On completion, the platform package installer is opened; Android's unknown-source permission remains under the user's control.

## Signing

Release signing uses four repository secrets, which must be configured before the first release:

- `ARABIC_RELEASE_KEYSTORE_B64`
- `ARABIC_RELEASE_STORE_PASSWORD`
- `ARABIC_RELEASE_KEY_ALIAS`
- `ARABIC_RELEASE_KEY_PASSWORD`

The keystore itself is never committed. Losing this keystore prevents Android updates to already-installed RikkaHub Arabic versions, so maintain an offline encrypted backup in addition to GitHub's encrypted secrets.

## Upstream maintenance

When the official project changes its settings or resource architecture, the scheduled workflow intentionally fails if the merge conflicts or Arabic coverage is incomplete. New Arabic translations should be added to `values-ar/strings.xml` before rerunning the release workflow.
