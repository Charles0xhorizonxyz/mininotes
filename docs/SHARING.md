# Sharing notes — design

Status: built, and seen working between two phones in both directions. A note is sealed for the device it is going to, carried by this phone's own Maxima node, filed on the far phone in the collection and book it came out of, merged where both ends wrote, and — since v0.0.91 — taken in while the far pad is closed. What is not built is listed at the end, and it is a real list.

## What this is being simplified into

Decided on 18 September 2026, after the person whose app this is said plainly that he did not understand what had been built. Everything below this section describes what exists; this section is where it is going, and what exists is judged against it.

**It works the way Google Drive does**, because that is a set-up people already know and nobody has to be taught it.

| In Drive | Here |
|---|---|
| Your account, on every device | **Connect your own device** once, and the whole pad is the same on both |
| People you can share with | **People**: anyone whose code you scanned once |
| The Share box: people, and a role beside each | The same box, on any note, book or collection |
| Viewer · Editor · an editor who may share | **Read · Write · Admin** — admin may add people, remove them and change what they may do |
| The owner cannot be removed | The same |
| Sharing a folder shares what is in it, now and later | Sharing a collection or a book covers what is in it, now and later |

**What is shared with you stands among your own things**, in the collection and book it came out of, as now — but it has to *say so*, clearly, and say who has which role on it.

**Every thing says one of three things about itself**, and nothing else: it is only on this phone; it is shared and everybody has it; it is shared and somebody is still waiting for it. Tap it to see who.

**The measure of all of it: somebody installs this and works it out in a few taps.** Said by the person whose app it is, and it is the test every screen here is put to.

**One mark, one box.** The mark says which of the three it is. Tapping it opens one box, the same whatever the thing is and whoever it belongs to, in the order it is needed: the state, as a sentence; **Sync now**; **Sync automatically**; the people and what each may do; how to add somebody, or how to leave. Built in v0.0.95 and v0.0.96.

**Sync now is one tap and goes both ways.** What is waiting goes, and everybody who has the thing is asked for what they have — `Receipt.ASK` — because nothing here can be fetched, and a Sync that only sends does nothing on the phone that is behind, which is the phone people press it on.

**Sync automatically is a switch**, on unless somebody says otherwise. How long it waits after the writing stops — 3 seconds, 10, 30, 2 minutes — is one quiet line under it rather than a row of choices in front of everybody.

**Nobody's writing is shown live, so people are told first.** When somebody starts writing in a shared note, the others see *Ana is writing…* on their copy. It is a flag and not a lock: they may still write, and sometimes two people will. When they do, changes to different lines are both kept, and the merge underneath is what makes the flag safe to ignore.

**Underneath all of it, a phone answers.** A note that arrives and is written down is answered with *I have it*, and only that answer counts as delivered — see *A phone that answers*, below. Built in v0.0.93, because none of the three things a note says about itself can be true without it.

In the order it is being built:

1. ~~The answer.~~ Done, v0.0.93.
2. The Share box as Drive has it, with roles that are rules. **Partly done, v0.0.101**: the box is two sections — *Who has access*, where a person wears a round initial and their role drops down, and *Syncing*, which is switches and one drop-down — and a role now **arrives**: changing one sends at once (`Post.changed`), and the phone given it writes down its own standing (schema 20, `standing`) and says *Admin* where it could only say *Can write*. **v0.0.102**: an admin on the receiving phone can add somebody and change what the others may do, and everybody but the owner can **Unfollow** — which, unlike stopping, tells everybody who has the thing, makes the copy here this phone's own, and is undone only by being given the thing again (see *Leaving*, below). **v0.0.108: the roles are rules.** A note shared to be read is read: its page takes no keyboard, its title is not for changing, and it says *Read only* under its name. What arrives from somebody who may only read a thing - or who has been taken off it - is not written down, whatever build they are on, and is answered so they stop sending it. And a copy this phone may only read takes what its owner sends rather than merging with it. *Remove* now tells the person taken off, by the road *Unfollow* uses the other way, and their copy becomes their own; see *Leaving*.
3. *Ana is writing…*
4. Taking away what this replaces: the chip that goes round four states, the two sets of marks, the toggle for whose a device is, the address list, and the box left over from the Minima Core design.

## The four levels

The pad is a library of collections, each holding books, each holding notes. The shelves go one level at a time — `All collections` → a collection's books → a book's notes — with the trail across the top saying where you are, and sharing is set with the arrow on any row, or the arrow in the bar for the level you are looking at:

