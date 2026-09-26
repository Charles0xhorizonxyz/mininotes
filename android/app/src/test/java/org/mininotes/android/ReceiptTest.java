package org.mininotes.android;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ReceiptTest {

    @Test public void anAnswerReadsBackAsWhatItSaid() {
        assertEquals(Receipt.HAVE,Receipt.open(Receipt.wrap(Receipt.HAVE)));
    }

    @Test public void somethingALaterBuildSaysIsStillAnAnswer() {
        // Read as a number rather than refused: what to do about one it does not know is the caller's.
        assertEquals(12,Receipt.open(Receipt.wrap(12)));
    }

    @Test public void aNoteIsNotAnAnswer() throws Exception {
        byte[] note=Parcel.wrap(new Parcel.Sent("c","C","b","B","","Milk",true));
        assertEquals(0,Receipt.open(note));
    }

    @Test public void anAnswerIsNotANote() {
        // The other way round matters more. A build that read an answer as a note would write five bytes
        // of nothing over somebody's writing.
        assertNull(Parcel.open(Receipt.wrap(Receipt.HAVE)));
        assertNull(Hello.open(Receipt.wrap(Receipt.HAVE)));
    }

    // ---- leaving ----------------------------------------------------------------------------------------

    @Test public void leavingSaysWhichKindOfThingWasLeft() {
        for(Sharing.Scope scope:new Sharing.Scope[]{Sharing.Scope.PAGE,Sharing.Scope.BOOK,Sharing.Scope.COLLECTION})
            assertEquals(scope,Receipt.leftScope(Receipt.open(Receipt.wrap(Receipt.left(scope)))));
    }

    @Test public void nothingElseIsTakenForLeaving() {
        for(int about:new int[]{0,Receipt.HAVE,Receipt.ASK,Receipt.TOOK,Receipt.REMOVED_PAGE,12})
            assertNull(Receipt.leftScope(about));
        // Everything on a phone is not something that can be left, and nothing is sent that says it was.
        assertEquals(0,Receipt.left(Sharing.Scope.LIBRARY));
        assertEquals(0,Receipt.left(null));
    }

    @Test public void leavingIsNotANote() {
        assertNull(Parcel.open(Receipt.wrap(Receipt.LEFT_BOOK)));
        assertNull(Hello.open(Receipt.wrap(Receipt.LEFT_BOOK)));
    }

    // ---- being taken off ----------------------------------------------------------------------------------

    @Test public void beingTakenOffSaysWhichKindOfThing() {
        for(Sharing.Scope scope:new Sharing.Scope[]{Sharing.Scope.PAGE,Sharing.Scope.BOOK,Sharing.Scope.COLLECTION})
            assertEquals(scope,Receipt.removedScope(Receipt.open(Receipt.wrap(Receipt.removed(scope)))));
    }

    @Test public void takingOffAndLeavingAreNotTheSameWord() {
        // The one is said by the person going; the other to them. A phone must never take one for the other.
        for(Sharing.Scope scope:new Sharing.Scope[]{Sharing.Scope.PAGE,Sharing.Scope.BOOK,Sharing.Scope.COLLECTION}) {
            assertNull(Receipt.leftScope(Receipt.removed(scope)));
            assertNull(Receipt.removedScope(Receipt.left(scope)));
        }
        for(int about:new int[]{0,Receipt.HAVE,Receipt.ASK,Receipt.TOOK,12})
            assertNull(Receipt.removedScope(about));
        assertEquals(0,Receipt.removed(Sharing.Scope.LIBRARY));
        assertEquals(0,Receipt.removed(null));
    }

    @Test public void beingTakenOffIsNotANote() {
        assertNull(Parcel.open(Receipt.wrap(Receipt.REMOVED_COLLECTION)));
        assertNull(Hello.open(Receipt.wrap(Receipt.REMOVED_COLLECTION)));
    }

    // ---- asking to be answered --------------------------------------------------------------------------

    private static Parcel.Sent note(boolean answer) {
        return new Parcel.Sent("c","Perso","b","Text","","Milk\nBread",true,
            java.util.Collections.<Parcel.Member>emptyList(),"BOOK","b",answer);
    }

    @Test public void aNoteCanAskToBeAnswered() throws Exception {
        assertTrue(Parcel.open(Parcel.wrap(note(true))).answer);
        assertFalse(Parcel.open(Parcel.wrap(note(false))).answer);
    }

    @Test public void aNoteFromABuildBeforeAnswersAsksForNone() throws Exception {
        // Such a build wrote everything but the last ten bytes - the asking, what the note was written on
        // top of, and whether its build carries. It must read whole, and it must not be answered: it would
        // take the answer for a note written the old way and put five bytes over somebody's writing.
        byte[] whole=Parcel.wrap(note(true));
        Parcel.Sent older=Parcel.open(java.util.Arrays.copyOf(whole,whole.length-10));
        assertNotNull(older);
        assertEquals("Milk\nBread",older.body);
        assertEquals("BOOK",older.scope);
        assertFalse(older.answer);
    }

    // ---- saying what it was written on top of -------------------------------------------------------------

    @Test public void aNoteSaysWhatItWasWrittenOnTopOf() throws Exception {
        Parcel.Sent out=new Parcel.Sent("c","Perso","b","Text","","Milk",true,
            java.util.Collections.<Parcel.Member>emptyList(),"BOOK","b",true,13L);
        assertEquals(13L,Parcel.open(Parcel.wrap(out)).basedOn);
    }

    @Test public void aNoteThatDoesNotSayIsNotTakenToHaveSaidNought() throws Exception {
        // Nought is a revision: "we never agreed on anything". Not saying is a different thing, and the
        // receiver falls back on what it believes itself.
        assertEquals(-1L,Parcel.open(Parcel.wrap(note(true))).basedOn);
        byte[] whole=Parcel.wrap(new Parcel.Sent("c","Perso","b","Text","","Milk",true,
            java.util.Collections.<Parcel.Member>emptyList(),"BOOK","b",true,13L));
        // A build that asked to be answered but knew nothing of this wrote nine bytes fewer: these eight,
        // and the one after them saying whether it carries.
        Parcel.Sent older=Parcel.open(java.util.Arrays.copyOf(whole,whole.length-9));
        assertNotNull(older);
        assertTrue(older.answer);
        assertEquals(-1L,older.basedOn);
    }

    @Test public void aNoteWithNoShelfAsksForNoneEither() throws Exception {
        assertFalse(Parcel.open(Parcel.wrap(new Parcel.Sent("","","","","","just text",false))).answer);
    }

    @Test public void nothingAndNonsenseAreNotAnswers() {
        assertEquals(0,Receipt.open(null));
        assertEquals(0,Receipt.open(new byte[0]));
        assertEquals(0,Receipt.open("MNR1".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0,Receipt.open("MNR1xx".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0,Receipt.open("MNB1x".getBytes(StandardCharsets.US_ASCII)));
    }
}
