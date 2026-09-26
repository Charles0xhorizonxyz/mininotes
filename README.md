# Mininotes

A paper pad on your phone that can hold the same note as another phone — yours,
or somebody else's — with no server, no account, and nobody in between who can
read it.

**Sharing is what it is for.** A note you share goes over **Maxima**, the
messaging layer of the [Minima](https://minima.global) network: the Maxima node
inside the app seals the note and hands it to a public relay of the Minima
network, which passes it to the other phone's node. It travels phone to phone,
sealed end to end, and it arrives while the pad is closed. Who may read, write
or hand a thing on works the way it does in Google Drive; when two people write
in the same note before either has seen the other, the two are merged line by
line and nothing either of them wrote is lost.

There is nothing else to install — the Maxima node is inside the app. What it
does need is the Minima network: its public relay nodes are what carry a note
from one phone to the other, so where none can be reached, nothing goes. On its
own, the pad still works: it opens on a ruled page with the cursor already in
it, keeps what you write the moment you write it, and needs no connection at all.

[![Latest release](https://img.shields.io/github/v/release/mininotesorg/mininotes?label=latest%20build)](https://github.com/mininotesorg/mininotes/releases/latest)

**Latest build:** the badge above names it, and
[Releases](https://github.com/mininotesorg/mininotes/releases/latest) has the file.
The app tells you itself when a newer one is out: it looks once a day, says so in
one line, and keeps **Update to v…** in the **⋮** menu until you have it.

---

## Install it

1. Download `Mininotes-<version>.apk` from
   [the latest release](https://github.com/mininotesorg/mininotes/releases/latest).
2. Check it is the file that was published:

   ```sh
   sha256sum -c Mininotes-<version>.apk.sha256
   ```

3. Open it on the phone. Android will ask whether to allow installing from
   wherever you downloaded it; that permission is per-app and can be switched
   back off afterwards.

Android 9 (API 28) or newer. No account, no sign-in, no analytics and
no advertising identifier. Two things use the network and nothing else does: the
pad's own Maxima node, which carries what you share, and the update check, which
reads one line of text from this repository, once a day when the pad is opened
and whenever you tap for it. Nothing is sent with it, nothing is downloaded and
nothing is installed; **⋮ → About** has the switch that turns the daily look off.

### Sharing between phones

Nothing else has to be installed: the pad is its own Maxima node. It needs the
network, and the first time it takes a few seconds to find a relay. **⋮ →
Profile → Connection** says *Connected* once it has, and says what to do if it
has not.

1. **Show a code.** Open the collection, book or note, tap the ring in its bar
   (or **⋮ → Sharing**), then **Share → Show them my code**. *Read only* or
   *Read & write* is chosen on the code itself.
2. **Scan it** on the other phone: **+** on the shelves → **From another
   device** — or just point the phone's own camera at it; the code is a
   `mininotes://` link and the camera offers to open it. Accept. A strip at the
   foot of the screen says what is happening — saving, finding the other phone,
   telling it — until the thing arrives.
3. The first phone asks whether to give it to them. Say yes and it is sent.
4. From then on it keeps itself up to date: what you write goes a few seconds
   after you stop, and what they write comes back and is merged. The ring on
   every thing says where it stands: empty for *only on this phone*, a tick for
   *up to date*, an arrow for *waiting to send* — tap it to sync now — and two
   bars for *paused*.

Tap the ring on anything shared and one box says it all. **Who has access**: a
role beside each person — Owner, Admin, Can write, Can read — which the owner
or an admin changes from a drop-down, and **Add someone**. **Syncing**: *Sync
automatically* and its delay, *Sync now*, *Pause receiving*, and **Unfollow**
for anyone but the owner, which tells the others.

Once a phone is paired with anything, the pad goes on listening after it is
closed. Android shows a notification for as long as that lasts. **⋮ → Profile →
Listen while the pad is closed** switches it off, and so does **Stop listening**
on the notification. **Keep listening while the phone sleeps** (Profile) asks
Android to let Mininotes through its deep sleep and wakes it for a moment every
few minutes; without it, the pad hears while the phone is awake or charging.
Whatever was sent in the meantime is sent again until your phone answers, and
another of your devices that is on can carry it to you. [docs/SHARING.md](docs/SHARING.md) says
plainly what is not built yet, and the first item on that list is the one to
read before trusting this with anything two people both write in.

The address a phone hands out is a Maxima contact address: a public key, then
the relay it can be reached through, like `Mx…@45.77.57.24:9501`. It changes when
the node moves relay, which is why pairing also introduces the two nodes to each
other rather than relying on it.

See [docs/SHARING.md](docs/SHARING.md) for what is sealed, what is signed, and
what happens when two people write on the same note before either has seen the
other.

---

## What it does

- **Shares.** A collection, a book or one note, to phones you have paired with,
  sealed end to end over Maxima — to read, to write in, or to hand on. It
  arrives while the pad is closed, and two people's writing is merged.
- **Writes.** A ruled page, the cursor in it, saved as you go. No save button.
- **Holds.** Collections hold books, books hold notes. Nothing is buried deeper.
- **Colours.** One colour scale and a tone slider, set per collection, book or
  note, and it follows the phone's light and dark themes.
- **Attaches.** Any file, kept with the note and carried in backups.
- **Remembers.** Every version of a note, with a way back to any of them.
- **Puts away.** One archive and one bin for everything, with a way back out.
- **Backs up.** One file holding every collection, book, note and attachment.
  Importing asks whether to add to what is here or replace it.
- **Finds.** Search across the whole pad, your favourites, what you wrote in
  lately, and **Tree view** — every collection, book and note at once.

**Lock Mininotes** (⋮ → Security) and the notebook, its attachments and your
backups are encrypted on the device (SQLCipher). It then opens with your
fingerprint or screen lock, with a backup password and 12 recovery words as
spare keys. Every note is also sealed end to end on its way to another device.

---

## Say something about it

**⋮ → Feedback** in the app. Pick what sort of thing it is and which part of the
app it is about, write it, and tap **Post it** — that opens a filled-in form here
under your own name. Everything anybody has said is in one place:

<https://github.com/mininotesorg/mininotes/issues?q=is%3Aissue+label%3Afeedback>

The app never posts for you and sends nothing by itself: it builds a web address
out of what you typed and hands it to the browser. What travels with a report is
one line — version, Android version, phone — shown to you before you send it.
Reports are public, so say what the app did rather than who you are.

A **security** problem goes privately to
[a security advisory](https://github.com/mininotesorg/mininotes/security/advisories/new),
not to an issue.

[docs/FEEDBACK.md](docs/FEEDBACK.md) has the rest.

---

## Build it yourself

### Windows preview

The Windows desktop build lives in [windows/](windows/README.md). On Windows
with JDK 17, run `./windows/build.ps1 -Package`; extract the resulting
`dist/latest/Mininotes-Windows-0.0.021.zip` and open `Mininotes/Mininotes.exe`.
The bundle includes Java. It is a preview: what was checked on which build is
recorded before each release in a log the maintainer keeps privately.

### Android

You need JDK 17 and the Android SDK (compileSdk 36, build-tools for AGP 8.10.1).

```sh
cd android
./gradlew testReleaseUnitTest lintRelease assembleRelease
```

The APK lands in `android/app/build/outputs/apk/release/`. That build is
unsigned; sign it with your own key before installing, or use a debug build:

```sh
./gradlew installDebug
```

`android/local.properties` points at your SDK and is not in the repository.

### Checks

```sh
cd android && ./gradlew test lint
```

Over two hundred unit tests, no device needed. They cover the pieces where being
wrong loses somebody's writing: the merge, what to do with an arriving note and
with the page that is open when it arrives, who is a member of what, the schema
migrations, the pairing format, QR encoding and decoding, attachments and the
colour scale. What a device is needed for is checked on two phones before each
release, in a log the maintainer keeps privately rather than publishes, because it
names their phones and quotes their notes. Ask in an issue for the entry for a build.

---

## Layout

| Path | What is in it |
| --- | --- |
| [android/](android/) | The Android app. Everything below is about it. |
| [windows/](windows/README.md) | Windows desktop preview and packaging. |
| [android/app/src/main/java/org/mininotes/android/](android/app/src/main/java/org/mininotes/android/) | All the code. No XML layouts; the views are built in Java. |
| [docs/PRODUCT.md](docs/PRODUCT.md) | What the app is for, and what it is not. |
| [docs/SHARING.md](docs/SHARING.md) | The sync design: keys, pairing, merge, conflicts. |
| [app/](app/) | The older MiniDapp, kept for anybody still running it. Not developed. |

The app has no Compose and no XML layouts, and few third-party runtime
dependencies: ZXing for QR codes, and SQLCipher with AndroidX SQLite for the
encrypted notebook. It is built that way so the whole thing can be read.

---

## Licence

Mininotes is **free and open-source software** under the
[GNU General Public License, version 3 or later](LICENSE). You may use it,
study it, change it and pass it on, and sell it too - but whatever you pass on,
changed or not, goes with its source code under the same licence. Third-party
terms are in [NOTICE](NOTICE).

The Maxima transport inside the app is the core of
[eurobuddha/maxima](https://github.com/eurobuddha/maxima), vendored unmodified
with its author's permission and credited in NOTICE. It is theirs, and is not
under the GPL; its own repository does not yet carry a licence.

Until 26 September 2026 Mininotes was source-available (Apache 2.0 with the
Commons Clause and a paid-product condition); copies taken before then keep
those terms.

Contributions: [CONTRIBUTING.md](CONTRIBUTING.md).
Security reports: [SECURITY.md](SECURITY.md).
