# DropFiler

An Android app that saves copies of files to folders on your phone, using the Share menu
or **Open with**.

DropFiler is a separately branded fork of [Phone Saver](https://github.com/ScreamingHawk/phone-saver),
originally developed by Michael Standen (ScreamingHawk). See [Credits](#credits).

| | |
| --- | --- |
| App name | DropFiler |
| Application ID | `fjm.sh.dropfiler` |
| Developer | [fjmsh](https://github.com/fjmsh) · [fjm.dev](https://fjm.dev) · [fjm.sh](https://fjm.sh) |
| Source | this repository (currently `fjmsh/file-saver-android`; it is planned to become `fjmsh/DropFiler-Android`) |

DropFiler is not (yet) published on Google Play or F-Droid. The store listings for
`link.standen.michael.phonesaver` belong to the original Phone Saver app.

## Description

Ever hit that share button and really wanted to just store the file on your phone?
That meme that you send to your friends that would be great in your reaction image folder.
That screenshot of that Snapchat that you want to hide in a secret folder.

Well look no further!

Set up any number of locations on your mobile, and save to them with a single click when using
Android's share feature, or by choosing **Open with → DropFiler** in a file manager.

## Features

* Works straight away: the built-in **Download** destination (`Internal storage/Download`,
  the standard Downloads folder itself — not a `Download/DropFiler` sub folder) is always
  available, needs no setup or storage permission on Android 10+, and cannot be removed.
  It can be hidden in Settings ("Hide "Download" save location") while you have other
  destinations that work; otherwise it is always offered
* Add any number of other folders with Android's system folder picker — on internal
  storage, SD cards, USB drives or storage apps that support it. Each is chosen once and
  remembered (a persisted Storage Access Framework permission); sharing never asks again
* Share (single or multiple files, text and links) **and** "Open with" for files
* Save on a single click (saving is automatic when only one location is configured)
* Direct Share targets for your saved locations
* Configure whether or not gallery and music apps are notified of saves
* Choose from a range of duplicate file handling options
* A notification with the result of every save (saved / not saved)
* Create new folders in the system folder picker (where the storage provider supports it)
* Reorder your destinations (drag the handle, or long-press a row); the share sheet uses the same order
* If a destination folder was deleted, its storage removed or its access revoked, it stays
  in the list marked "tap to reconnect", and the share sheet offers the other destinations
* Optional one-time **Save as** ("Choose location when saving"): pick a file location with
  the system picker for a single save, without adding a destination
* Links to images, video and audio are downloaded straight into the chosen destination;
  saving shows its progress and can be cancelled
* Light and dark themes, with optional dynamic (Material You) colors on Android 12+
* Automated bug reports for requesting support for file types

## "Open with DropFiler"

Besides the Share menu, DropFiler is offered as an **Open with** app for files that other
apps (e.g. file managers) open with a standard Android `ACTION_VIEW` intent.

* "Open with" in DropFiler means **save a copy** of the file to one of your destinations.
  DropFiler does not display, play, edit or run files, and never modifies, moves or deletes
  the original.
* It uses the same destination sheet, filename handling, duplicate-file setting and result
  notification as sharing a single file.
* Files are read through the `content://` URI and the temporary read permission granted by
  the opening app. `file://` URIs are accepted as a best effort only: they are rare on
  Android 7+, readable on Android 9 and older with the legacy permission, and usually not
  readable on Android 10+ (DropFiler then says so). Web links are not handled.
* If the file cannot be read (no permission granted, the file was deleted, a folder was
  passed, the provider refused access), DropFiler says so and saves nothing.

Limitations: DropFiler only appears where the opening app uses a standard implicit
`ACTION_VIEW` intent with a MIME type and a readable URI. Apps that open files in their own
viewer, use proprietary actions, or do not grant access to the file will not list DropFiler,
and DropFiler cannot change that. Because it is not a viewer, avoid choosing it as the
**default** ("Always") app for a file type; you can clear that in Android's app settings
(Open by default → Clear defaults).

## Un-Features

* No sign up
* No requests to rate the app
* No trail periods
* No paid upgrades

## User interface: Miuix XML

Since version 6.0 the interface follows the Xiaomi HyperOS design language using
[Miuix XML (HiMiuix)](https://github.com/fjmsh/miuix-xml), a traditional Android View/XML
component library. The app does **not** use Jetpack Compose.

Miuix XML components used:

| Screen | Components |
| --- | --- |
| All screens | `MiuixAppBar` (large collapsing title), `SpringBackLayout` (Miuix over-scroll), `MiuixRecyclerFillView` / `MiuixFillView`, `fragment.Fragment` / `fragment.PreferenceFragmentCompat` |
| Destinations, share sheet | `MiuixCardView` group cards, `ShadowHelper` press feedback, Miuix text / divider styles |
| Settings | `MiuixPreferenceCategory`, `MiuixSwitchPreference`, `MiuixDropDownPreference`, `MiuixPreference` |
| Dialogs | `MiuixAlertDialog` (change log, remove destination confirmation) |

Small app-side pieces follow the same design language where the library has no
equivalent: the rounded "Add destination" button, the share destination bottom sheet and
the destination row layout (`app/src/main/res/layout/fs_item_row.xml`).

### Integration

The library is included as a git submodule (`miuix-xml/`) and built as the Gradle module
`:HiMiuix`, as recommended by the library. Clone with submodules:

```shell
git clone --recurse-submodules https://github.com/fjmsh/file-saver-android.git
# or, in an existing clone
git submodule update --init
```

The submodule is pinned to a specific commit, so builds are reproducible.

### Blur

Miuix XML can use Xiaomi's private View blur. DropFiler only allows it when the device
provides those methods **and** Android reports that blur is enabled
(`WindowManager.isCrossWindowBlurEnabled()`, Android 12+). Everywhere else, including all
non-Xiaomi devices, the app bar uses a plain surface color. Navigation never depends on
blur. See `util/BlurSupport.kt`.

### Motion

Animations are short `ValueAnimator`/window animations. They follow the system animator
duration scale, so they are disabled when the user turns animations off
(Settings → Accessibility → Remove animations).

## Building

Requirements:

* JDK 21 (the Gradle toolchain targets Java 17 bytecode for the app, Java 21 for Miuix XML)
* Android SDK with platform **android-36** and build-tools 36 (only used to *compile*)
* Internet access to `google()` and Maven Central on first build

```shell
./gradlew assembleDebug          # build app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug              # Android lint
./gradlew testDebugUnitTest      # Robolectric tests on API 21-36
./gradlew testDebugUnitTest -ProbolectricSdks=28   # limit the API levels tested
```

Build stack: Android Gradle Plugin 8.13.2, Gradle 8.14.3 (wrapper), Kotlin 2.2.21,
AndroidX (appcompat 1.7.1, preference 1.2.1, recyclerview 1.4.0) and core library
desugaring (required by Miuix XML for `java.util.stream` below API 24).

## Supported Android versions

| | API level |
| --- | --- |
| Minimum (`minSdk`) | 21 (Android 5.0) |
| Target (`targetSdk`) | **36 (Android 16)** |
| Compile (`compileSdk`) | 36 |

`minSdk` was raised from 15 to 21 because current AndroidX (appcompat 1.7) requires API 21.
Miuix XML declared `minSdk 29`, but an audit of its actual API usage (Android lint
`NewApi`/`InlinedApi` at minSdk 21) found only a handful of calls above API 21. Those were
fixed in the library (compat helpers, version checks, core library desugaring) so the
app keeps supporting Android 5.0 – 9 instead of dropping to Android 10+. Known library
limitation below API 26: a Miuix seek bar cannot have a minimum other than 0 (not used by
this app).

Up to version 6.x the app had to target API 28 because it wrote to arbitrary folders with
`java.io.File`. Since 7.0 it uses scoped storage APIs only (see [Storage](#storage)), so it
targets API 36.

## Installing alongside Phone Saver

DropFiler uses its own application ID, `fjm.sh.dropfiler`. To Android it is a **new,
separate app**: it installs next to the original Phone Saver (`link.standen.michael.phonesaver`)
instead of updating it, and has its own data, permissions and signing identity.

* Phone Saver's destinations and settings are **not** transferred; set up your destinations
  again in DropFiler. DropFiler does not try to read another app's private data.
* Files you saved earlier stay where they are; DropFiler can save to the same folders.
* Future DropFiler updates install over each other only while the application ID stays
  `fjm.sh.dropfiler`, the APKs are signed with the same key and `versionCode` increases.

## Launcher icon

The icon is the three-folder DropFiler symbol from `DropFiler-Three-Folders-Smooth.svg` (repository
root). Its path data is used unchanged in a single vector, which every icon below is built from:

| Resource | Notes |
| --- | --- |
| `app/src/main/res/drawable/ic_dropfiler_symbol.xml` | The symbol as a VectorDrawable (light-blue `#A9C6FF` strokes, 640 x 640 viewport centred on the artwork). Single source of the geometry |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | Adaptive icon (Android 8+): background, foreground and monochrome layers |
| `app/src/main/res/values/ic_launcher_background.xml` | Solid dark-blue background `#1E2A43` and the symbol colour `#A9C6FF`: the dark-theme folder tile and folder glyph colours |
| `app/src/main/res/drawable-v26/ic_launcher_foreground.xml` | The light-blue symbol inset 27.5% on the 108 dp canvas: 48.6 dp wide, farthest stroke 30.2 dp from the centre, inside the 33 dp safe-zone radius |
| `app/src/main/res/drawable-v26/ic_launcher_monochrome.xml` | Same layer for themed icons (Android 13+); the system recolours it |
| `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`, `ic_launcher_round.png` | Legacy icons for Android 5-7 (48, 72, 96, 144, 192 px): dark-blue rounded square / circle with the light-blue symbol, rendered from the SVG paths |
| `app/src/main/ic_launcher_web.png`, `playstore/` | 512 px store icon and listing artwork, still Phone Saver's |

The main screen shows the same vector before the "DropFiler" title (`ui/AppBarTitleIcon.kt`),
tinted with `?attr/fsColorTitleIcon`, which is `?attr/fsColorOnAccentContainer`: the same colour as
the folder icons in the destination list (`#A9C6FF` in the dark theme, `#1A5FD0` in the light theme,
or the matching Monet tone when dynamic colors are on). The Credits screen shows
`@mipmap/ic_launcher`, so it updates with the launcher icon.

## Storage

DropFiler 7 has two kinds of destination, both written through one save engine
(`save/SaveCoordinator.kt`) used by Share, Share multiple, Open with, Direct Share, text
and links:

| | Built-in **Download** | Folders you add |
| --- | --- | --- |
| Where | `Internal storage/Download` (root of the standard Downloads folder) | Any folder chosen with the system picker (`ACTION_OPEN_DOCUMENT_TREE`) |
| Android 10+ | `MediaStore.Downloads` on the primary volume, `RELATIVE_PATH = Download/`; written as pending, then published. **No permission** | Storage Access Framework: persisted read/write tree grant, `DocumentsContract` |
| Android 5 – 9 | The public Download folder with `WRITE_EXTERNAL_STORAGE` (declared `maxSdkVersion="28"`, requested from the main screen; staged file renamed into place) | Same as Android 10+ (SAF trees exist since Android 5.0) |
| Setup | None | Pick once; never asked again while the grant exists |
| Can be removed | No (can be hidden) | Yes; removing also releases the folder grant |

* **Restricted folders.** On Android 11+ the system picker does not allow choosing the root
  of internal storage, the `Download` folder itself, the root of some SD cards, or
  `Android/data` / `Android/obb`. Choose (or create, in the picker) a sub folder instead.
  The built-in Download destination is the intended way to save into the Download root.
* **Duplicate files** ("Behaviour if file already exists"): *Overwrite*, *Do not save* and
  *Append number* work for both kinds. Overwrites are staged: the new copy is written
  completely before the old file is replaced, and a failed save only removes what it
  created. Without storage permission an app only sees the Download files it created
  itself, so a file of the same name from another app is never replaced: MediaStore saves
  the new file as e.g. `a (1).txt` (reported as such), and *Do not save* skips it. Document
  providers may also adjust names; the name reported in the notification is the real one.
  The old, never-implemented "Request new name" value is treated as *Append number*.
* **Links.** Images, video and audio (and anything with *Force saving*) are downloaded with
  `HttpURLConnection` and streamed directly into the destination; web pages are saved as a
  text file containing the link, as before. `DownloadManager` is no longer used (it can
  only write to file paths), so the download runs in the share sheet, which shows progress
  and can be cancelled; the result notification is posted only after the file is complete.
* **Save as** uses `ACTION_CREATE_DOCUMENT` for one file at a time (multiple files: one
  picker per file). That is a single document, not a destination.
* Nothing is written with file paths on Android 10+, and `MANAGE_EXTERNAL_STORAGE`,
  `requestLegacyExternalStorage` and hidden APIs are not used.

### Upgrading from DropFiler 6.x

* New files go to **Internal storage/Download** instead of `/DropFiler`. Files saved
  earlier stay in the `DropFiler` folder and are **not** moved or deleted; DropFiler cannot
  show that folder any more, use a file manager to move them if you like.
* Folders you added before were stored as paths. A path cannot be turned into a storage
  permission, so they are kept (same order and names) and marked **tap to reconnect**:
  tap one, choose the same folder in the picker, and it works again with the same place
  in the list (and old Direct Share shortcuts for it keep working). If you choose a
  different folder, DropFiler says so. A former `/Download` destination is shown as
  covered by the built-in Download destination; just remove it.
* The new list is stored in `files/DESTINATIONS.json` (versioned, written atomically);
  the old `FOLDER_STORE` file is left untouched. All settings keep their keys and values,
  including "Hide …" (now hiding the Download destination).
* A one-time card on the main screen explains this after the update.

### Permissions

| Permission | Why |
| --- | --- |
| `WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE` (`maxSdkVersion 28`) | Built-in Download destination on Android 5 – 9 only |
| `INTERNET` | Downloading shared links (normal permission, never asked) |
| `POST_NOTIFICATIONS` | Save result notifications; asked once on the main screen on Android 13+. Saving works without it (toasts only) |

### Direct Share

* Targets are identified by a stable destination id, never a path or list position.
  Destinations that need reconnecting are not offered. Old `folder:<path>` shortcuts from
  6.x are retired and, if used anyway, resolve to the migrated destination.
* Android 6 – 9: `LocationChooserTargetService`.
* Android 10+: destinations are also published as sharing shortcuts
  (`res/xml/shortcuts.xml`, `DirectShareHelper`), the supported mechanism since
  ChooserTargetService was deprecated (recent Android versions no longer query it).
  On some launchers they also appear when long-pressing the app icon and open the app.

## Testing

`./gradlew testDebugUnitTest` runs Robolectric tests (JVM, simulated Android framework) on
the API levels listed in each test class (23 – 36; API 21 – 22 are not covered by Robolectric runs here). Storage is simulated with two test
providers: `TestDocumentsProvider` (a document provider speaking the DocumentsContract
protocol, with read-only, no-delete, no-truncate, failing-write and unmounted modes) and
`FakeMediaStore` (MediaStore Downloads rules: pending items, automatic renaming, other
apps' files invisible). Link downloads use a local HTTP server. Covered: Share, Share
multiple, Open with (many MIME types, large files, missing names/types, revoked grants,
missing files), text and links into Download and into SAF folders, every duplicate policy
for both kinds of destination including other apps' files and concurrent saves, Save as,
Direct Share and old shortcuts, adding/rejecting/removing/reordering/reconnecting folders,
upgrade migration, hide-default and recovery, cancellation, activity recreation, manifest
and permissions, themes and accessibility.

Robolectric is not a device. It cannot prove real MediaProvider, DocumentsUI / persisted
grant or OEM behaviour, and these were **not** run on devices or emulators for 7.0. Check
on real Android 10 – 16 (and 5 – 9) devices: saving to Download, picking folders (internal,
SD card, a cloud provider), reconnecting after an upgrade, revoking access, and links.
See [MIGRATION.md](MIGRATION.md).

## Feature requests and bugs

Please use the [issues of this repository](https://github.com/fjmsh/file-saver-android/issues).
For problems that also affect the original app, see
[Phone Saver's issues](https://github.com/ScreamingHawk/phone-saver/issues).

## Credits

DropFiler is a separately branded fork of Phone Saver, originally developed by
[Michael Standen](https://michael.standen.link) (ScreamingHawk). The original project is
available at https://github.com/ScreamingHawk/phone-saver. Original code remains subject
to its applicable [MIT License](LICENSE); the copyright notice in `LICENSE` is kept unchanged.

The user interface uses [Miuix XML / HiMiuix](https://github.com/fjmsh/miuix-xml) by HChenX,
licensed under the [GNU LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html).
It is included as a separate module (git submodule, `miuix-xml/`) with its own source
and licence, so it can be replaced or relinked independently of the app.

Other libraries: AndroidX, Gson, ckChangeLog and Kotlin (Apache 2.0).

### Thanks

Special thanks to Michael Standen and all [the contributors](https://github.com/ScreamingHawk/phone-saver/graphs/contributors)
of Phone Saver, including its translators.
