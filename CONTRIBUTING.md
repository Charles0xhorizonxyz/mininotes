# Contributing

Mininotes is free software under the GNU General Public License, version 3 or
later ([LICENSE](LICENSE)). By contributing you agree that your work is
published under that licence. Submit only work you have the right to
contribute, and keep third-party attribution intact. No copyright assignment is asked for.

## Saying something without writing code

A report is a contribution. **⋮ → Feedback** in the app fills in the form for
you; the same form is [here](../../issues/new?template=feedback.yml). Everything
said so far is under the `feedback` label. [docs/FEEDBACK.md](docs/FEEDBACK.md)
explains what travels with a report, what must never go in one, and how the app's
fields and the form's fields are kept in step.

If you change `Feedback.Kind` or `Feedback.Area`, change
`.github/ISSUE_TEMPLATE/feedback.yml` in the same commit. Nothing compiles the
YAML, so nothing will tell you.

## Before you write code

Open an issue first for anything touching the sync protocol, the sealing, the
schema migrations or the backup format. Those four can lose somebody's writing,
and a change to any of them has to be argued before it is written.

Two rules the whole project is built on:

- **The pad works with no node, no network and no account.** Anything that syncs
  is an addition to that, never a condition of it.
- **Nothing silently loses writing.** Maxima accepting a message is not proof it
  arrived; a merge that guesses is worse than one that asks.

## House style

- Java, no AndroidX, no Compose, no XML layouts. Views are built in code.
- Anything that can be decided without a device lives in its own class with no
  Android types in it, and is unit tested. `Merge`, `Arriving`, `Pairing`,
  `Outbox`, `Tint`, `Qr` and `Attachment` are all that shape — follow it.
- Schema changes are ordered additive steps in `SchemaMigrations`, never an
  edit to an existing step, and each one gets a test.
- Comments say why, not what.

## Checks

```sh
cd android && ./gradlew testReleaseUnitTest lintRelease assembleRelease
```

Add tests for changed persistence, merge or sync behaviour. Screenshots are not
evidence on their own; say what you ran and on what. If you tested on a device,
say in the pull request what you did, on which phone and on which build - and,
every time, what you did *not* see.

## Never commit

Notes, backups, credentials, pairing lines, private keys, keystores, Maxima
addresses, device identifiers or logs from a real phone. Use made-up fixtures.
`android/local.properties`, `*.jks` and `*.keystore` are ignored; check anyway.

## Security

Do not open a public issue for a security problem. See [SECURITY.md](SECURITY.md).