| Level | Where it is set | What it reaches |
|---|---|---|
| Everything | the arrow at the All collections level | every collection, book and note, including ones made later |
| A collection | the arrow on that collection | that collection and every book in it |
| A book | the arrow on that book | that book and every note in it |
| A note | the arrow on that note | that note alone |

Addresses are saved under a name the first time one is pasted, then picked from a list. Retyping a long address is how a note reaches the wrong person, so it is typed once.

A note is reached by every rule that covers it, so a rule set high up keeps applying to things added underneath it later — share a collection once and next month's book is already shared. An address reached at several levels is one recipient, not several. `Sharing.audience` decides this and is unit tested, including that a rule on one collection, book or note never reaches another.

## Who you share with

You name the devices, and for each thing you share you say what that device may do with it. Three levels, and the difference is not cosmetic:

- **Reads.** A copy that keeps itself up to date. What they write in it stays on their phone.
- **Reads & writes.** The same notebook in two places. What they write comes back and is merged into yours.
- **Admin.** Reads and writes, and may hand it on. This is the one that makes a shared thing a shared thing rather than a broadcast, which is why it is deliberately not the first tap.

It is decided per share, so the same device can have one book of yours to read and another to work in. Whether a device is *yours* — a tablet rather than a friend — is said once on the device, and decides only which mark the thing wears.

**Read is a rule, at both ends.** On the phone given a thing to read, the page takes no writing: no keyboard comes, the title is not for changing, an old version is to look at and not to put back, and the line under the name says *Read only* (once, on a tap, the page says whose it is to change). On every phone that receives, what arrives is weighed against what the sender may do with the thing *here* — by every rule that reaches the note, the most any says — before a word of it is written down. A reader's words, or those of somebody taken off, are not; and they are answered all the same, because a phone that is not answered sends the same thing every quarter of an hour for ever. The rule is asked *after* the list that came with the note has been folded in, so the first thing ever to arrive from somebody is reached by the line saying they have it.

**A copy you may only read is a copy.** What its owner sends is what it says: nothing on it is merged with what arrived, and where the copy said something else - written in on a build that let a reader write, or before they were made one - that is kept as a version and the page says what the owner says. The one thing still set aside is what is older than what already came from the same phone.

Nothing leaves the phone unless you named a device. A note can be shared by where it sits — that is what setting a rule on a book or a collection means — which is exactly why moving something between them has to be confirmed.

What other devices share with you stands on the shelves among your own things, in the collection and book it came out of, and says on itself where it came from. It is not kept apart in a box of its own.

## A membership everybody keeps

Every device that has a thing is written down, by the key it signs with, with its level and when that level was decided — and that list travels with the note. Without it every copy but one cannot say who else is reading, and only the phone that began the sharing could ever hand it on.

**A device, not an address.** An address moves; a key does not. The same phone at a new address is the same member rather than a second one.

**Two admins deciding at once settle by themselves.** Per member, the later *decision* stands — not the later message, which would depend on the weather. It is the only rule two phones out of touch with each other can both apply and agree on afterwards.

**Being taken off is something rather than nothing.** A removed member stays in the list at `GONE`. If they simply vanished, the next copy of the list from a phone that had not heard would put them back, for ever. `MembershipTest` pins that, the ordering, and that a level from a later build reads as the most this one knows rather than as nothing.

**A member with nowhere to reach them is not written down.** A phone does not know the address others reach it at, so it puts its own in the list it sends; a member who still arrives without one is placed from the note they came with or from a device already known, and otherwise left for the next list.

## Moving something changes who can read it

Deleting for good, from the bin, also deletes every rule that pointed at the thing and at anything inside it, so an address never keeps a claim on something that no longer exists. Archiving and binning change nothing about who a thing is shared with: it is still there, and putting it back puts back the rules with it.

Notes and books can be moved: `⋮` → Move somewhere else, and the screen becomes the places it could go. Dragging a line up or down inside its own level only reorders it — it stays where it lives, so nobody's audience changes and nothing is confirmed. Before anything is written, the app works out the audience that thing would have in its new home and compares it with the audience it has now, and any difference has to be confirmed by name — *Starts reaching: Ana (someone else)* — because dragging a note into a book its owner shares widely is a disclosure, and dragging it out is a withdrawal. A move that changes nobody is simply done. `Sharing.moving` decides this and is unit tested, including that a rule set on the note itself follows it and so counts as neither.

