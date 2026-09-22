# Being a Maxima node

Written for whoever picks up the sync work next.

## What this is now

Mininotes **is** a Maxima node. It does not ask another app for an address, because every app it could ask
had a reason to say no that Mininotes could not fix:

- **Minima Core** (`org.minimarex.minimacore`) has no Maxima in its build at all — 136 command classes and
  not one under `commands/maxima/`, no `network/maxima/` package. `maxima` returns `Command not found`
  because the code is absent, not because a permission is withheld.
- **The Maxima transport app** (`com.eurobuddha.maxima.app`) has the whole thing and publishes an IPC
  surface for it, but that surface is guarded by a signature-level permission and the app re-checks the
  caller's certificate itself. An app signed with another key is refused at the door, silently.

Both are somebody else's decision about this app. The transport's core is a library — Java 11, no runtime
dependencies, written to be embedded, which is exactly how the transport's own phone app uses it — so
Mininotes carries it too. The address is not fetched from anywhere. It is what this phone is called.

## How it is put together

`android/maxima-core/` is the core's `core/src/main` tree, vendored unmodified from
[eurobuddha/maxima](https://github.com/eurobuddha/maxima) at commit `6e00175`. A submodule was not used:
the repository is 524 MB, most of it built artefacts. Java 11 is a hard constraint upstream — it is what
makes the module consumable by an Android build — so the module stays at 11 while the app is at 17.

`org.mininotes.android.Node` starts it: identity, store, relays.

```java
Hashes.setSha3(Sha3::of);                              // Android has no SHA3-256, at any version
MaximaNode node = new MaximaNode(identity, VERSION, 2);
node.setStore(new FileStore(new File(filesDir, "node")));
node.start(Bootstrap.RELAYS, 30000);
node.myAddresses();                                    // and permanentAddress()
```

Two things the platform will not supply:

- **SHA3-256.** Android provides none at any API level. Rather than take a cryptography library for one
  function, `Sha3` is Keccak-f[1600] in about a hundred lines, checked against the published NIST answers —
  and the core checks any hash it is handed against its own known answer before it will use one, because a
  subtly wrong hash makes this phone a different phone on the network, quietly.
- **The BIP39 wordlist.** It lives in the core's `resources/`, not its `java/`, and an identity cannot be
  made without it. Vendoring the sources alone gets you `bip39_english.txt missing from resources` at the
  first attempt to start.

## What it does on a real device

On the Android 15 emulator, from a cleared address: attached to 2 of 6 bootstrap relays in under half a
minute, and kept the **permanent** `MAX#…` address in preference to a per-relay one — the form that
survives the host moving. The node's store was written and the seed persisted, so the identity is the same
phone tomorrow.

## The two addresses

A **contact address** is `Mx<key>@host:port`. A **permanent address** is `MAX#<key>#<where to ask>` — not
routable itself, resolved through that lookup. `Pairing.reachable` takes both, so an address pasted from
another Minima app works whichever kind was copied.

A pairing code carries the contact address and never the permanent one. The permanent form reads like the
better thing to hand somebody, since it outlives a host move — but it only resolves once this node has
published itself in that directory *and* the asker is a contact allowed to read it, which somebody
scanning a code for the first time is not. A code handed out in a node's first seconds carried one and
failed days later on somebody else's phone with `directory replied UNKNOWN`. What survives a host move is
not the address but the **contact**: see below.

## Addresses move; contacts do not

A node that restarts or moves relay is at a different address within the minute. Per-relay keys are
derived from the identity and the relay, so the same phone on the same relay gets the same address back —
but which relays it lands on is not up to it.

So pairing *introduces* the two nodes (`introduce`), which makes each a contact of the other: a stable
identity key, every address it is reachable at, and a directory to ask when none answer. `Post.send` hands
a note to the contact (`sendToContact`), falling back to the address off the code only for a peer never
introduced. `refreshContacts` tells everybody who knows this phone where it is now.

## Looking after the node

**The core does not look after itself, and says so**: `maintain()` is documented as "drive from a
heartbeat", and for a long time Mininotes had no heartbeat. `maintain()` is the whole of a node's upkeep —
keep-alives (due every 2 minutes; a relay stops reading from a client silent for 10), dropping a relay
whose socket looks alive but which has stopped relaying, re-attaching, telling contacts when that moved
this phone, and the transport's own 20-minute round of re-publishing. Without it a node is attached when
the app opens and deaf ten quiet minutes later, with nothing to show for it.

`Node` runs it every 30 seconds on a daemon thread of its own, for as long as the process lives. Two things
to keep true if this is ever touched:

- **Nothing thrown may escape the task.** A scheduled task that throws once is never run again, which
  would be the old fault back, silently, an hour in.
- **It runs outside `Node`'s lock.** The only thing done under that lock is making the node, once.
  `refreshContacts` has a 90-second budget; when every `Node` method was `static synchronized`, telling
  everybody at start-up held the same lock a note needed in order to be sent.

## Staying up

`Listening` is a foreground service of type `remoteMessaging` — text from one device to another is what
the type is for, and it is the one kind Android does not cut off after six hours. It holds the *process*;
the node is `Node`'s. It runs only while some device is paired and the person has not switched it off.
What arrives is handed to a listener that belongs to the process rather than to a screen
(`Listening.hear`), which tells the screen if there is one and posts a notification if there is not.

Not done: starting again after the phone restarts. And **a foreground service does not survive Doze as far
as the network goes.** Measured on a Pixel 7 Pro, off its charger, screen off: `dumpsys deviceidle` said
`mState=IDLE` within twelve minutes, the process and the service were both still up, and nothing reached
the node — a note sent to it then was taken by a relay and never heard. An app that wants to receive
through the phone's deep sleep has to be exempted from battery optimisation by its owner
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); nothing here asks. When testing arrival, check
`adb shell dumpsys deviceidle | grep mState` on the receiving phone before believing a silence.

