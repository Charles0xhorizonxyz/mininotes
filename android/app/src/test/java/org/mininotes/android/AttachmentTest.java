package org.mininotes.android;
import org.junit.Test;
import static org.junit.Assert.*;

public class AttachmentTest {

    @Test public void aNameIsTheLastPartOfWhateverTheOtherAppSaid() {
        assertEquals("scan.pdf",Attachment.named("scan.pdf"));
        assertEquals("scan.pdf",Attachment.named("/storage/emulated/0/Download/scan.pdf"));
        assertEquals("scan.pdf",Attachment.named("C:\\Users\\me\\Documents\\scan.pdf"));
    }

    @Test public void aNameNeverClimbsOutOfWhereItIsUnpacked() {
        assertEquals("passwd",Attachment.named("../../../etc/passwd"));
        assertEquals("evil.txt",Attachment.named("..\\..\\evil.txt"));
        assertEquals(Attachment.UNNAMED,Attachment.named("../"));
    }

    @Test public void aNameIsOneLineAndNotThreeHundredCharacters() {
        assertEquals("two lines",Attachment.named("two\nlines"));
        assertEquals(120,Attachment.named("x".repeat(400)).length());
        assertEquals(Attachment.UNNAMED,Attachment.named(null));
        assertEquals(Attachment.UNNAMED,Attachment.named("   "));
    }

    @Test public void anythingIsWhatAFileIsWhenNothingSaysWhatItIs() {
        assertEquals("image/jpeg",Attachment.kind("image/jpeg"));
        assertEquals("image/jpeg",Attachment.kind("  image/jpeg  "));
        assertEquals(Attachment.ANYTHING,Attachment.kind(null));
        assertEquals(Attachment.ANYTHING,Attachment.kind(""));
        assertEquals(Attachment.ANYTHING,Attachment.kind("jpeg"));
        assertEquals(Attachment.ANYTHING,Attachment.kind("image/"+"x".repeat(200)));
    }

    @Test public void sizeIsSaidTheWayAPersonWouldSayIt() {
        assertEquals("1 byte",Attachment.size(1));
        assertEquals("812 bytes",Attachment.size(812));
        assertEquals("1 KB",Attachment.size(1024));
        assertEquals("24 KB",Attachment.size(24*1024));
        assertEquals("3.5 MB",Attachment.size(3_670_016));
        assertEquals("24 MB",Attachment.size(25_165_824));
        assertEquals("0 bytes",Attachment.size(-5));
    }

    @Test public void oneFileHasALimitAndTheLimitIsTheOnlyOne() {
        assertFalse(Attachment.tooBig(Attachment.LIMIT));
        assertTrue(Attachment.tooBig(Attachment.LIMIT+1));
        assertFalse(Attachment.tooBig(0));
        assertTrue(Attachment.PLENTY>Attachment.LIMIT);
    }

    @Test public void aBackupEntryIsAnIdAndNothingElse() {
        assertEquals("files/abc-123",Attachment.entry("abc-123"));
        assertEquals("abc-123",Attachment.idOf("files/abc-123"));
        assertEquals("abc-123",Attachment.idOf(Attachment.entry("abc-123")));
    }

    @Test public void aBackupCannotNameAPathToWriteTo() {
        assertNull(Attachment.idOf("files/../../etc/passwd"));
        assertNull(Attachment.idOf("files/sub/one"));
        assertNull(Attachment.idOf("/etc/passwd"));
        assertNull(Attachment.idOf("notes.json"));
        assertNull(Attachment.idOf("files/"));
        assertNull(Attachment.idOf("files/"+"a".repeat(80)));
        assertNull(Attachment.idOf(null));
    }
}
