package org.mininotes.android;
import org.junit.Test;
import static org.junit.Assert.*;

public class ArrivingTest {

    @Test public void aNoteThisPhoneHasNeverSeenIsSimplyKept() {
        Arriving.Decision said=Arriving.weigh(null,0,null,0,"Their note",3);
        assertEquals(Arriving.What.NEW,said.what);
        assertEquals("Their note",said.text);
        assertEquals(3,said.revision);
        assertFalse(said.keepTheirs);
    }

    @Test public void whatArrivedUnchangedChangesNothing() {
        Arriving.Decision said=Arriving.weigh("Same",4,"Same",4,"Same",4);
        assertEquals(Arriving.What.NEWER,said.what);
        assertEquals("Same",said.text);
    }

    @Test public void aVersionThisPhoneHasAlreadyWrittenPastIsIgnored() {
        // They sent revision 2; this phone is at 5 and they had not written since the two agreed at 2.
        Arriving.Decision said=Arriving.weigh("Mine, later",5,"What we agreed",2,"What we agreed",2);
        assertEquals(Arriving.What.OLDER,said.what);
        assertNull(said.text);
        assertEquals(5,said.revision);
    }

    @Test public void aVersionThatFollowsOnFromThisOneIsTaken() {
        // This phone has not written since the agreement; theirs descends from it.
        Arriving.Decision said=Arriving.weigh("Agreed",2,"Agreed",2,"Agreed, and more",3);
        assertEquals(Arriving.What.NEWER,said.what);
        assertEquals("Agreed, and more",said.text);
        assertEquals(3,said.revision);
        assertFalse(said.keepTheirs);
    }

    @Test public void bothSidesWritingInDifferentPlacesJustMerges() {
        Arriving.Decision said=Arriving.weigh(
            "Oat milk\nBread\nEggs",4,
            "Milk\nBread\nEggs",2,
            "Milk\nBread\nEggs, six",3);
        assertEquals(Arriving.What.MERGED,said.what);
        assertEquals("Oat milk\nBread\nEggs, six",said.text);
        assertFalse("nothing was written over, so nothing has to be kept twice",said.keepTheirs);
        assertTrue(said.revision>4);
    }

    @Test public void bothSidesWritingOverEachOtherKeepsBoth() {
        Arriving.Decision said=Arriving.weigh(
            "Oat milk",4,
            "Milk",2,
            "Almond milk",3);
        assertEquals(Arriving.What.MERGED,said.what);
        // Both lines are in the note now, and the reader is told they wrote in the same place.
        assertEquals("Almond milk\nOat milk",said.text);
        assertTrue(said.keepTheirs);
    }

    @Test public void twoPhonesThatNeverAgreedOnAnythingStillLoseNothing() {
        Arriving.Decision said=Arriving.weigh("Mine",3,null,0,"Theirs",3);
        assertEquals(Arriving.What.MERGED,said.what);
        assertEquals("Mine\nTheirs",said.text);
        assertTrue(said.keepTheirs);
    }

    @Test public void theRevisionAfterAMergeIsPastBothSides() {
        // Different lines, so what comes out is a text neither phone had: a new revision, past both.
        Arriving.Decision said=Arriving.weigh("a!\nb\nc",7,"a\nb\nc",1,"a\nb\nc?",9);
        assertEquals("a!\nb\nc?",said.text);
        assertTrue("a merged note has to count past both, or the other phone would not take it",
            said.revision>9);
    }

    @Test public void bothAddingALineInTheSamePlaceKeepsBothLines() {
        Arriving.Decision said=Arriving.weigh("a\nb",7,"a",1,"a\nc",9);
        assertEquals(Arriving.What.MERGED,said.what);
        assertEquals("a\nb\nc",said.text);
        assertTrue(said.keepTheirs);
        assertTrue("it is a text neither phone had, so it counts past both",said.revision>9);
    }

    @Test public void whatIsAlreadyAllHereIsNotANewRevision() {
        // They sent lines this phone already has, with some of its own besides. Nothing to take, and
        // nothing was written - counting that as a revision is what made two phones owe each other for ever.
        Arriving.Decision said=Arriving.weigh("a\nb\nc",7,"a",1,"a\nc",9);
        assertEquals(Arriving.What.OLDER,said.what);
        assertEquals(7,said.revision);
    }