## What to look for in the log

`adb logcat -s Mininotes/Node Mininotes/Post`. Counts and outcomes only — no address, no key, no text:

- `attached to 2 of 6 relays, holding 2 address(es)` — the node came up.
- `heard 1481 bytes for this app` — the transport delivered something. If the other phone says it sent
  and this line is missing, the fault is between the nodes.
- `sent to a contact, revision 18: OK` — which road a note took and what the relay said. **OK means a
  relay took it, not that it arrived.**
- `dropped an arriving message: …` — the app refused what the transport brought, and what kind of thing
  went wrong.
- `not taken in: this phone has unsubscribed from it` — somebody's decision rather than a fault, and
  nothing is said to them about it. But from the outside it looks exactly like a note that never arrived,
  and an evening was once spent finding that out.

## Licence

The maxima repository declares no licence, and its author is not this project's owner. An earlier
version of this page said the two had the same owner and called the matter a formality; that was wrong,
and it is not one. Code published with no licence is its author's alone: reading it is allowed, copying
it on to other people is not, until the author says so.

So the author was asked, on 2026-09-21, whether Mininotes may be published with `android/maxima-core/`
inside it and credited to them — and told that if they would rather it were not redistributed, it would
not be. Until they had answered, this repository was private and no build was handed to anybody.

