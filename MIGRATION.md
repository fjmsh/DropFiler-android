# 6.0 migration summary

This document records what changed when Phone Saver was modernised with
[Miuix XML](https://github.com/fjmsh/miuix-xml), what was deliberately kept, how it was
verified, and the known limitations.

> The app was later renamed **DropFiler** and moved to the application ID
> `fjm.sh.dropfiler`, and "Open with" support was added. See
> [DropFiler rename and "Open with"](#dropfiler-rename-and-open-with) at the end. The 6.0
> sections below are kept as a historical record (the `fjmd3v` account is now `fjmsh`).
>
> **7.0 replaced the storage model** (targetSdk 28 → 36, MediaStore Downloads + Storage
> Access Framework). The "targetSdk 28" non-negotiable below no longer applies. See
> [7.0: MediaStore + SAF storage](#70-mediastore--saf-storage) at the end.

## Non-negotiables (kept)

| Requirement | Status |
| --- | --- |
| `targetSdkVersion 28` | Kept. Verified in the built APK with `aapt2 dump badging` (`targetSdkVersion:'28'`). |
| `applicationId link.standen.michael.phonesaver` | Kept in 6.0 (verified in APK). Changed later for DropFiler, see below. |
| No Jetpack Compose | Views/XML only. |
| Storage architecture | Unchanged `java.io` + `DownloadManager` + SAF "location select". No `MANAGE_EXTERNAL_STORAGE`. |
| Destination data | Same `FOLDER_STORE` JSON file and format. The list order is the user's order (see "Reordering" below). |
| Preferences | Same keys, value formats and defaults. Nothing is written just by opening Settings. |
| Share intents | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with `*/*`, unchanged handling, automatic save with one destination. |

## Build system

| | Before | After |
| --- | --- | --- |
| Android Gradle Plugin | 3.4.2 | 8.13.2 |
| Gradle wrapper | 5.1.1 | 8.14.3 |
| Kotlin | 1.3.30 | 2.2.21 |
| Support library 28.0.0 | `com.android.support:*` | AndroidX: appcompat 1.7.1, preference 1.2.1, recyclerview 1.4.0, cardview 1.0.0 |
| Repositories | JCenter | `google()`, Maven Central |
| compileSdk / minSdk / targetSdk | 28 / 15 / 28 | 36 / **21** / **28** |
| versionCode / versionName | 36 / 5.2.0 | 37 / 6.0.0 |

Dependency changes:

* **Removed** `com.amulyakhare:com.amulyakhare.textdrawable` — only published on JCenter,
  which no longer works. Replaced by `util/DirectShareIcon.kt` (same round letter icon).
* **Removed** `constraint-layout`, `design`, `support-vector-drawable`, `support-v4` — no
  longer used by the new layouts / covered by AppCompat.
* **Added** `com.android.tools:desugar_jdk_libs` (core library desugaring) — required by
  Miuix XML (`java.util.stream`) below API 24.
* **Kept** Gson 2.8.2 and ckChangeLog 1.2.2 (both on Maven Central, no reason to change).
* **Added (tests only)** JUnit 4, Robolectric 4.16.1, AndroidX Test.

## Why minSdk is 21 (not 15, not 29)

* 15 → 21: current AndroidX (appcompat 1.7.x, recyclerview 1.4) requires API 21.
* Miuix XML declared `minSdk 29`. Its sources were audited by running Android lint with
  `NewApi`/`InlinedApi` at `minSdk 21`. Only these APIs above 21 were used, and they were
  fixed in the library (branch `ccr-4958b7dd-tn8be5` of fjmd3v/miuix-xml):

  | API | Level | Fix |
  | --- | --- | --- |
  | `Context#getColor` | 23 | `ContextCompat.getColor` |
  | `Parcel#readBoolean/writeBoolean` | 29 | int encoding |
  | `Activity#registerActivityLifecycleCallbacks` | 29 | `Application` callbacks filtered to the activity |
  | `LayerDrawable#setDrawable` | 23 | `setDrawableByLayerId` fallback |
  | `GradientDrawable#getColor` | 24 | version check, default color |
  | `SeekBar#setMin/getMin` | 26 | `setMinCompat` (minimum fixed at 0 below 26) |
  | `HapticFeedbackConstants.CONTEXT_CLICK` | 23 | `VIRTUAL_KEY` below 23 |
  | `ImageView#setImageIcon`, `autofill()` | 23 / 26 | `@RequiresApi` (framework only calls autofill on 26+) |
  | `java.util.stream`, `Optional`, `Map#getOrDefault` … | 24 | core library desugaring |
  | Vectors with `fillType` | 24 | `vectorDrawables.useSupportLibrary` |

  After the changes lint reports no `NewApi` issues at minSdk 21.

## Miuix XML integration

Originally added as a git submodule at `miuix-xml/` (Gradle project `:HiMiuix`); the source
is now vendored in this repository, see
[Miuix XML vendored as source](#miuix-xml-vendored-as-source). Other library fixes
made while integrating (all on the same library branch):

* `MiuixAppBar`: no `NullPointerException` when a nested scroll happens before the
  scroll target is registered.
* `PropUtils`: no crash in the static initialiser when `android.os.SystemProperties` cannot
  be reflected (this crashed every `MiuixAlertDialog`).
* `MiuixStateView` (switch / checkbox / radio rows): reports checkable state and widget
  class to accessibility services; the inner indicator is hidden from TalkBack.
* `MiuiSuperBlur.setBlurAllowed()` / `isBlurAvailable()`: lets the app disable blur.
* `WindowInsetsHelper`: still runs the DecorView default inset handling.
* Dialog buttons are no longer all-caps.
* Drop-down and list dialog options respond to real taps (rows forwarded touch events to a
  small check box with the wrong coordinates, so any finger movement cancelled the tap).

App-side handling of library behaviour:

* **Preference value format.** `MiuixDropDownPreference` stores the entry *index*; the old
  `ListPreference` stored the entry *value* ("Overwrite", "Debug", …) and the saving code
  reads that. `util/SettingsDataStore.kt` converts in both directions so existing settings
  are read and written in the original format.
* **Chinese-only library strings** (dialog buttons, color picker) are overridden in the
  app, with translations for the app's locales.
* **Accent colors** of library widgets are redirected (API 31+) to `?attr/fsColorAccent`,
  so dynamic colors also apply to switches, dialogs and lists.

## UI changes

* Main screen: large collapsing Miuix title, grouped rounded destination cards with folder
  icons, folder name + full path, delete button with confirmation dialog, extended
  "Add destination" button, empty-state card, rounded overflow menu.
* Settings: grouped Miuix preference cards (Saving, Links, Appearance, Advanced, About),
  new **Theme** (follow system / light / dark) and **Dynamic colors** (Android 12+ only;
  disabled with an explanation elsewhere).
* Share target: a bottom sheet over the sharing app instead of a full-screen list; shows
  what is being shared; tap outside or Cancel to close. One destination still saves
  immediately without showing a choice.
* Folder picker: Miuix list with current folder card, parent folder row, sorted folders,
  "Save here" / read-only button.
* About and Change log: Miuix screens/dialogs. ckChangeLog still tracks the last seen
  version, so "What's New" still appears once after upgrading.
* The "support the developer" pop up and its "Hide support pop up" setting were removed.
  A stored `hide_support` value from older versions is simply ignored. The overflow menu
  item is replaced by a GitHub item ("fjmd3v") that opens https://github.com/fjmd3v.
* Dark theme backgrounds use #131313 instead of pure black.
* Reordering: destinations on the main screen can be reordered by dragging the handle on
  the right of each row (or long-pressing the row). Screen reader / switch access users get
  "Move up" / "Move down" actions. The order is saved in the existing `FOLDER_STORE` list,
  so the share sheet and Direct Share use it. "Choose location when saving" stays first.
  New destinations are now added at the end instead of re-sorting the list alphabetically
  (which would undo a custom order); existing lists keep their current order.
* Folder picker: "New folder" button (left of "Save here") creates a folder in the current
  folder and opens it. Names that are empty, contain "/", are "." / "..", or already exist
  are rejected in the dialog. Disabled in read-only folders. Uses System Accent 3 (tonal)
  when dynamic colors are on, the Miuix blue tonal style otherwise.
* Missing destinations: saving to a destination folder that was deleted or moved used to
  crash (`IOException` from `File.createNewFile`). The share sheet now checks the folder
  first and, if it is gone, explains it and lets the user pick another destination or the
  system file picker. Missing destinations are shown disabled in the list. Direct Share
  targets pointing at a missing folder behave the same way.
* Save result notifications: after a share is saved (or fails) a notification shows the
  file name and folder, or the reason it failed (channel "Save results"). URL downloads
  report when DownloadManager finishes. Because the app targets API 28, Android 13+ asks
  for the notification permission automatically the first time the app is opened; if it is
  denied only the toasts are shown.

## Manifest

* `package` attribute moved to `namespace` (required by AGP 8).
* `android:exported` declared everywhere:
  * exported: launcher activity, `SaverActivity` (share target), `DownloadBroadcastReceiver`
    (DOWNLOAD_COMPLETE comes from the DownloadManager provider, another package),
    `LocationChooserTargetService` (protected by `BIND_CHOOSER_TARGET_SERVICE`).
  * not exported: `FolderSelectActivity`, `SettingsActivity`, `CreditsActivity`.
* Added sharing shortcuts (`res/xml/shortcuts.xml`) for Direct Share on Android 10+.

## Verification performed

Environment: Linux container, JDK 21, Android SDK platform 36 / build-tools 36.0.0. No
emulator could be run (no KVM), so **no test was run on a physical or emulated device**.

| Check | Result |
| --- | --- |
| Gradle dependency resolution | OK |
| `assembleDebug`, `assembleRelease` | OK |
| `lintDebug` | 0 errors, 9 warnings (pre-existing: deprecated ChooserTargetService, `ObsoleteSdkInt` checks in unchanged saving code, `scheduleAtFixedRate` in DebugLogger) |
| Miuix XML lint `NewApi`/`InlinedApi` at minSdk 21 | No issues |
| APK `aapt2 dump badging` | applicationId unchanged, versionCode 37, versionName 6.0.0, minSdk 21, **targetSdk 28** |
| Robolectric unit tests | 418 run, 0 failures (2 opt-in screenshot tests skipped) on API 23, 26, 28, 29, 30, 31, 33, 34, 35, 36 |
| Visual review | Screens rendered with Robolectric native graphics (light + dark) and reviewed; found and fixed a sheet layout bug |

Robolectric coverage (see `app/src/test`):

| Scenario from the test plan | Covered |
| --- | --- |
| Fresh installation | Defaults, empty state, nothing written on first Settings open |
| Upgrade installation / existing settings | 5.2.0-style SharedPreferences read unchanged, not rewritten |
| Existing destinations | Existing `FOLDER_STORE` shown in stored order |
| Adding / removing destinations | Folder picker result (sorted, no duplicates), remove with confirmation |
| Sharing images / videos / documents / text / multiple files | Files written with correct names and content |
| Duplicate filename / overwrite | Overwrite, do not save, append number |
| Automatic saving | Single destination saves without the selector |
| Direct Share | `DIRECT_SHARE_FOLDER` extra and sharing shortcut id |
| Dark / light mode, dynamic colors | Night qualifier inflation, theme mapping, Monet accent on 31+, ignored on 28 |
| Accessibility | Miuix switch row reports checkable/checked/Switch class |

Not covered by automated tests (requires devices):

* Runtime storage permission prompts and their enforcement on Android 10 – 16.
* Real `DownloadManager` downloads, MediaScanner registration, SAF picker UI
  ("location select").
* System share sheet presentation of Direct Share targets (ChooserTargetService on 6 – 9,
  sharing shortcuts on 10+).
* Xiaomi/HyperOS blur on real hardware; OEM specific storage restrictions.
* API 21 – 22: Robolectric 4.16 no longer supports these levels; they were only checked by
  lint (`NewApi`).
* TalkBack, switch access and font scaling on real devices (layouts use `sp`, 48dp touch
  targets and focus indicators, but this was not manually tested).

## Known limitations

* targetSdk 28 means the app cannot be published as a *new update* on Google Play under
  current target API policy; this is an intentional trade-off to keep legacy storage.
* On Android 11+, `Android/data`, `Android/obb` and some removable storage roots are not
  writable regardless of target SDK; use "location select" for SD cards.
* "Follow system" theme has no effect before Android 10 (no system dark theme); the app
  uses the light theme there and says so in Settings.
* Dynamic colors require Android 12.
* Xiaomi blur is only enabled when the platform reports blur as enabled (Android 12+);
  on older HyperOS/MIUI builds the app bar uses a solid surface color.
* Sharing shortcuts may also be shown by some launchers when long-pressing the app icon;
  launching one opens the app.
* Miuix XML seek bars cannot have a minimum other than 0 below API 26 (not used here).

## DropFiler rename and "Open with"

The app is now **DropFiler**, a separately branded fork of Phone Saver by Michael Standen
(ScreamingHawk). Original attribution and the MIT `LICENSE` are kept; Miuix XML (LGPL 2.1)
is credited unchanged.

| | Before | After |
| --- | --- | --- |
| Display name | Phone Saver | DropFiler (all locales) |
| `applicationId` / `namespace` | `fjm.sh.filesaver` (earlier `link.standen.michael.phonesaver`) | `fjm.sh.dropfiler` |
| Kotlin package | `fjm.sh.filesaver.*` | `fjm.sh.dropfiler.*` (same subpackages) |
| Application class | `FileSaverApplication` | `DropFilerApplication` |
| Settings fragment | `SettingsActivity$PhoneSaverPreferenceFragment` | `SettingsActivity$DropFilerPreferenceFragment` |
| Sharing shortcut category | `fjm.sh.filesaver.category.SAVE_TO_FOLDER` | `fjm.sh.dropfiler.category.SAVE_TO_FOLDER` |
| GitHub menu item | fjmd3v | fjmsh (https://github.com/fjmsh) |
| "Unsupported content" feature request link | ScreamingHawk/phone-saver issues | this repository's issues (`@string/issues_new_url`) |
| Miuix XML submodule URL | github.com/fjmd3v/miuix-xml | github.com/fjmsh/miuix-xml (same pinned commit) |
| Gradle root project | `phone-saver` | `DropFiler-Android` |
| CI debug APK name | `phone-saver-<version>-debug-<run>.apk` | `dropfiler-<version>-debug-<run>.apk` |

Unchanged: `minSdk 21`, **`targetSdk 28`**, `compileSdk 36`, versionCode 37 /
versionName 6.0.0, AGP/Gradle/Kotlin and all dependencies, signing configuration, the
storage implementation, preference keys and the `FOLDER_STORE` format. Internal style
resource names (`Theme.FileSaver`, `Widget.FileSaver.*`) are not user visible and were
kept to avoid churn.

Because the application ID changed, DropFiler installs as a **new app** next to Phone
Saver; destinations and settings are not migrated.

### "Open with" (`ACTION_VIEW`)

`SaverActivity` has a separate `ACTION_VIEW` filter (`DEFAULT` category, `content` and
`file` schemes, `*/*`). No `BROWSABLE`, no http(s), no `EDIT` / `GET_CONTENT` /
`OPEN_DOCUMENT`. The SEND / SEND_MULTIPLE filters, Direct Share and shortcuts are unchanged.

The file is taken from the intent data. Before showing the destination sheet, DropFiler
checks on a background thread that the URI can be opened for reading (missing URI, other
schemes, revoked grants, missing files, folders and failing providers end with a "cannot
read this file" toast and a failure notification). The MIME type comes from the intent,
then `ContentResolver.getType()`, then the name's extension, otherwise
`application/octet-stream`. Then the file goes through the existing `getFilename` /
`saveUri` path, so filename sanitisation, duplicate handling, "location select" and the
result notification are shared with Share. The source is only opened for reading.

Also hardened for both Share and Open with: provider `query()` failures fall back to the
URI name, and a `SecurityException` while copying is reported as a failed save instead of
crashing.

### Verification

| Check | Result |
| --- | --- |
| `assembleDebug`, `lintDebug` | OK; lint 0 errors, the same 9 pre-existing warnings |
| APK `aapt2 dump badging` | `package: name='fjm.sh.dropfiler'`, versionCode 37, versionName 6.0.0, minSdk 21, **targetSdk 28**, label DropFiler |
| Merged manifest | All components under `fjm.sh.dropfiler.*`; VIEW filter present; SEND filters unchanged |
| Robolectric | 523 run, 0 failures at the time of the rename (565 after the UI follow-ups below) |

`OpenWithTest` covers implicit `ACTION_VIEW` resolution for `content://` with
`application/pdf`, `image/jpeg`, `video/mp4`, `application/zip`, `application/octet-stream`,
`text/plain`, APK and a non-standard type, plus `file://`. It checks that web links and
EDIT/GET_CONTENT/OPEN_DOCUMENT do not resolve and that SEND still resolves. It also covers
byte-exact copies (including 6 MB), the destination sheet and its subtitle, cancel, missing
MIME type / extension / display name, repeated invocations with duplicate handling, and
failures (null URI, revoked grant, missing file, failing provider, folder, explicit web URL).

**Not tested on a device or emulator** (no KVM in the build environment): the real
"Open with" dialogs of file managers (including the system Files app), real URI grant
behaviour, and coexistence with an installed Phone Saver.

### UI follow-ups

* Overflow menu: "Change Log" is now "Changelog" and "Credits" is now "About". Every item
  (Settings, GitHub, Changelog, About) has a rounded-square icon tile like the list rows
  (`fs_menu_ic_*`: 32dp tile, 10dp corners, 20dp Material/Octicons glyph on
  `?attr/fsColorAccentContainer`).
* Menu width: AppCompat gives popup menu items a 196dp minimum width, which left a lot of
  empty space. `res/layout/abc_popup_menu_item_layout.xml` is a copy of the AppCompat 1.7.1
  layout with a 157dp minimum (-20%). All English items fit (widest ~142dp); it is only a
  minimum, so longer translations still widen the menu. Re-check the copy when upgrading
  AppCompat.
* About: the line below the version reads "Save anywhere. Organize effortlessly." followed
  by "A modern Android file-saving utility, based on the app "Phone Saver" by Michael
  Standen", linking to https://github.com/ScreamingHawk (translated in fr/ja/zh-TW).
* Main screen and share sheet: muted hairline dividers between destination rows
  (`@color/fs_row_divider`, from the title text to the row end), and the same dividers in
  the folder picker (`CardListAdapter(showDividers = true)`).
* "Save Destinations" / "Add Destination" (English title case).
* Fix: with dynamic colors enabled, the Settings screen kept the Miuix blue in **dark
  mode**. The accent overrides of Miuix colors lived in `color-v31/`, but the library
  defines the same colors in `values-night/`, and the night qualifier takes precedence over
  the API level. The overrides are now also in `color-night-v31/`. Covered by
  `AppearanceTest.dynamicColors_enabledInSettings_recolorSettingsScreen[_dark]`, which turns
  the setting on from the Settings screen and checks the switch color after the recreate.

### Built-in "DropFiler" destination

Before, sharing with no destinations ended with "Error loading save locations" (no stored
list yet) or "Please open the app to add save locations".

* `/DropFiler` (below the legacy storage root) is a built-in destination:
  `LocationHelper.DEFAULT_FOLDER`. It is listed first on the main screen ("Default
  location", no delete button or drag handle), in the share sheet, for Open with and in
  Direct Share. It is created when it is first needed (`LocationHelper.prepareDestination`),
  also again if it was deleted. No other destination is ever created automatically.
* It is **not** stored in `FOLDER_STORE`, which still holds only the user's destinations in
  the same format. Choosing `/DropFiler` in the folder picker does not add a duplicate.
* New setting (Saving): "Hide "DropFiler" save location" (`hide_default_folder`, default
  off). When on, the folder is hidden while at least one other destination exists. With no
  other destination it is still shown and used. Toggling it republishes Direct Share targets.
* `LocationHelper.destinations()` is the single source for the effective list.
* Behaviour change: with the default visible, one user destination plus DropFiler means two
  choices, so the share sheet asks instead of saving automatically. Turning on the hide
  setting restores automatic saving to a single destination.
* The main screen's empty state ("Welcome to DropFiler!") and the two "no locations" toasts
  were removed, since there is always a destination now.
* Tests: `DefaultFolderTest`. The other share and main-screen tests run with the default
  folder hidden, so they keep testing the user's destinations as before.

### "Save Imgur images" removed

The Links setting "Save Imgur images" (`special_imgur`, off by default) and the feature it
controlled (`ImgurHelper`: saving the first image of a shared Imgur page) were removed.
Imgur links are now saved like any other link. A stored `special_imgur` value is ignored
and left untouched, like the earlier `hide_support`.

## 7.0: MediaStore + SAF storage

### What changed

| | 6.x | 7.0 |
| --- | --- | --- |
| `targetSdk` | 28 (legacy storage) | **36** |
| `minSdk` | 21 | 21 (unchanged) |
| Built-in destination | `/DropFiler` (`java.io`, created on demand) | `Internal storage/Download` — `MediaStore.Downloads`, `RELATIVE_PATH=Download/`, pending → published (API 29+); public Download folder with `WRITE_EXTERNAL_STORAGE maxSdkVersion=28` (API 21 – 28) |
| User destinations | Paths chosen in the app's own folder browser (`FolderSelectActivity`) | `ACTION_OPEN_DOCUMENT_TREE` + persisted grant, written with `DocumentsContract` |
| Destination store | `FOLDER_STORE`: JSON list of paths | `DESTINATIONS.json`: version 2, records with stable id, kind, label, tree URI, legacy path; `AtomicFile` |
| Destination identity (sheet, Direct Share, shortcuts) | Path string / list position | Stable id (`system:downloads`, `saf:<uuid>`, `legacy:<uuid-from-path>`); shortcut ids `dest:<id>` |
| Links | `DownloadManager.setDestinationInExternalPublicDir` + `DOWNLOAD_COMPLETE` receiver | `HttpURLConnection` streamed into the chosen destination; receiver removed |
| Saving code | `SaverActivity`, `HandleSingleTextTask`, `SaveFromUrlTask`, `LocationSelectTask` (separate write paths) | `save/` package: `IncomingContentParser`, `Filenames`, `SaveCoordinator`, writers `MediaStoreDownloadsWriter`, `LegacyDownloadsWriter`, `SafTreeWriter`, `SingleDocumentWriter`, `UrlDownloader` |
| Permissions | `READ/WRITE_EXTERNAL_STORAGE` (all versions), runtime request incl. `INTERNET` | Storage only `maxSdkVersion=28`; `INTERNET` normal; `POST_NOTIFICATIONS` requested once on Android 13+ |

Kept: application ID, namespace, branding, icons, Miuix XML Views UI (cards, sheet, drag
reordering and accessibility actions), all preference keys and stored values, the
hide-default setting (now for Download), duplicate policies, Save as, Share/Open with
intent filters, notifications and Direct Share on both mechanisms.

Removed: `FolderSelectActivity` and its layouts/strings (the system picker replaces it and
offers "New folder"), `LocationHelper`, `JsonFileHelper`, `LinkHelper`, the `saver/` tasks
and `DownloadBroadcastReceiver`.

### Data migration

* Runs on the first load of the destination list. Each `FOLDER_STORE` path (except the
  implicit `/DropFiler`, and duplicates) becomes a `LEGACY_RECONNECT_REQUIRED` record with
  its order and label and an id derived from the path, so an interrupted migration repeats
  identically. The new file is written atomically; `FOLDER_STORE` is never changed.
* No grant is invented. Legacy records are listed with "tap to reconnect"; reconnecting
  keeps the id and position and the legacy path (old `folder:<path>` shortcuts resolve to
  it). Picking a different folder is reported explicitly. `/Download` records are marked
  as covered by the built-in destination.
* Files in `/DropFiler` are not moved, copied or deleted. A one-time card explains this.
* A corrupt version 2 file is kept as `DESTINATIONS.json.corrupt-<time>` and the migration
  runs again from `FOLDER_STORE`.

### Platform behaviour relied on (and its limits)

* MediaStore lets an app insert Download items without permission (API 29+) and see only
  its own items; it renames on conflicts. So overwrite only ever replaces DropFiler's own
  earlier files; other apps' files are never replaced, and "Do not save" is honoured by
  deleting the renamed new item. Global filename uniqueness is not claimed.
* Overwrite is staged on all targets (new copy complete before the old one is deleted, or
  replaced via `"wt"` from the complete copy). Cross-provider atomic replace is not
  available and not claimed; a crash between the two steps can leave both files.
* `"w"` is never relied on to truncate an existing document.
* SAF trees: Android 11+ refuses the storage root, Download root, some SD card roots and
  `Android/data|obb` in the picker; DropFiler does not work around it.
* Persisted grants are limited per app (128 before Android 11, 512 after); hitting the
  limit is reported as "not given lasting access".
* Downloads run in the share sheet's process (no DownloadManager). If Android kills the
  process mid-download, the pending/partial item is removed by DropFiler on failure or,
  for MediaStore, expires as a pending item.

### Verification

Performed in this environment (Linux container, no emulator/KVM):

* `./gradlew assembleDebug lintDebug testDebugUnitTest` (results in the pull request).
* `aapt2 dump badging` of the debug APK: package `fjm.sh.dropfiler`, `targetSdkVersion:'36'`,
  `sdkVersion:'21'`, storage permissions with `maxSdkVersion='28'`.
* Robolectric suites with simulated providers (see README, "Testing").

**Not performed:** instrumented tests, real MediaProvider / DocumentsUI / persisted grant
behaviour, SD card or cloud providers, OEM (HyperOS) builds, multi-GB files, low storage on
a real device. These need device testing before release.

## Miuix XML vendored as source

The `miuix-xml` git submodule was replaced by an ordinary, Git-tracked copy of the same
source in `miuix-xml/`. A plain clone or a GitHub source ZIP now builds without
`git submodule update --init` and without access to `fjmsh/miuix-xml`.

* Source: `fjmsh/miuix-xml` commit `c7af21c3d6c122a87b96bf0a1b740ed9ad8e0a92`, the commit
  the submodule was pinned to. The files were taken unchanged from that revision
  (`build.gradle`, `proguard-rules.pro`, `LICENSE`, `.gitignore`, `src/main/**`); only
  `miuix-xml/README.md` gained a note about the local integration.
* Not copied: the library repository's `.github/` (Dependabot) and `image/miuix.jpg`
  (a README screenshot, not used by the build).
* Unchanged: Gradle project name `:HiMiuix`, directory `miuix-xml/`,
  `implementation project(':HiMiuix')`, namespace `com.hchen.himiuix`, the library's build
  configuration and dependencies, and its LGPL-2.1 licence and copyright notices.
* `.gitmodules` was removed and the CI workflows no longer check out submodules.
* `fjmsh/miuix-xml` is kept only as provenance; it is no longer maintained or synchronised.
  Library changes are made directly in `miuix-xml/`. This is a stop-gap until a possible
  future Compose migration.

Existing clones may keep stale `.git/modules/miuix-xml` metadata; it is unused and can be
deleted. If `git pull` complains about `miuix-xml`, run `git submodule deinit -f miuix-xml`
(or remove the empty directory) before pulling.
