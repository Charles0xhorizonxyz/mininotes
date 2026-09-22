# Feedback

Everything anybody says about Mininotes ends up in one place: the repository's
issues, labelled `feedback`. Open, readable by anyone, answerable by anyone, and
countable — so "three people said this" is a fact rather than a feeling.

Read the whole lot:
<https://github.com/Charles0xhorizonxyz/mininotes/issues?q=is%3Aissue+label%3Afeedback>

## From the app

**⋮ → Feedback.** Pick what sort of thing it is and which part of the app it is
about, write what you want to say, and tap **Post it**.

That opens the repository's feedback form in a browser with every box already
filled in. You read it over, tick the line saying there is nothing personal in
it, and post it under your own name.

**The app does not post it for you, and it never will.** Posting on your behalf
would mean a GitHub token inside the app, and a token inside an app that anybody
can download is a token anybody has. Handing a filled-in form to the browser
costs one extra tap and keeps the report yours: your name on it, your account,
and you can edit or delete it afterwards.

If the repository is not reachable — or you would rather send it some other way —
**Copy it** puts the whole report on the clipboard as plain text.

## What goes with it

One line, and it is shown to you in the box before you send anything:

    Mininotes 0.0.58 · Android 16 · Pixel 7

The version so a fault can be placed in time, the Android version and the phone
so it can be placed in hardware. Nothing else. No identifier, no note content,
no address, no network call — the app does not talk to GitHub at all, it opens a
browser at a web address it has built out of what you typed.

## Say what the app did, not who you are

This is public the moment it is posted, and stays public. The box says so before
you type rather than in small print underneath.

- No names, yours or anybody's.
- No Maxima or Minima addresses, except the one field that asks for one.
- Nothing off a note you have written. If a screenshot shows a note, blank it.
- Nothing from a backup file.

A **security** problem does not go here. Report it privately:
<https://github.com/Charles0xhorizonxyz/mininotes/security/advisories/new>

## The Minima address field

Optional, and public like everything else in the form.

**There is no fund, and nothing is promised.** The field exists so that if the
people who improved this are ever paid, there is a list of who they were and
where to send it — rather than trying to reconstruct one afterwards from two
years of issue threads. Leave it blank and your report counts exactly the same.

## The categories

Both lists are short on purpose: a list you can answer without thinking is a list
people answer.

**What sort of thing** — each one also sets a label:

| In the app | Label |
|---|---|
| Something is broken | `bug` |
| Something is confusing | `confusing` |
| An idea | `idea` |
| Something is missing | `missing` |

**Which part of the app** — the words the app itself uses, each one a thing you
were doing when you decided to say something:

Writing a note · Collections and books · Sharing and syncing · Attachments ·
Backup and restore · Text size and colour · The node and addresses ·
Something else

## Keeping the two ends in step

The app's fields and the form's fields are the same fields. A prefilled GitHub
issue form matches each query parameter to a field `id`, and a dropdown value
has to be one of the options listed:

| Field `id` in `.github/ISSUE_TEMPLATE/feedback.yml` | Where it comes from |
|---|---|
| `kind` | `Feedback.Kind` — the enum's `said` |
| `area` | `Feedback.Area` — the enum's `said` |
| `what` | what was typed |
| `about` | version, Android version, phone |
| `minima` | the optional address |

Change one end and change the other, or the form opens with that box empty and
nobody notices until a report arrives unfiled. `FeedbackTest` pins the parameter
names and the exact dropdown text, so the app side cannot drift quietly; the YAML
side is not compiled by anything and has to be kept honest by hand.

**The labels have to exist.** A label an issue form asks for that the repository
does not have is dropped without a word, which would leave every report unfiled
and the whole lot unfindable. Run the **Labels** workflow once from the Actions
tab after creating the repository — it creates all five with `gh`, using the
repository's own token, and can be re-run safely.

## Talking rather than filing

Not everything is worth an issue. A Parlons group would be the place for the rest
— but see the constraint below before planning around one.

**Parlons groups hold twelve members, including you, and there is no join link.**
The transport seals one copy of every post per member on the sender's device, so
twelve is where a group is still instant to post to; and `ChatEngine` ignores any
group it was not explicitly added to, on purpose, so that nobody can drop you into
one unasked. There is no invite code and no link to hand out: an admin adds each
person by their identity public key.

So a Parlons group can be a room for a dozen regulars — worth having — but it
cannot be the public channel for an app with users. Anything meant to be found by
somebody who has just installed Mininotes belongs in the issues, which is why the
app points there and not at a chat.
