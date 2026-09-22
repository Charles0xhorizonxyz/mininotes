package org.mininotes.android;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReorderTest {
    /** Four lines of the same height, drawn one under the other. */
    private static final float[] FOUR={50,150,250,350};

    @Test public void aLineHeldWhereItAlreadyIsStaysWhereItIs() {
        for(int line=0;line<FOUR.length;line++)
            assertEquals(line,Reorder.slot(FOUR[line],FOUR,line));
    }
    @Test public void draggingAboveEverythingPutsItFirst() {
        assertEquals(0,Reorder.slot(0,FOUR,3));
        assertEquals(0,Reorder.slot(49,FOUR,2));
    }
    @Test public void draggingBelowEverythingPutsItLast() {
        assertEquals(3,Reorder.slot(900,FOUR,0));
        assertEquals(3,Reorder.slot(900,FOUR,3));
    }
    @Test public void aLineIsNeverCountedAgainstItself() {
        // Held just below its own middle, the first line has passed nobody and must not step down.
        assertEquals(0,Reorder.slot(60,FOUR,0));
        assertEquals(1,Reorder.slot(160,FOUR,0));
    }
    @Test public void passingTheMiddleOfTheLineAboveMovesItUp() {
        assertEquals(3,Reorder.slot(251,FOUR,3));   // still below that line's middle, so still last
        assertEquals(2,Reorder.slot(249,FOUR,3));   // above it now, so they swap
    }
    @Test public void linesOfDifferentHeightsAreJudgedByTheirMiddles() {
        float[] mixed={30,120,300};   // a short line, then a taller one, then a tall one
        assertEquals(0,Reorder.slot(29,mixed,2));
        assertEquals(1,Reorder.slot(31,mixed,2));
        assertEquals(2,Reorder.slot(121,mixed,2));
    }
    @Test public void aLevelHoldingOneThingHasOnlyOnePlace() {
        assertEquals(0,Reorder.slot(-40,new float[]{20},0));
        assertEquals(0,Reorder.slot(9000,new float[]{20},0));
    }
    @Test public void anEmptyLevelAsksForTheFirstPlace() {
        assertEquals(0,Reorder.slot(100,new float[0],-1));
    }

    // ---- a desktop, where things are laid out across as well as down ------------------------------------
    /** Two rows of three, tiles 100 wide and 100 tall: middles at 50/150/250 across, 50 and 150 down. */
    private static final float[] ACROSS={50,150,250,50,150,250}, DOWN={50,50,50,150,150,150};

    @Test public void onADesktopATileHeldWhereItIsStaysWhereItIs() {
        for(int at=0;at<ACROSS.length;at++)
            assertEquals(at,Reorder.slot(ACROSS[at],DOWN[at],ACROSS,DOWN,50,at));
    }
    @Test public void aRowBelowCountsAsPassed() {
        assertTrue(Reorder.past(10,150,250,50,50));      // second row, first column: past the top-right tile
        assertFalse(Reorder.past(290,50,50,150,50));     // first row: not past anything on the second
    }
    @Test public void onOneRowItIsWhicheverSideOfTheMiddle() {
        assertTrue(Reorder.past(160,50,150,50,50));
        assertFalse(Reorder.past(140,50,150,50,50));
    }
    @Test public void draggingToTheEndOfADesktopPutsItLast() {
        assertEquals(5,Reorder.slot(400,300,ACROSS,DOWN,50,0));
    }
    @Test public void draggingToTheStartOfADesktopPutsItFirst() {
        assertEquals(0,Reorder.slot(0,0,ACROSS,DOWN,50,5));
    }
    @Test public void aTileFromTheSecondRowMovesIntoTheFirst() {
        // Held between the first and second tiles of the top row.
        assertEquals(1,Reorder.slot(120,50,ACROSS,DOWN,50,4));
    }

}
