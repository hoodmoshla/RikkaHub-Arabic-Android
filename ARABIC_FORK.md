# RikkaHub Arabic

RikkaHub Arabic is an independent Android distribution based on `rikkahub/rikkahub`. It uses the stable application ID `me.rerere.rikkahub.arabic`, so it can be installed beside the official RikkaHub application. The application ID must not be changed after the first public release.

## Language architecture

Arabic is a real app language exposed in **Settings → Preferences → General → App language**. The selector includes system default, English, Simplified Chinese, Traditional Chinese, Japanese, Korean, Russian, and Arabic. The choice is persisted locally and applied before the activity receives its base context. Android's normal resource resolution then supplies `values-ar`, and `android:supportsRtl="true"` plus the locale configuration supplies right-to-left layout behavior. Selecting system default clears the app override and follows the device locale.

The complete Arabic resource is stored in `app/src/main/res/values-ar/strings.xml`. The maintenance helper `tools/translate_android_strings.py` can translate newly added base strings while checking keys and placeholders. Its local cache is ignored and must never be committed.

## Releases and updates

The `sync-and-release.yml` workflow fetches `rikkahub/rikkahub`, merges `upstream/master`, validates Arabic resource coverage, builds a signed APK, and creates a GitHub Release only when the merge and build succeed. A merge conflict, missing Arabic key, missing signing secret, or build failure stops the workflow without publishing an APK.

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
