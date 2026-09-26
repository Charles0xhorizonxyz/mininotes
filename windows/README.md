# Mininotes for Windows

A first Windows desktop build, using the existing Android app's Java notebook
and sharing logic. The interface is Swing; SQLite, preferences and protected
keys have Windows adapters. Android source files are not modified by this build.
The launcher, window, header and tray use the mobile notebook logo. Its exact
geometry and colours from Android's `res/drawable/ic_note.xml` are rendered by
`DesktopIcon.java`; packaging generates a multi-resolution Windows ICO.

## Build and run

Windows x64, JDK 17, and internet access for the first dependency download:

```powershell
./windows/build.ps1 -Package
```

The script runs the shared and Windows tests and makes
`dist/latest/Mininotes-Windows-0.0.021.zip` plus its SHA-256 checksum. Extract the whole
ZIP and run `Mininotes/Mininotes.exe`. Keep the runtime and app folders beside
the executable. No Java installation is required to run this bundle.

This is an unsigned portable preview, not an installer or a published release.
The bundled runtime makes it substantially larger than the Android APK.

For development:

```powershell
$env:JAVA_HOME = 'path-to-your-JDK-17'
./android/gradlew.bat -p windows test run
```

Local data lives in `%LOCALAPPDATA%/Mininotes`. Only one process may open that
notebook at a time. `--data-dir <folder>` selects a separate notebook for tests;
`--offline` prevents the desktop session from starting a node. A normal launch
starts its own Maxima node. **Profile → Listen while the pad is closed** keeps
the node running in the system tray when the window closes. Double-click the
tray icon to reopen, or use its Exit command to stop Mininotes. This does not
start the app automatically when Windows starts.

## Using the pad

Notes, profile names and settings save automatically. Profile connection refreshes
preserve the scroll position and leave address selections alone when unchanged.

Under a note's title, the saved date/time is local to this PC. Shared notes show
`Sync confirmed` with the time recorded when all current recipients confirmed
the same saved revision, or a waiting status until that happens. Times include
the time zone and survive restarting. New edits clear the confirmation for the
current version; relay acceptance alone never confirms sync. This is the last
known shared state, not a guarantee that an offline device has no newer edits.
Attachments remain local and are not covered by the note's sync confirmation.

Write on the ruled page; edits save automatically. Ctrl+N makes a note, Ctrl+F
focuses search, Ctrl+S saves immediately, and Ctrl+Z/Ctrl+Y undo/redo text edits.
Select a book before making a note to put it there. The menu creates collections
and books, opens versions, manages attachments, archives or bins notes, restores
them, and exports or adds backups. Import adds copies, leaving current notes in
place. ZIP backups carry local attachments and use Android's existing format.

Select a note, book or collection and choose **Share**. Show the code to the
phone's Mininotes scanner, then approve the phone's request on this PC. To accept
a phone's offer, use **From another device**: start a webcam, open a QR image,
or paste an image or sharing link from the clipboard. Camera access starts only
when you press Start camera; frames are not recorded. Windows camera permissions
must allow desktop apps. Pasted links and saved images ask you to compare the
same six-digit verification code on both devices, as mobile does.

**Profile**, also in the top bar, contains the editable device name, personal
pairing QR, copyable live Maxima address, address-only QR, permanent address,
relay connection status, Reconnect, listening switch, People and backup actions.
**Connect my other device** offers your whole pad; selecting All collections in
the tree and sharing it does the same. Per-item sync timing is set on collections,
books or notes.
It refreshes the address and QR as the node changes relays. An offline session
still lets you change your name and manage local data.

**Share → Add someone** chooses an already-paired person or opens the scanner.
Choose Can read, Can write or Admin. People also shows addresses and whether a
device is yours. Share a collection to include future books and notes. Roles,
pause, sync delay and manual sync use the existing protocol. Windows does not
yet register `mininotes://` as a system protocol handler; paste links in the app.

## Boundaries

- What was checked, on which build, is recorded before each release in a log the
  maintainer keeps privately. Matching formats alone do not prove live delivery.
- Notes are encrypted on disk once Mininotes is locked (Security). Device signing/agreement keys and preferences
  (including the node seed) are protected with current-user Windows DPAPI. They
  cannot be restored by copying those files to another Windows account.
- Attachments are local and included in backups; the shared protocol does not
  transfer them. This build has no automatic updates, theme controls,
  drag ordering or full Android interface parity.
- Local input follows the existing backup limits (24,000 characters per note).
  A save failure leaves the writing on screen and blocks navigation/exit; copy
  that text somewhere safe if a storage problem cannot be corrected.

## Maintenance

`build.gradle` generates shared sources into `build/generated`: pure Java classes
are copied verbatim; `NoteStore`, `Post` and `Node` receive platform-name
substitutions. The small API in `desktop/platform` implements only the operations
these classes need. Compilation fails when new platform APIs require attention.
Do not edit generated files or fork the wire formats. JDBC transactions hold a
reentrant connection lock through their entire lifetime; cursors are detached
so a query inside a cursor loop is safe. Failed and nested transactions roll back.

Keep dependency versions pinned and preserve the bundled license notices.
Packaging uses JDK `jpackage --type app-image` and ships its runtime/legal tree.
Open an issue before changing protocol, storage or release behavior (see ../CONTRIBUTING.md).