## Nothing is fetched: it is sent

Maxima carries a message from your node to a contact's node. It is not a shelf the other end can come and read, and there is no call that asks it for your own notes back: a node cannot retrieve its own notes through Maxima, because Maxima does not hold them. Anything that arrives anywhere arrives because a device sent it to an address that was listening.

Two things follow, and both are visible in the app.

**The sending side has to remember.** For every address and every note, the pad records which revision got through. Everything written after that is owed again. That record is the only thing that can say whether an address is up to date, so it is what the `↑` on a row is reading, and it is why only a delivery can clear one — never the app deciding that enough time has passed. `Outbox` decides what is owed and is unit tested, including that what one address received says nothing about another, that a note reached by two rules is owed once, and that a clock that went backwards cannot resurrect a delivery that already happened.

**Your other device is a contact like any other.** It runs its own node with its own address; you name it as a device of yours, and this phone sends to it. It receives when it is running and reachable, which is why the outbox keeps waiting rather than assuming. Sharing with yourself and sharing with someone else are the same mechanism; only the direction differs.

**It goes by itself.** What the open note owes is sent once the writing has stopped for as long as that thing asks for — 3 seconds, 10, 30, 2 minutes, or only *when I ask*, set on the thing's own card and inherited from whatever holds it when it is not. A shopping list two people are reading in a shop and a diary being written into are not the same thing. Everything owed to anybody is also sent when the pad opens, so a phone that was off while somebody wrote catches itself up.

`⋮` → **Sync now** says what a thing owes and to whom, and sends it. Each note is sealed once per address that is owed it and handed to this phone's own node; a delivery the transport accepted writes the row that clears the mark, and nothing else does. An address that has never been paired has no key to seal for, and is counted as a failure with a reason rather than skipped in silence.

Minima Core carries none of this and never did — Core's build has no Maxima in it at all. The transport is vendored and runs inside the app, which is why the pad is its own node rather than a client of one.

## The shelf travels with the note

A note on its own is not much use to the end receiving it. It came out of a book, in a collection, and
those are most of what says what it is *about* — dropped into whatever book happened to be first on the
other phone, the same note means something else.

So the sealed payload is a **parcel**: the collection's id and name, the book's id and name, the note's
title and body, and one flag saying what this share lets the far end do. It is the plaintext the envelope
seals, so all of it is exactly as private as the note is.

**The shelf is matched by id, never by name.** A second note out of the same book has to land beside the
first. Matching on the name would build a second book the moment somebody renamed theirs, and would merge
two different books that happened to share a name.

**Their id is not reused as it stands.** Every pad is made with the same first collection and the same
first book, under the same two ids — `collection-first` and `book-first`. A note shared out of somebody's
first book and filed under the id it arrived with would land inside *your* first book and look like
something you wrote; two people's collections would silently become one. So the local name for a shelf of
theirs is `Parcel.localId`: a digest of who sent it and what they call it. The same every time, so the
second note lands beside the first; different for every sender, so no two people's shelves can be confused
for each other. `ParcelTest` pins both halves of that.

A shelf built this way is marked `theirs` with the address it came from. What they call it is followed on
every arrival — a rename at their end is a rename at yours — but **where you put it is yours**, and a note
already here is never moved: somebody else deciding where your copy lives, every time they touch it, would
undo any tidying you had done.

A note sent by a build that had no shelf to send still reads. It comes back with no collection and no book,
and goes where anything whose shelf we were not told goes.

## Which way it is going, on the thing itself

Every collection, book and note carries a mark saying where it stands, drawn rather than taken from the
font — three marks that have to be told apart at twenty pixels cannot be left to whatever typeface the
phone happens to use.

| Mark | Means |
|---|---|
| an arrow leaving, outlined | you are sending this to somebody |
| arrows both ways, outlined | it is going to another device of yours and coming back |
| an arrow arriving, filled | it came from another device |

Nothing is drawn on a thing that is only on this phone: that is what everything is until it is shared, and
a mark on everything marks nothing.

The mark is also the control, because the question it raises — *who else has this?* — should be one tap
from the question. Tapping an outgoing mark opens who has it and what each of them may do. Tapping an
incoming one opens who shared it, what you may do with it, and the way out.

**"Another device", not "someone else".** What arrives may be from your own tablet as easily as from a
friend; both are devices, and calling both of them somebody else was wrong half the time.