**They said yes, on 2026-09-22**, in a private message, in these words: *"go for it"* (typed "fo for
it"), *"with my blessing"*, *"open source is free although I do appreciate you asking."*

What that settles, and what it does not:

- Mininotes may be published with this tree inside it, credited to its author. It is credited in NOTICE
  at the root, and again in `android/maxima-core/NOTICE` beside the code, so that the credit travels with
  the directory if it is ever copied on its own.
- The tree is what it says it is: `core/src/main` at commit `6e00175`, 88 files, checked byte for byte
  against that commit on 2026-09-22 and found identical. It stays unmodified; whatever Mininotes needs
  of it is done from outside — the SHA3 it is handed, the heartbeat it is given.
- Nothing more is claimed for it. The upstream still declares no licence, so this tree is not under
  Mininotes' LICENSE and the conditions in that LICENSE are not put on it. Somebody who wants the Maxima
  transport for their own work holds a permission that was given to Mininotes, not to them: they should
  take it from its own repository and ask its author, as this project did. If the author later adds a
  licence to their repository, that licence governs this tree from then on, and this page should say so.
- The message itself is not kept in the repository — it is a private conversation — but the person who
  received it has it, and its date and words are recorded above.

---

# Appendix: the IPC surface, if you would rather not be a node

Written for whoever picks up the sync work next, on either side.

## Why this exists

Mininotes was built against **Minima Core** (`org.minimarex.minimacore`), which publishes a broadcast IPC
surface for companion apps. It answers `status`. It cannot answer `maxima`, and it never will: the build
carries 136 command classes and not one of them is under `org/minima/system/commands/maxima/`, and there is
no `org/minima/system/network/maxima/` package either. The word *maxima* survives in it only as config keys
and in `healthcheck`'s help text. So Core can neither name this device's address nor carry a message.

The **Maxima transport** (`com.eurobuddha.maxima.app`, the app behind Parlons) carries the whole thing —
`maxima`, `maxcontacts`, `maxcreate`, `maxextra`, `MaximaManager`, `mls` — and publishes an outward IPC
surface deliberately shaped like Core's, so nothing about talking to it is a new idea.

On a phone with both, the ports tell them apart: the transport's node holds 9001 and 9003, Core holds 11001.

## The contract

Source of truth: `app/src/main/java/com/eurobuddha/maxima/app/ipc/MaximaApiMessages.java` in
[eurobuddha/maxima](https://github.com/eurobuddha/maxima). What Mininotes uses:

| Direction | Action | For |
| --- | --- | --- |
| to it | `com.eurobuddha.maxima.app.REGISTER` | ask to be let in, and wake the transport |
| to it | `…IDENTITY` | this device's addresses, comma separated |
| to it | `…SEND` | carry one message (not used yet) |
| to it | `…SUBSCRIBE` | receive what arrives for our application string (not used yet) |
| back | `…RESPONSE` | every reply, matched by `requestid` |
| back | `…DELIVER` | an inbound message |

Every request carries `package`, `class` and `requestid`; the reply is sent to that exact class, so the
caller needs an exported receiver for it to name. Mininotes declares `MaximaConnection$Answers` for this and
listens on a registered receiver while a question is outstanding.

Our application string is `mininotes.v1`. The transport namespaces callers to their own strings, so nobody
else can read or forge ours.

## The one thing still missing

The receiver is guarded by `com.eurobuddha.maxima.app.permission.USE_MAXIMA`, declared **signature** level,
and the transport additionally checks that the package a caller claims is signed with its own certificate.

Mininotes asks for that permission and is refused, because it is signed with a different key. On the Pixel 7
Pro: Mininotes `c59d005e`, the transport `c55d417e`. The refusal happens in the system before the receiver
runs, so there is no log line at either end — the broadcast simply goes nowhere.

**To finish this, build Mininotes with the same release key as the rest of the family.** Nothing else in the
code has to change. `Node settings` says so plainly until then:

> The Maxima app did not answer. Mininotes has to be signed with the same key as it before it is allowed to ask.

The alternative, if Mininotes should stay outside the family, is the bound-Service route the transport's own
IPC notes already anticipate: a service reading `Binder.getCallingUid()` can identify a caller honestly
without requiring a shared key, and the user approves it once, as they already do per package.

## Two things reading Parlons itself taught us

**The address it hands out is picked, not just taken.** `ContactsPage.pickShareAddrRaw()` prefers a
permanent `MAX#` address where one exists, and otherwise prefers an address whose host is already an IP over
one with a domain — "appended host is ALWAYS an IP, never a domain". Mininotes now picks the same way, so
the two apps hand out the same address for the same node instead of quietly disagreeing.

**`IDENTITY` does not return the permanent address.** It returns `node.myAddresses()`, which is the
per-relay `Mx…@host:port` list. Parlons' own screen reaches past that to `permanentAddress()`, so a user who
has pinned a static MLS sees a `MAX#` address in Parlons and would get a rotating one over IPC — the same
node, two different answers, and the rotating one goes stale after a host move.

*A suggestion for the transport, not a complaint:* `handleIdentity` could add the permanent address as an
extra alongside `addresses`, so a companion app can hand out the durable form too. A note shared today is
meant to still arrive next month, which is exactly the case `MAX#` exists for.

Mininotes accepts both forms either way: `Pairing.reachable` takes `MAX#<key>#<where to ask>` as well as
`Mx<key>@host:port`, so an address pasted from Parlons works whichever kind the user copied.

## Getting the key in

`android/app/build.gradle` reads `android/keystore.properties`, which `.gitignore` keeps out of the
repository. Create it with four lines:

```properties
storeFile=C:/path/to/family-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then `./gradlew assembleRelease` produces `app-release.apk`, signed. Without that file the release build is
unsigned exactly as it was and nothing fails — the config is inert until a key is named.

Check you have the right key before installing: the transport on this phone is signed

```
CN=eurobuddha, OU=Minima Family, O=PandaApps, L=London, ST=England, C=GB
SHA-256  eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f
```

and `apksigner verify --print-certs app-release.apk` must print the same digest. A different one means the
transport will go on ignoring the app, silently, exactly as it does now.

**Installing a signed build over a debug one fails** — Android refuses a signature change on an existing
package. Uninstall Mininotes first, which takes the notebook with it, so export a backup beforehand.

## After the key

`MaximaConnection` reads the address today. Sending is the same surface: `SEND` with `to`, `application`
and the payload — inline under the transport's size limit, a `content://` URI above it, because a broadcast
parcel dies somewhere around 256KB and takes the receiving app with it. Receiving is `SUBSCRIBE` plus a
`DELIVER` receiver, which lands in `NoteStore.landed(...)` where the merge already waits for it.
