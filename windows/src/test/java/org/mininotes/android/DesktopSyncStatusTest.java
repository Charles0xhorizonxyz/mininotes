package org.mininotes.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import org.mininotes.desktop.platform.content.Context;

public class DesktopSyncStatusTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private NoteStore store;
    private Context context;
    private NoteStore.Note note;
    @Before public void open() throws Exception {
        context=new Context(temp.newFolder("sync-times").toPath().toFile());
        store=new NoteStore(context);note=new NoteStore.Note();note.book=store.someBook();
        note.body="Synthetic note";note.revision=1;store.save(note);
    }
    @After public void close(){store.close();}
    private void share(String address){store.setLevel(Sharing.Scope.PAGE,note.id,address,Sharing.Level.WRITE,null);}
    @Test public void relayAcceptanceIsNotConfirmationAndConfirmationSurvivesReopen() {
        assertEquals("Only here",SyncStatus.read(store,note.id).status());
        share("peer-a");store.handedOver("peer-a",note.id,note.revision);
        assertEquals("Waiting for delivery",SyncStatus.read(store,note.id).status());
        store.acknowledged("peer-a",note.id,note.revision,true);
        var confirmed=SyncStatus.read(store,note.id);
        assertEquals("Sync confirmed",confirmed.status());assertTrue(confirmed.confirmed()>0);
        store.close();store=new NoteStore(context);
        assertEquals(confirmed,SyncStatus.read(store,note.id));
    }
    @Test public void newerEditsAndLateReceiptsDoNotWearOldConfirmation() {
        share("peer-a");store.acknowledged("peer-a",note.id,1,true);
        note=DesktopEdits.save(store,note,"","Changed locally");
        store.acknowledged("peer-a",note.id,1,true);
        var state=SyncStatus.read(store,note.id);
        assertEquals("Waiting for delivery",state.status());assertEquals(0,state.confirmed());
        assertEquals(note.updated,state.saved());
    }
    @Test public void everyCurrentRecipientMustConfirmMatchingVersion() {
        share("peer-a");share("peer-b");store.agreedOn("peer-a",note.id,1);
        assertEquals("Waiting for delivery",SyncStatus.read(store,note.id).status());
        store.acknowledged("peer-b",note.id,1,false);
        assertEquals("Waiting for matching versions",SyncStatus.read(store,note.id).status());
        store.acknowledged("peer-b",note.id,1,true);
        assertEquals("Sync confirmed",SyncStatus.read(store,note.id).status());
        share("peer-c");assertEquals("Waiting for delivery",SyncStatus.read(store,note.id).status());
    }
    @Test public void oneShortLineAndTheWholeTimeOnAsking() {
        var now=java.time.ZonedDateTime.of(2026,9,25,15,0,0,0,java.time.ZoneId.of("Europe/Istanbul"));
        long today=now.withHour(9).withMinute(5).toInstant().toEpochMilli(),earlier=now.minusDays(2).toInstant().toEpochMilli();
        long lastYear=now.minusYears(1).toInstant().toEpochMilli();
        assertEquals("Only on this PC · saved 23 Sep, 15:00",new SyncStatus.State(earlier,0,SyncStatus.ONLY_HERE).brief("this PC",now));
        assertEquals("✓ Synced 09:05",new SyncStatus.State(earlier,today,SyncStatus.CONFIRMED).brief("this PC",now));
        assertEquals("↑ Saved 09:05 · waiting to sync",new SyncStatus.State(today,0,SyncStatus.WAITING).brief("this PC",now));
        assertEquals("↑ Saved 25 Sep 2025 · waiting for the others",new SyncStatus.State(lastYear,0,SyncStatus.MATCHING).brief("this PC",now));
        String detail=new SyncStatus.State(earlier,today,SyncStatus.CONFIRMED).detail("this PC");
        assertTrue(detail.startsWith("Saved on this PC: "));assertTrue(detail.contains("Everyone it is shared with has this version: "));
    }
}