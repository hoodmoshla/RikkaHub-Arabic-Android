# RikkaHub Arabic

RikkaHub Arabic is an independent Android distribution based on `rikkahub/rikkahub`. It uses the stable application ID `me.rerere.rikkahub.arabic.dev`, so it can be installed side-by-side beside the original build and official RikkaHub application without conflict. The application ID serves as the permanent baseline for all future development.

## Language architecture

Arabic is a real app language exposed in **Settings → Preferences → General → App language**. The selector includes system default, English, Simplified Chinese, Traditional Chinese, Japanese, Korean, Russian, and Arabic. The choice is persisted locally and applied before the activity receives its base context. Android's normal resource resolution then supplies `values-ar`, and `android:supportsRtl="true"` plus the locale configuration supplies right-to-left layout behavior. Selecting system default clears the app override and follows the device locale.

The complete Arabic resource is stored in `app/src/main/res/values-ar/strings.xml`. The maintenance helper `tools/translate_android_strings.py` can translate newly added base strings while checking keys and placeholders. Its local cache is ignored and must never be committed.

## Releases and updates

The `sync-and-release.yml` workflow checks out Git submodules recursively, fetches `rikkahub/rikkahub`, merges `upstream/master`, validates Arabic resource coverage, builds a signed APK, and creates a GitHub Release only when the merge and build succeed. A merge conflict, missing Arabic key, missing signing secret, or build failure stops the workflow without publishing an APK. Manual `workflow_dispatch` always performs a build; scheduled runs build only when upstream changed.

The recursive submodule checkout is required. The earlier `surfaceContainerLowest` and `primaryFixed` compiler errors were not caused by Arabic code or dependency-version changes: the `material3` module imports `DynamicScheme` from the official `material-color-utilities` Git submodule, and an ordinary checkout had left that submodule empty. Initializing the pinned upstream submodule commit `6fd88eb3e95ba1d457842e2a2bf847d06b3a018a` restored the API and produced a successful debug build.

The Arabic distribution intentionally removes Firebase Analytics, Firebase Crashlytics, the Google Services plugin, and their runtime dependencies. The official project used Firebase only for telemetry events (`ai_send_message`, `ai_edit_message`, `ai_regenerate_at_message`, `ai_tool_approval`, and `ai_tool_answer`) and crash reporting; none of those services are required by chat, model providers, tools, storage, settings, language selection, RTL, or updates. Release builds therefore do not require a Firebase project or `google-services.json`.

Before the first manual release, add these four encrypted repository secrets under **Settings → Secrets and variables → Actions**: `ARABIC_DEV_KEYSTORE_B64`, `ARABIC_DEV_STORE_PASSWORD`, `ARABIC_DEV_KEY_ALIAS`, and `ARABIC_DEV_KEY_PASSWORD`. The workflow intentionally fails with an explicit missing-signing-secret message rather than printing or exposing secret values.

The application update checker reads the latest release from this repository's GitHub Releases API. APK assets are offered through the existing update card and downloaded with Android DownloadManager. On completion, the platform package installer is opened; Android's unknown-source permission remains under the user's control.

## Signing

Release signing uses four repository secrets, which must be configured before the first release:

- `ARABIC_DEV_KEYSTORE_B64`
- `ARABIC_DEV_STORE_PASSWORD`
- `ARABIC_DEV_KEY_ALIAS`
- `ARABIC_DEV_KEY_PASSWORD`

The keystore itself (`rikkahub-arabic-dev.keystore`, alias `rikkahub-arabic-dev`) is never committed. Losing this keystore prevents Android updates to already-installed RikkaHub Arabic versions, so maintain an offline encrypted backup in addition to GitHub's encrypted secrets.

## Default AI Providers and Models

RikkaHub Arabic includes the official built-in free provider **RikkaHub** enabled by default with its `Auto` chat model (`b7055fb4-39f9-4042-a88a-0d80ed76cf08`). Upon clean installation, `isNotConfigured()` evaluates to `false`, and the app immediately selects `Auto` without showing the unconfigured warning ("يرجى تكوين API والنموذج" / "يرجى اختيار نموذج أولاً"). Users can still navigate to Settings → Providers, add personal API keys (OpenAI, Anthropic, Google Gemini, Ollama, DeepSeek, etc.), fetch their available model lists, and switch active chat models at any time.

## Upstream maintenance and Conflict Guard

The release workflow (`sync-and-release.yml`) contains a strict Sensitive Merge Conflict Guard. If an upstream merge conflicts with Arabic localization (`values-ar/`), RTL/app locale switching (`AppLocale.kt`, `SettingPreferencesGeneralPage.kt`), default provider architecture (`DefaultProviders.kt`, `PreferencesStore.kt`), `app/build.gradle.kts` (which pins the `me.rerere.rikkahub.arabic.dev` package identity), or `UpdateChecker.kt`, the workflow immediately aborts the merge (`git merge --abort`), reports the conflicting files, and terminates without generating or publishing an invalid APK.

