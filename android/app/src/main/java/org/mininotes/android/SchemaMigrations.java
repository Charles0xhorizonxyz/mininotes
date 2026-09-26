// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered schema of the local notebook. An upgrade is a sequence of additive statements, never a
 * drop and recreate: an installed build must not lose notes because the schema moved. Holds no Android
 * types, so the version rules are unit tested without a device.
 */
final class SchemaMigrations {
    static final int VERSION=23;

    /** Every pad starts with one collection holding one book, so writing never begins with a decision. */
    static final String FIRST_COLLECTION="collection-first", FIRST_BOOK="book-first";

    private static final String NOTES_TABLE=
        "CREATE TABLE notes(id TEXT PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL,notebook TEXT NOT NULL,"
        +"pinned INTEGER NOT NULL,deleted INTEGER NOT NULL,updated INTEGER NOT NULL)";
    /** The list filters on deleted and orders by pinned then updated; a reverse index scan serves that directly. */
    private static final String VIEW_INDEX="CREATE INDEX IF NOT EXISTS notes_view ON notes(deleted,pinned,updated)";

    private static final String NOW="CAST(strftime('%s','now') AS INTEGER)*1000";
    private static final String COLLECTIONS="CREATE TABLE IF NOT EXISTS collections(id TEXT PRIMARY KEY,name TEXT NOT NULL,updated INTEGER NOT NULL)";
    private static final String BOOKS="CREATE TABLE IF NOT EXISTS books(id TEXT PRIMARY KEY,collection TEXT NOT NULL,name TEXT NOT NULL,updated INTEGER NOT NULL)";
    /** One row per address that a level is shared with. The primary key makes adding the same address twice a no-op. */
    private static final String SHARES="CREATE TABLE IF NOT EXISTS shares(scope TEXT NOT NULL,target TEXT NOT NULL,address TEXT NOT NULL,"
        +"mine INTEGER NOT NULL,added INTEGER NOT NULL,PRIMARY KEY(scope,target,address))";
    private static final String BOOK_COLUMN="ALTER TABLE notes ADD COLUMN book TEXT NOT NULL DEFAULT '"+FIRST_BOOK+"'";
    private static final String FIRST_COLLECTION_ROW="INSERT OR IGNORE INTO collections(id,name,updated) VALUES('"+FIRST_COLLECTION+"','My notes',"+NOW+")";
    private static final String FIRST_BOOK_ROW="INSERT OR IGNORE INTO books(id,collection,name,updated) VALUES('"+FIRST_BOOK+"','"+FIRST_COLLECTION+"','Notes',"+NOW+")";
    /** Addresses you share with, kept once so they are picked rather than pasted each time. */
    private static final String ADDRESSES="CREATE TABLE IF NOT EXISTS addresses(address TEXT PRIMARY KEY,name TEXT NOT NULL,"
        +"mine INTEGER NOT NULL,added INTEGER NOT NULL)";
    private static final String BOOKS_INDEX="CREATE INDEX IF NOT EXISTS books_collection ON books(collection)";
    private static final String PAGES_INDEX="CREATE INDEX IF NOT EXISTS notes_book ON notes(book,deleted,updated)";

    /**
     * The order the reader put things in, one number per row, smallest first. Seeded as the negative of
     * `updated` so an upgraded pad opens in exactly the order it closed in — newest first — and so anything
     * made later, which also starts at minus the clock, arrives at the top rather than at the end.
     */
    private static final String[] PLACES={
        "ALTER TABLE collections ADD COLUMN place INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE books ADD COLUMN place INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE notes ADD COLUMN place INTEGER NOT NULL DEFAULT 0",
        "UPDATE collections SET place=-updated",
        "UPDATE books SET place=-updated",
        "UPDATE notes SET place=-updated",
        "CREATE INDEX IF NOT EXISTS notes_place ON notes(book,deleted,place)",
    };

