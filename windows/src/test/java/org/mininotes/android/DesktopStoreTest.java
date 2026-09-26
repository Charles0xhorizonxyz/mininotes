package org.mininotes.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.mininotes.desktop.platform.content.Context;
import org.mininotes.desktop.platform.database.Cursor;

public class DesktopStoreTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private NoteStore store;
    private Context context;
    @Before public void open() throws Exception {context=new Context(temp.newFolder("pad").toPath().toFile());store=new NoteStore(context);store.getWritableDatabase();}
    @After public void close(){store.close();}
    private NoteStore.Note note(String body){NoteStore.Note n=new NoteStore.Note();n.book=store.someBook();n.body=body;store.save(n);return n;}

    @Test public void createsCurrentSchemaAndReopensUnicodeWriting() {
        NoteStore.Note n=note("A shopping list\nÇay, café, 日本語 ✨");
        n=DesktopEdits.save(store,n,"Saturday",n.body+"\nBread");store.close();store=new NoteStore(context);
        assertEquals(n.body,store.get(n.id).body);assertEquals(1,store.get(n.id).revision);
        try(Cursor row=store.getReadableDatabase().rawQuery("PRAGMA user_version",null)){assertTrue(row.moveToFirst());assertEquals(SchemaMigrations.VERSION,row.getInt(0));}
    }
    @Test public void overlappingSavesKeepBothAuthorsAndVersion() {
        NoteStore.Note base=note("one\ntwo\nthree");
        DesktopEdits.save(store,base,"","ONE\ntwo\nthree");
        NoteStore.Note combined=DesktopEdits.save(store,base,"","one\ntwo\nTHREE");
        assertEquals("ONE\ntwo\nTHREE",combined.body);assertEquals(2,combined.revision);assertFalse(store.versions(base.id).isEmpty());
    }
    @Test public void rollbackAndNestedFailureLeaveNoPartialWriting() {
        NoteStore.Note n=note("before");var db=store.getWritableDatabase();db.beginTransaction();
        try {n.body="outer";store.save(n);db.beginTransaction();try{n.body="inner";store.save(n);}finally{db.endTransaction();}db.setTransactionSuccessful();}
        finally{db.endTransaction();}
        assertEquals("before",store.get(n.id).body);
    }
    @Test public void refusesNewerDatabaseWithoutRewritingVersion() {
        store.getWritableDatabase().execSQL("PRAGMA user_version=999");store.close();
        store=new NoteStore(context);assertThrows(IllegalStateException.class,()->store.getWritableDatabase());
        try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+new java.io.File(context.getFilesDir(),"mininotes.db"));var q=c.createStatement();var r=q.executeQuery("PRAGMA user_version")){assertEquals(999,r.getInt(1));}
        catch(Exception e){throw new AssertionError(e);}
    }
    @Test public void previewCannotTruncateOriginal() {
        NoteStore.Note n=note("words ".repeat(200));NoteStore.Note partial=store.pages(n.book).get(0);
        assertThrows(IllegalStateException.class,()->store.save(partial));assertEquals(n.body,store.get(n.id).body);
    }
    @Test public void backupsRoundTripShelvesAndAttachmentAndAddCopies() throws Exception {
        var shelf=store.addCollection("Trips");var book=store.addBook(shelf.id,"Istanbul");NoteStore.Note n=note("Ferry times");n.book=book.id;store.save(n);
        String id=UUID.randomUUID().toString();Files.writeString(store.fileFor(id).toPath(),"test attachment");
        store.keep(new NoteStore.Held(id,n.id,"ticket.txt","text/plain",15,1234));
        Path zip=temp.getRoot().toPath().resolve("backup.zip");DesktopBackup.write(store,zip);
        assertEquals(1,DesktopBackup.add(store,zip));assertEquals(2,store.pages(book.id).size());assertEquals(2,store.everyFile().size());
        for(var file:store.everyFile())assertEquals("test attachment",Files.readString(store.fileFor(file.id).toPath()));
    }
    @Test public void damagedBackupIsAtomicAndZipPathsAreRefused() throws Exception {
        NoteStore.Note n=note("keep me");Path zip=temp.getRoot().toPath().resolve("bad.zip");
        try(var out=new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))){out.putNextEntry(new java.util.zip.ZipEntry("../outside"));out.write(1);}
        assertThrows(java.io.IOException.class,()->DesktopBackup.add(store,zip));assertEquals("keep me",store.get(n.id).body);
        assertFalse(Files.exists(temp.getRoot().toPath().resolve("outside")));
    }
    @Test public void keysAndNodePreferencesPersistProtected() throws Exception {
        Keys first=new Keys(context);byte[] pub=first.signing().getPublic().getEncoded();
        assertArrayEquals(pub,new Keys(context).signing().getPublic().getEncoded());
        var preferences=context.getSharedPreferences("node",0);preferences.edit().putString("seed","synthetic-test-seed").apply();
        byte[] onDisk=Files.readAllBytes(context.getFilesDir().toPath().resolve("node.protected"));
        assertFalse(new String(onDisk,java.nio.charset.StandardCharsets.ISO_8859_1).contains("synthetic-test-seed"));
        assertEquals("synthetic-test-seed",new Context(context.getFilesDir()).getSharedPreferences("node",0).getString("seed",""));
    }
    @Test public void envelopesLandFromPairedWriterAndReadOnlyCannotEdit() throws Exception {
        var sender=Envelope.keys();var agreement=Envelope.keys();String address="MxFixture@127.0.0.1:9001";
        store.pairedWith(address,"Test phone",false,agreement.getPublic().getEncoded(),sender.getPublic().getEncoded());
        Keys receiver=new Keys(context);store.mySigningKey=Base64.getEncoder().encodeToString(receiver.signing().getPublic().getEncoded());
        String id=UUID.randomUUID().toString();
        store.addShare(new Sharing.Rule(Sharing.Scope.PAGE,id,address,true));
        byte[] plain=Parcel.wrap(new Parcel.Sent("c","From phone","b","Shared notes","A note","From Android's wire format",false));
        UUID uuid=UUID.fromString(id);byte[] page=java.nio.ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        byte[] sealed=Envelope.seal(page,1,12345,plain,sender,receiver.agreement().getPublic());
        Post.Landed arrived=Post.arrived(context,store,receiver,sealed);assertEquals(id,arrived.note);assertEquals("From Android's wire format",store.get(id).body);
        assertTrue((Boolean)store.readOnlyHere(id)[0]);
        assertThrows(IllegalStateException.class,()->DesktopEdits.save(store,store.get(id),"","unauthorized edit"));
        assertEquals("From Android's wire format",store.get(id).body);
        // A copy that is only read is owed to nobody, not even another member it reaches.
        store.addShare(new Sharing.Rule(Sharing.Scope.PAGE,id,"MxOther@127.0.0.1:9002",true));
        assertTrue(store.owed(NoteStore.Branch.Kind.PAGE,id).isEmpty());
        assertTrue(store.onlyReads(id));
    }
    @Test public void aLockedNotebookOpensOnlyWithItsKeyAndCanBeUnlockedAgain() throws Exception {
        NoteStore.Note note=new NoteStore.Note();note.book=store.someBook();note.body="Synthetic secret line";store.save(note);
        Vault.Made made=Vault.make("password1".toCharArray(),20_000);
        store.getWritableDatabase().rekey(made.key);store.close();
        java.nio.file.Path file=context.getFilesDir().toPath().resolve("mininotes.db");
        assertFalse("the note is not readable on disk",new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.ISO_8859_1).contains("Synthetic secret"));
        assertFalse("no plain write-ahead log is left beside it",Files.exists(file.resolveSibling("mininotes.db-wal"))&&Files.size(file.resolveSibling("mininotes.db-wal"))>0);
        try(NoteStore stranger=new NoteStore(new Context(context.getFilesDir()))){assertThrows(IllegalStateException.class,stranger::latest);}
        Context keyed=new Context(context.getFilesDir());keyed.unlock(Vault.open(made.kept,"password1".toCharArray()));
        try(NoteStore reopened=new NoteStore(keyed)){assertEquals("Synthetic secret line",reopened.get(note.id).body);reopened.getWritableDatabase().rekey(null);}
        try(NoteStore plain=new NoteStore(new Context(context.getFilesDir()))){assertEquals("Synthetic secret line",plain.get(note.id).body);}
    }
    @Test public void theLockCoversAttachmentsAndBackups() throws Exception {
        // A note with an attachment, then the lock put on.
        NoteStore.Note note=new NoteStore.Note();note.book=store.someBook();note.body="Synthetic list with a receipt";store.save(note);
        java.nio.file.Path source=temp.newFile("receipt.txt").toPath();Files.writeString(source,"Synthetic receipt contents");
        String fileId=UUID.randomUUID().toString();java.nio.file.Path kept=store.fileFor(fileId).toPath();
        DesktopFiles.keep(context,source,kept);
        store.keep(new NoteStore.Held(fileId,note.id,"receipt.txt","text/plain",Files.size(source),System.currentTimeMillis()));
        Vault.Made made=Vault.make("password1".toCharArray(),20_000);
        store.getWritableDatabase().rekey(made.key);context.unlock(made.key);DesktopFiles.every(store,made.key,true);
        assertFalse(new String(Files.readAllBytes(kept),java.nio.charset.StandardCharsets.ISO_8859_1).contains("Synthetic receipt"));
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();DesktopFiles.copyOut(context,kept,out);
        assertEquals("Synthetic receipt contents",out.toString(java.nio.charset.StandardCharsets.UTF_8));
        // A backup written while locked: sealed whole, nothing readable in it.
        java.nio.file.Path backup=temp.getRoot().toPath().resolve("locked.mnbackup");
        DesktopBackup.write(store,backup,made.key,made.kept);
        assertTrue(DesktopBackup.locked(backup));
        String raw=new String(Files.readAllBytes(backup),java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("Synthetic"));assertFalse(raw.contains("mininotes-backup.json"));
        // Opened into a new, plain notebook with the recovery words: the note and the attachment, as they were.
        Context fresh=new Context(temp.newFolder("restored"));
        try(NoteStore restored=new NoteStore(fresh)) {
            assertThrows(java.io.IOException.class,()->DesktopBackup.add(restored,backup,lock->null));
            assertEquals(1,DesktopBackup.add(restored,backup,lock->Vault.recover(lock,String.join(" ",made.words))));
            NoteStore.Note back=null;for(NoteStore.Held one:restored.everyFile()){java.io.ByteArrayOutputStream b=new java.io.ByteArrayOutputStream();DesktopFiles.copyOut(fresh,restored.fileFor(one.id).toPath(),b);assertEquals("Synthetic receipt contents",b.toString(java.nio.charset.StandardCharsets.UTF_8));}
            assertEquals(1,restored.everyFile().size());
        }
        // A changed backup is refused rather than half imported.
        byte[] bent=Files.readAllBytes(backup);bent[bent.length-40]^=1;java.nio.file.Path damaged=temp.getRoot().toPath().resolve("bent.mnbackup");Files.write(damaged,bent);
        try(NoteStore other=new NoteStore(new Context(temp.newFolder("other")))){assertThrows(java.io.IOException.class,()->DesktopBackup.add(other,damaged,lock->made.key));}
        // Lock off: the attachment is a plain file again.
        DesktopFiles.every(store,made.key,false);store.getWritableDatabase().rekey(null);context.unlock(null);
        assertEquals("Synthetic receipt contents",Files.readString(kept));
    }
    @Test public void plainPairingSaysHelloAndIsAnsweredBack() throws Exception {
        Keys receiver=new Keys(context);var sender=Envelope.keys();var agreement=Envelope.keys();String address="MxPhone@127.0.0.1:9003";
        byte[] hello=Hello.wrap(new Hello.Said("Phone fixture",address,Point.shorten(agreement.getPublic()),Point.shorten(sender.getPublic()),"","",false));
        // From a stranger: handed on, to be put to the owner - not dropped, not saved by itself.
        Post.Landed first=Post.arrived(context,store,receiver,Envelope.seal(new byte[16],0,1,hello,sender,receiver.agreement().getPublic()));
        assertNotNull(first.accepted);assertEquals("",first.accepted.target);assertEquals("Phone fixture",first.accepted.name);
        assertTrue(store.addresses().isEmpty());
        // Once paired, the same hello is only an answer: this end stops saying hello to them.
        store.pairedWith(address,"Phone fixture",false,agreement.getPublic().getEncoded(),sender.getPublic().getEncoded());
        store.accepting(address,"Phone fixture","","",false);assertEquals(1,store.waitingToAccept().size());
        Post.Landed again=Post.arrived(context,store,receiver,Envelope.seal(new byte[16],0,2,hello,sender,receiver.agreement().getPublic()));
        assertNull(again.accepted);assertTrue(store.waitingToAccept().isEmpty());
    }
    @Test public void unpairedOrTamperedEnvelopeDoesNotCreateNote() throws Exception {
        Keys receiver=new Keys(context);var stranger=Envelope.keys();byte[] sealed=Envelope.seal(new byte[16],1,1,"ignored".getBytes(),stranger,receiver.agreement().getPublic());
        assertEquals("",Post.arrived(context,store,receiver,sealed).note);assertNull(store.latest());
        sealed[sealed.length-1]^=1;assertEquals("",Post.arrived(context,store,receiver,sealed).note);assertNull(store.latest());
    }
    @Test public void relayAcceptanceDoesNotClearDeliveryButAuthenticatedReceiptDoes() throws Exception {
        Keys here=new Keys(context);var peer=Envelope.keys();var agreement=Envelope.keys();String address="MxReceiptFixture@127.0.0.1:9001";
        store.pairedWith(address,"Test phone",false,agreement.getPublic().getEncoded(),peer.getPublic().getEncoded());
        NoteStore.Note n=note("receipt fixture");n.revision=7;store.save(n);store.addShare(new Sharing.Rule(Sharing.Scope.PAGE,n.id,address,true));
        store.handedOver(address,n.id,7);assertEquals(1,store.owed(NoteStore.Branch.Kind.PAGE,n.id).size());
        UUID uuid=UUID.fromString(n.id);byte[] page=java.nio.ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
        byte[] sealed=Envelope.seal(page,7,12345,Receipt.wrap(Receipt.TOOK),peer,here.agreement().getPublic());
        assertTrue(Post.arrived(context,store,here,sealed).answered);assertTrue(store.owed(NoteStore.Branch.Kind.PAGE,n.id).isEmpty());
        store.close();store=new NoteStore(context);assertTrue(store.owed(NoteStore.Branch.Kind.PAGE,n.id).isEmpty());
    }
}
