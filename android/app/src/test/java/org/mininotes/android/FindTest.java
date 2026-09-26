// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Looking for a word, and showing where it was found. */
public class FindTest {

    @Test public void findsAWordWhateverItsCase() {
        assertTrue(Find.holds("A note about Istanbul","istanbul"));
        assertTrue(Find.holds("a note about istanbul","Istanbul"));
        assertFalse(Find.holds("A note about Ankara","istanbul"));
    }

    /** Nothing to look for finds nothing, rather than everything. */
    @Test public void nothingFindsNothing() {
        assertFalse(Find.holds("anything at all",""));
        assertFalse(Find.holds("anything at all","   "));
        assertFalse(Find.holds("anything at all",null));
        assertFalse(Find.holds(null,"x"));
    }

    /**
     * The whole point of showing the line rather than the name.
     *
     * <p>A word four hundred characters into a note is not shown by the first forty, so the piece is cut
     * at the match.
     */
    @Test public void theLineShownIsTheOneTheWordIsOn() {
        StringBuilder long1=new StringBuilder();
        for(int at=0;at<40;at++)long1.append("padding words here. ");
        String body=long1+"the tickets are booked for Tuesday"+long1;
        String around=Find.around(body,"tickets");
        assertTrue(around,around.contains("tickets are booked"));
        assertTrue("it should say it was cut at both ends: "+around,
            around.startsWith("\u2026")&&around.endsWith("\u2026"));
        assertTrue(around.length()<=Find.AROUND+2);
    }

    /** A result is one row, so what was three lines becomes one. */
    @Test public void brokenLinesBecomeOneLine() {
        String around=Find.around("Shopping\n\n  milk\n  bread\n","bread");
        assertFalse(around,around.contains("\n"));
        assertTrue(around,around.contains("bread"));
        assertFalse("runs of spaces should be closed up: "+around,around.contains("  "));
    }

    /** A short note is shown whole, with nothing to say it was cut. */
    @Test public void aShortNoteIsShownWhole() {
        assertEquals("milk and bread",Find.around("milk and bread","milk"));
    }

    /** A note with no match still reads as its beginning rather than as nothing. */
    @Test public void noMatchShowsTheBeginning() {
        assertEquals("milk and bread",Find.around("milk and bread","tickets"));
    }

    /**
     * The characters a LIKE would otherwise read as wildcards.
     *
     * <p>Without this, looking for "100%" matches every note on the pad, and looking for "_" matches all
     * of them twice.
     */
    @Test public void wildcardsAreLookedForRatherThanObeyed() {
        assertEquals("%100\\%%",Find.like("100%"));
        assertEquals("%a\\_b%",Find.like("a_b"));
        assertEquals("%c:\\\\x%",Find.like("c:\\x"));
        assertEquals("%plain%",Find.like("plain"));
    }

    /** What is looked for is trimmed, because a trailing space is a typing accident, not a word. */
    @Test public void theWordIsTrimmed() {
        assertEquals("%milk%",Find.like("  milk  "));
        assertEquals("milk",Find.tidy("  milk  "));
        assertEquals("",Find.tidy(null));
    }
}