    /**
     * Nothing is thrown away by surprise. A page already carried `deleted`, which now means the bin rather
     * than gone; collections and books gain the same, and all three gain `archived` for what is only put
     * away. Existing soft-deleted pages therefore turn up in the bin, which is where they belong.
     */
    private static final String[] AWAY={
        "ALTER TABLE collections ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE books ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE notes ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collections ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE books ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
    };

    /**
     * The colour a single collection, book or page was given, as which colour rather than as a pixel value,
     * so it holds when the paper under it moves. 0 is no colour of its own, which is what everything starts
     * as and what an unknown number falls back to.
     */
    private static final String[] COLOURS={
        "ALTER TABLE collections ADD COLUMN colour INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE books ADD COLUMN colour INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE notes ADD COLUMN colour INTEGER NOT NULL DEFAULT 0",
    };

    /**
     * Which revision of which page reached which address. Maxima carries a message to a contact rather than
     * holding it for collection, so the sending side is the only place that can know what got through: a row
     * appears here when an address has received a page, and everything written after it is owed again.
     */
    private static final String[] OUTBOX={
        "CREATE TABLE IF NOT EXISTS sent(address TEXT NOT NULL,page TEXT NOT NULL,revision INTEGER NOT NULL,"
        +"at INTEGER NOT NULL,PRIMARY KEY(address,page))",
    };

    /**
     * Files kept with a note. The bytes live in the app's own folder, one file to a row; this table is what
     * says whose they are, what they were called and how big they are. They follow their note: putting it
     * away takes them with it, and deleting it for good deletes them for good.
     */
    private static final String[] FILES={
        "CREATE TABLE IF NOT EXISTS files(id TEXT PRIMARY KEY,note TEXT NOT NULL,name TEXT NOT NULL,"
        +"kind TEXT NOT NULL,bytes INTEGER NOT NULL,added INTEGER NOT NULL,place INTEGER NOT NULL DEFAULT 0)",
        "CREATE INDEX IF NOT EXISTS files_note ON files(note,place)",
    };

    /**
     * A file can be kept with a collection or a book as well as a note. The column the file already had
     * holds whichever one it belongs to; this says which kind that is. Everything already in the table was
     * kept with a note, which is what the default says, so nothing has to be moved.
     */
    private static final String[] FILES_ANYWHERE={
        "ALTER TABLE files ADD COLUMN held TEXT NOT NULL DEFAULT 'note'",
        "CREATE INDEX IF NOT EXISTS files_held ON files(held,note,place)",
    };

    /**
     * What a device you have paired with is, beyond an address: the keys to seal for it and to check it by,
     * and the name Maxima knows it under so a message can be handed to it. And, on a note, whether it came
     * from somebody else and which address it came from — a note of theirs is never written back to them.
     */
    private static final String[] PAIRED={
        "ALTER TABLE addresses ADD COLUMN contact TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE addresses ADD COLUMN agreement TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE addresses ADD COLUMN signing TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE notes ADD COLUMN theirs INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE notes ADD COLUMN origin TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE notes ADD COLUMN revision INTEGER NOT NULL DEFAULT 0",
    };

    /**
     * Every version a note has had: what it said, when that was kept, and where it came from — this phone,
     * or an address that sent it. Nothing is ever overwritten out of existence: putting an old version back
     * writes a new one rather than erasing what happened in between, and a note two people wrote at once
     * keeps both sides here rather than choosing between them silently.
     */
    private static final String[] VERSIONS={
        "CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY,note TEXT NOT NULL,revision INTEGER NOT NULL,"
        +"at INTEGER NOT NULL,source TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS versions_note ON versions(note,at DESC)",
    };

    /**
     * A collection and a book can have come from somebody else, the same way a note already could, and a
     * shelf you were given has to be told apart from one you made. And what this phone will no longer take
     * in: unsubscribing cannot stop somebody sending - only they can decide that - but it can stop what
     * they send being put on the shelves, which is the part this phone owns.
     */
    private static final String[] SHARED_IN={
        "ALTER TABLE collections ADD COLUMN theirs INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE collections ADD COLUMN origin TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE books ADD COLUMN theirs INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE books ADD COLUMN origin TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE notes ADD COLUMN writes INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS refused(address TEXT NOT NULL,target TEXT NOT NULL,"
        +"kind TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(address,target))",
    };