## Unsubscribing, and what it cannot do

You can stop taking in what another device shares.

It **cannot stop them sending**. Only they can decide that, and no phone gets a say over another. What it
stops is the arriving being put on your shelves — which is the part this phone owns. Nothing new turns up;
what is already here stays until you delete it like anything else. The box says exactly that rather than
leaving somebody to work it out from what does not happen.

Refusing a book refuses the notes in it, and refusing a collection refuses everything under it — otherwise
unsubscribing from a book would stop nothing at all. A refusal is one row of `refused`, keyed by the
address and the thing, and it is checked before anything is written. The shelf is looked for under the name
it has *here* and by device rather than by address: until v0.0.102 it was looked for under a name made from
the sender's address, where shelves are filed by the sender's key, so pausing a book paused nothing.

## Leaving

**Pause receiving** is this phone's own business: it tells nobody, it wears the pause mark, and *Resume*
undoes it. **Unfollow** is a different thing, offered to everybody but the owner, and asked once.

- Everybody who has the thing is told, in the five bytes of an answer: *left a note*, *a book*, *a collection*
  (`Receipt.LEFT_*`). Which note rides in the envelope; for a shelf it is any note out of it, and the phone
  that hears finds the shelf from its own shelves. **When** rides where a revision does.
- The phone that hears takes them off **as a decision made at that time** — a row at *gone*, which an older
  list cannot undo, and which a *later* decision about them is not undone by. It stops waiting for their
  answers, and says *Ana unfollowed a note*.
- On the phone that left, what is here becomes its own (`NoteStore.letGo`): no sharing rows, no standing, not
  *theirs* — though where it came from is kept — and the empty ring for a mark. A shelf that was only ever
  here to hold what they sent becomes an ordinary shelf, and the worked-out line saying they have it goes.
- A row of `refused` with `gone=1` stays. What still arrives from somebody who has not heard is dropped **and
  they are told again**; and because the first telling may go to a phone that is asleep, it is said again at
  every opening for a week. Nobody answers it, so there is no knowing.
- **Being given it again brings it back, and nothing else does.** What arrives carrying this phone's own
  entry, decided *later* than the leaving, is an invitation: the leaving is forgotten and the note is theirs
  again.

**Remove is the same thing from the other side** (v0.0.108). Whoever has a say in a thing - its owner, or an
admin of it - takes somebody off it, and that is written down as a decision at *gone* with when it was made,
never as a row deleted: a deleted row came back with the next list, and the next word the removed person wrote
wrote them back in, since whoever sends a thing is written down as having it.

- The person taken off is told, in the same five bytes as leaving, the other way (`Receipt.REMOVED_*`): which
  note in the envelope, when it was decided where a revision goes. Their phone takes it only from somebody with
  a say in the thing, and only for a thing that is theirs, and then does what it does on leaving
  (`NoteStore.takenOff` → `letGo`): the copy is its own as of that moment, and everybody who had the thing is told
  they are off it - which reaches whoever else had not heard.
- Everybody else hears in the list, which goes at once with one note out of the thing (`Post.changed`).
- The telling is said again at every opening for a week (`Post.removedAgain`), as leaving is; a phone that has
  already heard, or that left by itself, does nothing about hearing it again. And whatever the removed phone
  still sends before it hears is not written down, and is answered with the telling.
- Being given the thing again brings it back, exactly as after leaving: an entry for this phone decided *later*
  than the taking off.

## What a file kept with a note does, which is stay here

A note can keep files: copies in the pad's own folder, one row apiece, drawn as chips under the writing. Sharing does not carry them yet, and the share sheet says so plainly rather than letting somebody find out at the other end.

The reason is size. A Maxima message is a message, not a transfer: it is bounded, so a file of any size has to be split into numbered pieces, reassembled at the far end, and the missing pieces asked for again — over a link that only goes one way, to a node that may not be running. That is a small protocol with its own state, its own retries and its own record of what got through, sitting beside the one the outbox already keeps for writing. It is worth building, and it is worth building after a note itself can be sent at all: pairing and a send command come first, and this rides on them.

Until then a file is `▫` — on this device only — whatever the note it sits in says, and a backup is the way to move one, which is why a backup became a zip.

## Carried by a third device

Nothing in Maxima waits, so two devices that are never on at the same moment never meet: the PC shut just after something was written in it, a phone that was out of signal just then. Since v0.0.128 (PC v0.0.018) a third device that is on carries it between them (`Courier`).

