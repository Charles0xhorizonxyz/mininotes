// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.io.File;

/** Local storage for the pad. Every method blocks on disk and belongs on {@link Background}, not the interface thread. */
final class NoteStore extends SQLiteOpenHelper {
    /** Characters of a page read for a list row. The row shows three lines; the rest stays on disk. */
    static final int PREVIEW=280;

    /** A collection or a book: both are a named thing holding other things. */
    static final class Shelf {
        final String id,name; final int count,colour;
        /** True when this shelf was built because somebody shared into it, and which address that was. */
        final boolean theirs; final String origin;
        Shelf(String id,String name,int count,int colour){this(id,name,count,colour,false,"");}
        Shelf(String id,String name,int count,int colour,boolean theirs,String origin) {
            this.id=id;this.name=name;this.count=count;this.colour=colour;
            this.theirs=theirs;this.origin=origin==null?"":origin;
        }
    }

    /** One line of the tree, with how deep it sits and what holds it. */
    static final class Branch {
        /**
         * INBOX is what others share with you; ARCHIVE is what you put away and BIN what you deleted;
         * NOTICE is a line that only explains something. FAVOURITES is the place that lists what is kept to
         * hand - a place like the archive and the bin, not a collection: nothing lives in it, nothing is
         * moved into it, and so nothing about who can read a thing changes by its being there.
         */
        enum Kind { LIBRARY, COLLECTION, BOOK, PAGE, INBOX, ARCHIVE, BIN, NOTICE, FAVOURITES }
        final Kind kind; final String id,parent,name,detail; final int depth,shared,colour,waiting; final boolean holds;
        final Sharing.State state;
        /** This phone has stopped taking this thing in, or something that holds it. Set as it is listed. */
        boolean paused;
        /** A favourite, so the thing can say so where it lives and not only where favourites are gathered. */
        boolean kept;
        /** Who shared this with you, when somebody did. Empty for everything you made yourself. */
        final String origin;
        Branch(Kind kind,String id,String parent,String name,String detail,int depth,int shared,boolean holds) {
            this(kind,id,parent,name,detail,depth,shared,holds,Tint.NONE);
        }
        Branch(Kind kind,String id,String parent,String name,String detail,int depth,int shared,boolean holds,int colour) {
            this(kind,id,parent,name,detail,depth,shared,holds,colour,0);
        }
        /** {@code waiting} is how many notes inside this one an address has not been given yet. */
        Branch(Kind kind,String id,String parent,String name,String detail,int depth,int shared,boolean holds,
               int colour,int waiting) {
            this(kind,id,parent,name,detail,depth,shared,holds,colour,waiting,Sharing.State.HERE);
        }
        /** {@code state} is where this thing stands: here, on your devices, with somebody else, or from them. */
        Branch(Kind kind,String id,String parent,String name,String detail,int depth,int shared,boolean holds,
               int colour,int waiting,Sharing.State state) {
            this(kind,id,parent,name,detail,depth,shared,holds,colour,waiting,state,"");
        }
        Branch(Kind kind,String id,String parent,String name,String detail,int depth,int shared,boolean holds,
               int colour,int waiting,Sharing.State state,String origin) {
            this.kind=kind;this.id=id;this.parent=parent;this.name=name;this.detail=detail;
            this.depth=depth;this.shared=shared;this.holds=holds;this.colour=colour;this.waiting=waiting;
            this.state=state;this.origin=origin==null?"":origin;
        }
        /** The sharing level this line is, or null for lines that are not yours to share. */
        Sharing.Scope scope() {
            switch(kind) {
                case LIBRARY: return Sharing.Scope.LIBRARY;
                case COLLECTION: return Sharing.Scope.COLLECTION;
                case BOOK: return Sharing.Scope.BOOK;
                case PAGE: return Sharing.Scope.PAGE;
                default: return null;
            }
        }
    }

    static final class Note {
        String id=UUID.randomUUID().toString(), title="", body="", notebook="Personal", preview="",
            book=SchemaMigrations.FIRST_BOOK;
        boolean pinned, deleted, archived;
        /** Which colour the reader gave this page, or {@link Tint#NONE}. */
        int colour=Tint.NONE;
        /** False for a list row, which carries a truncated preview in place of the body. */
        boolean complete=true;
        long updated=System.currentTimeMillis();
        /** Where the reader put this page in its book, smallest first. A new one starts above everything. */
        long place=-System.currentTimeMillis();
        /**
         * How many times this note has been written, counted rather than timed. It is what decides whether
         * a version that arrives descends from this one or diverged from it — a clock cannot say that, and
         * two phones whose clocks disagree would otherwise lose somebody's writing.
         */
        long revision;
        /** True when this note came from somebody else rather than being written here. */
        boolean theirs;
        /** Which address it came from, empty when it is ours. */
        String origin="";
        /** What the sender said this end may do with it: false is read it, true is read and write it. */
        boolean writes;
        Note copy() {
            Note c=new Note();c.id=id;c.title=title;c.body=body;c.notebook=notebook;c.preview=preview;c.book=book;
            c.pinned=pinned;c.deleted=deleted;c.archived=archived;c.complete=complete;c.updated=updated;
            c.place=place;c.colour=colour;c.revision=revision;c.theirs=theirs;c.origin=origin;c.writes=writes;
            return c;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("id",id).put("title",title).put("body",body).put("tag",notebook)
                .put("book",book).put("pinned",pinned).put("deleted",deleted).put("archived",archived)
                .put("updated",updated).put("place",place).put("colour",colour).put("revision",revision);
        }
        /**
         * What a note is called on a shelf: its title, or - for one that has none - its first line, so
         * that a note written before titles existed is not a row of "Untitled".
         */
        String heading() {
            String head=title.trim();
            if(head.isEmpty()){int line=preview.indexOf('\n');head=(line<0?preview:preview.substring(0,line)).trim();}
            return head.isEmpty()?"Untitled":head;
        }
        String rest() {
            if(!title.trim().isEmpty())return preview.replace('\n',' ').trim();
            int line=preview.indexOf('\n');
            return line<0?"":preview.substring(line+1).replace('\n',' ').trim();
        }
    }

    private final Context where;
    NoteStore(Context c) { super(c,"mininotes.db",null,SchemaMigrations.VERSION); where=c.getApplicationContext(); }

    /**
     * The one notebook this process has.
     *
     * <p>There used to be one per screen, made when the screen was and closed when it went. That was enough
     * while the screen was the only thing that ever wrote — but a note can now arrive with no screen there
     * at all, and whatever takes it in has to be writing in the same notebook the screen will open, not in a
     * second copy of it that the first one closes underneath it. Never closed: it goes when the process does.
     */
    private static NoteStore only;
    static synchronized NoteStore of(Context c) {
        if(only==null)only=new NoteStore(c.getApplicationContext());
        return only;
    }