    /**
     * An offer taken up, until the phone that made it answers.
     *
     * <p>An acceptance is one message to a phone that may be asleep, in a drawer, or being updated - and a
     * message to a node nobody is listening on is gone. Kept here instead, and said again every time this
     * app opens, until something arrives from them: otherwise accepting something works when the two
     * phones happen to be awake together and silently does nothing when they are not.
     */
    private static final String[] ACCEPTING={
        "CREATE TABLE IF NOT EXISTS accepting(address TEXT PRIMARY KEY,name TEXT NOT NULL,"
        +"scope TEXT NOT NULL,target TEXT NOT NULL,writes INTEGER NOT NULL,at INTEGER NOT NULL,"
        +"tries INTEGER NOT NULL DEFAULT 0)",
    };

    /**
     * A collection or a book can be a favourite, the way a note already could.
     *
     * <p>Notes have carried `pinned` since the first schema and nothing ever read it. It is what a
     * favourite is; the other two levels only lacked the column.
     */
    private static final String[] FAVOURITES={
        "ALTER TABLE collections ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE books ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX IF NOT EXISTS notes_recent ON notes(deleted,archived,updated)",
    };

    /**
     * Who has a thing, kept by everybody who has it.
     *
     * <p>A share was one row saying "this address may write": enough for one phone handing something out,
     * and not enough for a thing several people hold. It gains what it takes to be a membership that can
     * travel and be merged — the level rather than a yes or no, the device by the key it signs with
     * rather than by an address that moves, and when the decision was made, so that two people deciding at
     * once settle on the later decision rather than on whichever message arrived last.
     *
     * <p>Existing rows keep their meaning: what was "may write" becomes WRITE, and what was not becomes
     * READ. `changed` is seeded from when the row was added, so an old decision never outranks a new one.
     */
    private static final String[] MEMBERS={
        "ALTER TABLE shares ADD COLUMN level INTEGER NOT NULL DEFAULT 1",
        "ALTER TABLE shares ADD COLUMN changed INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE shares ADD COLUMN who TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE shares ADD COLUMN name TEXT NOT NULL DEFAULT ''",
        "UPDATE shares SET level=CASE WHEN mine=1 THEN 2 ELSE 1 END",
        "UPDATE shares SET changed=added WHERE changed=0",
    };

    /**
     * How long a thing waits after the writing stops before it goes.
     *
     * <p>Zero means "whatever the thing above says", so a collection can be set once and every book and
     * note in it follows; a negative number means it waits to be asked. Set on any of the three levels,
     * because a shopping list somebody is reading in a shop and a diary are not the same thing.
     */
    private static final String[] PAUSE={
        "ALTER TABLE collections ADD COLUMN pause INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE books ADD COLUMN pause INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE notes ADD COLUMN pause INTEGER NOT NULL DEFAULT 0",
    };

    /**
     * What the network took, and nobody has yet said they have.
     *
     * <p>`sent` was written when the network took a message, and read as though somebody had received it.
     * Those are different events. From here on `sent` means what it was always read as — the far phone said
     * it has this — and this table holds the time in between: handed over, when, and how many times, so
     * that what was never answered can be tried again. Rows already in `sent` are left as they are: most
     * of them are true. Where one is not, it claims the far phone has more than it does - and a note now
     * says what it was written on top of, so the older of the two beliefs is the one a merge uses.
     */
    private static final String[] HANDED={
        "CREATE TABLE IF NOT EXISTS handed(address TEXT NOT NULL,page TEXT NOT NULL,revision INTEGER NOT NULL,"
        +"at INTEGER NOT NULL,tries INTEGER NOT NULL,PRIMARY KEY(address,page))",
    };