- **When a copy is left.** A note going to a device not heard from in the last two minutes is also *left* with up to three other paired devices whose build has said it carries (a flag at the end of every note, `Parcel.carries`; nothing of this goes to a device that has not said it, because an older build would read it as a note and write it over somebody's words). An answer to a note that was carried goes back the same way, or the writer would send it again for ever.
- **What the carrier holds.** The note exactly as it was sealed for the device it is for. The carrier cannot open it, change it unseen, or pass it off as its own; it knows who it is from, who it is for, which note by its id, and how big. One copy per sender, recipient, note and kind — a newer revision replaces an older — at most 500 things and 16 MB together, and nothing longer than 30 days (`carried`, schema 23).
- **Bringing it.** As soon as the device it is for is heard from, and otherwise after a minute, two, four, eight and then every ten, because a relay says yes for a device that is not there. The device it is for opens it as if it had come straight, answers the writer, and tells the carrier *collected* (`Receipt.COLLECTED`, `COLLECTED_ANSWER`); only that lets go of it.
- **What it cannot do.** With only two devices there is no third to carry. A carrier holds only for a device it is paired with itself. A carrier whose notebook is locked keeps what arrives in its inbox and carries it once opened. A message inside something carried is never carried again.

## Where sync actually stands, on a real phone

The pad runs its own Maxima node: the transport's core is vendored into the app, started with it, and attaches to the public relays. `⋮` → **Profile** shows the address it was given, the code another device scans, and a checklist of what is and is not working — whether a relay has answered, whether there is anybody to send to, whether their keys are known.

Seen between a Pixel 7 Pro and a GrapheneOS Pixel 7, in both directions: scanned, accepted, granted, sent, carried by a public relay, opened, filed on the right shelf, marked, and merged into a page that was open at the time. Each of those is recorded with the build it was seen on, in a verification log the maintainer keeps privately.

## Staying up while the pad is closed

Maxima is not a shelf. Nothing waits anywhere for this phone to come and fetch it, so a node that lives only while the pad is on screen makes sharing work when two people happen to have the app open together and do nothing when they do not.

`Listening` is a foreground service that keeps the process — and so the node — up after the pad is closed. Android allows that on one condition, which is that it says so for as long as it lasts, and that is the right condition. Three things keep it honest:

- **Only while there is somebody to hear from.** A pad that has never been paired keeps nothing running.
- **It can be switched off where it is.** `⋮` → **Profile** → *Listens while the pad is closed*, or **Stop listening** on the notification itself — which is where somebody wondering what it is will already be standing.
- **What arrives is said in one line that never quotes the note**: who, and what kind of thing. A notification is read by whoever is holding the phone, locked or not. Both channels are quiet by default: a shared list being written in sends every few seconds, and a pad that chimed each time would be switched off within the hour.

**The node has to be looked after, and for a long time was not.** The transport is a library and says in as many words that whoever carries it drives its upkeep: `maintain()` on a heartbeat. A relay stops reading from a client it has not heard from in ten minutes, and a keep-alive is due every two — so left alone, this phone was dropped by every relay it had within ten quiet minutes of opening and never went back for another. It looked exactly like a node that was working. `Node` now runs that upkeep every thirty seconds for as long as the process lives: keep-alives, a relay that has gone quiet swapped for one that answers, everybody told if that moved this phone.

**Three workers, not one.** The notebook's worker is serialized so that a read sees the writes before it, which is right for a notebook and wrong for a relay. Sending, pairing and asking the node for its address have a worker of their own, and the housekeeping a node wants when it has just come up — telling every contact where this phone now is, which the transport gives a minute and a half — has a third. A note somebody has just written does not wait behind a courtesy, and a word typed does not wait to be saved behind a note being sent.

## A phone that answers

The network can say it took a message. It cannot say anybody received one, and it will take a message for a phone that is asleep, switched off, or no longer taking that note. For a long time *delivered* was written on the network's word, and two things were leaning on it:

- **The mark.** It cleared when a relay said yes.
- **Every merge.** The text two phones are taken to have last agreed on was read from the same record. A phone that had been handing revisions to a relay for somebody who never got them believed they had its latest, weighed what they wrote next as *older*, and set it aside without a word. Seen on 18 September 2026: two words typed on one phone, kept under Versions on the other, on neither page, neither phone thinking anything was wrong.

So a note that goes asks to be answered, and the phone that gets it answers — `Receipt`, five bytes, sealed and signed like everything else, with the note and the revision in the envelope's own header — **after** it has written the note down, never before. Only that answer records a delivery. It is given whatever was decided about what arrived: taken, merged, or set aside as older than what was there. The question was *did it arrive*.

**What is never answered is sent again**: after a minute, then two, four, eight, and from then on every quarter of an hour, for as long as it takes. From the node's own upkeep, so it happens with the pad closed. Only a revision that already went once: a note written in since is the business of whatever that note is set to, which may be *when I ask*. `Outbox.due` decides and is unit tested.

**A note also says what it was written on top of** — the revision its sender believes both phones last had. The receiver has its own belief, and uses **the older of the two**. Both beliefs fail the same way, by taking the other phone to have more than it does, and the two mistakes are not alike: a base that is too old makes a merge work harder; one that is too new makes what the other phone never had look like something it had and deleted. `Arriving.agreed`, with last night's case pinned as a test.

**Only a phone that asks is answered.** A build from before answers existed reads anything it does not recognise as a note written the oldest way, as bare text — send it an answer it never asked for and it would write those five bytes over the note. `ReceiptTest` pins that an answer is not a note and a note is not an answer.

**Whoever sent a note has it.** Obvious, and it was not being written down, so every phone "owed" each note straight back to the phone it came from: a message each way for every note, and *Not sent yet* on a page nobody had touched.

**And the count was being compared with a clock.** What decides whether a note is owed compares the revision that was delivered with the revision the note is at. The second of those was being read from the wrong column — the time the note was last changed, in milliseconds, against a count of a few dozen. Nothing was ever up to date. Every shared note was owed for ever, *Not sent yet* showed whatever had happened, and the whole pad was sent again to everybody each time it was opened — which hid a good deal, because notes the network had lost turned up anyway the next morning and looked as though they had only been slow.

## The page that is open when something arrives

A page is a copy of a note, taken when it was opened. If the note is written in from the other end while the page is open — or while the pad was in a pocket with that page still on it — the page is holding the older text, and the next word typed would write that back over what arrived, one revision higher, and then send it: both ends lose the same words and neither is told.

So the page is brought up to the note rather than the other way round. Where nothing was typed since the page was last written down, it simply becomes what the notebook now says. Where something was, those words are in neither place, and they are put together the way any two writings are — line by line, against the text the page started from. `Arriving.onThePage` decides this and is unit tested.

And the writing itself checks: a page is written down only if the notebook has not moved past what that page last saw, in the same transaction. Arrivals come on the node's thread and pages are written on their own, so without that check the two could pass each other.

**An address is not what a device is.** A node that has not reached a relay yet gives out a permanent `MAX#<key>#<directory>` address, which is a key and a directory to go and ask rather than somewhere to send — and it resolves only once that node has published itself there. A code offered in a node's first seconds carried one of those and failed days later on somebody else's phone with `directory replied UNKNOWN`. Codes now carry a routable address or nothing, and a device is filed under its signing key, so scanning the same phone again moves the row it already has — with its share rules and its delivery records — instead of leaving a second copy pointing at an address nothing answers.

**An address is a snapshot; a contact is a peer.** A node that restarts or moves relay is somewhere else within the minute. So pairing also *introduces* the two nodes at the transport, which makes each a contact of the other: a stable identity key, every address it is currently reachable at, and a directory to ask when none of them answer. A note is handed to the peer rather than to an address they used to be at, and a phone that has just come up tells everybody who knows it where it is now.

Minima Core is not part of any of this. Nothing has to be installed beside the pad and nothing has to be enabled anywhere else; an earlier design asked Core to carry the post, and Core's build turned out to have no Maxima in it at all. [MAXIMA-TRANSPORT.md](MAXIMA-TRANSPORT.md) has that story.

## The sealed note

`Envelope` is the whole format. Per message: an ephemeral P-256 key agreed with the recipient's key (ECDH), HKDF-SHA256 to an AES-256-GCM key, the note encrypted under it, then ECDSA-P-256 over header and ciphertext by the sending device.

The app seals the note itself rather than trusting the transport. Maxima encrypts between nodes, but a node that relays or receives for you would otherwise handle readable notes; here it carries bytes it cannot read and cannot alter undetected.

The header — note id, revision, moment, sender key, ephemeral key, nonce — is authenticated as associated data, so none of it can be edited in flight. The sender's public key travels inside the message. That proves the message is intact and self-consistent; **it does not prove who sent it.** `open` hands back the sender's fingerprint and the caller decides whether that fingerprint is a device it paired with or a person it chose. Trusting a fingerprint you have never confirmed is the mistake this design exists to prevent.

Bounds are enforced while parsing, before allocation: a stranger's bytes cannot make the app reserve memory or crash. Refusals are refusals — a partly parsed note is never returned.

`revision` rides inside the seal so a receiver can drop a replay or a stale update without trusting a clock. `RevisionClock` decides ancestry between revisions; wall-clock time never does.

## Which address, and when the six digits matter

**It is the Maxima address**, the one a node shows as its contact address — it ends in `@host:port`. That is what carries a message from one node to another. A wallet address carries coins and cannot carry a note, so the app asks for the first and says so.

**A code scanned off the other screen needs no further check.** You are looking at the device you mean, and nothing came between the two of you: a photograph of a screen cannot be substituted by anything in the middle. So scanning pairs straight away, with nothing to compare.

**The six digits are for a line that travelled.** Pasted out of a message, a mail, or a note passed along, a pairing line could have been altered on the way. The digits come from both devices' keys, which is why one device alone cannot show them: they exist only for a pair. So the other side has to read your line too, and then both phones show six digits which must agree. The app says exactly that, and offers scanning as the way to avoid it.

**The code is drawn as a link, `mininotes://pair/…`, so the phone's own camera can open it** (v0.0.104, `Pairing.link`). A line of text shown to a camera gets an offer to search the web for it; a link this app answers to gets an offer to open it here, and somebody handed a code need not know there is a scanner inside the app. The line inside is unchanged; the scanner in the app and *Paste* read either. Two things follow from its arriving from outside. **Nothing it brings is kept without being asked about** — an introduction scanned in the app pairs at once, because scanning it there is somebody saying "this device"; opened from outside it asks first. And **only a camera is taken at its word**: a link that came any other way is a line that travelled, and gets the six digits. If a camera somewhere shows the link as text and will not open it, the remedy is an ordinary web link, which wants the project to have a site of its own.

## Two people wrote at once

Nobody's writing is lost, and nothing is invented. That is the whole rule; everything below is how it is kept.

**Revisions are counted, not timed.** Every writing of a note puts its revision up by one. Two phones whose clocks disagree cannot be sorted by time without losing somebody's work, so time is never what decides. What decides is what each side had when they last agreed — the revision an address was last given, which the outbox already records.

**Four cases, and only one of them needs anybody's attention.** `Arriving` decides which, from four facts and nothing else, and is unit tested:

- **New.** Nothing here by that name. Keep what arrived.
- **Older.** This phone has written past it. Ignore it, and say nothing — it carries nothing this phone does not already have.
- **Newer.** It descends from what is here, so it contains it. Take it.
- **Merged.** Both sides wrote since they last agreed. `Merge` puts the two together.

**The merge works on lines, against the last text both sides had.** Each side is read as what it *changed* — a few stretches of lines replaced, removed or added. Where the two changed different stretches, both changes are taken: you rewrote line three, they rewrote line nine, and the note ends up with both, silently and correctly. Changes that merely sit next to each other are separate stretches, not a conflict. This is the common case and it needs no one's attention.

**Where both wrote over the same lines, nothing is chosen for you, and nothing is put away: the note keeps what each of them wrote, both of it.** What the two share is there once; what they do not share is there from each; whoever is reading deletes the line they do not want. `Merge.both`, in one order whichever phone works it out, so the two phones come to the same note and not to two.

It used to keep this phone's lines on the page and put the other phone's under Versions. That was careful, and it was wrong in the way that matters. Two phones each kept their own, each told the other it had the other's, and the result — seen on 19 September 2026 — was two ticks over two different notes, 91 characters on one phone and 118 on the other, with the difference somewhere nobody looks and nothing on either screen to press. The person whose notes they were asked three times how to sync them. There was nothing to figure out.

**Arrived is not agreed.** The text a merge is made against is the text two phones last *both had*, and that is not the last thing delivered: a phone can receive a note, put it together with its own, and end somewhere else. So an answer says which it was — `TOOK`, my note now says exactly what you sent, or `HAVE`, it arrived and was put with my own — and only the first moves what the two are taken to have agreed on. The mark is about delivery; the merge is about agreement; they were one number and are two (`sent.revision`, `sent.agreed`).

**Sync on a note sends it whole and asks for it whole**, whatever either phone believes the other has. What pressing Sync means is *make these the same*.

## Everything a note has said

Every note keeps its history: what it said, when that was kept, and where it came from — this phone, or the address that sent it. A version is kept when an editing session ends rather than at every keystroke, so the list reads as a history and not as a keystroke log, and a hundred of them are kept per note, which for text is nothing.

`⋮` → **Versions** lists them, any of them can be read whole, and **Put this back** writes it as a *new* version rather than erasing what happened in between. Nothing in this app ever removes a version to make room for another one except the oldest beyond the hundred.

This is also what makes the conflict case safe: a conflict is nothing more exotic than two versions sitting in a list you can already read.

## Keys at rest

Each device holds two EC keypairs, one to sign and one to agree. Android Keystore cannot hold an ECDH key below API 31 and this app supports API 28, so the private keys live in app storage sealed under an AES-GCM key that the Keystore does hold. Pairing tokens and note text must never reach logs or OS cloud backup; `data_extraction_rules.xml` already excludes the app's storage from both.

## Not built

**Pausing does not tell anybody.** A phone that has *paused* a note hears it, sets it aside and says nothing, by design — so the phone sending it never gets an answer, its mark says *waiting* for ever, and it goes on trying every quarter of an hour. *Unfollow* (v0.0.102) and *Remove* (v0.0.108) are the two ways of going that do say so; see *Leaving*.

**An admin adding a third device has not been seen.** There are two phones. What is pure in it is tested — a shelf keeps one name whoever sends it, and a list for the owner's own book comes home to that book (`ComingHomeTest`) — and the rest waits for a third phone. One thing is known to be wrong already: the third phone will call whoever *sent* it the note the owner, because *owner* is worked out from where a note arrived from.

**It stops hearing when the phone goes into its deep sleep.** Measured: a Pixel 7 Pro off its charger with the screen off was in Doze within twelve minutes, and in Doze Android cuts an app's network whether or not it has a foreground service. The process stayed up, the service stayed up, and nothing arrived — and the note sent to it in that time was *taken by a relay and lost*, which is the item above seen happening. The only way through is for the person to exempt the app from battery optimisation, which is theirs to grant and has a cost, so nothing asks for it yet. Until then the honest description is: it listens while the pad is closed *and the phone is awake or charging*.

**It does not start again by itself after the phone restarts.** The pad has to be opened once.

**The list is taken at its word.** Every phone folds in the membership that arrives with a note before asking what the sender may do, and the later decision wins per person. So a phone that *claimed* to have been made an admin, in a list it wrote itself, would be believed. Nothing in the app writes such a list, and a stranger cannot: the envelope has to be signed by a device paired here. It is a thing a changed build could do, and the answer - a decision signed by whoever made it - is a change to what travels.

**A reader's phone still sends.** It has nothing of its own to send, but what is owed is worked out from who has the thing, and a copy that arrived from the owner is owed, by that reckoning, to the other people on the list; it goes, is not written down at the other end, and is answered, after which nothing is owed. One message per person per note, once. It could be spared by not counting a read-only copy as owed at all.

**Attachments do not travel.** A Maxima message is a message, not a transfer, so a file needs splitting, reassembling and asking again for the pieces that did not arrive — a small protocol with its own state beside the one the outbox already keeps. The share box says so rather than letting somebody find out at the other end.

**What failed to go is not tried again on a clock.** It goes when the writing next stops, when the pad is next opened, or when somebody asks — not because ten minutes have passed.

**Unsubscribing is local.** It stops this phone putting what arrives on its shelves; it cannot stop the other end sending, and there is no message that asks them to.

Open questions worth settling: how long an outbox should retry when a recipient is offline, what a Maxima message expiring means for something still owed, and what a reader sees when a note they were sent stops being updated because it was revoked.

And one that wants arguing before anybody writes it: **two phones that wrote over the same line may never converge by themselves.** Each keeps what it was showing, which is right. But each then counts a new revision for a text it did not change, the other takes that for news, and the two go on owing each other the same two texts until a person puts one of them back from Versions. This is reasoned from `Arriving.weigh` and has not been watched happening. The likely answer: what arrives unchanged from the text both sides last agreed on carries nothing, whatever its number says, and should be weighed as older.