    @Test public void whatComesOutTheSameAsWhatTheySentIsSimplyTaken() {
        // This phone's line is already in what they sent. Taken at their revision, nothing to send back.
        Arriving.Decision said=Arriving.weigh("a\nb",7,"a",1,"a\nb\nc",9);
        assertEquals(Arriving.What.NEWER,said.what);
        assertEquals("a\nb\nc",said.text);
        assertEquals(9,said.revision);
    }

    @Test public void anEchoIsNothing() {
        // They sent back, under a higher number, exactly the text both sides last had. This phone has
        // written since. There is nothing in it to take and nothing to say.
        Arriving.Decision said=Arriving.weigh("agreed\nmine",8,"agreed",5,"agreed",9);
        assertEquals(Arriving.What.OLDER,said.what);
        assertEquals(8,said.revision);
    }

    // ---- what two phones last agreed on -------------------------------------------------------------------

    @Test public void theOlderOfTheTwoBeliefsIsTheOneUsed() {
        assertEquals(13,Arriving.agreed(20,13));
        assertEquals(13,Arriving.agreed(13,20));
        assertEquals(15,Arriving.agreed(15,15));
    }

    @Test public void aSenderThatDoesNotSayLeavesThisPhoneToItsOwnBelief() {
        assertEquals(20,Arriving.agreed(20,-1));
    }

    @Test public void whatHappenedLastNightNoLongerLosesAnybodysWords() {
        // The Pro had handed revision 20 to the network five times for a phone that took none of them, and
        // so believed that phone had revision 20. That phone then sent its own revision 17, written on top
        // of the 13 it really had. Weighed against 20, it was old news and set aside in silence.
        long believed=20, theySay=13;
        Arriving.Decision before=Arriving.weigh("Pro, much later",20,"Pro at twenty",believed,"Theirs",17);
        assertEquals("this is the fault: their writing taken for old news",Arriving.What.OLDER,before.what);
        long base=Arriving.agreed(believed,theySay);
        Arriving.Decision after=Arriving.weigh("Milk\nPro's line",20,"Milk",base,"Their line\nMilk",17);
        assertEquals(Arriving.What.MERGED,after.what);
        assertTrue(after.text.contains("Pro's line"));
        assertTrue(after.text.contains("Their line"));
    }

    // ---- the note that is open changed underneath the page ---------------------------------------------

    @Test public void aPageNobodyHasTypedOnBecomesWhatTheNotebookSays() {
        Arriving.Page said=Arriving.onThePage("Milk\nBread","Milk\nBread","Milk\nBread\nEggs");
        assertEquals("Milk\nBread\nEggs",said.text);
        assertFalse("nothing here the notebook lacks, so nothing to write down again",said.unsaved);
    }

    @Test public void wordsTypedSinceTheLastSaveAreNotLostToWhatArrived() {
        // Typed "Butter" at the end and had not been written down yet; "Eggs" arrived at the top.
        Arriving.Page said=Arriving.onThePage("Milk\nBread","Milk\nBread\nButter","Eggs\nMilk\nBread");
        assertEquals("Eggs\nMilk\nBread\nButter",said.text);
        assertTrue("the page now holds what the notebook does not",said.unsaved);
    }

    @Test public void whatArrivedIsNotLostToWordsTypedSinceEither() {
        Arriving.Page said=Arriving.onThePage("a\nb\nc","a!\nb\nc","a\nb\nc?");
        assertTrue(said.text.contains("a!"));
        assertTrue(said.text.contains("c?"));
    }

    @Test public void typingOverTheSameLineThatArrivedKeepsBothLines() {
        // The same rule as everywhere: nothing is chosen for you, and nothing is put away.
        Arriving.Page said=Arriving.onThePage("Milk","Oat milk","Almond milk");
        assertEquals("Almond milk\nOat milk",said.text);
        assertTrue(said.unsaved);
    }

    @Test public void aPageAlreadySayingWhatTheNotebookSaysIsLeftAlone() {
        Arriving.Page said=Arriving.onThePage("old","same","same");
        assertEquals("same",said.text);
        assertFalse(said.unsaved);
    }

    @Test public void nothingAtAllIsStillSomethingToCompare() {
        Arriving.Page said=Arriving.onThePage(null,null,"arrived");
        assertEquals("arrived",said.text);
        assertFalse(said.unsaved);
    }
}