    /**
     * What two phones last both had, kept apart from what was last delivered.
     *
     * <p>`sent.revision` is the revision the far phone has said it received, and it is what the mark
     * shows. `agreed` is the revision at which its note and this one said the same thing, and it is what a
     * merge is made against. They were one number, and they are not one thing: a phone can receive a note,
     * put it together with its own, and end somewhere else. Nought for every row there is, which is "never
     * agreed": the first time two copies meet after this they are put together with nothing assumed, so
     * where they differ both keep everything, and where they are the same nothing happens at all.
     */
    private static final String[] AGREED={
        "ALTER TABLE sent ADD COLUMN agreed INTEGER NOT NULL DEFAULT 0",
    };

    /**
     * What this phone itself may do with a thing another device shares with it.
     *
     * <p>Every member of a shared thing is written down in `shares` - except the phone doing the writing,
     * which leaves its own entry out, and has to: a row there is somebody to send to, and a phone that
     * wrote itself down sent itself its own notes. So its own standing had nowhere to live, and a phone
     * made an admin went on saying it could write, because whether it could write was the only thing about
     * itself it kept. Here, with when it was decided, so an older word never overrules a newer one.
     */
    private static final String[] STANDING={
        "CREATE TABLE IF NOT EXISTS standing(scope TEXT NOT NULL,target TEXT NOT NULL,level INTEGER NOT NULL,"
        +"changed INTEGER NOT NULL,PRIMARY KEY(scope,target))",
    };

    /**
     * Stopped for now, or left.
     *
     * <p>`refused` was one thing: what this phone has stopped taking in, which it can start again by
     * itself. Leaving is another. The others are told, the copy here becomes this phone's own, and only
     * being given the thing again brings it back - so what arrives late from somebody who has not heard
     * yet has to be told apart from an invitation that is newer than the leaving, and `at` is what it is
     * told apart by.
     */
    private static final String[] LEAVING={
        "ALTER TABLE refused ADD COLUMN gone INTEGER NOT NULL DEFAULT 0",
    };

    /**
     * What was given at pairing time is what the offer said.
     *
     * <p>Since rows have had a level, the one thing that wrote a row without one was giving somebody a
     * thing off their code: `mine` went in as the offer said, `level` was left to the table's default,
     * and the default is <i>read</i>. Nothing read `mine` any more, so everybody given something that way
     * was a reader by the column everything reads - which showed nowhere while a reader could still write,
     * and would have made every such share read-only the day that stopped. Those rows, and only those,
     * say one thing in `mine` and another in `level`; they are made to agree with the offer, and dated from
     * when they were made, so that they can be outranked by a decision and not by any list that arrives.
     */
    private static final String[] GIVEN={
        "UPDATE shares SET level=2 WHERE level=1 AND mine=1",
        "UPDATE shares SET changed=added WHERE changed=0",
    };

    /**
     * What this device is carrying for others, sealed for somebody else: see {@link Courier}. One row per
     * sender, recipient, note and kind, so a newer revision replaces an older one; the bytes as text, since
     * nothing else here is kept as bytes. `tried` and `tries` say when it was last brought, and how often.
     */
    private static final String[] CARRIED={
        "CREATE TABLE IF NOT EXISTS carried(sender TEXT NOT NULL,recipient TEXT NOT NULL,page TEXT NOT NULL,"
        +"sort INTEGER NOT NULL,revision INTEGER NOT NULL,bytes TEXT NOT NULL,size INTEGER NOT NULL,"
        +"kept INTEGER NOT NULL,tried INTEGER NOT NULL DEFAULT 0,tries INTEGER NOT NULL DEFAULT 0,"
        +"PRIMARY KEY(sender,recipient,page,sort))",
    };

