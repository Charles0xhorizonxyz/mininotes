package org.mininotes.android;

import java.time.*;
import java.time.format.DateTimeFormatter;

/** Read persisted evidence, never the time a relay accepted a parcel. */
final class SyncStatus {
    static final String ONLY_HERE="Only here",WAITING="Waiting for delivery",MATCHING="Waiting for matching versions",
        CONFIRMED="Sync confirmed",NOT_CONFIRMED="Sync not confirmed",UNAVAILABLE="Note unavailable";
    record State(long saved,long confirmed,String status) {
        /** One quiet line, the same marks as the tree: ✓ everybody has it, ↑ somebody is waiting. */
        String brief(String device){return brief(device,ZonedDateTime.now());}
        String brief(String device,ZonedDateTime now) {
            return switch(status) {
                case ONLY_HERE -> "Only on "+device+" · saved "+shortly(saved,now);
                case CONFIRMED -> "✓ Synced "+shortly(confirmed,now);
                case WAITING, NOT_CONFIRMED -> "↑ Saved "+shortly(saved,now)+" · waiting to sync";
                case MATCHING -> "↑ Saved "+shortly(saved,now)+" · waiting for the others";
                default -> status;
            };
        }
        /** Everything, exactly: for a tooltip or a tap. */
        String detail(String device) {
            return "Saved on "+device+": "+date(saved)
                +"\n"+(status.equals(CONFIRMED)?"Everyone it is shared with has this version: "+date(confirmed)
                    :status.equals(ONLY_HERE)?"Not shared.":status+".")
                +"\n\nTimes are local to "+device+". Devices that are offline may have newer edits. Attachments stay on this device.";
        }
    }
    private static String date(long millis) {
        return DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss z")
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }
    /** "14:05" today, "23 Sep, 14:05" this year, "23 Sep 2025" before. */
    static String shortly(long millis,ZonedDateTime now) {
        ZonedDateTime at=Instant.ofEpochMilli(millis).atZone(now.getZone());
        String pattern=at.toLocalDate().equals(now.toLocalDate())?"HH:mm":at.getYear()==now.getYear()?"d MMM, HH:mm":"d MMM yyyy";
        return DateTimeFormatter.ofPattern(pattern).format(at);
    }
    static State read(NoteStore store,String id) {
        var db=store.getReadableDatabase();db.beginTransaction();
        try {
            var note=store.get(id);
            if(note==null)return new State(0,0,UNAVAILABLE);
            var audience=Sharing.audience(store.shares(),store.collectionOfBook(note.book),note.book,id);
            if(audience.isEmpty())return new State(note.updated,0,
                store.sharedAtAll(NoteStore.Branch.Kind.PAGE,id)?NOT_CONFIRMED:ONLY_HERE);
            if(!store.owed(NoteStore.Branch.Kind.PAGE,id).isEmpty())
                return new State(note.updated,0,WAITING);
            long latest=0;
            for(String address:audience.keySet()) {
                try(var row=db.query("sent",new String[]{"revision","agreed","at"},"address=? AND page=?",
                    new String[]{address,id},null,null,null,"1")) {
                    if(!row.moveToFirst()||row.getLong(0)!=note.revision||row.getLong(1)!=note.revision)
                        return new State(note.updated,0,MATCHING);
                    latest=Math.max(latest,row.getLong(2));
                }
            }
            return new State(note.updated,latest,latest>0?CONFIRMED:NOT_CONFIRMED);
        } finally {db.setTransactionSuccessful();db.endTransaction();}
    }
}
