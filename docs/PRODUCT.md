# Mininotes: one paper pad, on every device you own

**What this is.** A paper pad. You open it and write: no title, no formatting, no tools on the page. The one thing paper cannot do is be the same pad on your phone, your tablet and your desktop at once, and that is what Minima is here for. Every element beyond a ruled page has to justify itself against a pad that has none, and multi-device sync is the justification the whole project rests on.

This replaces the feature-tier scope below, which was written before that decision: treat the tiers as a survey of what other notes apps do, not as a plan. A feature enters Mininotes only when it earns its place on the pad.

Architecture as built: a native Android APK that is its own Maxima node — the transport's core is vendored into the app. The first assumption was a separately installed Minima Core carrying the post; Core's build turned out to have no Maxima in it, and the notebook/sync model did not have to change when that did. The existing MiniDapp is the older browser companion, kept for anybody still running it, and not a participant in sharing.

**Where this document stands.** Everything from *Sync design* down was written on 13 September 2026 as a plan, before any of it existed, and is kept as that: what was intended, against which what was built can be judged. What was built is in [SHARING.md](SHARING.md); what has been seen working is recorded, build by build, in a verification log the maintainer keeps privately. The plan is still ahead of the app in three places that matter — an acknowledgement from the far end (item 5), tombstones (item 7), and encryption at rest (item 3) — and behind it in one: the app merges line by line where the plan only promised conflict copies.

## What the network brings

| Capability | User benefit | What Mininotes must supply |
|---|---|---|
| Maxima encrypted peer messages | Exchange updates between owned devices without a notes-provider account | Explicit device pairing, app-level vault encryption, authorization and key lifecycle |
| Maxima public-key identities and contacts | Recognize the node behind a paired device or selected recipient | Bind app device keys to node identities; a contact is not automatically a trusted device |
| Minima Core background node | Network access shared by native apps on Android | Durable inbox/outbox, Android lifecycle handling and clear offline status |
| Minima decentralized network | Avoid requiring a company-operated notes backend | Test routing, battery/data usage and recovery; connectivity is not guaranteed |
| Optional Minima commitments | Later prove that a particular document version was committed by a chain position | Explicit consent, randomized commitments and confirmation verification; no claim of authorship from a hash alone |

Maxima transports information; it is not a replicated notes database or guaranteed offline mailbox. Do not assume both devices can be offline at disjoint times and still exchange data. A user-owned always-on device can become an optional store-and-forward peer, but we must implement retention, acknowledgement, storage quotas and recovery. Large attachments need chunking and resumable transfer; performance must be measured. Do not advertise unlimited free storage or immediate sync.

Notes, titles, tags, attachment names, device lists and edit history stay off-chain. Blockchain transactions are not part of ordinary note editing. Payments, NFTs and tokens are not required for the core product. Optional proof-of-existence belongs after dependable notes and sync.

## Modern notes baseline

| Priority | Features |
|---|---|
| Essential writing | Fast capture; autosave; undo/redo; formatted text and Markdown; headings, lists, checklists, links; offline editing |
| Essential organization | Notebooks; multiple tags; full-text search; sorting; pins/favorites; archive; recoverable trash |
| Essential continuity | Android plus a desktop experience; reliable device sync; per-device status; conflict copies; version history; export/import |
| Essential privacy | Vault encryption at rest and in transit; app lock; recovery/export; device revoke; no telemetry by default |
| Common next additions | Images/files; share-to-app capture; reminders; home-screen widget; templates; dark mode; accessibility and tablet layout |
| Optional power features | OCR, document scanning, audio/transcription, handwriting, backlinks, browser clipping, shared notebooks, plugins |

These tiers are a proposed scope, not a promise that every notes app has every feature. Joplin provides the offline/Markdown/search/export benchmark. Notesnook provides a useful organization/editor/privacy benchmark. AI and simultaneous collaborative editing are not prerequisites for a good personal notebook.

## Sync design (the plan, as written before it was built)