    /** STEPS[i] upgrades a database at version i+1 to version i+2. */
    private static final String[][] STEPS={
        {VIEW_INDEX},
        // Pages gain the book they sit on. Existing notes default into the first book rather than being moved
        // by a statement that could half-run, and the two seed rows are ignored if a fresh install made them.
        {COLLECTIONS,BOOKS,SHARES,BOOK_COLUMN,FIRST_COLLECTION_ROW,FIRST_BOOK_ROW,BOOKS_INDEX,PAGES_INDEX},
        // 3 -> 4: the addresses you share with become a list of their own.
        {ADDRESSES},
        // 4 -> 5: every collection, book and page carries the place the reader dragged it to.
        PLACES,
        // 5 -> 6: the archive and the bin, as two flags every thing carries.
        AWAY,
        // 6 -> 7: a colour of its own, for anything that wants one.
        COLOURS,
        // 7 -> 8: what has reached which address, so what is still owed can be shown.
        OUTBOX,
        // 8 -> 9: files kept with a note.
        FILES,
        // 9 -> 10: what a paired device is, and which notes came from one.
        PAIRED,
        // 10 -> 11: every version a note has had.
        VERSIONS,
        // 11 -> 12: a file can be kept with a collection or a book, not only with a note.
        FILES_ANYWHERE,
        // 12 -> 13: a shelf can have come from somebody, and this phone can stop taking what they send.
        SHARED_IN,
        // 13 -> 14: an offer taken up, said again until the phone that made it answers.
        ACCEPTING,
        // 14 -> 15: a collection or a book can be a favourite too.
        FAVOURITES,
        // 15 -> 16: a share becomes a membership: a level, a device, and when it was decided.
        MEMBERS,
        // 16 -> 17: how long a thing waits after the writing stops before it goes.
        PAUSE,
        // 17 -> 18: handed to the network is not the same as arrived.
        HANDED,
        // 18 -> 19: and arrived is not the same as agreed.
        AGREED,
        // 19 -> 20: what this phone itself may do with what is shared with it.
        STANDING,
        // 20 -> 21: stopped for now is not the same as left.
        LEAVING,
        // 21 -> 22: what was given at pairing time is what the offer said.
        GIVEN,
        // 22 -> 23: what this device carries for two others that are not on at the same time.
        CARRIED,
    };

    /** The one collection and the one book a pad cannot be without, for a restore that carries neither. */
    static String firstCollection(){return FIRST_COLLECTION_ROW;}
    static String firstBook(){return FIRST_BOOK_ROW;}

    /** Statements for a database created directly at the current {@link #VERSION}. */
    static List<String> create() {
        List<String> statements=new ArrayList<>(List.of(NOTES_TABLE,VIEW_INDEX,COLLECTIONS,BOOKS,SHARES,
            "ALTER TABLE notes ADD COLUMN book TEXT NOT NULL DEFAULT '"+FIRST_BOOK+"'",
            FIRST_COLLECTION_ROW,FIRST_BOOK_ROW,BOOKS_INDEX,PAGES_INDEX,ADDRESSES));
        Collections.addAll(statements,PLACES);
        Collections.addAll(statements,AWAY);
        Collections.addAll(statements,COLOURS);
        Collections.addAll(statements,OUTBOX);
        Collections.addAll(statements,FILES);
        Collections.addAll(statements,PAIRED);
        Collections.addAll(statements,VERSIONS);
        Collections.addAll(statements,FILES_ANYWHERE);
        Collections.addAll(statements,SHARED_IN);
        Collections.addAll(statements,ACCEPTING);
        Collections.addAll(statements,FAVOURITES);
        Collections.addAll(statements,MEMBERS);
        Collections.addAll(statements,PAUSE);
        Collections.addAll(statements,HANDED);
        Collections.addAll(statements,AGREED);
        Collections.addAll(statements,STANDING);
        Collections.addAll(statements,LEAVING);
        Collections.addAll(statements,GIVEN);
        Collections.addAll(statements,CARRIED);
        return statements;
    }

    /** Ordered statements that move an existing database from one version to another. */
    static List<String> upgrade(int from,int to) {
        if(from<1||to<1||from>VERSION||to>VERSION)throw new IllegalArgumentException("Unknown notebook schema version");
        if(from>to)throw new IllegalArgumentException("This notebook was written by a newer version of Mininotes");
        List<String> statements=new ArrayList<>();
        for(int version=from;version<to;version++)Collections.addAll(statements,STEPS[version-1]);
        return statements;
    }

    private SchemaMigrations(){}
}