    /**
     * Whether this phone may write in something that came from another device: a note says so itself, and
     * a book or a collection is whatever every note of theirs in it says. Null where they do not agree, or
     * where there is nothing in it yet to say.
     */
    Boolean mayWriteIn(Branch.Kind kind,String id) {
        String where=kind==Branch.Kind.PAGE?"id=?":kind==Branch.Kind.BOOK?"book=?"
            :"book IN (SELECT id FROM books WHERE collection=?)";
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT MIN(writes),MAX(writes),COUNT(*) FROM notes WHERE theirs=1 AND deleted=0 AND "+where,
                new String[]{id})) {
            if(!c.moveToFirst()||c.getInt(2)==0||c.getInt(0)!=c.getInt(1))return null;
            return c.getInt(0)==1;
        }
    }

    /**
     * Everything this phone has stopped taking in for now, by id. What it has left is not among them: a
     * thing that was left is this phone's own, and wears the mark of something that is only here.
     */
    Set<String> paused() {
        Set<String> all=new HashSet<>();
        try(Cursor c=getReadableDatabase().query("refused",new String[]{"target"},"gone=0",null,null,null,null)) {
            while(c.moveToNext())all.add(c.getString(0));
        }
        return all;
    }

    /** Whether this phone has stopped taking one thing in — itself, or whatever holds it. */
    boolean pausedHere(Branch.Kind kind,String id) {
        Set<String> all=paused();
        if(all.isEmpty())return false;
        if(all.contains(id))return true;
        if(kind==Branch.Kind.PAGE) {
            String book=bookOf(id);
            return all.contains(book)||all.contains(collectionOfBook(book));
        }
        return kind==Branch.Kind.BOOK&&all.contains(collectionOfBook(id));
    }

    /** Taken in again: whatever was stopped, on the thing itself or on anything that holds it. */
    void resume(Branch.Kind kind,String id) {
        List<String> all=new ArrayList<>();all.add(id);
        if(kind==Branch.Kind.PAGE){String book=bookOf(id);all.add(book);all.add(collectionOfBook(book));}
        if(kind==Branch.Kind.BOOK)all.add(collectionOfBook(id));
        for(String one:all)if(one!=null&&!one.isEmpty())
            getWritableDatabase().delete("refused","target=? AND gone=0",new String[]{one});
    }

    /** Every address that has anything in this thing: whoever a Sync has to talk to. */
    Set<String> everybodyIn(Branch.Kind kind,String id) {
        Set<String> all=new java.util.LinkedHashSet<>();
        List<Sharing.Rule> rules=shares();
        for(Outbox.Page page:pagesUnder(kind,id))
            all.addAll(Sharing.audience(rules,page.collection,page.book,page.id).keySet());
        return all;
    }

    /**
     * Whether a thing is shared at all — itself, or anything on it.
     *
     * <p>A shelf says what is true of what is on it: a book holding one shared note wears the shared mark
     * on its tile. The mark in that book's own bar asked a narrower question, whether the book itself was
     * shared, and so the same book said one thing from outside and another from inside.
     */
    boolean sharedAtAll(Branch.Kind kind,String id) {
        List<Sharing.Rule> rules=shares();
        if(kind==Branch.Kind.LIBRARY)return !rules.isEmpty();
        if(kind!=Branch.Kind.COLLECTION&&kind!=Branch.Kind.BOOK&&kind!=Branch.Kind.PAGE)return false;
        if(!cameFrom(kind,id).isEmpty())return true;
        for(Outbox.Page page:pagesUnder(kind,id))
            if(!Sharing.audience(rules,page.collection,page.book,page.id).isEmpty())return true;
        // And a shelf with nothing on it yet can still have been shared by itself.
        Sharing.Scope scope=kind==Branch.Kind.COLLECTION?Sharing.Scope.COLLECTION
            :kind==Branch.Kind.BOOK?Sharing.Scope.BOOK:Sharing.Scope.PAGE;
        return !reaches(scope,id).isEmpty();
    }

    /**
     * The address a thing came from, or empty for one that was made here. Asked by the one mark a thing
     * wears, which opens a different box for something of yours than for something of somebody else's.
     */
    String cameFrom(Branch.Kind kind,String id) {
        if(kind!=Branch.Kind.COLLECTION&&kind!=Branch.Kind.BOOK&&kind!=Branch.Kind.PAGE)return "";
        try(Cursor c=getReadableDatabase().query(table(kind),new String[]{"theirs","origin"},"id=?",
                new String[]{id},null,null,null,"1")) {
            if(!c.moveToFirst()||c.getInt(0)!=1)return "";
            String origin=c.getString(1);
            return origin==null?"":origin;
        }
    }

    /** Whether there is any device at all that something could arrive from. */
    boolean anybodyPaired() {
        for(Contact one:addresses())if(one.paired())return true;
        return false;
    }

    // ---- files kept with a collection, a book or a note ---------------------------------------------------

    /**
     * One file kept with something: the row says what it is, the folder holds what it was.
     *
     * <p>{@code note} is whatever it is kept with, and {@code held} says which kind of thing that is. The
     * column kept its old name because renaming one would mean rewriting a table that is already right.
     */
    static final class Held {
        final String id,note,name,kind; final long bytes,added; final Branch.Kind held;
        Held(String id,String note,String name,String kind,long bytes,long added) {
            this(id,note,name,kind,bytes,added,Branch.Kind.PAGE);
        }
        Held(String id,String note,String name,String kind,long bytes,long added,Branch.Kind held) {
            this.id=id;this.note=note;this.name=name;this.kind=kind;this.bytes=bytes;this.added=added;
            this.held=held==null?Branch.Kind.PAGE:held;
        }
    }

    /** What the row says, and what it says back. A file kept with a note is written 'note', as it always was. */
    private static String heldAs(Branch.Kind kind) {
        return kind==Branch.Kind.COLLECTION?"collection":kind==Branch.Kind.BOOK?"book":"note";
    }
    private static Branch.Kind heldFrom(String said) {
        return "collection".equals(said)?Branch.Kind.COLLECTION:"book".equals(said)?Branch.Kind.BOOK:Branch.Kind.PAGE;
    }
    /** The columns a Held is read from, in the order {@link #held} reads them. */
    private static final String[] FILE_ROW={"id","note","name","kind","bytes","added","held"};
    private static Held held(Cursor c) {
        return new Held(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),
            heldFrom(said(c,"held","note")));
    }

    /** Where the bytes live: the app's own folder, one file to a row, named by the row's id and nothing else. */
    File shed() { File shed=new File(where.getFilesDir(),"files"); if(!shed.isDirectory())shed.mkdirs(); return shed; }
    File fileFor(String id) { return new File(shed(),id); }

    /**
     * Where a backup is unpacked, which is never the shed itself. A backup carries the ids it was written
     * with, and importing one into the pad it came from would otherwise land on the files already here —
     * which, since an import makes copies, would then be carried off under the copy's new name.
     */
    File landing() { File room=new File(where.getFilesDir(),"incoming"); if(!room.isDirectory())room.mkdirs(); return room; }
    File landingFor(String id) { return new File(landing(),id); }

    /** A row for a file about to be copied in. The bytes are written to {@link #fileFor} under this id. */
    Held opening(Branch.Kind held,String what,String name,String kind,long bytes) {
        return new Held(UUID.randomUUID().toString(),what,Attachment.named(name),Attachment.kind(kind),
            Math.max(0,bytes),System.currentTimeMillis(),held);
    }

    /** The row, once the bytes are there. Written last, so a half-copied file is never a file of the note. */
    void keep(Held file){keep(getWritableDatabase(),file);}

    /** The same, inside a transaction somebody else opened. */
    void keep(SQLiteDatabase db,Held file) {
        ContentValues values=new ContentValues();
        values.put("id",file.id);values.put("note",file.note);values.put("name",file.name);
        values.put("kind",file.kind);values.put("bytes",file.bytes);values.put("added",file.added);
        values.put("held",heldAs(file.held));
        values.put("place",-file.added);
        db.insertWithOnConflict("files",null,values,SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** What is kept with this thing, in the order it was added, newest first. */
    List<Held> filesOf(Branch.Kind kind,String id) {
        List<Held> found=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("files",FILE_ROW,"note=? AND held=?",
                new String[]{id,heldAs(kind)},null,null,"place ASC, added DESC")) {
            while(c.moveToNext())found.add(held(c));
        }
        return found;
    }

    /**
     * Everything one thing can reach: what is kept with it, and then what each thing it sits inside keeps.
     * A map put on a collection is meant for every note in it, so it is offered in all of them rather than
     * only where it was left. Nearest first, so what was put here is the first thing on the strip.
     *
     * <p>One query. Walking up and asking again per level would be three round trips to say one thing.
     */
    List<Held> filesReaching(Branch.Kind kind,String id) {
        String book="", collection="";
        if(kind==Branch.Kind.PAGE) {
            try(Cursor c=getReadableDatabase().rawQuery(
                    "SELECT n.book,b.collection FROM notes n LEFT JOIN books b ON b.id=n.book WHERE n.id=?",
                    new String[]{id})) {
                if(c.moveToFirst()){book=c.isNull(0)?"":c.getString(0);collection=c.isNull(1)?"":c.getString(1);}
            }
        } else if(kind==Branch.Kind.BOOK) {
            try(Cursor c=getReadableDatabase().query("books",new String[]{"collection"},"id=?",
                    new String[]{id},null,null,null,"1")) {
                if(c.moveToFirst()&&!c.isNull(0))collection=c.getString(0);
            }
        }
        // The library is not a thing anything is kept with, so it reaches only what is kept with it.
        List<Held> found=new ArrayList<>();
        gather(found,Branch.Kind.PAGE==kind?Branch.Kind.PAGE:kind,id);
        if(kind==Branch.Kind.PAGE&&!book.isEmpty())gather(found,Branch.Kind.BOOK,book);
        if(kind!=Branch.Kind.COLLECTION&&!collection.isEmpty())gather(found,Branch.Kind.COLLECTION,collection);
        return found;
    }

    private void gather(List<Held> into,Branch.Kind kind,String id) {
        if(id==null||id.isEmpty())return;
        into.addAll(filesOf(kind,id));
    }

    /** One file, by its id, or null. The provider that lends a file to another app asks this. */
    Held file(String id) {
        try(Cursor c=getReadableDatabase().query("files",FILE_ROW,"id=?",new String[]{id},null,null,null,"1")) {
            return c.moveToFirst()?held(c):null;
        }
    }

    /** Everything the pad is holding in files, so it can say so before it fills the phone. */
    long weight() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(bytes),0) FROM files",null)) {
            return c.moveToFirst()?c.getLong(0):0;
        }
    }

    /** The row goes, then the bytes. A file nobody has a row for is swept up next time. */
    void drop(String id) {
        getWritableDatabase().delete("files","id=?",new String[]{id});
        sweep();
    }

    /**
     * Bytes with no row are deleted. Rows are written after the copy and deleted before it, so the only
     * thing that can be left behind is a file nobody claims — never a row pointing at nothing.
     */
    void sweep() {
        Set<String> kept=new HashSet<>();
        try(Cursor c=getReadableDatabase().query("files",new String[]{"id"},null,null,null,null,null)) {
            while(c.moveToNext())kept.add(c.getString(0));
        }
        File[] there=shed().listFiles();
        if(there!=null)for(File file:there)if(!kept.contains(file.getName()))
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        // Nothing in the landing room belongs to anybody: what an import wanted has already been moved out.
        File[] left=landing().listFiles();
        if(left!=null)for(File file:left)
            //noinspection ResultOfMethodCallIgnored
            file.delete();
    }
    @Override public void onCreate(SQLiteDatabase db) { for(String statement:SchemaMigrations.create())db.execSQL(statement); }
    // SQLiteOpenHelper runs this inside a transaction: a step that fails leaves the old schema and notes intact.
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next) { for(String statement:SchemaMigrations.upgrade(old,next))db.execSQL(statement); }
    @Override public void onDowngrade(SQLiteDatabase db,int old,int next) { throw new IllegalStateException("This notebook was written by a newer version of Mininotes"); }

    // ---- pages -------------------------------------------------------------------------------------------

    void save(Note n){save(getWritableDatabase(),n);}

    /**
     * The open page written down — unless the note has moved on since the page last looked.
     *
     * <p>A page is a copy of a note, and a note can be given something newer while the page is open: it
     * arrives on the node's thread, not on the page's. Written regardless, the page's older copy goes back
     * over what arrived, a revision higher, and is then sent on as the newer of the two. So the writing
     * checks first, in the same transaction, and where the notebook is ahead it writes nothing and says so:
     * the page puts the two together and tries again.
     *
     * @param seen the revision the page was holding before this writing
     * @return false where the notebook holds something the page has not seen, and nothing was written
     */
    boolean saveFrom(Note n,long seen) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            try(Cursor c=db.query("notes",new String[]{"revision"},"id=?",new String[]{n.id},null,null,null,"1")) {
                if(c.moveToFirst()&&c.getLong(0)>seen)return false;
            }
            save(db,n);
            db.setTransactionSuccessful();
            return true;
        } finally {db.endTransaction();}
    }

    /** The same, inside a transaction somebody else opened: a restore is one piece of work or none. */
    void save(SQLiteDatabase db,Note n) {
        // A list row holds a truncated body; writing one back would silently cut the page down to its preview.
        if(!n.complete)throw new IllegalStateException("Refusing to save a note that was only partially read");
        ContentValues v=new ContentValues();v.put("id",n.id);v.put("title",n.title);v.put("body",n.body);v.put("notebook",n.notebook);
        v.put("book",n.book);v.put("pinned",n.pinned?1:0);v.put("deleted",n.deleted?1:0);v.put("updated",n.updated);
        // Saving replaces the whole row, so the place has to be written back or every edit would reshuffle the book.
        v.put("place",n.place);v.put("archived",n.archived?1:0);v.put("colour",n.colour);
        v.put("revision",n.revision);v.put("theirs",n.theirs?1:0);v.put("origin",n.origin);
        v.put("writes",n.writes?1:0);
        if(db.insertWithOnConflict("notes",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Could not save the note");
    }

    // ---- every version a note has had ---------------------------------------------------------------------

    /** One version of a note: what it said, when it was kept, and where it came from. */
    static final class Version {
        final String id,note,title,body,source; final long revision,at;
        Version(String id,String note,long revision,long at,String source,String title,String body) {
            this.id=id;this.note=note;this.revision=revision;this.at=at;this.source=source;
            this.title=title;this.body=body;
        }
        /** Empty for this phone; otherwise the address it arrived from. */
        boolean ours(){return source.isEmpty();}
    }

    /** How many versions of one note are worth keeping. Text is small; a hundred of them is still small. */
    static final int VERSIONS_KEPT=100;

    /**
     * Keeps what a note says now, if it is not already the last thing kept. Called when an editing session
     * ends and when something arrives from somebody else, so the list reads as the note's history rather
     * than as a keystroke log.
     *
     * @param source empty for this phone, or the address this text came from
     */
    void keepVersion(String note,String source) {
        Note now=get(note);
        if(now==null)return;
        Version last=lastVersion(note);
        if(last!=null&&last.title.equals(now.title)&&last.body.equals(now.body))return;
        ContentValues v=new ContentValues();
        v.put("id",UUID.randomUUID().toString());v.put("note",note);
        v.put("revision",now.revision);v.put("at",System.currentTimeMillis());
        v.put("source",source==null?"":source);v.put("title",now.title);v.put("body",now.body);
        if(getWritableDatabase().insert("versions",null,v)<0)throw new IllegalStateException("Could not keep that version");
        prune(note);
    }

    /** One version written straight down, for text that arrived rather than text that is here. */
    void keepVersion(String note,long revision,String source,String title,String body) {
        ContentValues v=new ContentValues();
        v.put("id",UUID.randomUUID().toString());v.put("note",note);
        v.put("revision",revision);v.put("at",System.currentTimeMillis());
        v.put("source",source==null?"":source);v.put("title",title);v.put("body",body);
        if(getWritableDatabase().insert("versions",null,v)<0)throw new IllegalStateException("Could not keep that version");
        prune(note);
    }

    /** The versions of a note, newest first. */
    List<Version> versions(String note) {
        List<Version> all=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("versions",null,"note=?",new String[]{note},null,null,"at DESC")) {
            while(c.moveToNext())all.add(readVersion(c));
        }
        return all;
    }

    Version version(String id) {
        try(Cursor c=getReadableDatabase().query("versions",null,"id=?",new String[]{id},null,null,null,"1")) {
            return c.moveToFirst()?readVersion(c):null;
        }
    }

    private Version lastVersion(String note) {
        try(Cursor c=getReadableDatabase().query("versions",null,"note=?",new String[]{note},null,null,"at DESC","1")) {
            return c.moveToFirst()?readVersion(c):null;
        }
    }

    private static Version readVersion(Cursor c) {
        return new Version(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("note")),
            c.getLong(c.getColumnIndexOrThrow("revision")),c.getLong(c.getColumnIndexOrThrow("at")),
            c.getString(c.getColumnIndexOrThrow("source")),c.getString(c.getColumnIndexOrThrow("title")),
            c.getString(c.getColumnIndexOrThrow("body")));
    }

    /** The oldest beyond what is kept are dropped: a note's history is long, not endless. */
    private void prune(String note) {
        getWritableDatabase().execSQL(
            "DELETE FROM versions WHERE note=? AND id NOT IN (SELECT id FROM versions WHERE note=? ORDER BY at DESC LIMIT ?)",
            new Object[]{note,note,VERSIONS_KEPT});
    }

    /**
     * What this note said at the revision this phone and that address last both had: the text a merge is
     * made against.
     *
     * <p>Exactly that revision where it was kept, and it is kept: what is sent is written down as a
     * version when it goes, and what arrives is written down as it arrives. Two phones count their own
     * revisions, so the same number can name two different texts — which is why this asks for the one
     * that was this phone's own or came from that address, and not for anybody's. Only where neither was
     * kept does it fall back to the nearest thing before.
     */
    String textAt(String note,long revision,String address) {
        try(Cursor c=getReadableDatabase().query("versions",new String[]{"body"},
                "note=? AND revision=? AND (source='' OR source=?)",
                new String[]{note,String.valueOf(revision),address==null?"":address},null,null,"at DESC","1")) {
            if(c.moveToFirst())return c.getString(0);
        }
        return textAt(note,revision);
    }

    /** Whatever a note said when an address was last given it: the text a merge is weighed against. */
    String textAt(String note,long revision) {
        try(Cursor c=getReadableDatabase().query("versions",new String[]{"body"},"note=? AND revision<=?",
                new String[]{note,String.valueOf(revision)},null,null,"revision DESC, at DESC","1")) {
            return c.moveToFirst()?c.getString(0):null;
        }
    }

    /**
     * A note that arrived from an address, weighed against what is here and written down accordingly. What
     * arrived is always kept as a version whatever happens to the page, so nothing anybody wrote is ever
     * only somewhere else.
     *
     * @param book where a note nobody here has seen before should land
     * @return what was decided, for the app to say out loud
     */
    Arriving.Decision landed(String id,String from,long revision,String title,String body,String book) {
        return landed(id,from,revision,title,body,book,-1L);
    }

    /**
     * @param basedOn the revision the sender believes both phones last had, or -1 where it did not say
     */
    Arriving.Decision landed(String id,String from,long revision,String title,String body,String book,
                             long basedOn) {
        // Read and decided inside the transaction that writes it. This runs on the node's thread while
        // the page writes on its own, and a decision made from a note that was written a moment later is
        // a decision about a note that no longer exists.
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            Note here=get(id);
            // What this phone believes they have, and what they say they had: the older of the two.
            long baseRevision=Arriving.agreed(here==null?0:lastSeen(id,from),basedOn);
            String base=here==null?null:textAt(id,baseRevision,from);
            Arriving.Decision said=Arriving.weigh(here==null?null:here.body,here==null?0:here.revision,
                base,baseRevision,body,revision);
            // What arrived is kept as it arrived, whether or not it is what the page ends up showing.
            keepVersion(id,revision,from,title==null?"":title,body==null?"":body);
            if(said.what!=Arriving.What.OLDER) {
                Note now=here==null?new Note():here;
                if(here==null){now.id=id;now.book=book;now.theirs=true;now.origin=from;}
                // The title is one word and cannot be put together line by line. Where only they have
                // written, theirs is taken; where both have, this phone keeps what it calls the note and
                // sends that back with everything else, so the two end up agreeing on it.
                String theirs=title==null?"":title;
                if(here==null||said.what!=Arriving.What.MERGED||now.title==null||now.title.trim().isEmpty())
                    now.title=theirs;
                now.body=said.text==null?"":said.text;
                now.revision=said.revision;now.updated=System.currentTimeMillis();
                save(db,now);
            }
            // Whoever sent it has it. Where what is kept is exactly what arrived, that is all there is
            // to say, and saying it is what stops this phone "owing" a note straight back to the phone
            // it came from — a message each way for every note, and a line on a page nobody had touched
            // saying it had not been sent. Where the two were put together, what is here now is something
            // they have not seen, and they are owed it.
            if(said.what==Arriving.What.NEW||said.what==Arriving.What.NEWER)agreedOn(from,id,revision);
            db.setTransactionSuccessful();
            return said;
        } finally {db.endTransaction();}
    }

    /**
     * A note that arrived with the shelf it stood on.
     *
     * <p>The collection and the book are built here if this is the first note out of them, named as the
     * sender names them and marked as theirs. Their ids are not reused as they stand - see
     * {@link Parcel#localId} for why a shared note filed under the id it arrived with would land inside
     * your own first book.
     *
     * <p>A note already here is not moved. Somebody else deciding where your copy of a note lives, every
     * time they touch it, would undo any tidying you had done.
     */
    Arriving.Decision landed(String id,String from,long revision,Parcel.Sent parcel,String fallbackBook) {
        String book=fallbackBook;
        // A note of our own, come back to us. Somebody we shared it with has shared it on, or back, and
        // what arrives describes the shelf it sits on at their end. Ours is where it already is: building
        // their shelf here would leave a second, empty collection of the same name beside our own, which
        // is what happened the first time this was tried.
        Note already=get(id);
        boolean ours=already!=null&&!already.theirs;
        if(!ours&&parcel!=null&&!parcel.book.trim().isEmpty()) {
            String collection=shelfFrom(from,parcel.collection,parcel.collectionName,null);
            book=shelfFrom(from,parcel.book,parcel.bookName,
                collection.isEmpty()?collectionOfBook(fallbackBook):collection);
            if(book.isEmpty())book=fallbackBook;
        }
        Arriving.Decision said=landed(id,from,revision,parcel==null?"":parcel.title,
            parcel==null?"":parcel.body,book,parcel==null?-1L:parcel.basedOn);
        if(parcel!=null) {
            // What they say this end may do with it. Said every time, because they can change their mind.
            ContentValues v=new ContentValues();v.put("writes",parcel.writes?1:0);
            getWritableDatabase().update("notes",v,"id=? AND theirs=1",new String[]{id});
            // And where they are now, where the address it first came from is nobody's any more.
            ContentValues now=new ContentValues();now.put("origin",from);
            getWritableDatabase().update("notes",now,
                "id=? AND theirs=1 AND origin NOT IN (SELECT address FROM addresses)",new String[]{id});
        }
        return said;
    }

    /**
     * Somebody else's collection or book on this phone, made if it is not here yet.
     *
     * @param inside the collection a book goes in, or null when the shelf being named is a collection
     * @return the local id of that shelf, or empty when there was nothing to name
     */
    private String shelfFrom(String address,String theirs,String name,String inside) {
        if(theirs==null||theirs.trim().isEmpty())return "";
        String table=inside==null?"collections":"books";
        // Named after the device, not the address it was at.
        //
        // An address moves - a node that restarts is at a new one - and a shelf filed under the old one
        // arrived again under a new name and became a second, empty copy of itself beside the first. The
        // key a device signs with does not move, so the same shelf is the same shelf.
        String id=shelfId(address,theirs,inside==null);
        // A shelf filed the old way is adopted rather than left behind with the notes already on it. Not
        // where the id arrived already made up: that names a shelf that is somewhere already.
        String was=Parcel.localId(address,theirs);
        if(!theirs.startsWith(Parcel.FROM)&&!was.equals(id))adopt(table,was,id);
        String called=name==null||name.trim().isEmpty()?"Shared":name.trim();
        try(Cursor c=getReadableDatabase().query(table,new String[]{"id"},"id=?",new String[]{id},
                null,null,null,"1")) {
            if(c.moveToFirst()) {
                // Here already. Only what they call it is followed; where you put it is yours.
                ContentValues rename=new ContentValues();rename.put("name",called);
                getWritableDatabase().update(table,rename,"id=? AND theirs=1",new String[]{id});
                return id;
            }
        }
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();
        v.put("id",id);v.put("name",called);v.put("updated",now);v.put("place",-now);
        v.put("theirs",1);v.put("origin",address);
        if(inside!=null)v.put("collection",inside);
        return getWritableDatabase().insert(table,null,v)<0?"":id;
    }

    /**
     * What a shelf named in something that arrived is called on this phone. See {@link Parcel#shelfHere}:
     * one of this phone's own, come home; or the name it was given when it first left its owner.
     */
    private String shelfId(String address,String theirs,boolean collection) {
        if(theirs==null||theirs.trim().isEmpty())return "";
        Contact who=address(address);
        String by=who==null||who.signing.length==0?address:canonical(who.signing);
        List<String> own=new ArrayList<>();
        if(theirs.startsWith(Parcel.FROM))
            try(Cursor c=getReadableDatabase().query(collection?"collections":"books",new String[]{"id"},
                    "theirs=0",null,null,null,null)) {
                while(c.moveToNext())own.add(c.getString(0));
            }
        return Parcel.shelfHere(mySigningKey,own,by,theirs);
    }

    // ---- an offer taken up, until they answer -------------------------------------------------------------

    /** One acceptance still waiting to be heard. */
    static final class Accepting {
        final String address,name,scope,target; final boolean writes; final int tries;
        Accepting(String address,String name,String scope,String target,boolean writes,int tries) {
            this.address=address;this.name=name;this.scope=scope;this.target=target;
            this.writes=writes;this.tries=tries;
        }
    }

    /** Kept the moment it is sent, because the sending may be to nobody. */
    void accepting(String address,String name,String scope,String target,boolean writes) {
        ContentValues v=new ContentValues();
        v.put("address",address);v.put("name",name==null?"":name);
        v.put("scope",scope);v.put("target",target);v.put("writes",writes?1:0);
        v.put("at",System.currentTimeMillis());v.put("tries",0);
        getWritableDatabase().insertWithOnConflict("accepting",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Everything still unanswered, oldest first, and not tried past all reason. */
    List<Accepting> waitingToAccept() {
        List<Accepting> waiting=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("accepting",null,"tries<?",new String[]{"60"},
                null,null,"at ASC")) {
            while(c.moveToNext())waiting.add(new Accepting(
                c.getString(c.getColumnIndexOrThrow("address")),c.getString(c.getColumnIndexOrThrow("name")),
                c.getString(c.getColumnIndexOrThrow("scope")),c.getString(c.getColumnIndexOrThrow("target")),
                c.getInt(c.getColumnIndexOrThrow("writes"))==1,c.getInt(c.getColumnIndexOrThrow("tries"))));
        }
        return waiting;
    }

    void triedAgain(String address) {
        getWritableDatabase().execSQL("UPDATE accepting SET tries=tries+1 WHERE address=?",
            new String[]{address});
    }

    /**
     * They answered: something of theirs is here, so there is nothing left to ask for.
     *
     * <p>By device rather than by address. A note arrives from whichever address of theirs this phone
     * happened to file them under, which need not be the one their code was read at.
     */
    void answered(String address) {
        getWritableDatabase().execSQL(
            "DELETE FROM accepting WHERE address=? OR address IN ("
            +"SELECT a.address FROM addresses a WHERE a.signing<>'' AND a.signing="
            +"(SELECT b.signing FROM addresses b WHERE b.address=?))",
            new String[]{address,address});
    }

    // ---- what this phone will no longer take in ----------------------------------------------------------

    /**
     * Stop taking in what somebody sends of one thing.
     *
     * <p>It cannot stop them sending: only they can decide that, and this end has no say over another
     * phone. What it stops is the arriving being put on the shelves, which is the part this phone owns -
     * so nothing new turns up, and what is already here stays here until it is deleted like anything else.
     */
    void refuse(String address,String target,Branch.Kind kind) {
        ContentValues v=new ContentValues();
        v.put("address",address);v.put("target",target);
        v.put("kind",kind==null?"note":kind.name().toLowerCase(java.util.Locale.ROOT));
        v.put("at",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("refused",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Take it again. */
    void accept(String address,String target) {
        getWritableDatabase().delete("refused","address=? AND target=?",new String[]{address,target});
    }

    /** Whether this phone is refusing that one thing from that one address. */
    boolean refusing(String address,String target) {
        if(target==null||target.trim().isEmpty())return false;
        try(Cursor c=getReadableDatabase().query("refused",new String[]{"target"},"address=? AND target=?",
                new String[]{address,target},null,null,null,"1")) {
            return c.moveToFirst();
        }
    }

    /** One thing this phone does not take in: stopped for now, or left. */
    static final class Refusal {
        final String target,kind; final long at; final boolean gone;
        Refusal(String target,String kind,long at,boolean gone){this.target=target;this.kind=kind;this.at=at;this.gone=gone;}
        /** The level it was left at, which is what the others are told. */
        Sharing.Scope scope() {
            return "book".equals(kind)?Sharing.Scope.BOOK:"collection".equals(kind)?Sharing.Scope.COLLECTION
                :Sharing.Scope.PAGE;
        }
    }

    /**
     * Whatever refuses a note arriving from somebody - the note itself, or the book or the collection it
     * arrived in - or null where nothing does. Refusing a book has to refuse the notes in it, or stopping
     * would stop nothing.
     *
     * <p>The shelf is looked for under the name it has here. It was looked for under a name made from the
     * sender's address, where shelves have been filed by the sender's key since addresses were found to
     * move - so a book that had been stopped went on taking in every note sent out of it.
     */
    Refusal refusal(String address,String note,Parcel.Sent parcel) {
        Refusal found=refusalOf(address,note);
        if(found!=null||parcel==null)return found;
        found=refusalOf(address,shelfId(address,parcel.book,false));
        return found!=null?found:refusalOf(address,shelfId(address,parcel.collection,true));
    }

    boolean refusingParcel(String address,String note,Parcel.Sent parcel) {
        return refusal(address,note,parcel)!=null;
    }

    /** By device rather than by address: somebody's address moves, and what was refused of them stays so. */
    private Refusal refusalOf(String address,String target) {
        if(target==null||target.trim().isEmpty())return null;
        Contact from=address(address);
        String key=from==null?"":canonical(from.signing);
        try(Cursor c=getReadableDatabase().query("refused",new String[]{"address","kind","at","gone"},
                "target=?",new String[]{target},null,null,null)) {
            while(c.moveToNext()) {
                String theirs=c.getString(0);
                boolean same=theirs.equals(address);
                if(!same&&!key.isEmpty()) {
                    Contact kept=address(theirs);
                    same=kept!=null&&key.equals(canonical(kept.signing));
                }
                if(same)return new Refusal(target,c.getString(1),c.getLong(2),c.getInt(3)==1);
            }
        }
        return null;
    }

    // ---- leaving, and being left ---------------------------------------------------------------------------

    private static Sharing.Scope scopeOf(Branch.Kind kind) {
        return kind==Branch.Kind.PAGE?Sharing.Scope.PAGE:kind==Branch.Kind.BOOK?Sharing.Scope.BOOK
            :kind==Branch.Kind.COLLECTION?Sharing.Scope.COLLECTION:null;
    }

    /**
     * Left. What is here stays, and is this phone's own from now on; nothing more of it is taken in from
     * anybody who had it, and nothing of it is owed to them.
     *
     * <p>Telling them is the post's business and is done first - see {@link Post#leave}. This is the half
     * that has to happen whether or not anybody could be told.
     *
     * @param everybody every address that had it, each of whom may still send it before they hear
     * @param now       when it was left, which is what they are told and what a later invitation is later than
     */
    void letGo(Branch.Kind kind,String id,java.util.Collection<String> everybody,long now) {
        Sharing.Scope scope=scopeOf(kind);
        if(scope==null||id==null||id.isEmpty())return;
        List<Outbox.Page> pages=pagesUnder(kind,id);
        String book=kind==Branch.Kind.PAGE?bookOf(id):kind==Branch.Kind.BOOK?id:"";
        String collection=kind==Branch.Kind.COLLECTION?"":collectionOfBook(book);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(String address:everybody) {
                ContentValues v=new ContentValues();
                v.put("address",address);v.put("target",id);
                v.put("kind",kind.name().toLowerCase(java.util.Locale.ROOT));
                v.put("at",now);v.put("gone",1);
                db.insertWithOnConflict("refused",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
            for(Outbox.Page page:pages)db.delete("handed","page=?",new String[]{page.id});
            db.delete("shares","scope=? AND target=?",new String[]{scope.name(),id});
            db.delete("standing","scope=? AND target=?",new String[]{scope.name(),id});
            // Where it came from is kept, for the day it is given again. Whose it is, is not.
            ContentValues mine=new ContentValues();mine.put("theirs",0);
            if(kind==Branch.Kind.PAGE)db.update("notes",mine,"id=?",new String[]{id});
            if(kind==Branch.Kind.BOOK) {
                db.update("books",mine,"id=?",new String[]{id});
                db.update("notes",mine,"book=?",new String[]{id});
            }
            if(kind==Branch.Kind.COLLECTION) {
                db.update("collections",mine,"id=?",new String[]{id});
                db.update("books",mine,"collection=?",new String[]{id});
                db.update("notes",mine,"book IN (SELECT id FROM books WHERE collection=?)",new String[]{id});
            }
            if(kind==Branch.Kind.PAGE)emptied(db,Branch.Kind.BOOK,book);
            if(kind!=Branch.Kind.COLLECTION)emptied(db,Branch.Kind.COLLECTION,collection);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    /**
     * A shelf that was built here to hold what somebody sent, with nothing of theirs left on it.
     *
     * <p>It is an ordinary shelf now. And the line saying whoever sent its notes has the shelf - which
     * nobody ever decided: it was worked out from notes arriving - has nothing left to be worked out from,
     * so it goes. Left standing, it kept the note that had just been let go going to them by way of the
     * shelf, and a note somebody has unfollowed went on wearing the mark of a shared one.
     */
    private void emptied(SQLiteDatabase db,Branch.Kind kind,String shelf) {
        if(shelf==null||shelf.isEmpty())return;
        String inside=kind==Branch.Kind.BOOK?"book=?":"book IN (SELECT id FROM books WHERE collection=?)";
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM notes WHERE theirs=1 AND "+inside,new String[]{shelf})) {
            if(c.moveToFirst()&&c.getInt(0)>0)return;
        }
        ContentValues mine=new ContentValues();mine.put("theirs",0);
        db.update(table(kind),mine,"id=?",new String[]{shelf});
        String scope=kind==Branch.Kind.BOOK?Sharing.Scope.BOOK.name():Sharing.Scope.COLLECTION.name();
        db.delete("shares","scope=? AND target=? AND changed<=1",new String[]{scope,shelf});
        db.delete("standing","scope=? AND target=?",new String[]{scope,shelf});
    }

    /** One leaving that may not have been heard yet: who was told, what was left, and a note that names it. */
    static final class Leaving {
        final String address,note; final Sharing.Scope scope; final long at;
        Leaving(String address,String note,Sharing.Scope scope,long at) {
            this.address=address;this.note=note;this.scope=scope;this.at=at;
        }
    }

    /**
     * What this phone has left lately, to be said again. Saying it is one message to a phone that may be
     * asleep, and a message to a phone that is asleep is gone - the first one sent from a real phone went
     * exactly that way. Nobody answers it, so there is no knowing; it is said again for a week, which costs
     * a few bytes, and a phone that has heard already does nothing about hearing it twice.
     */
    List<Leaving> leavings() {
        List<Leaving> all=new ArrayList<>();
        long since=System.currentTimeMillis()-7L*24*60*60*1000;
        try(Cursor c=getReadableDatabase().query("refused",new String[]{"address","target","kind","at"},
                "gone=1 AND at>?",new String[]{String.valueOf(since)},null,null,null)) {
            while(c.moveToNext()) {
                Refusal one=new Refusal(c.getString(1),c.getString(2),c.getLong(3),true);
                Branch.Kind kind=one.scope()==Sharing.Scope.PAGE?Branch.Kind.PAGE
                    :one.scope()==Sharing.Scope.BOOK?Branch.Kind.BOOK:Branch.Kind.COLLECTION;
                // Any note still here out of it names it. Where none is left, there is nothing to name it by.
                for(Outbox.Page page:pagesUnder(kind,one.target)) {
                    all.add(new Leaving(c.getString(0),page.id,one.scope(),one.at));
                    break;
                }
            }
        }
        return all;
    }

    /**
     * Somebody says they have left something. They are taken off it, as a decision like any other, so
     * that an older copy of the list cannot put them back; and what was still waiting for their answer
     * stops waiting.
     *
     * <p>As of when they left, and only where nothing has been decided about them since. It is said more
     * than once, because the first time may not arrive; somebody who has been given the thing again in
     * the meantime is not taken off it by an old goodbye turning up late.
     *
     * @param note any note out of what they left; which shelf that means is worked out from this phone's own
     * @param when when they left, by their clock
     * @return whether that changed anything here
     */
    boolean left(String address,String note,Sharing.Scope scope,long when) {
        if(scope==null)return false;
        if(when<=0)when=System.currentTimeMillis();
        String book=bookOf(note);
        String target=scope==Sharing.Scope.PAGE?(get(note)==null?"":note)
            :scope==Sharing.Scope.BOOK?book:collectionOfBook(book);
        if(target==null||target.isEmpty())return false;
        Contact who=address(address);
        String key=who==null?"":canonical(who.signing);
        boolean any=false;
        for(Sharing.Rule rule:membership(scope,target)) {
            if(rule.level==Sharing.Level.GONE)continue;
            if(!rule.address.equals(address)&&(key.isEmpty()||!key.equals(rule.key)))continue;
            if(rule.changed>=when)continue;
            setLevel(scope,target,rule.address,Sharing.Level.GONE,null,when);
            any=true;
        }
        Branch.Kind kind=scope==Sharing.Scope.PAGE?Branch.Kind.PAGE
            :scope==Sharing.Scope.BOOK?Branch.Kind.BOOK:Branch.Kind.COLLECTION;
        for(Outbox.Page page:pagesUnder(kind,target))
            getWritableDatabase().delete("handed","address=? AND page=?",new String[]{address,page.id});
        return any;
    }

    /**
     * Given again, after leaving. The leaving is forgotten and what is here is theirs once more, so what
     * arrives next is taken in like anything else of theirs.
     */
    void followAgain(String address,String note,Refusal left) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            db.delete("refused","target=? AND gone=1",new String[]{left.target});
            ContentValues theirs=new ContentValues();theirs.put("theirs",1);
            ContentValues from=new ContentValues();from.put("theirs",1);from.put("origin",address);
            db.update("notes",from,"id=?",new String[]{note});
            if(left.scope()==Sharing.Scope.BOOK) {
                db.update("books",theirs,"id=?",new String[]{left.target});
                db.update("notes",theirs,"book=? AND origin<>''",new String[]{left.target});
            }
            if(left.scope()==Sharing.Scope.COLLECTION) {
                db.update("collections",theirs,"id=?",new String[]{left.target});
                db.update("books",theirs,"collection=? AND origin<>''",new String[]{left.target});
                db.update("notes",theirs,"origin<>'' AND book IN (SELECT id FROM books WHERE collection=?)",
                    new String[]{left.target});
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    /** The nearest thing a rule was given on - this, or the book or the collection holding it - or null. */
    Object[] givenOn(Sharing.Scope scope,String target,String name) {
        if(scope==null||target==null)return null;
        if(!membership(scope,target).isEmpty())return new Object[]{scope,target,name};
        String book=scope==Sharing.Scope.PAGE?bookOf(target):scope==Sharing.Scope.BOOK?target:"";
        if(scope==Sharing.Scope.PAGE&&!book.isEmpty()&&!membership(Sharing.Scope.BOOK,book).isEmpty())
            return new Object[]{Sharing.Scope.BOOK,book,nameOf(book,false)};
        String collection=scope==Sharing.Scope.COLLECTION?"":collectionOfBook(book);
        if(!collection.isEmpty()&&!membership(Sharing.Scope.COLLECTION,collection).isEmpty())
            return new Object[]{Sharing.Scope.COLLECTION,collection,nameOf(collection,true)};
        return null;
    }

    /** What a collection or a book is called, so the name can travel with a note out of it. */
    String nameOf(String id,boolean collection) {
        if(id==null||id.trim().isEmpty())return "";
        try(Cursor c=getReadableDatabase().query(collection?"collections":"books",new String[]{"name"},
                "id=?",new String[]{id},null,null,null,"1")) {
            return c.moveToFirst()?c.getString(0):"";
        }
    }

    /**
     * Whether this address may write back into this note, by whichever rule reaches it.
     *
     * <p>The sending end is the only end that knows: the rule lives here. So it is said in the parcel,
     * every time, and the receiving end can show what it is allowed rather than having to guess.
     */
    boolean mayWrite(String address,String collection,String book,String page) {
        return Boolean.TRUE.equals(Sharing.audience(shares(),collection,book,page).get(address));
    }

    /**
     * Whether a thing is still on the shelves: here, and neither binned nor archived.
     *
     * <p>Asked of the place you are standing in, because the place you were standing in yesterday can have
     * been put away since. A level that is gone lists nothing and says nothing, so without this the app
     * shows you the inside of a binned collection with its name at the top - and the library, one tap
     * above, saying there are no collections at all.
     */
    boolean stillThere(Branch.Kind kind,String id) {
        String table=kind==Branch.Kind.COLLECTION?"collections"
                    :kind==Branch.Kind.BOOK?"books"
                    :kind==Branch.Kind.PAGE?"notes":null;
        if(table==null)return true;
        try(Cursor c=getReadableDatabase().query(table,new String[]{"id"},"id=? AND "+HERE,
                new String[]{id},null,null,null,"1")) {
            return c.moveToFirst();
        }
    }

    /** Which book a note is on, and which collection a book is in: what an undo needs to put one back. */
    String bookOf(String note){String said=parentOf("notes","book",note);return said==null?"":said;}
    String collectionOfBook(String book){String said=parentOf("books","collection",book);return said==null?"":said;}

    /** The revision of this note that an address was last known to have. */
    /** The revision this phone takes itself and that address to have last both had. */
    long agreedAt(String note,String address){return lastSeen(note,address);}

    private long lastSeen(String note,String address) {
        // What the two last agreed on, which is not what was last delivered - see SchemaMigrations.AGREED.
        try(Cursor c=getReadableDatabase().query("sent",new String[]{"agreed"},"page=? AND address=?",
                new String[]{note,address},null,null,null,"1")) {
            return c.moveToFirst()?c.getLong(0):0;
        }
    }

    /** Deletes a page outright. The app has no trash to hide it in, so nothing pretends it is recoverable. */
    void remove(String id) { getWritableDatabase().delete("notes","id=?",new String[]{id}); }

    /** The page written to most recently, anywhere on the shelves, or null on a first run. */
    Note latest() {
        try(Cursor c=getReadableDatabase().query("notes",null,HERE,null,null,null,"updated DESC","1")) {
            return c.moveToFirst()?read(c,true):null;
        }
    }

    /** The full page, or null if it is no longer stored. */
    Note get(String id) {
        try(Cursor c=getReadableDatabase().query("notes",null,"id=?",new String[]{id},null,null,null,"1")) {
            return c.moveToFirst()?read(c,true):null;
        }
    }

    /** Rows for one book, in the reader's own order, carrying previews rather than whole pages. */
    List<Note> pages(String book) {
        List<Note> notes=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("notes",ROW,HERE+" AND book=?",new String[]{book},null,null,ORDER)) {
            while(c.moveToNext())notes.add(read(c,false));
        }
        return notes;
    }

    /** One order for every level: where the reader put it, and for anything never dragged, newest first. */
    private static final String ORDER="place ASC, updated DESC";
    /** On the shelves means neither put away nor in the bin. Every live list says so the same way. */
    private static final String HERE="deleted=0 AND archived=0";
    private static final String[] ROW={"id","title","notebook","book","pinned","deleted","archived","updated","place",
        "colour","revision","theirs","origin","writes","substr(body,1,"+PREVIEW+") AS preview"};

    private static long whole(Cursor c,String column,long fallback) {
        int at=c.getColumnIndex(column);
        return at<0||c.isNull(at)?fallback:c.getLong(at);
    }
    private static String said(Cursor c,String column,String fallback) {
        int at=c.getColumnIndex(column);
        return at<0||c.isNull(at)?fallback:c.getString(at);
    }

    private static Note read(Cursor c,boolean complete) {
        Note n=new Note();
        n.id=c.getString(c.getColumnIndexOrThrow("id"));n.title=c.getString(c.getColumnIndexOrThrow("title"));
        n.notebook=c.getString(c.getColumnIndexOrThrow("notebook"));n.book=c.getString(c.getColumnIndexOrThrow("book"));
        n.pinned=c.getInt(c.getColumnIndexOrThrow("pinned"))==1;
        n.deleted=c.getInt(c.getColumnIndexOrThrow("deleted"))==1;n.updated=c.getLong(c.getColumnIndexOrThrow("updated"));
        n.place=c.getLong(c.getColumnIndexOrThrow("place"));
        n.archived=c.getInt(c.getColumnIndexOrThrow("archived"))==1;
        n.colour=c.getInt(c.getColumnIndexOrThrow("colour"));
        // Read for what is there rather than for what ought to be: a row fetched with fewer columns — a list
        // row, or one from an older read — must not take the app down, it must simply say less.
        n.revision=whole(c,"revision",0);
        n.theirs=whole(c,"theirs",0)==1;
        n.writes=whole(c,"writes",0)==1;
        n.origin=said(c,"origin","");
        n.complete=complete;
        if(complete){n.body=c.getString(c.getColumnIndexOrThrow("body"));n.preview=n.body.length()>PREVIEW?n.body.substring(0,PREVIEW):n.body;}
        else n.preview=c.getString(c.getColumnIndexOrThrow("preview"));
        return n;
    }

    // ---- looking for something, and seeing the whole of it -----------------------------------------------

    /**
     * Everywhere a word turns up, across the whole pad.
     *
     * <p>Notes first and then the shelves that hold them, because a word is usually something somebody
     * wrote rather than something they named. Only what is on the shelves: the archive and the bin are
     * places you go on purpose, and a search that quietly returned what you had thrown away would be
     * offering you back a decision you had already made.
     */
    List<Branch> looking(String term) {
        List<Branch> found=new ArrayList<>();
        String tidy=Find.tidy(term);
        if(tidy.isEmpty())return found;
        String like=Find.like(tidy);
        String sql="SELECT n.id,n.title,n.body,n.colour,b.name,c.name FROM notes n"
            +" LEFT JOIN books b ON b.id=n.book LEFT JOIN collections c ON c.id=b.collection"
            +" WHERE n.deleted=0 AND n.archived=0 AND (n.title LIKE ? ESCAPE '\\' OR n.body LIKE ? ESCAPE '\\')"
            +" ORDER BY n.updated DESC LIMIT 200";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{like,like})) {
            while(c.moveToNext()) {
                Note page=new Note();
                page.id=c.getString(0);page.title=c.getString(1)==null?"":c.getString(1);
                page.body=c.getString(2)==null?"":c.getString(2);page.preview=page.body;
                String where=(c.isNull(5)?"":c.getString(5)+" \u203a ")+(c.isNull(4)?"":c.getString(4));
                String said=Find.around(Find.holds(page.title,tidy)?page.title:page.body,tidy);
                found.add(new Branch(Branch.Kind.PAGE,page.id,"",page.heading(),
                    where.trim().isEmpty()?said:where+" \u00b7 "+said,0,0,false,c.getInt(3)));
            }
        }
        String books="SELECT b.id,b.name,b.collection,c.name,b.colour FROM books b"
            +" LEFT JOIN collections c ON c.id=b.collection"
            +" WHERE b.deleted=0 AND b.archived=0 AND b.name LIKE ? ESCAPE '\\' ORDER BY b.place ASC LIMIT 60";
        try(Cursor c=getReadableDatabase().rawQuery(books,new String[]{like})) {
            while(c.moveToNext())found.add(new Branch(Branch.Kind.BOOK,c.getString(0),c.getString(2),
                c.getString(1),"book in "+(c.isNull(3)?"a collection that is gone":c.getString(3)),
                0,0,true,c.getInt(4)));
        }
        String shelves="SELECT id,name,colour FROM collections WHERE deleted=0 AND archived=0"
            +" AND name LIKE ? ESCAPE '\\' ORDER BY place ASC LIMIT 60";
        try(Cursor c=getReadableDatabase().rawQuery(shelves,new String[]{like})) {
            while(c.moveToNext())found.add(new Branch(Branch.Kind.COLLECTION,c.getString(0),
                Sharing.EVERYTHING,c.getString(1),"collection",0,0,true,c.getInt(2)));
        }
        return found;
    }

    /**
     * What was kept to hand, whatever level it sits at.
     *
     * <p>A favourite is a thing you come back to, so the list is in the order they were made favourites
     * rather than the order they were last touched - that is what {@link #lately} is for, and a list that
     * reshuffled itself every time you opened something would not be somewhere to keep anything.
     */
    List<Branch> favourites() {
        List<Branch> kept=new ArrayList<>();
        String shelves="SELECT id,name,colour FROM collections WHERE pinned=1 AND "+HERE+" ORDER BY place ASC";
        try(Cursor c=getReadableDatabase().rawQuery(shelves,null)) {
            while(c.moveToNext())kept.add(new Branch(Branch.Kind.COLLECTION,c.getString(0),Sharing.EVERYTHING,
                c.getString(1),"collection",0,0,true,c.getInt(2)));
        }
        String books="SELECT b.id,b.name,b.collection,c.name,b.colour FROM books b"
            +" LEFT JOIN collections c ON c.id=b.collection WHERE b.pinned=1 AND b.deleted=0 AND b.archived=0"
            +" ORDER BY b.place ASC";
        try(Cursor c=getReadableDatabase().rawQuery(books,null)) {
            while(c.moveToNext())kept.add(new Branch(Branch.Kind.BOOK,c.getString(0),c.getString(2),
                c.getString(1),"book in "+(c.isNull(3)?"a collection that is gone":c.getString(3)),
                0,0,true,c.getInt(4)));
        }
        try(Cursor c=getReadableDatabase().query("notes",ROW,"pinned=1 AND "+HERE,null,null,null,ORDER)) {
            while(c.moveToNext()) {
                Note page=read(c,false);
                kept.add(new Branch(Branch.Kind.PAGE,page.id,page.book,page.heading(),
                    "note in "+bookName(page.book),0,0,false,page.colour));
            }
        }
        return kept;
    }

    /** What was written in most recently, newest first. Notes: a shelf is not a thing you write on. */
    List<Branch> lately(int most) {
        List<Branch> recent=new ArrayList<>();
        String sql="SELECT n.id,n.title,substr(n.body,1,"+PREVIEW+"),n.colour,b.name,c.name FROM notes n"
            +" LEFT JOIN books b ON b.id=n.book LEFT JOIN collections c ON c.id=b.collection"
            +" WHERE n.deleted=0 AND n.archived=0 ORDER BY n.updated DESC LIMIT "+Math.max(1,most);
        try(Cursor c=getReadableDatabase().rawQuery(sql,null)) {
            while(c.moveToNext()) {
                Note page=new Note();
                page.title=c.getString(1)==null?"":c.getString(1);
                page.preview=c.getString(2)==null?"":c.getString(2);
                String where=(c.isNull(5)?"":c.getString(5)+" \u203a ")+(c.isNull(4)?"":c.getString(4));
                recent.add(new Branch(Branch.Kind.PAGE,c.getString(0),"",page.heading(),
                    where.trim(),0,0,false,c.getInt(3)));
            }
        }
        return recent;
    }

    /**
     * How long this thing waits after the writing stops before it goes, in seconds.
     *
     * <p>Its own setting, or the book's, or the collection's, or the one everything starts with. The same
     * way a colour is inherited, and for the same reason: setting a thing once at the top is what makes a
     * setting worth having.
     *
     * @return seconds to wait, or {@link #WHEN_ASKED} for a thing that goes only when somebody says so
     */
    int pauseFor(Branch.Kind kind,String id) {
        int own=pauseOn(kind,id);
        if(own!=0)return own;
        if(kind==Branch.Kind.PAGE) {
            String book=bookOf(id);
            if(!book.isEmpty())return pauseFor(Branch.Kind.BOOK,book);
        }
        if(kind==Branch.Kind.BOOK) {
            String collection=collectionOfBook(id);
            if(!collection.isEmpty())return pauseFor(Branch.Kind.COLLECTION,collection);
        }
        return USUALLY;
    }

    /** What a thing waits when nobody has said otherwise, and what "only when asked" is written as. */
    static final int USUALLY=10, WHEN_ASKED=-1;

    /** What is set on this thing itself, where 0 means it takes after whatever holds it. */
    int pauseOn(Branch.Kind kind,String id) {
        String table=kind==Branch.Kind.COLLECTION?"collections":kind==Branch.Kind.BOOK?"books":"notes";
        try(Cursor c=getReadableDatabase().query(table,new String[]{"pause"},"id=?",new String[]{id},
                null,null,null,"1")) {
            return c.moveToFirst()?c.getInt(0):0;
        }
    }

    void setPause(Branch.Kind kind,String id,int seconds) {
        String table=kind==Branch.Kind.COLLECTION?"collections":kind==Branch.Kind.BOOK?"books":"notes";
        ContentValues v=new ContentValues();v.put("pause",seconds);
        getWritableDatabase().update(table,v,"id=?",new String[]{id});
    }

    /** What the place that gathers favourites is known by, where a collection would have an id. */
    static final String FAVOURITES="favourites";

    /** Everything that is a favourite, by id, whatever kind of thing it is. */
    Set<String> keptIds() {
        Set<String> all=new HashSet<>();
        for(String table:new String[]{"collections","books","notes"})
            try(Cursor c=getReadableDatabase().query(table,new String[]{"id"},"pinned=1",null,null,null,null)) {
                while(c.moveToNext())all.add(c.getString(0));
            }
        return all;
    }

    /**
     * How many favourites there are to show: on the shelves, and inside nothing that has been put away.
     * The same things the place lists when it is opened, so its tile never promises more than is in it.
     */
    int keptCount() {
        String on="c.deleted=0 AND c.archived=0";
        String[] asks={
            "SELECT COUNT(*) FROM collections c WHERE c.pinned=1 AND "+on,
            "SELECT COUNT(*) FROM books b JOIN collections c ON c.id=b.collection"
                +" WHERE b.pinned=1 AND b.deleted=0 AND b.archived=0 AND "+on,
            "SELECT COUNT(*) FROM notes n JOIN books b ON b.id=n.book JOIN collections c ON c.id=b.collection"
                +" WHERE n.pinned=1 AND n.deleted=0 AND n.archived=0 AND b.deleted=0 AND b.archived=0 AND "+on};
        int all=0;
        for(String ask:asks)try(Cursor c=getReadableDatabase().rawQuery(ask,null)) {
            if(c.moveToFirst())all+=c.getInt(0);
        }
        return all;
    }

    /** Whether one thing is kept to hand. */
    boolean favourite(Branch.Kind kind,String id) {
        String table=kind==Branch.Kind.COLLECTION?"collections":kind==Branch.Kind.BOOK?"books":"notes";
        try(Cursor c=getReadableDatabase().query(table,new String[]{"pinned"},"id=?",new String[]{id},
                null,null,null,"1")) {
            return c.moveToFirst()&&c.getInt(0)==1;
        }
    }

    /** Kept to hand, or not. */
    void keepToHand(Branch.Kind kind,String id,boolean kept) {
        String table=kind==Branch.Kind.COLLECTION?"collections":kind==Branch.Kind.BOOK?"books":"notes";
        ContentValues v=new ContentValues();v.put("pinned",kept?1:0);
        getWritableDatabase().update(table,v,"id=?",new String[]{id});
    }

    /**
     * The whole pad at once: every collection, the books in each, and the notes on each book.
     *
     * <p>Walking in and out of things shows you one room at a time, which is the right way to work and the
     * wrong way to remember where you put something. This is the plan of the building.
     */
    List<Branch> wholeTree() {
        List<Branch> tree=new ArrayList<>();
        for(Shelf collection:collections()) {
            tree.add(new Branch(Branch.Kind.COLLECTION,collection.id,Sharing.EVERYTHING,collection.name,
                collection.count+(collection.count==1?" book":" books"),0,0,true,collection.colour,0,
                Sharing.state(new java.util.LinkedHashMap<>(),collection.theirs),collection.origin));
            for(Shelf book:books(collection.id)) {
                tree.add(new Branch(Branch.Kind.BOOK,book.id,collection.id,book.name,
                    book.count+(book.count==1?" note":" notes"),1,0,true,book.colour,0,
                    Sharing.state(new java.util.LinkedHashMap<>(),book.theirs),book.origin));
                for(Note page:pages(book.id))
                    tree.add(new Branch(Branch.Kind.PAGE,page.id,book.id,page.heading(),page.rest(),2,0,
                        false,page.colour,0,Sharing.state(new java.util.LinkedHashMap<>(),page.theirs),
                        page.origin));
            }
        }
        Set<String> kept=keptIds();
        if(!kept.isEmpty())for(Branch one:tree)one.kept=kept.contains(one.id);
        return tree;
    }

    // ---- collections and books ---------------------------------------------------------------------------

    List<Shelf> collections() {
        List<Shelf> shelves=new ArrayList<>();
        String sql="SELECT c.id,c.name,(SELECT COUNT(*) FROM books b WHERE b.collection=c.id AND b.deleted=0 AND b.archived=0) AS held,"
            +"c.colour,c.theirs,c.origin FROM collections c WHERE "+HERE+" ORDER BY c.place ASC, c.updated DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,null)) {
            while(c.moveToNext())shelves.add(new Shelf(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),
                c.getInt(4)==1,c.getString(5)));
        }
        return shelves;
    }

    List<Shelf> books(String collection) {
        List<Shelf> shelves=new ArrayList<>();
        String sql="SELECT b.id,b.name,(SELECT COUNT(*) FROM notes n WHERE n.book=b.id AND n.deleted=0 AND n.archived=0) AS held,"
            +"b.colour,b.theirs,b.origin FROM books b WHERE b.collection=? AND "+HERE+" ORDER BY b.place ASC, b.updated DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{collection})) {
            while(c.moveToNext())shelves.add(new Shelf(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),
                c.getInt(4)==1,c.getString(5)));
        }
        return shelves;
    }

    Shelf addCollection(String name) {
        String id=UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("id",id);v.put("name",name);v.put("updated",now);v.put("place",-now);
        if(getWritableDatabase().insert("collections",null,v)<0)throw new IllegalStateException("Could not add the collection");
        return new Shelf(id,name,0,Tint.NONE);
    }

    Shelf addBook(String collection,String name) {
        String id=UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("id",id);v.put("collection",collection);v.put("name",name);v.put("updated",now);v.put("place",-now);
        if(getWritableDatabase().insert("books",null,v)<0)throw new IllegalStateException("Could not add the book");
        return new Shelf(id,name,0,Tint.NONE);
    }

    /**
     * A book to write a fresh page in. The first book normally, but a pad whose last book was deleted
     * still has to have somewhere to write, so one is made rather than the page landing nowhere.
     */
    String someBook() {
        try(Cursor c=getReadableDatabase().query("books",new String[]{"id"},HERE,null,null,null,ORDER,"1")) {
            if(c.moveToFirst())return c.getString(0);
        }
        String collection=null;
        try(Cursor c=getReadableDatabase().query("collections",new String[]{"id"},HERE,null,null,null,ORDER,"1")) {
            if(c.moveToFirst())collection=c.getString(0);
        }
        if(collection==null)collection=addCollection("My notes").id;
        return addBook(collection,"Notes").id;
    }

    void renameCollection(String id,String name) {
        ContentValues v=new ContentValues();v.put("name",name);v.put("updated",System.currentTimeMillis());
        getWritableDatabase().update("collections",v,"id=?",new String[]{id});
    }
    void renameBook(String id,String name) {
        ContentValues v=new ContentValues();v.put("name",name);v.put("updated",System.currentTimeMillis());
        getWritableDatabase().update("books",v,"id=?",new String[]{id});
    }

    /** The collection a book sits in, so browsing can walk back up from a page. */
    String collectionOf(String book) {
        try(Cursor c=getReadableDatabase().query("books",new String[]{"collection"},"id=?",new String[]{book},null,null,null,"1")) {
            return c.moveToFirst()?c.getString(0):SchemaMigrations.FIRST_COLLECTION;
        }
    }

    String collectionName(String collection) {
        try(Cursor c=getReadableDatabase().query("collections",new String[]{"name"},"id=?",new String[]{collection},null,null,null,"1")) {
            return c.moveToFirst()?c.getString(0):"My notes";
        }
    }

    String bookName(String book) {
        try(Cursor c=getReadableDatabase().query("books",new String[]{"name"},"id=?",new String[]{book},null,null,null,"1")) {
            return c.moveToFirst()?c.getString(0):"Notes";
        }
    }

    // ---- who a level is shared with ----------------------------------------------------------------------

    /**
     * The library as one indented list: All collections, then each collection, then the books of the ones
     * opened, then their pages. Built in a single call so the tree is read and drawn as one piece, and so
     * every line already knows how many addresses it is shared with.
     */
    static final String INBOX="shared-with-me";

    /** A level and what it holds, with how many addresses the level itself is shared with. */
    /** Two audiences as one. Somebody reached both ways is reached both ways. */
    private static Map<String,Boolean> with(Map<String,Boolean> one,Map<String,Boolean> two) {
        if(two==null||two.isEmpty())return one;
        Map<String,Boolean> all=new java.util.LinkedHashMap<>(one);
        for(Map.Entry<String,Boolean> who:two.entrySet())
            all.merge(who.getKey(),who.getValue(),(a,b)->a||b);
        return all;
    }

    static final class Level {
        final List<Branch> holds; final int shared;
        Level(List<Branch> holds,int shared){this.holds=holds;this.shared=shared;}
    }

    /** What sits inside one level: the collections of the library, the books of a collection, the pages of a book. */
    Level inside(Branch.Kind level,String id) {
        List<Sharing.Rule> rules=shares();
        // One read of what got through, then the same answer for every line of this level. A page counts
        // once however many addresses are owed it: the mark says a page is behind, not how many deliveries.
        List<Outbox.Page> under=pagesUnder(level,id);
        Map<String,Outbox.Page> byId=new HashMap<>();
        for(Outbox.Page page:under)byId.put(page.id,page);
        Map<String,Set<String>> owedIn=new HashMap<>();
        for(Outbox.Wait wait:Outbox.waiting(rules,under,sent())) {
            Outbox.Page where=byId.get(wait.page);
            if(where==null)continue;
            for(String key:new String[]{where.id,where.book,where.collection})
                if(key!=null&&!key.isEmpty())owedIn.computeIfAbsent(key,any->new HashSet<>()).add(wait.page);
        }
        // Everybody anything underneath reaches, gathered per shelf.
        //
        // A note shared on its own used to leave the book and the collection holding it saying nothing at
        // all, so the only way to find out that something in a collection was going somewhere was to open
        // every note in it. A shelf says what is true of the things on it.
        Map<String,Map<String,Boolean>> reachedUnder=new HashMap<>();
        Set<String> theirsUnder=new HashSet<>();
        for(Outbox.Page page:under) {
            Map<String,Boolean> mine=Sharing.audience(rules,page.collection,page.book,page.id);
            for(String key:new String[]{page.book,page.collection}) {
                if(key==null||key.isEmpty())continue;
                Map<String,Boolean> all=reachedUnder.computeIfAbsent(key,any->new java.util.LinkedHashMap<>());
                for(Map.Entry<String,Boolean> who:mine.entrySet())
                    all.merge(who.getKey(),who.getValue(),(a,b)->a||b);
            }
        }
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT n.book,b.collection FROM notes n LEFT JOIN books b ON b.id=n.book"
                +" WHERE n.theirs=1 AND n.deleted=0 AND n.archived=0",null)) {
            while(c.moveToNext()) {
                if(!c.isNull(0))theirsUnder.add(c.getString(0));
                if(!c.isNull(1))theirsUnder.add(c.getString(1));
            }
        }
        Sharing.Scope scope=level==Branch.Kind.LIBRARY?Sharing.Scope.LIBRARY
            :level==Branch.Kind.COLLECTION?Sharing.Scope.COLLECTION
            :level==Branch.Kind.BOOK?Sharing.Scope.BOOK:null;
        List<Branch> lines=new ArrayList<>();
        final Set<String> kept=keptIds();
        if(level==Branch.Kind.LIBRARY) {
            // First, and only once something is a favourite: an empty place on somebody's first screen is
            // a thing to wonder about. Counted from what it will actually show, so it never opens on nothing.
            int held=keptCount();
            if(held>0)lines.add(new Branch(Branch.Kind.FAVOURITES,FAVOURITES,Sharing.EVERYTHING,"Favourites",
                held+(held==1?" favourite":" favourites"),0,0,true));
            for(Shelf collection:collections()) {
                Map<String,Boolean> reaches=with(Sharing.audience(rules,collection.id,"",""),
                    reachedUnder.get(collection.id));
                lines.add(new Branch(Branch.Kind.COLLECTION,collection.id,Sharing.EVERYTHING,collection.name,
                    collection.count+(collection.count==1?" book":" books"),0,reaches.size(),true,collection.colour,
                    held(owedIn,collection.id),
                    Sharing.state(reaches,collection.theirs||theirsUnder.contains(collection.id)),
                    collection.origin));
            }

        } else if(level==Branch.Kind.COLLECTION) {
            for(Shelf book:books(id)) {
                Map<String,Boolean> reaches=with(Sharing.audience(rules,id,book.id,""),
                    reachedUnder.get(book.id));
                lines.add(new Branch(Branch.Kind.BOOK,book.id,id,book.name,
                    book.count+(book.count==1?" note":" notes"),0,reaches.size(),true,book.colour,
                    held(owedIn,book.id),
                    Sharing.state(reaches,book.theirs||theirsUnder.contains(book.id)),book.origin));
            }
        } else if(level==Branch.Kind.ARCHIVE||level==Branch.Kind.BIN) {
            lines.addAll(heldIn(level==Branch.Kind.BIN));
        } else if(level==Branch.Kind.FAVOURITES) {
            // Every favourite, as the same line it is on its own shelf - the same mark, the same colour,
            // the same count - and by kind: collections, then books, then notes. Walked the way the
            // shelves are, so what is in the archive or the bin, or inside something that is, is not here.
            List<Branch> books=new ArrayList<>(), pages=new ArrayList<>();
            for(Shelf collection:collections()) {
                if(kept.contains(collection.id)) {
                    Map<String,Boolean> reaches=with(Sharing.audience(rules,collection.id,"",""),
                        reachedUnder.get(collection.id));
                    lines.add(new Branch(Branch.Kind.COLLECTION,collection.id,Sharing.EVERYTHING,collection.name,
                        collection.count+(collection.count==1?" book":" books"),0,reaches.size(),true,
                        collection.colour,held(owedIn,collection.id),
                        Sharing.state(reaches,collection.theirs||theirsUnder.contains(collection.id)),
                        collection.origin));
                }
                for(Shelf book:books(collection.id)) {
                    if(kept.contains(book.id)) {
                        Map<String,Boolean> reaches=with(Sharing.audience(rules,collection.id,book.id,""),
                            reachedUnder.get(book.id));
                        books.add(new Branch(Branch.Kind.BOOK,book.id,collection.id,book.name,
                            book.count+(book.count==1?" note":" notes"),0,reaches.size(),true,book.colour,
                            held(owedIn,book.id),
                            Sharing.state(reaches,book.theirs||theirsUnder.contains(book.id)),book.origin));
                    }
                    for(Note page:pages(book.id)) {
                        if(!kept.contains(page.id))continue;
                        Map<String,Boolean> reaches=Sharing.audience(rules,collection.id,book.id,page.id);
                        pages.add(new Branch(Branch.Kind.PAGE,page.id,book.id,page.heading(),page.rest(),0,
                            reaches.size(),false,page.colour,held(owedIn,page.id),
                            Sharing.state(reaches,page.theirs),page.origin));
                    }
                }
            }
            lines.addAll(books);lines.addAll(pages);
        } else if(level==Branch.Kind.BOOK) {
            for(Note page:pages(id)) {
                Map<String,Boolean> reaches=Sharing.audience(rules,collectionOf(id),id,page.id);
                lines.add(new Branch(Branch.Kind.PAGE,page.id,id,page.heading(),page.rest(),0,
                    reaches.size(),false,page.colour,held(owedIn,page.id),
                    Sharing.state(reaches,page.theirs),page.origin));
            }
        }
        // The archive and the bin are not things on the shelves: they are places to walk into, named in the
        // menu at every level rather than standing among the collections and notes they hold.
        // And which of them this phone has stopped taking in, so the mark on each can say so.
        Set<String> stopped=paused();
        if(!stopped.isEmpty())for(Branch one:lines)
            // Among the favourites a thing is not inside the place it is listed in, so it is asked itself.
            one.paused=level==Branch.Kind.FAVOURITES?pausedHere(one.kind,one.id)
                :stopped.contains(one.id)||stopped.contains(id)
                ||(one.kind==Branch.Kind.PAGE&&stopped.contains(collectionOfBook(id)));
        if(!kept.isEmpty())for(Branch one:lines)one.kept=kept.contains(one.id);
        return new Level(lines,scope==null?0:shared(rules,scope,id));
    }

    /**
     * Everywhere a carried thing could go: every book when moving a page, every collection when moving a
     * book. A page and the book it would move to are never on the same screen, so the destinations become
     * the screen while something is being carried.
     */
    List<Branch> places(boolean forPage) {
        List<Sharing.Rule> rules=shares();
        List<Branch> where=new ArrayList<>();
        for(Shelf collection:collections()) {
            if(!forPage) {
                where.add(new Branch(Branch.Kind.COLLECTION,collection.id,Sharing.EVERYTHING,collection.name,
                    collection.count+(collection.count==1?" book":" books"),0,
                    shared(rules,Sharing.Scope.COLLECTION,collection.id),true));
                continue;
            }
            for(Shelf book:books(collection.id))
                where.add(new Branch(Branch.Kind.BOOK,book.id,collection.id,book.name,
                    "in "+collection.name,0,shared(rules,Sharing.Scope.BOOK,book.id),true));
        }
        return where;
    }

    /**
     * Everything written inside one thing, as one piece of text: a page is itself, a book is its pages, a
     * collection is the pages of its books, the library is all of them, and the archive and the bin are what
     * is waiting in them. Pages are separated by a blank line and come in the order the reader put them in.
     */
    String gather(Branch.Kind kind,String id) {
        String where;String[] values;
        switch(kind) {
            case PAGE: where="id=?";values=new String[]{id};break;
            case BOOK: where=HERE+" AND book=?";values=new String[]{id};break;
            case COLLECTION: where=HERE+" AND book IN (SELECT id FROM books WHERE collection=?)";values=new String[]{id};break;
            case ARCHIVE: where="archived=1";values=null;break;
            case BIN: where="deleted=1";values=null;break;
            default: where=HERE;values=null;break;
        }
        StringBuilder all=new StringBuilder();
        try(Cursor c=getReadableDatabase().query("notes",new String[]{"body","title"},where,values,null,null,ORDER)) {
            while(c.moveToNext()) {
                String body=c.getString(0).trim();
                String title=c.isNull(1)?"":c.getString(1).trim();
                if(body.isEmpty()&&title.isEmpty())continue;
                if(all.length()>0)all.append("\n\n");
                // A note goes out under its title, where it has one: it is part of what the note says.
                if(!title.isEmpty())all.append(title).append(body.isEmpty()?"":"\n\n");
                all.append(body);
            }
        }
        return all.toString();
    }

    // ---- what the addresses have not been given ------------------------------------------------------------

    /** Which revision of which page reached which address, as {@link Outbox} keys. */
    Map<String,Long> sent() {
        Map<String,Long> got=new HashMap<>();
        try(Cursor c=getReadableDatabase().query("sent",new String[]{"address","page","revision"},null,null,null,null,null)) {
            while(c.moveToNext())got.put(Outbox.mark(c.getString(0),c.getString(1)),c.getLong(2));
        }
        return got;
    }

    /**
     * An address has a page, at this revision — because it said so, or because the page came from it.
     *
     * <p>Nothing else writes this, which is the whole of why the mark is worth looking at. It used to be
     * written when the network took a message, and the network will take a message for a phone that is
     * asleep, or gone, or has stopped taking this note; see {@link Receipt}. Never backwards: an answer
     * about an older revision that turns up late says nothing about a newer one.
     */
    void reached(String address,String page,long revision){record(address,page,revision,-1);}

    /**
     * That address and this phone both say the same thing about a page, at this revision: it took what
     * this phone sent as it stood, or this phone took what it sent. Delivered as well, necessarily.
     */
    void agreedOn(String address,String page,long revision){record(address,page,revision,revision);}

    private void record(String address,String page,long revision,long agreed) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            long had=-1, both=0;
            try(Cursor c=db.query("sent",new String[]{"revision","agreed"},"address=? AND page=?",
                    new String[]{address,page},null,null,null,"1")) {
                if(c.moveToFirst()){had=c.getLong(0);both=c.getLong(1);}
            }
            if(revision>had||agreed>both) {
                ContentValues v=new ContentValues();
                v.put("address",address);v.put("page",page);v.put("revision",Math.max(revision,had));
                v.put("agreed",Math.max(agreed,both));
                v.put("at",System.currentTimeMillis());
                db.insertWithOnConflict("sent",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }
            // Whatever was waiting on an answer about this much, or less, has had it.
            db.delete("handed","address=? AND page=? AND revision<=?",
                new String[]{address,page,String.valueOf(Math.max(revision,had))});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    /**
     * The network took a page for an address. Not the same as the address having it: that is
     * {@link #reached}, and only the address itself can say so.
     */
    void handedOver(String address,String page,long revision) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            int tries=0;
            try(Cursor c=db.query("handed",new String[]{"revision","tries"},"address=? AND page=?",
                    new String[]{address,page},null,null,null,"1")) {
                // Counted per revision: a newer one starts again from the short waits.
                if(c.moveToFirst()&&c.getLong(0)==revision)tries=c.getInt(1);
            }
            ContentValues v=new ContentValues();
            v.put("address",address);v.put("page",page);v.put("revision",revision);
            v.put("at",System.currentTimeMillis());v.put("tries",tries+1);
            db.insertWithOnConflict("handed",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    /** Everything handed over and not yet answered. */
    List<Outbox.Handed> handed() {
        List<Outbox.Handed> all=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("handed",new String[]{"address","page","revision","at","tries"},
                null,null,null,null,"at ASC")) {
            while(c.moveToNext())all.add(new Outbox.Handed(c.getString(0),c.getString(1),c.getLong(2),
                c.getLong(3),c.getInt(4)));
        }
        return all;
    }

    /** The revision every note on the shelves is at now, by id. */
    Map<String,Long> revisions() {
        Map<String,Long> now=new HashMap<>();
        for(Outbox.Page page:pagesUnder(Branch.Kind.LIBRARY,Sharing.EVERYTHING))now.put(page.id,page.revision);
        return now;
    }

    /**
     * An answer arrived: that address says it has that page at that revision.
     *
     * <p>Believed only as far as it could be true. A revision this phone has never reached is not
     * something anybody can have been sent, whoever signs for it.
     */
    boolean acknowledged(String address,String page,long revision,boolean took) {
        Note here=get(page);
        if(here==null||revision>here.revision)return false;
        if(took)agreedOn(address,page,revision);else reached(address,page,revision);
        return true;
    }

    /** Every page inside one thing, as the outbox needs to see it: where it sits, and how new it is. */
    List<Outbox.Page> pagesUnder(Branch.Kind kind,String id) {
        String where;String[] values;
        switch(kind) {
            case PAGE: where="n.id=?";values=new String[]{id};break;
            case BOOK: where="n.book=?";values=new String[]{id};break;
            case COLLECTION: where="b.collection=?";values=new String[]{id};break;
            default: where="1=1";values=null;break;
        }
        List<Outbox.Page> pages=new ArrayList<>();
        // Only what is on the shelves is owed: a page inside an archived or binned book has gone away with
        // it, and nothing that is put away should be pushed at anybody.
        // The revision, which is what a delivery is recorded as. This read `n.updated` for as long as notes
        // have been sent: a clock, in milliseconds, set beside a count of a few dozen. No count is ever as
        // big as a clock, so nothing was ever up to date — every shared note was owed for ever, the line
        // under the bar said "Not sent yet" whatever had happened, and the whole pad was sent again to
        // everybody each time it was opened. That last part hid a good deal: notes the network had lost
        // turned up anyway, a day later, and looked like they had only been slow.
        String sql="SELECT n.id,n.book,b.collection,COALESCE(n.revision,0) FROM notes n"
            +" LEFT JOIN books b ON b.id=n.book LEFT JOIN collections c ON c.id=b.collection"
            +" WHERE n.deleted=0 AND n.archived=0 AND COALESCE(b.deleted,0)=0 AND COALESCE(b.archived,0)=0"
            +" AND COALESCE(c.deleted,0)=0 AND COALESCE(c.archived,0)=0 AND "+where;
        try(Cursor c=getReadableDatabase().rawQuery(sql,values)) {
            while(c.moveToNext())pages.add(new Outbox.Page(c.getString(0),c.getString(1),c.getString(2),c.getLong(3)));
        }
        return pages;
    }

    /** What one thing owes, ready to be counted or shown by name. */
    List<Outbox.Wait> owed(Branch.Kind kind,String id) {
        return Outbox.waiting(shares(),pagesUnder(kind,id),sent());
    }

    private static int held(Map<String,Set<String>> owedIn,String id) {
        Set<String> pages=owedIn.get(id);
        return pages==null?0:pages.size();
    }

    /** The colour one collection, book or page was given. Which colour, never a pixel value. */
    void paint(Branch.Kind kind,String id,int colour) {
        ContentValues v=new ContentValues();v.put("colour",colour);
        getWritableDatabase().update(table(kind),v,"id=?",new String[]{id});
    }

    /** The colour a thing has now, for the menu that is about to offer to change it. */
    int colourOf(Branch.Kind kind,String id) {
        try(Cursor c=getReadableDatabase().query(table(kind),new String[]{"colour"},"id=?",new String[]{id},null,null,null,"1")) {
            return c.moveToFirst()?c.getInt(0):Tint.NONE;
        }
    }

    // ---- the archive and the bin ---------------------------------------------------------------------------

    /** The two places a thing can be put instead of being on the shelves. */
    static final String ARCHIVE="archive", BIN="bin";

    private static String table(Branch.Kind kind) {
        return kind==Branch.Kind.PAGE?"notes":kind==Branch.Kind.BOOK?"books":"collections";
    }

    /** Puts one thing away, or takes it out again. What it holds goes with it, still inside it. */
    void putAway(Branch.Kind kind,String id,boolean bin,boolean away) {
        ContentValues v=new ContentValues();v.put(bin?"deleted":"archived",away?1:0);
        getWritableDatabase().update(table(kind),v,"id=?",new String[]{id});
    }

    /**
     * Back on the shelves. Whatever holds it comes back too, because a page put back into a book that is
     * itself in the bin would be back nowhere.
     */
    void restore(Branch.Kind kind,String id) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            ContentValues v=new ContentValues();v.put("deleted",0);v.put("archived",0);
            db.update(table(kind),v,"id=?",new String[]{id});
            if(kind==Branch.Kind.PAGE) {
                String book=parentOf("notes","book",id);
                if(book!=null){db.update("books",v,"id=?",new String[]{book});
                    String collection=parentOf("books","collection",book);
                    if(collection!=null)db.update("collections",v,"id=?",new String[]{collection});}
            } else if(kind==Branch.Kind.BOOK) {
                String collection=parentOf("books","collection",id);
                if(collection!=null)db.update("collections",v,"id=?",new String[]{collection});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private String parentOf(String table,String column,String id) {
        try(Cursor c=getReadableDatabase().query(table,new String[]{column},"id=?",new String[]{id},null,null,null,"1")) {
            return c.moveToFirst()?c.getString(0):null;
        }
    }

    /** Gone for good: the thing, everything inside it, and every sharing rule that pointed at any of it. */
    void erase(Branch.Kind kind,String id) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{burn(db,kind,id);db.setTransactionSuccessful();}finally{db.endTransaction();}
        sweep();
    }

    private void burn(SQLiteDatabase db,Branch.Kind kind,String id) {
        if(kind==Branch.Kind.COLLECTION)
            for(String book:childrenOf("books","collection",id))burn(db,Branch.Kind.BOOK,book);
        if(kind==Branch.Kind.BOOK)
            for(String page:childrenOf("notes","book",id))burn(db,Branch.Kind.PAGE,page);
        // What a note holds goes with it: the rows first, and the bytes when the writing has been committed.
        if(kind==Branch.Kind.PAGE)db.delete("files","note=?",new String[]{id});
        // And whatever was still waiting for somebody to say they had it: there is nothing left to have.
        if(kind==Branch.Kind.PAGE)db.delete("handed","page=?",new String[]{id});
        db.delete(table(kind),"id=?",new String[]{id});
        Sharing.Scope scope=kind==Branch.Kind.PAGE?Sharing.Scope.PAGE
            :kind==Branch.Kind.BOOK?Sharing.Scope.BOOK:Sharing.Scope.COLLECTION;
        db.delete("shares","scope=? AND target=?",new String[]{scope.name(),id});
        db.delete("standing","scope=? AND target=?",new String[]{scope.name(),id});
    }

    private List<String> childrenOf(String table,String column,String id) {
        List<String> held=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query(table,new String[]{"id"},column+"=?",new String[]{id},null,null,null)) {
            while(c.moveToNext())held.add(c.getString(0));
        }
        return held;
    }

    /** Everything in the bin, gone for good, and how many things that was. */
    int emptyBin() {
        List<Branch> waiting=heldIn(true);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{for(Branch thing:waiting)burn(db,thing.kind,thing.id);db.setTransactionSuccessful();}
        finally{db.endTransaction();}
        sweep();
        return waiting.size();
    }

    /**
     * What is in the archive, or what is in the bin: collections first, then books, then pages, each saying
     * what it is and where it came from. Only the thing itself is listed — what it holds travels inside it.
     */
    List<Branch> heldIn(boolean bin) {
        String flag=bin?"deleted":"archived";
        List<Branch> away=new ArrayList<>();
        String collections="SELECT c.id,c.name,(SELECT COUNT(*) FROM books b WHERE b.collection=c.id),c.colour"
            +" FROM collections c WHERE c."+flag+"=1 ORDER BY c.place ASC, c.updated DESC";
        try(Cursor c=getReadableDatabase().rawQuery(collections,null)) {
            while(c.moveToNext())
                away.add(new Branch(Branch.Kind.COLLECTION,c.getString(0),"",c.getString(1),
                    "collection · "+held(c.getInt(2)," book"," books"),0,0,false,c.getInt(3)));
        }
        String books="SELECT b.id,b.name,b.collection,c.name,(SELECT COUNT(*) FROM notes n WHERE n.book=b.id),b.colour"
            +" FROM books b LEFT JOIN collections c ON c.id=b.collection WHERE b."+flag+"=1"
            +" ORDER BY b.place ASC, b.updated DESC";
        try(Cursor c=getReadableDatabase().rawQuery(books,null)) {
            while(c.moveToNext())
                away.add(new Branch(Branch.Kind.BOOK,c.getString(0),c.getString(2),c.getString(1),
                    "book in "+(c.isNull(3)?"a collection that is gone":c.getString(3))
                    +" · "+held(c.getInt(4)," note"," notes"),0,0,false,c.getInt(5)));
        }
        try(Cursor c=getReadableDatabase().query("notes",ROW,flag+"=1",null,null,null,ORDER)) {
            while(c.moveToNext()) {
                Note page=read(c,false);
                away.add(new Branch(Branch.Kind.PAGE,page.id,page.book,page.heading(),
                    "note in "+bookName(page.book),0,0,false,page.colour));
            }
        }
        return away;
    }

    private static String held(int count,String one,String many){return count+(count==1?one:many);}

    /** How many things are waiting in the archive, or in the bin. */
    int awayCount(boolean bin) {
        String flag=bin?"deleted":"archived";
        int count=0;
        for(String table:new String[]{"collections","books","notes"})
            try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE "+flag+"=1",null)) {
                if(c.moveToFirst())count+=c.getInt(0);
            }
        return count;
    }

    // ---- moving things between shelves --------------------------------------------------------------------

    // Something you just moved arrives at the top of where it landed, which is where you are looking for it.
    void movePage(String page,String book) {
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("book",book);v.put("updated",now);v.put("place",-now);
        getWritableDatabase().update("notes",v,"id=?",new String[]{page});
    }
    void moveBook(String book,String collection) {
        long now=System.currentTimeMillis();
        ContentValues v=new ContentValues();v.put("collection",collection);v.put("updated",now);v.put("place",-now);
        getWritableDatabase().update("books",v,"id=?",new String[]{book});
    }

    /**
     * The order of one level, written as the reader left it. Places are rewritten for the whole level at
     * once, in one transaction, so a list is never half reordered; `updated` is untouched, because putting
     * a page somewhere is not writing on it.
     */
    void order(Branch.Kind kind,List<String> ids) {
        String table=kind==Branch.Kind.PAGE?"notes":kind==Branch.Kind.BOOK?"books":"collections";
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(int at=0;at<ids.size();at++) {
                ContentValues v=new ContentValues();v.put("place",at);
                db.update(table,v,"id=?",new String[]{ids.get(at)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    // ---- the addresses you share with ---------------------------------------------------------------------

    /** An address you share with, under the name you gave it. */
    static final class Contact {
        final String address,name; final boolean mine;
        /** What Maxima knows this device as, once its node has been told about it; empty until then. */
        final String contact;
        /** The keys to seal for it and to check it by, as they arrived in its pairing line; empty until paired. */
        final byte[] agreement,signing;
        Contact(String address,String name,boolean mine){this(address,name,mine,"",new byte[0],new byte[0]);}
        Contact(String address,String name,boolean mine,String contact,byte[] agreement,byte[] signing) {
            this.address=address;this.name=name;this.mine=mine;this.contact=contact;
            this.agreement=agreement;this.signing=signing;
        }
        /** Whether this device can be sealed for: an address alone is somewhere to send nothing. */
        boolean paired(){return agreement.length>0&&signing.length>0;}
    }

    /** Saved once, then picked: an address is long, and retyping one is how pages reach the wrong person. */
    /**
     * Every device this one can reach.
     *
     * <p>A row with no address in it is not one of them. One got written before the address was checked,
     * and it showed in the list as a second device with the same name as the first - two identical names,
     * one of which can never be sent to and gives no sign of it until a send fails. They are left in the
     * table rather than deleted, because a row nobody asked to remove is not one to remove quietly, and
     * they are simply not a device you can pick.
     */
    List<Contact> addresses() {
        List<Contact> known=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("addresses",null,"TRIM(address)<>''",null,null,null,"added ASC")) {
            while(c.moveToNext())known.add(readContact(c));
        }
        return known;
    }

    /** One saved address, or null. */
    Contact address(String address) {
        try(Cursor c=getReadableDatabase().query("addresses",null,"address=?",new String[]{address},null,null,null,"1")) {
            return c.moveToFirst()?readContact(c):null;
        }
    }

    private static Contact readContact(Cursor c) {
        return new Contact(c.getString(c.getColumnIndexOrThrow("address")),
            c.getString(c.getColumnIndexOrThrow("name")),c.getInt(c.getColumnIndexOrThrow("mine"))==1,
            c.getString(c.getColumnIndexOrThrow("contact")),
            unwritten(c.getString(c.getColumnIndexOrThrow("agreement"))),
            unwritten(c.getString(c.getColumnIndexOrThrow("signing"))));
    }

    private static byte[] unwritten(String kept) {
        if(kept==null||kept.isEmpty())return new byte[0];
        try{return android.util.Base64.decode(kept,android.util.Base64.NO_WRAP);}catch(RuntimeException e){return new byte[0];}
    }
    private static String written(byte[] raw) {
        return raw==null||raw.length==0?"":android.util.Base64.encodeToString(raw,android.util.Base64.NO_WRAP);
    }

    /**
     * A key as the one form this table keeps them in.
     *
     * <p>The same key travels in two shapes: the long one Java hands out, and the thirty-three bytes a
     * pairing code carries. Stored as they arrive, two rows for one device hold two different strings, and
     * every test of "is this the same device?" says no — which is how a phone ended up filed twice, once
     * under an address nothing can be sent to. So whichever shape arrives, one shape is written down.
     */
    private static String canonical(byte[] raw) {
        if(raw==null||raw.length==0)return "";
        try{return written(Point.read(raw).getEncoded());}
        catch(Exception notAKey){return written(raw);}
    }

    /** The same, for something already written down. */
    private static String canonical(String kept) {
        return canonical(unwritten(kept));
    }

    /** The keys a device handed over when it was paired with. Its address and name are kept as they were. */
    void pairedWith(String address,String name,boolean mine,byte[] agreement,byte[] signing) {
        // Keys without an address to send them to is not a device, it is half of one. Refused here rather
        // than kept and found out later, when the only sign is a send that fails for no stated reason.
        if(address==null||address.trim().isEmpty())
            throw new IllegalArgumentException("That code carried no address to send to.");

        // A device is its keys, not its address.
        //
        // The address changes: a node that has not reached a relay yet gives out one thing and a node
        // that has gives out another, and a phone that moves house gives out a third. Keyed on the
        // address, scanning the same phone's code again made a second device with the same name beside
        // the first - and everything already pointing at the old one, every share rule and every record
        // of what got through, went on pointing at an address nothing answers. So the row is found by its
        // signing key, and what is found is moved rather than duplicated.
        String was=deviceKeyed(signing);
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            // Every other row for this same device, not only the newest of them. Two rows with one key
            // is one device wearing two addresses, and the one that answers first decides what a note
            // arriving says it came from - which is how a note turned up from an address nothing can be
            // sent to, and how an acceptance went on waiting for an answer that had already arrived.
            for(String other:everyAddressOf(signing)) {
                if(other.equals(address)||other.equals(was))continue;
                db.execSQL("UPDATE OR REPLACE shares SET address=? WHERE address=?",new String[]{address,other});
                db.execSQL("UPDATE OR REPLACE sent SET address=? WHERE address=?",new String[]{address,other});
                db.execSQL("UPDATE OR REPLACE handed SET address=? WHERE address=?",new String[]{address,other});
                db.execSQL("UPDATE OR REPLACE refused SET address=? WHERE address=?",new String[]{address,other});
                db.execSQL("UPDATE OR REPLACE accepting SET address=? WHERE address=?",new String[]{address,other});
                db.delete("addresses","address=?",new String[]{other});
            }
            boolean moving=was!=null&&!was.equals(address);
            if(moving) {
                // Anything already sitting under the new address is a stub of the same device.
                db.delete("addresses","address=?",new String[]{address});
                // What points at it comes along: otherwise a re-scan silently unshares everything.
                db.execSQL("UPDATE OR REPLACE shares SET address=? WHERE address=?",new String[]{address,was});
                db.execSQL("UPDATE OR REPLACE sent SET address=? WHERE address=?",new String[]{address,was});
                db.execSQL("UPDATE OR REPLACE handed SET address=? WHERE address=?",new String[]{address,was});
                db.execSQL("UPDATE OR REPLACE refused SET address=? WHERE address=?",new String[]{address,was});
            }
            Contact already=was!=null?address(was):address(address);
            ContentValues v=new ContentValues();
            v.put("address",address);v.put("name",name);
            // Whether this is a device of yours was said once, deliberately, and a re-scan is not the
            // moment to quietly change it back.
            v.put("mine",already!=null?(already.mine?1:0):(mine?1:0));
            v.put("added",System.currentTimeMillis());
            v.put("agreement",canonical(agreement));v.put("signing",canonical(signing));
            v.put("contact",already==null?"":already.contact);
            if(db.insertWithOnConflict("addresses",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)
                throw new IllegalStateException("Could not save that device");
            if(moving)db.delete("addresses","address=?",new String[]{was});
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }

    /**
     * One row per device, wherever there is more than one.
     *
     * <p>Done when the app opens, because a duplicate is not something the owner did and not something
     * they should have to tidy. It arises on its own: an address is a snapshot, a device that moved was
     * filed again under the new one, and the two rows then carry the same keys. Whichever answered first
     * decided what an arriving note said it came from - and if that was the older, it said it came from an
     * address nothing can be sent to.
     *
     * <p>The newest row wins, and everything pointing at the others is carried to it.
     */
    void tidyDevices() {
        java.util.Map<String,List<String>> byKey=new java.util.LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().query("addresses",new String[]{"signing","address"},
                "signing<>'' AND TRIM(address)<>''",null,null,null,"added ASC")) {
            while(c.moveToNext())
                byKey.computeIfAbsent(canonical(c.getString(0)),any->new ArrayList<>()).add(c.getString(1));
        }
        SQLiteDatabase db=getWritableDatabase();
        for(List<String> same:byKey.values()) {
            if(same.size()<2)continue;
            String keep=same.get(same.size()-1);
            db.beginTransaction();
            try {
                // And the survivor's keys are rewritten in the one form, so the next opening has nothing
                // left to do.
                ContentValues one=new ContentValues();
                try(Cursor c=getReadableDatabase().query("addresses",new String[]{"agreement","signing"},
                        "address=?",new String[]{keep},null,null,null,"1")) {
                    if(c.moveToFirst()) {
                        one.put("agreement",canonical(c.getString(0)));
                        one.put("signing",canonical(c.getString(1)));
                        db.update("addresses",one,"address=?",new String[]{keep});
                    }
                }
                for(String other:same) {
                    if(other.equals(keep))continue;
                    db.execSQL("UPDATE OR REPLACE shares SET address=? WHERE address=?",new String[]{keep,other});
                    db.execSQL("UPDATE OR REPLACE sent SET address=? WHERE address=?",new String[]{keep,other});
                    db.execSQL("UPDATE OR REPLACE handed SET address=? WHERE address=?",new String[]{keep,other});
                    db.execSQL("UPDATE OR REPLACE refused SET address=? WHERE address=?",new String[]{keep,other});
                    db.execSQL("UPDATE OR REPLACE accepting SET address=? WHERE address=?",new String[]{keep,other});
                    db.execSQL("UPDATE notes SET origin=? WHERE origin=?",new String[]{keep,other});
                    db.execSQL("UPDATE collections SET origin=? WHERE origin=?",new String[]{keep,other});
                    db.execSQL("UPDATE books SET origin=? WHERE origin=?",new String[]{keep,other});
                    db.delete("addresses","address=?",new String[]{other});
                }
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
        }
    }

    /**
     * A shelf filed under an old name, taken over under the new one.
     *
     * <p>Only where there is nothing under the new name yet: if both exist, the one already in use stays
     * and the other is left for {@link #tidyShelves} to clear away if it is empty.
     */
    private void adopt(String table,String was,String now) {
        if(was.isEmpty()||now.isEmpty()||was.equals(now))return;
        if(!there(table,now)||!there(table,was))
            if(there(table,was)&&!there(table,now)) {
                SQLiteDatabase db=getWritableDatabase();
                db.beginTransaction();
                try {
                    ContentValues v=new ContentValues();v.put("id",now);
                    db.update(table,v,"id=?",new String[]{was});
                    if("collections".equals(table))
                        db.execSQL("UPDATE books SET collection=? WHERE collection=?",new String[]{now,was});
                    else db.execSQL("UPDATE notes SET book=? WHERE book=?",new String[]{now,was});
                    db.execSQL("UPDATE OR REPLACE shares SET target=? WHERE target=?",new String[]{now,was});
                    db.setTransactionSuccessful();
                } finally {db.endTransaction();}
            }
    }

    private boolean there(String table,String id) {
        try(Cursor c=getReadableDatabase().query(table,new String[]{"id"},"id=?",new String[]{id},
                null,null,null,"1")) {
            return c.moveToFirst();
        }
    }

    /**
     * Empty copies of a shelf somebody shares, cleared away.
     *
     * <p>They came from the same shelf arriving twice under two names, back when a shelf was named after
     * the address it came from. Only the empty ones go, and only where another shelf from the same device
     * has the same name: nothing that holds anything is ever removed by tidying.
     */
    /**
     * Anything already here that came from somebody, with that somebody written down as having it.
     *
     * <p>For things that arrived before the membership travelled with them. Without it, a collection
     * somebody shared with you lists them under "not shared with", which is the one thing they are not.
     */
    void tidyOrigins() {
        owned("collections",Sharing.Scope.COLLECTION);
        owned("books",Sharing.Scope.BOOK);
        owned("notes",Sharing.Scope.PAGE);
    }

    private void owned(String table,Sharing.Scope scope) {
        java.util.List<String[]> theirs=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery(
                "SELECT id,origin FROM "+table+" WHERE theirs=1 AND TRIM(origin)<>''",null)) {
            while(c.moveToNext())theirs.add(new String[]{c.getString(0),c.getString(1)});
        }
        for(String[] one:theirs) {
            boolean known=false;
            for(Sharing.Rule rule:membership(scope,one[0]))
                if(one[1].equals(rule.address))known=true;
            if(known||one[1].trim().isEmpty())continue;
            Contact who=address(one[1]);
            // Where it came from is where they were that day. A device's address moves, and one that is
            // nobody's any more is not somebody to write down as having a thing: this wrote them down
            // again at every opening, a share with nobody that nothing could be sealed for - so the pad
            // said "waiting to go" for ever and every Sync of everything ended in "somebody has not been
            // paired yet". The next thing to arrive from them says where they are now; see landed.
            if(who==null)continue;
            ContentValues v=new ContentValues();
            v.put("scope",scope.name());v.put("target",one[0]);v.put("address",one[1]);
            v.put("level",Sharing.Level.ADMIN.said());v.put("mine",1);
            v.put("changed",1L);v.put("added",System.currentTimeMillis());
            v.put("who",who==null?"":canonical(who.signing));v.put("name",who==null?"":who.name);
            getWritableDatabase().insertWithOnConflict("shares",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    /**
     * Rows that can never do anything, cleared away.
     *
     * <p>A share with no address to send to is not a share: nothing can be sealed for nobody, and the
     * attempt is reported as "somebody has not been paired yet" — which sends the reader looking for a
     * pairing problem that is not there. A device with no keys is the same thing from the other end.
     * Both were written by this app and neither is anybody's decision to keep.
     */
    void tidyBroken() {
        SQLiteDatabase db=getWritableDatabase();
        db.delete("shares","TRIM(address)=''",null);
        db.delete("addresses","TRIM(address)='' OR signing='' OR agreement=''",null);
        // And a line nobody decided - worked out from where something once came from - that points at an
        // address no device here is at any more. One somebody did decide is left: that they are not
        // paired yet is then the truth, and worth being told.
        db.delete("shares","changed<=1 AND address NOT IN (SELECT address FROM addresses)",null);
    }

    void tidyShelves() {
        SQLiteDatabase db=getWritableDatabase();
        // A shelf of ours that came back to us as somebody else's. It happens when you share something
        // with a phone that shares it on, or back: what arrives describes their shelf, and ours is where
        // the notes already are - so theirs stays empty beside it with the same name on it. Only the empty
        // ones go, and a note arriving for one would build it again.
        db.execSQL("DELETE FROM books WHERE theirs=1 AND id NOT IN (SELECT book FROM notes)"
            +" AND name IN (SELECT name FROM books WHERE theirs=0)");
        db.execSQL("DELETE FROM collections WHERE theirs=1 AND id NOT IN (SELECT collection FROM books)"
            +" AND name IN (SELECT name FROM collections WHERE theirs=0)");
        clearEmpty(db,"books","SELECT b.id,b.name,b.origin FROM books b WHERE b.theirs=1",
            "SELECT COUNT(*) FROM notes WHERE book=?");
        clearEmpty(db,"collections","SELECT c.id,c.name,c.origin FROM collections c WHERE c.theirs=1",
            "SELECT COUNT(*) FROM books WHERE collection=?");
    }

    private void clearEmpty(SQLiteDatabase db,String table,String list,String counting) {
        java.util.Map<String,List<String>> alike=new java.util.LinkedHashMap<>();
        try(Cursor c=db.rawQuery(list,null)) {
            while(c.moveToNext())
                alike.computeIfAbsent(c.getString(2)+"\u0000"+c.getString(1),any->new ArrayList<>())
                    .add(c.getString(0));
        }
        for(List<String> same:alike.values()) {
            if(same.size()<2)continue;
            for(String one:same) {
                long holds;
                try(Cursor c=db.rawQuery(counting,new String[]{one})){holds=c.moveToFirst()?c.getLong(0):1;}
                if(holds>0)continue;
                // Leave at least one, even if every copy is empty.
                if(same.size()-1<1)break;
                db.delete(table,"id=?",new String[]{one});
                db.delete("shares","target=?",new String[]{one});
                same.remove(one);
                break;
            }
        }
    }

    /** Every address this one device is filed under, which should be one and sometimes is not. */
    private List<String> everyAddressOf(byte[] signing) {
        List<String> all=new ArrayList<>();
        String key=canonical(signing);
        if(key.isEmpty())return all;
        try(Cursor c=getReadableDatabase().query("addresses",new String[]{"address","signing"},
                "signing<>''",null,null,null,null)) {
            while(c.moveToNext())if(key.equals(canonical(c.getString(1))))all.add(c.getString(0));
        }
        return all;
    }

    /** One device by the key it signs with, whatever address it is filed under. */
    private Contact byKey(String key) {
        if(key==null||key.trim().isEmpty())return null;
        for(Contact one:addresses())if(key.equals(canonical(one.signing)))return one;
        return null;
    }

    /** The address a device is filed under now, found by the key it signs with, or null for a new one. */
    private String deviceKeyed(byte[] signing) {
        String key=canonical(signing);
        if(key.isEmpty())return null;
        // Compared in the one form, not as it happens to be written: rows put there by an older build
        // hold the long shape and a code read today carries the short one.
        try(Cursor c=getReadableDatabase().query("addresses",new String[]{"address","signing"},
                "signing<>''",null,null,null,"added ASC")) {
            while(c.moveToNext())if(key.equals(canonical(c.getString(1))))return c.getString(0);
        }
        return null;
    }

    /** What Maxima came to know this address as, so a message can be handed to it by name. */
    void knownAs(String address,String contact) {
        ContentValues v=new ContentValues();v.put("contact",contact);
        getWritableDatabase().update("addresses",v,"address=?",new String[]{address});
    }
    void addAddress(String address,String name,boolean mine) {
        if(address==null||address.trim().isEmpty())
            throw new IllegalArgumentException("That code carried no address to send to.");
        ContentValues v=new ContentValues();v.put("address",address);v.put("name",name);
        v.put("mine",mine?1:0);v.put("added",System.currentTimeMillis());
        if(getWritableDatabase().insertWithOnConflict("addresses",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)
            throw new IllegalStateException("Could not save the address");
    }
    void removeAddress(String address){getWritableDatabase().delete("addresses","address=?",new String[]{address});}

    /**
     * Whether an address is another device of yours. It decides which way things travel — both ways with
     * your own devices, one way to anybody else — and it is the kind of thing you set once by looking at a
     * list, not by answering a question in the middle of adding something.
     */
    void setMine(String address,boolean mine) {
        ContentValues v=new ContentValues();v.put("mine",mine?1:0);
        // Only the address is changed. What each share lets this address do is its own setting, decided
        // where the sharing is decided: the same person may read one book of yours and work in another.
        getWritableDatabase().update("addresses",v,"address=?",new String[]{address});
    }

    private static int shared(List<Sharing.Rule> rules,Sharing.Scope scope,String target) {
        int count=0;
        for(Sharing.Rule rule:rules)if(rule.scope==scope&&rule.target.equals(target))count++;
        return count;
    }

    /** Every sharing rule in the pad. Small enough to read whole, and the audience of a page is decided in memory. */
    List<Sharing.Rule> shares() {
        List<Sharing.Rule> rules=new ArrayList<>();
        // Somebody taken off keeps a row, at GONE, so that an older copy of the list cannot put them back.
        // A membership without that is a membership where removing somebody is undone by the next message.
        try(Cursor c=getReadableDatabase().query("shares",null,"level>0",null,null,null,"added ASC")) {
            while(c.moveToNext())rules.add(rule(c));
        }
        return rules;
    }

    /** Every row, including the people who were taken off: what travels, and what merges. */
    List<Sharing.Rule> membership(Sharing.Scope scope,String target) {
        List<Sharing.Rule> all=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("shares",null,"scope=? AND target=?",
                new String[]{scope.name(),target},null,null,"added ASC")) {
            while(c.moveToNext())all.add(rule(c));
        }
        return all;
    }

    private static Sharing.Rule rule(Cursor c) {
        return new Sharing.Rule(Sharing.Scope.valueOf(c.getString(c.getColumnIndexOrThrow("scope"))),
            c.getString(c.getColumnIndexOrThrow("target")),c.getString(c.getColumnIndexOrThrow("address")),
            Sharing.Level.of(c.getInt(c.getColumnIndexOrThrow("level"))),
            c.getLong(c.getColumnIndexOrThrow("changed")),
            c.getString(c.getColumnIndexOrThrow("who")));
    }

    /**
     * One person's standing in one thing, written down with when it was decided.
     *
     * <p>Taking somebody off is a decision like any other, so it is written down as one rather than by
     * deleting the row: a list that forgets a removal is a list where the removal comes undone the next
     * time an older copy of it arrives.
     */
    void setLevel(Sharing.Scope scope,String target,String address,Sharing.Level level,String name) {
        setLevel(scope,target,address,level,name,System.currentTimeMillis());
    }

    /** @param changed when it was decided, where that was somewhere else and some time ago */
    void setLevel(Sharing.Scope scope,String target,String address,Sharing.Level level,String name,long changed) {
        NoteStore.Contact who=address(address);
        ContentValues v=new ContentValues();
        v.put("scope",scope.name());v.put("target",target);v.put("address",address);
        v.put("level",level.said());v.put("mine",level.writes()?1:0);
        v.put("changed",changed);v.put("added",System.currentTimeMillis());
        v.put("who",who==null?"":canonical(who.signing));
        v.put("name",name==null?(who==null?"":who.name):name);
        if(getWritableDatabase().insertWithOnConflict("shares",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)
            throw new IllegalStateException("Could not save who this is shared with");
    }

    /**
     * A membership that arrived, folded into the one here.
     *
     * <p>Per person, the later decision stands. Not the later message: two admins out of touch with each
     * other both have a say, and the only thing they can agree on afterwards without asking anybody is
     * which of them decided last.
     *
     * @return how many entries this phone did not already have as new or newer
     */
    int mergeMembership(Sharing.Scope scope,String target,List<Sharing.Rule> theirs) {
        if(theirs==null||theirs.isEmpty())return 0;
        java.util.Map<String,Sharing.Rule> mine=new java.util.HashMap<>();
        for(Sharing.Rule one:membership(scope,target))
            mine.put(one.key.isEmpty()?one.address:one.key,one);
        int changed=0;
        SQLiteDatabase db=getWritableDatabase();
        db.beginTransaction();
        try {
            for(Sharing.Rule one:theirs) {
                String by=one.key.isEmpty()?one.address:one.key;
                Sharing.Rule here=mine.get(by);
                if(here!=null&&here.changed>=one.changed)continue;
                String where=here!=null&&one.address.trim().isEmpty()?here.address:one.address;
                if(where.trim().isEmpty())continue;
                ContentValues v=new ContentValues();
                v.put("scope",scope.name());v.put("target",target);
                v.put("address",where);
                v.put("level",one.level.said());v.put("mine",one.level.writes()?1:0);
                v.put("changed",one.changed);v.put("added",System.currentTimeMillis());
                v.put("who",one.key);
                db.insertWithOnConflict("shares",null,v,SQLiteDatabase.CONFLICT_REPLACE);
                if(here!=null&&!here.address.equals(one.address))
                    db.delete("shares","scope=? AND target=? AND address=?",
                        new String[]{scope.name(),target,here.address});
                changed++;
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        return changed;
    }

    /**
     * Which level a note is actually shared at — its own, the book's, or the collection's.
     *
     * <p>The membership belongs to whichever of those carries the rule, because that is the thing people
     * were given. Sharing a book and then listing its members note by note would be a different list on
     * every page of it.
     */
    Sharing.Scope sharedAt(String collection,String book,String page) {
        for(Sharing.Rule rule:shares()) {
            if(rule.scope==Sharing.Scope.PAGE&&rule.target.equals(page))return Sharing.Scope.PAGE;
        }
        for(Sharing.Rule rule:shares()) {
            if(rule.scope==Sharing.Scope.BOOK&&rule.target.equals(book))return Sharing.Scope.BOOK;
        }
        for(Sharing.Rule rule:shares()) {
            if(rule.scope==Sharing.Scope.COLLECTION&&rule.target.equals(collection))
                return Sharing.Scope.COLLECTION;
        }
        return null;
    }

    /** The membership of one thing, as it travels: every device, by key, with when it was decided. */
    List<Parcel.Member> travelling(Sharing.Scope scope,String target) {
        List<Parcel.Member> going=new ArrayList<>();
        if(scope==null)return going;
        for(Sharing.Rule rule:membership(scope,target)) {
            Contact who=address(rule.address);
            going.add(new Parcel.Member(rule.key.isEmpty()&&who!=null?canonical(who.signing):rule.key,
                rule.address,who==null?"":who.name,rule.level.said(),rule.changed));
        }
        // And this phone, which is a member of anything it shares and never appears in its own list.
        going.add(new Parcel.Member(mySigningKey,myAddress,myName,Sharing.Level.ADMIN.said(),1L));
        return going;
    }

    /**
     * What this phone calls itself and signs with, so it can name itself in a membership.
     *
     * <p>Set once when the app starts. The notebook has no business fetching keys; it is told.
     */
    String mySigningKey="", myName="", myAddress="";

    /**
     * A membership that arrived with a note, folded into the one here.
     *
     * <p>Filed under this phone's own name for the thing, which is not the name the sender used: a shelf
     * of theirs is kept under an id made from who they are and what they call it. See {@link Parcel}.
     */
    void tookMembership(String from,String note,Parcel.Sent parcel) {
        if(parcel==null)return;
        Sharing.Scope scope=Sharing.Scope.PAGE;
        if(!parcel.scope.isEmpty())
            try{scope=Sharing.Scope.valueOf(parcel.scope);}catch(IllegalArgumentException unknown){}
        Contact who=address(from);
        String by=who==null||who.signing.length==0?from:canonical(who.signing);
        // Under the shelf's name here, whoever sent it: see Parcel.shelfHere. A list for the owner's own
        // book, sent back by an admin who had added somebody, was filed under a name for a book that is
        // not on this phone, and the owner never heard about the person.
        String here=scope==Sharing.Scope.COLLECTION?shelfId(from,parcel.collection,true)
                   :scope==Sharing.Scope.BOOK?shelfId(from,parcel.book,false)
                   :(parcel.target.isEmpty()?note:parcel.target);
        if(here.isEmpty())return;

        // Whoever sent it has it. Obvious, and it was not being written down: a thing that arrived from
        // somebody listed them under "not shared with", which is the one thing they demonstrably are not.
        // Written weakly - as of the beginning of time - so that any real word from them outranks it.
        if(!by.isEmpty()&&!from.trim().isEmpty()) {
            boolean known=false;
            for(Sharing.Rule rule:membership(scope,here))
                if(by.equals(rule.key)||from.equals(rule.address))known=true;
            if(!known) {
                ContentValues v=new ContentValues();
                v.put("scope",scope.name());v.put("target",here);v.put("address",from);
                v.put("level",Sharing.Level.ADMIN.said());v.put("mine",1);
                v.put("changed",1L);v.put("added",System.currentTimeMillis());
                v.put("who",by);v.put("name",who==null?"":who.name);
                getWritableDatabase().insertWithOnConflict("shares",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            }
        }
        if(parcel.members.isEmpty())return;
        List<Sharing.Rule> theirs=new ArrayList<>();
        for(Parcel.Member one:parcel.members) {
            if(!one.key.isEmpty()&&one.key.equals(mySigningKey)) {
                // Ourselves, from their side. Never a share - that would be this phone sending to itself -
                // but it is the only place this phone is told what it may do, so it is kept where it can.
                // Only for a thing of theirs: what somebody says this phone may do with its own is nothing.
                Note mine=get(note);
                if(mine==null||mine.theirs)stands(scope,here,Sharing.Level.of(one.level),one.changed);
                continue;
            }
            String where=one.address;
            // The sender's own entry carries no address in lists written before this: a phone does not
            // know the address others reach it at. It is the phone this arrived from, so it is that one.
            if(where.trim().isEmpty()&&!one.key.isEmpty()&&one.key.equals(by))where=from;
            if(where.trim().isEmpty()) {
                Contact known=byKey(one.key);
                if(known!=null)where=known.address;
            }
            // Somebody we have no way to reach and no way to name is not something to write down. They
            // will be in the next list that arrives, with an address on it.
            if(where.trim().isEmpty())continue;
            theirs.add(new Sharing.Rule(scope,here,where,Sharing.Level.of(one.level),one.changed,one.key));
        }
        mergeMembership(scope,here,theirs);
    }

    /** What this phone was told it may do with one thing. The later decision stands, as for anybody. */
    void stands(Sharing.Scope scope,String target,Sharing.Level level,long changed) {
        if(scope==null||target==null||target.isEmpty()||level==null)return;
        SQLiteDatabase db=getWritableDatabase();
        try(Cursor c=db.query("standing",new String[]{"changed"},"scope=? AND target=?",
                new String[]{scope.name(),target},null,null,null,"1")) {
            if(c.moveToFirst()&&c.getLong(0)>changed)return;
        }
        ContentValues v=new ContentValues();
        v.put("scope",scope.name());v.put("target",target);v.put("level",level.said());v.put("changed",changed);
        db.insertWithOnConflict("standing",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * What this phone may do with something another device shares with it, or null where nothing says.
     *
     * <p>On the thing itself, or on whatever holds it: somebody made an admin of a book is an admin of
     * every note in it. The most that any of them says.
     */
    Sharing.Level myLevel(Sharing.Scope scope,String target) {
        List<String[]> where=new ArrayList<>();
        where.add(new String[]{scope.name(),target});
        if(scope==Sharing.Scope.PAGE) {
            String book=bookOf(target);
            where.add(new String[]{Sharing.Scope.BOOK.name(),book});
            where.add(new String[]{Sharing.Scope.COLLECTION.name(),collectionOfBook(book)});
        } else if(scope==Sharing.Scope.BOOK)
            where.add(new String[]{Sharing.Scope.COLLECTION.name(),collectionOfBook(target)});
        Sharing.Level most=null;
        for(String[] one:where) {
            if(one[1]==null||one[1].isEmpty())continue;
            try(Cursor c=getReadableDatabase().query("standing",new String[]{"level"},"scope=? AND target=?",
                    one,null,null,null,"1")) {
                if(!c.moveToFirst())continue;
                Sharing.Level said=Sharing.Level.of(c.getInt(0));
                if(most==null||said.ordinal()>most.ordinal())most=said;
            }
        }
        if(most!=null)return most;
        // Told nothing: a note at least says whether this phone may write in it.
        Boolean writes=mayWriteIn(scope==Sharing.Scope.PAGE?Branch.Kind.PAGE
            :scope==Sharing.Scope.BOOK?Branch.Kind.BOOK:Branch.Kind.COLLECTION,target);
        return writes==null?null:writes?Sharing.Level.WRITE:Sharing.Level.READ;
    }

    /**
     * Everybody this thing already reaches, by any rule at any level — its own, or one on the book or the
     * collection above it. Who it reaches is one question; which rule does it is another, and the sheet
     * needs both to say what can be undone here and what has to be undone where it was given.
     */
    Map<String,Boolean> reaches(Sharing.Scope scope,String target) {
        List<Sharing.Rule> rules=shares();
        switch(scope) {
            case PAGE: {
                String book=parentOf("notes","book",target);
                return Sharing.audience(rules,book==null?"":collectionOf(book),book==null?"":book,target);
            }
            case BOOK: return Sharing.audience(rules,collectionOf(target),target,"");
            case COLLECTION: return Sharing.audience(rules,target,"","");
            default: return Sharing.audience(rules,"","","");
        }
    }

    /**
     * What gives this address what it has, when the rule is not on this thing itself.
     *
     * <p>"Through what holds this" is true and tells you nothing: the question anybody asks next is
     * <i>through what?</i> - and the answer decides where to go to change it. So it is named.
     *
     * @return the thing, named, or empty when the rule is on this thing or there is none
     */
    String grantedBy(Sharing.Scope scope,String target,String address) {
        String collection="", book="", page="";
        switch(scope) {
            case PAGE: {
                String on=parentOf("notes","book",target);
                book=on==null?"":on; collection=book.isEmpty()?"":collectionOf(book); page=target; break;
            }
            case BOOK: book=target; collection=collectionOf(target); break;
            case COLLECTION: collection=target; break;
            default: break;
        }
        for(Sharing.Rule rule:shares()) {
            if(!rule.address.equals(address))continue;
            if(rule.scope==scope&&rule.target.equals(target))continue;
            if(!Sharing.covers(rule,collection,book,page))continue;
            switch(rule.scope) {
                case LIBRARY: return "everything on this phone";
                case COLLECTION: return nameOf(rule.target,true)+" collection";
                case BOOK: return nameOf(rule.target,false)+" book";
                default: break;
            }
        }
        return "";
    }

    List<Sharing.Rule> sharesOn(Sharing.Scope scope,String target) {
        List<Sharing.Rule> here=new ArrayList<>();
        for(Sharing.Rule rule:shares())if(rule.scope==scope&&rule.target.equals(target))here.add(rule);
        return here;
    }

    void addShare(Sharing.Rule rule) {
        ContentValues v=new ContentValues();
        v.put("scope",rule.scope.name());v.put("target",rule.target);v.put("address",rule.address);
        v.put("mine",rule.mine?1:0);v.put("added",System.currentTimeMillis());
        if(getWritableDatabase().insertWithOnConflict("shares",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)
            throw new IllegalStateException("Could not save who this is shared with");
    }

    void removeShare(Sharing.Rule rule) {
        getWritableDatabase().delete("shares","scope=? AND target=? AND address=?",
            new String[]{rule.scope.name(),rule.target,rule.address});
    }

    // ---- backups -----------------------------------------------------------------------------------------

    /**
     * Everything, as one piece of text. Files are listed here by name, kind and size; the bytes travel
     * beside this in the backup itself, which is why a backup is now a zip rather than one file of text.
     */
    String backup() throws JSONException {
        JSONArray a=new JSONArray();
        try(Cursor c=getReadableDatabase().query("notes",null,null,null,null,null,"updated DESC")) {
            while(c.moveToNext()) {
                Note note=read(c,true);
                JSONObject written=note.json();
                JSONArray files=new JSONArray();
                for(Held file:filesOf(Branch.Kind.PAGE,note.id))
                    files.put(new JSONObject().put("id",file.id).put("name",file.name).put("kind",file.kind)
                        .put("bytes",file.bytes).put("added",file.added));
                if(files.length()>0)written.put("files",files);
                a.put(written);
            }
        }
        // The shelves travel with what is on them. A backup of notes alone restores onto a phone that has
        // nowhere to put them: the books they name would not exist, and the notes would be nowhere.
        JSONArray shelves=new JSONArray(), volumes=new JSONArray();
        try(Cursor c=getReadableDatabase().query("collections",null,null,null,null,null,"place ASC, updated DESC")) {
            while(c.moveToNext())shelves.put(shelfJson(c,null));
        }
        try(Cursor c=getReadableDatabase().query("books",null,null,null,null,null,"place ASC, updated DESC")) {
            while(c.moveToNext())volumes.put(shelfJson(c,"collection"));
        }
        return new JSONObject().put("app","mininotes.v1").put("version",1)
            .put("collections",shelves).put("books",volumes).put("notes",a).toString(2);
    }

    private static JSONObject shelfJson(Cursor c,String parent) throws JSONException {
        JSONObject written=new JSONObject()
            .put("id",c.getString(c.getColumnIndexOrThrow("id")))
            .put("name",c.getString(c.getColumnIndexOrThrow("name")))
            .put("updated",c.getLong(c.getColumnIndexOrThrow("updated")))
            .put("place",c.getLong(c.getColumnIndexOrThrow("place")))
            .put("colour",c.getInt(c.getColumnIndexOrThrow("colour")))
            .put("archived",c.getInt(c.getColumnIndexOrThrow("archived"))==1)
            .put("deleted",c.getInt(c.getColumnIndexOrThrow("deleted"))==1);
        if(parent!=null)written.put(parent,c.getString(c.getColumnIndexOrThrow(parent)));
        return written;
    }

    /** Every file the backup should carry, so the writer knows what to put beside the text. */
    List<Held> everyFile() {
        List<Held> all=new ArrayList<>();
        try(Cursor c=getReadableDatabase().query("files",FILE_ROW,null,null,null,null,"added ASC")) {
            while(c.moveToNext())all.add(held(c));
        }
        return all;
    }

    /**
     * A backup read back in, either way a person might mean it.
     *
     * <p><b>Adding</b> keeps what is here and brings the backup's notes in beside it, as copies under ids of
     * their own — the same backup can be added twice and the first copy keeps its own files. Shelves are
     * reused where they already exist and made where they do not, so notes never land nowhere.
     *
     * <p><b>Replacing</b> is a restore: everything here goes and the pad becomes what the backup was, ids
     * included, so the sharing rules that pointed at those things still point at them. It happens in one
     * transaction — a restore that failed half way would be worse than the one it replaced.
     */
    int importBackup(String input,boolean replacing) throws JSONException {
        JSONObject root=new JSONObject(input);
        if(!root.getString("app").equals("mininotes.v1")||root.getInt("version")!=1)throw new JSONException("Unsupported backup");
        JSONArray a=root.getJSONArray("notes");if(a.length()>1000)throw new JSONException("Maximum 1,000 notes per import");
        JSONArray shelves=root.optJSONArray("collections"), volumes=root.optJSONArray("books");
        List<Note> incoming=new ArrayList<>();
        List<Held> arriving=new ArrayList<>();
        for(int i=0;i<a.length();i++) {JSONObject o=a.getJSONObject(i);Note n=new Note();
            // A restore is the pad as it was, ids included; an addition is a copy, which needs its own.
            if(replacing&&!o.optString("id").isEmpty())n.id=o.getString("id");
            n.title=o.getString("title");n.body=o.getString("body");n.notebook=o.getString("tag");
            // A backup written before books existed lands in the first book rather than nowhere.
            n.book=o.optString("book",SchemaMigrations.FIRST_BOOK);
            n.pinned=o.getBoolean("pinned");n.deleted=o.getBoolean("deleted");n.updated=o.getLong("updated");
            // A backup written before pages could be dragged keeps its old order: newest first.
            n.place=o.optLong("place",-n.updated);
            n.archived=o.optBoolean("archived",false);
            n.colour=o.optInt("colour",Tint.NONE);
            n.revision=Math.max(0,o.optLong("revision",0));
            if(n.title.length()>160||n.body.length()>24000||n.notebook.length()>40||n.updated<0)throw new JSONException("Invalid note limits");
            incoming.add(n);
            // A file is only a file of the note if its bytes came with the backup: a row pointing at
            // nothing would be a name you can tap and never open.
            JSONArray kept=o.optJSONArray("files");
            for(int f=0;kept!=null&&f<kept.length();f++) {
                JSONObject file=kept.getJSONObject(f);
                String came=Attachment.idOf(Attachment.entry(file.optString("id")));
                if(came==null||!landingFor(came).isFile())continue;
                String id=replacing?came:UUID.randomUUID().toString();
                Held own=new Held(id,n.id,Attachment.named(file.optString("name")),
                    Attachment.kind(file.optString("kind")),landingFor(came).length(),file.optLong("added",n.updated));
                if(landingFor(came).renameTo(fileFor(own.id)))arriving.add(own);
            }
        }
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if(replacing) {
                db.delete("files",null,null);
                db.delete("notes",null,null);
                db.delete("books",null,null);
                db.delete("collections",null,null);
            }
            for(int i=0;shelves!=null&&i<shelves.length();i++)shelf(db,"collections",shelves.getJSONObject(i),null,replacing);
            for(int i=0;volumes!=null&&i<volumes.length();i++)shelf(db,"books",volumes.getJSONObject(i),"collection",replacing);
            // A backup from before the shelves travelled, restored onto a pad with none, still needs one.
            if(replacing&&(shelves==null||shelves.length()==0)) {
                db.execSQL(SchemaMigrations.firstCollection());
                db.execSQL(SchemaMigrations.firstBook());
            }
            for(Note n:incoming)save(db,n);
            for(Held file:arriving)keep(db,file);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        sweep();
        return incoming.size();
    }

    /**
     * One collection or book from a backup. Replacing writes it as it was; adding leaves a shelf that is
     * already here alone — the notes are the copies, not the shelves they sit on.
     */
    private void shelf(SQLiteDatabase db,String table,JSONObject said,String parent,boolean replacing) throws JSONException {
        String id=said.optString("id");
        if(id.isEmpty())return;
        if(!replacing) {
            try(Cursor c=db.query(table,new String[]{"id"},"id=?",new String[]{id},null,null,null,"1")) {
                if(c.moveToFirst())return;
            }
        }
        String name=said.optString("name","Untitled");
        if(name.length()>80)name=name.substring(0,80);
        long updated=Math.max(0,said.optLong("updated",System.currentTimeMillis()));
        ContentValues v=new ContentValues();
        v.put("id",id);v.put("name",name);v.put("updated",updated);
        v.put("place",said.optLong("place",-updated));
        v.put("colour",said.optInt("colour",Tint.NONE));
        v.put("archived",said.optBoolean("archived",false)?1:0);
        v.put("deleted",said.optBoolean("deleted",false)?1:0);
        if(parent!=null)v.put(parent,said.optString(parent,SchemaMigrations.FIRST_COLLECTION));
        if(db.insertWithOnConflict(table,null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)
            throw new IllegalStateException("Could not restore a shelf");
    }
}