1. **Local save is independent of Core.** Commit a note revision and an outbox operation in the same local transaction. Show “Saved on this device” separately from “Synced to phone/tablet”.
2. **Pair owned devices explicitly.** QR-assisted challenge/response, short-lived invitation, verification code and approval on both devices. Each device has a distinct app signing key. Never copy a wallet seed to pair devices. Maxima contacts receive no automatic vault privileges.
3. **Encrypt at the app layer.** Use a reviewed implementation, authenticated encryption, unique nonces, explicit versioning and authenticated metadata. Wrap a vault key independently for each approved device. Android Keystore protects the local wrapping key; design password/recovery export before relying on it.
4. **Use causal revisions.** Operation ID, vault ID, device ID, per-device counter, parent revision(s), schema version, content commitment and ciphertext. Vector clocks or equivalent causal metadata determine ancestry, not device wall-clock time. Concurrent updates create visible conflict copies in v1; a CRDT editor is a possible later enhancement.
5. **Persist before acknowledgement.** Sender retries a durable operation ID. Receiver authenticates, bounds, validates, deduplicates and commits before returning an app acknowledgement. Transport acceptance does not mark a note synced. Acknowledgements bind sender, vault, operation and commitment.
6. **Catch up after long offline periods.** Exchange bounded inventory summaries and request missing revisions. Support restarts, interrupted batches, slow peers and missing acknowledgements. Expiry of transport packets must not delete the underlying outbox operation.
7. **Propagate deletions safely.** Retain tombstones until every active device has acknowledged, or require an explicitly expired device to rejoin from a fresh snapshot. Do not let an old offline device resurrect deleted notes.
8. **Treat revocation honestly.** Stop sending to revoked devices and rotate keys for future revisions. Previously received data cannot be remotely unlearned or reliably erased.
9. **Separate backup from sync.** Sync also replicates mistakes and deletions. Encrypted versioned backups must be restorable without the original node, phone or app provider.

## Delivery milestones

*As of v0.0.91, 18 September 2026:* M2's sharing is largely built and seen working between two phones — pairing by scanned code, the sealed format, three levels with a membership that travels, line-by-line merge, automatic sending, versions, and arrival while the pad is closed. Not yet: an acknowledgement from the far end, tombstones, revoke-and-re-pair, the loss/duplication/reorder test suite M2 asks for, and the encrypted vault from M1. Of M3, attachments exist locally and do not travel. The entries below are the milestones as first written.

- **Foundation, as first written:** a source-built native Android pad. It opens on the last note, ruled, keyboard up; "Notes" lists the pages and "+" turns a fresh one. Local SQLite with autosave, JSON backup compatibility, Core registration/status check, and a tested causal-order primitive. Storage and document work is off the interface thread, the list reads previews instead of whole notes, and the schema has a versioned additive migration path. No device sync and no encrypted vault yet.
- **M1 — a pad you can trust with your writing:** persistence, lifecycle and device tests; recoverable backup; encrypted vault; migration from the MiniDapp. Titles, notebooks, tags, archive, pins, search and Markdown are **not** in this milestone; they were cut from the app as unjustified. The threading and migration groundwork is in place; the device testing that would confirm it is not.
- **M2 — sharing with addresses you name:** an address is either another device of yours (two-way, the whole pad) or someone else (one-way, the pages you pick, still updating as you edit). Authenticated pairing by comparing six digits, encrypted durable protocol, conflict copies between your own devices, retries, tombstones, revoke and re-pair. Must pass loss, duplication, reordered delivery, simultaneous edits, clock skew, process death, and restored-backup tests. The sealed-page format exists and is tested; see [the sharing design](SHARING.md). Nothing is sent yet.
- **M3 — daily convenience:** desktop companion parity, attachments, reminders, Android capture/widget, optional always-on personal peer.
- **M4 — optional extensions:** selective notebook sharing, clipping/OCR and optional document commitments. No chain feature may gate note access.

## Open-source requirements

The project licence is the Apache License 2.0 with the Commons Clause and a paid-product condition, in LICENSE — source-available rather than OSI open source, chosen deliberately (the paragraph that follows predates that choice). The vendored Maxima core is its author's, redistributed with their permission and credited in NOTICE; it is not under this licence. No copyright assignment is required for contributions.

Keep the Android client, browser/desktop client, sync protocol, any relay, migrations, tests and build scripts public. Use open formats and document encryption/recovery so an independent client can recover data. No proprietary analytics, login, push provider or paid backend is required. Publish build requirements, pinned dependencies, third-party notices, source tags and artifact hashes. Target independently reproducible APK builds; do not claim reproducibility until separate clean builds match. APK signing is separate from compilation; never commit signing keys. The repository was published on 2026-09-21 — private until the transport's author had been asked and had answered, which they did on 2026-09-22 — and v0.0.107 is its first release.

## Sources inspected 2026-09-13

- Core source/API/Apache-2.0: https://github.com/spartacusrex-minima/minima-core-android (pinned commit in research/core-upstream-commit.txt)
- Maxima transport: https://github.com/minima-global/docs/blob/main/content/docs/learn/maxima-about.mdx
- Maxima messaging/encryption: https://github.com/minima-global/docs/blob/main/content/docs/learn/maxima-messaging.mdx
- Joplin baseline: https://joplinapp.org/help/
- Notesnook baseline: https://notesnook.com/help/

Architecture choices above are proposals derived from these capabilities, not claims of features already implemented by Minima or Mininotes.
