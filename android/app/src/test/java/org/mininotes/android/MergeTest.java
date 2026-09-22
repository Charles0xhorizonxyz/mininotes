package org.mininotes.android;
import org.junit.Test;
import static org.junit.Assert.*;

public class MergeTest {

    @Test public void twoPeopleWhoWroteTheSameThingAgree() {
        Merge.Result said=Merge.merge("one\ntwo","one\ntwo\nthree","one\ntwo\nthree");
        assertTrue(said.clean);
        assertEquals(0,said.conflicts);
        assertEquals("one\ntwo\nthree",said.text);
    }

    @Test public void oneSideThatNeverMovedTakesTheOther() {
        Merge.Result theirs=Merge.merge("one\ntwo","one\ntwo","one\ntwo\nthree");
        assertTrue(theirs.clean);
        assertEquals("one\ntwo\nthree",theirs.text);

        Merge.Result mine=Merge.merge("one\ntwo","one\ntwo\nthree","one\ntwo");
        assertTrue(mine.clean);
        assertEquals("one\ntwo\nthree",mine.text);
    }

    @Test public void changesInDifferentPlacesAreBothThere() {
        // You wrote on the first line, they wrote on the last. Both are simply in the note.
        Merge.Result said=Merge.merge(
            "Milk\nBread\nEggs",
            "Oat milk\nBread\nEggs",
            "Milk\nBread\nEggs, six");
        assertTrue("a merge that had to choose: "+said.text,said.clean);
        assertEquals("Oat milk\nBread\nEggs, six",said.text);
    }

    @Test public void twoAdditionsAtDifferentEndsBothArrive() {
        Merge.Result said=Merge.merge(
            "Bread\nEggs",
            "Coffee\nBread\nEggs",
            "Bread\nEggs\nRhubarb");
        assertTrue(said.clean);
        assertEquals("Coffee\nBread\nEggs\nRhubarb",said.text);
    }

    @Test public void thesameLineWrittenTwoWaysIsAConflict() {
        Merge.Result said=Merge.merge(
            "Milk\nBread",
            "Oat milk\nBread",
            "Almond milk\nBread");
        assertFalse(said.clean);
        assertEquals(1,said.conflicts);
        // Both lines are in the note, where the one line was. Nothing is put away where nobody looks.
        assertEquals("Almond milk\nOat milk\nBread",said.text);
    }

    @Test public void twoPhonesPuttingTheSameTwoTextsTogetherGetTheSameNote() {
        // Each phone calls the other "theirs". If that decided the order, two phones would come to two
        // different notes and have to start again - for ever.
        String here=Merge.merge("Milk\nBread","Oat milk\nBread","Almond milk\nBread").text;
        String there=Merge.merge("Milk\nBread","Almond milk\nBread","Oat milk\nBread").text;
        assertEquals(here,there);
    }

    @Test public void whatTheyShareIsThereOnceAndWhatTheyDoNotIsThereFromEach() {
        // Two phones that never agreed on anything, holding notes that are mostly the same.
        Merge.Result said=Merge.merge(null,"Title\n\nmilk\neggs\nmine","Title\n\nmilk\ntheirs\neggs");
        assertEquals("Title\n\nmilk\ntheirs\neggs\nmine",said.text);
        String other=Merge.merge(null,"Title\n\nmilk\ntheirs\neggs","Title\n\nmilk\neggs\nmine").text;
        assertEquals(said.text,other);
    }

    @Test public void aConflictNeverInventsText() {
        // Both of what was written, and not a letter that neither of them wrote.
        Merge.Result said=Merge.merge("a","b","c");
        assertFalse(said.clean);
        assertEquals("b\nc",said.text);
    }

    @Test public void aNoteNobodyEverSharedStillMerges() {
        // No common version: what differs is written by both, so both of it stays.
        Merge.Result said=Merge.merge(null,"mine","theirs");
        assertFalse(said.clean);
        assertEquals("mine\ntheirs",said.text);
    }

    @Test public void emptinessOnEitherSideIsNotAnEdgeCase() {
        assertEquals("theirs",Merge.merge("","","theirs").text);
        assertEquals("mine",Merge.merge("","mine","").text);
        assertEquals("",Merge.merge("","","").text);
        assertTrue(Merge.merge("","","").clean);
    }

    @Test public void deletingALineOnOneSideKeepsTheOtherSideEdits() {
        Merge.Result said=Merge.merge(
            "one\ntwo\nthree",
            "one\nthree",
            "one\ntwo\nthree, please");
        assertTrue("had to choose: "+said.text,said.clean);
        assertEquals("one\nthree, please",said.text);
    }

    @Test public void aLongNoteMergesWithoutLosingAnything() {
        StringBuilder base=new StringBuilder();
        for(int at=0;at<200;at++)base.append("line ").append(at).append('\n');
        String was=base.toString().trim();
        String mine=was.replace("line 5","line 5 — mine");
        String theirs=was.replace("line 150","line 150 — theirs");
        Merge.Result said=Merge.merge(was,mine,theirs);
        assertTrue(said.clean);
        assertTrue(said.text.contains("line 5 — mine"));
        assertTrue(said.text.contains("line 150 — theirs"));
        assertEquals(200,said.text.split("\n",-1).length);
    }
}
