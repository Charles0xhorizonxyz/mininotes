# Security

## Reporting

Report privately, not in a public issue:

- GitHub → **Security** → **Report a vulnerability**
  ([private advisories](https://github.com/Charles0xhorizonxyz/mininotes/security/advisories/new)).

Say what you did, what happened, and what you expected. A proof of concept
helps. You will get a first reply within a week, and will be credited in the
release notes unless you would rather not be.

Please do not test against anybody else's phone, node or address.

## What is supported

The latest release. There are no backports to older builds.

## What this app protects, and what it does not

**Protected.** Anything sent between paired phones is sealed end to end: ECDH on
P-256, HKDF, AES-GCM, and an ECDSA signature over what was sent. The keys are
generated on the phone and never leave it — they are kept sealed under a key
held in the Android Keystore. A pairing line carries public keys only. The six
digits both screens show come from the two keys themselves, so a line altered on
the way will not agree.

**Not protected.**

- **Notes on the phone are not encrypted at rest.** Anybody who can read the
  app's data directory can read the notes. Protect the phone.
- **Backups are not encrypted.** A backup file is the whole pad in the clear.
- **Metadata.** Maxima carries who talked to whom and when.
- **A phone somebody else controls.** Once you share a note with an address, the
  phone behind it has the note. Removing the share stops what comes next; it
  does not unsend what has gone.
- **Minima Core.** The node is a separate application with its own trust
  boundary. Mininotes asks it to send and receive, and cannot vouch for it. Core
  will not answer an app at all until you enable that app in Core's own list.

## Scope

In scope: this repository. Out of scope: Minima Core, the Minima node, Maxima
itself, and anything that needs physical access to an unlocked phone.
