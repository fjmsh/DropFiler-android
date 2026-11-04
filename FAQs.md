## Frequently Asked Question (FAQs)

This is a compilation of Frequently Asked Questions for DropFiler. This document will aid prospective and existing end-users of the software as a self-service guide in cases where the developer can't be reached. This document is subject to change and modifications at any time. Thus it'll be updated from time to time consequently to suit any new changes to the software as new developments would be implemented which might cause a discrepancy with this current document.

### What is DropFiler?

DropFiler is an Android application that enables you to save a copy of a file to your device using the Android share function or "Open with". This can be done with a single click.

DropFiler is a separately branded fork of [Phone Saver](https://github.com/ScreamingHawk/phone-saver), originally developed by Michael Standen (ScreamingHawk).

### What does "Open with DropFiler" do?

It saves a copy of the file to one of your destinations, exactly like sharing the file to DropFiler. DropFiler is not a viewer or editor and never changes, moves or deletes the original. It only appears when the app opening the file uses a standard Android "Open with" request and grants access to the file.

### Is DropFiler an update of Phone Saver?

No. DropFiler has its own application ID (`fjm.sh.dropfiler`), so it installs as a separate app next to Phone Saver. Destinations and settings are not copied over; add your destinations again in DropFiler.

### How do I set up DropFiler?

* Open the application
* Use the `Add Destination` button and choose a folder in Android's folder picker. Repeat for
  as many folders as you like; each is chosen once and remembered
* Check the `Settings` to configure the application to your needs

You can also share to DropFiler straight away: until you add a location, files are saved to
the built-in **Download** destination (`Internal storage/Download`). No setup or permission
is needed for it on Android 10 and newer.

### What is the "Download" save location? Can I remove it?

It is a built-in destination, so there is always somewhere to save. It saves into the
standard Download folder itself (not a sub folder). It cannot be removed, but you can turn
on `Hide "Download" save location` in Settings to hide it while you have other locations
that work. If none of them works, it is still offered.

### I used DropFiler before. Where are my files, and why do my folders say "reconnect"?

Files saved earlier stay in the `DropFiler` folder on your internal storage; nothing was
moved or deleted. New files go to Download. Folders you added in older versions need to be
chosen again once, because newer Android versions only let apps save to folders you picked
in the system folder picker: tap the folder on the main screen and choose it again.

### Why can't I select some folders, like the Download folder or the storage root?

Android (11 and newer) does not let apps be given access to the root of internal storage,
the Download folder itself, the root of some SD cards, or `Android/data`. Choose or create
a sub folder in the picker. To save into Download itself, use the built-in Download
destination.

### Can I save to my SD card?

Yes, if your device's folder picker shows the SD card: add a folder on it with `Add
Destination`. If the card is removed, the destination is shown as not available until it
is back. You can also enable `Choose location when saving` to pick a location for a single
save.

### I get an "Unsupported" message. What can I do?

If you see this message, please submit a bug report using the link provided on the screen. This will help the developer add support later. 
While you wait for development, you can try enabling the `Force saving` option in the settings. This will bypass a number of validation checks to force the file to save. There is no guarantee this option will work for you.

### Can I create a folder in DropFiler?

Yes, where the storage supports it: Android's folder picker has a "New folder" option. DropFiler is otherwise not a file manager; use another app to rename, move or delete files. 

### Can I save multiple shared files?

Yes, if the multiple files can be shared it can be saved.

### What other information is collected when I use DropFiler?

No personal information is collected and used by the developer directly. Read the [privacy policy](Privacy.md) to understand the developer's policy towards your privacy.

### Is DropFiler available to any other operating system?

There is no intention to migrate DropFiler to another operating system.
However, the code is [permissively licensed](LICENSE) and clones are allowed so long as the license conditions are met.

### Where can I download DropFiler?

DropFiler is not published on an app store yet. You can build it from this repository (see the README). The Google Play and F-Droid listings for `link.standen.michael.phonesaver` are for the original Phone Saver app.

### Can I contribute to DropFiler?

**Yes please!**

Feel free to submit a bug or pull request for anything you feel should be added.

Phone Saver, which DropFiler is based on, has had many contributors. Changes are listed in the application [release notes](app/src/main/res/xml/changelog_master.xml), which are displayed when the application is loaded after an update.

### I have some questions not contained in this FAQs document, how can I contact the developer?

Please [open an issue](https://github.com/fjmsh/DropFiler-android/issues/new) on this repository. You can also find the developer at [fjm.dev](https://fjm.dev).

Please do not contact the original Phone Saver developer about DropFiler.
