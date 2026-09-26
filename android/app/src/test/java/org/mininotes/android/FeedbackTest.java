// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the app hands to the browser when somebody has something to say. */
public class FeedbackTest {

    private static final String SOURCE="https://github.com/mininotesorg/mininotes";
    private static final String ABOUT="Mininotes 0.0.58 · Android 16 · Pixel 7";

    /** The form is named, and every field the form asks for is filled in. */
    @Test public void fillsInTheForm() {
        String url=Feedback.url(SOURCE,Feedback.Kind.BROKEN,Feedback.Area.SHARING,
            "Scanning a code did nothing.","",ABOUT);
        assertTrue(url.startsWith(SOURCE+"/issues/new?template=feedback.yml"));
        assertTrue(url.contains("&kind=Something+is+broken"));
        assertTrue(url.contains("&area=Sharing+and+syncing"));
        assertTrue(url.contains("&what=Scanning+a+code+did+nothing."));
        assertTrue(url.contains("&about=Mininotes+0.0.58"));
    }

    /** The label every one carries, and the one that says which sort it is. */
    @Test public void carriesBothLabels() {
        String url=Feedback.url(SOURCE,Feedback.Kind.IDEA,Feedback.Area.WRITING,"A wider margin.","",ABOUT);
        assertTrue(url.contains("&labels=feedback%2Cidea"));
    }

    /** An address given is sent; one not given leaves no empty field behind. */
    @Test public void theAddressIsOptional() {
        String with=Feedback.url(SOURCE,Feedback.Kind.IDEA,Feedback.Area.WRITING,"Something.","MxABC123",ABOUT);
        assertTrue(with.contains("&minima=MxABC123"));
        String without=Feedback.url(SOURCE,Feedback.Kind.IDEA,Feedback.Area.WRITING,"Something.","  ",ABOUT);
        assertFalse(without.contains("minima"));
    }

    /** The title says what it is, where it is, and how it starts, so a list of them can be read down. */
    @Test public void theTitleSaysWhatItIs() {
        assertEquals("Something is broken — Attachments: A photo would not open",
            Feedback.title(Feedback.Kind.BROKEN,Feedback.Area.FILES,
                "A photo would not open\nI tapped it twice and nothing happened."));
    }

    /** A title cannot run away with the whole first paragraph. */
    @Test public void theTitleIsCutRatherThanRunOn() {
        StringBuilder long1=new StringBuilder();
        for(int i=0;i<40;i++)long1.append("word ");
        String title=Feedback.title(Feedback.Kind.IDEA,Feedback.Area.WRITING,long1.toString());
        assertTrue(title.length()<=80);
        assertTrue(title.endsWith("…"));
    }

    /**
     * A long story is cut here rather than dropped there.
     *
     * <p>What is past a browser's limit is not refused, it is quietly ignored, which would take the end
     * off somebody's sentence without either of us being told.
     */
    @Test public void aLongStoryStillFits() {
        StringBuilder said=new StringBuilder();
        for(int i=0;i<Feedback.SAID_MOST;i++)said.append('x');
        String url=Feedback.url(SOURCE,Feedback.Kind.BROKEN,Feedback.Area.NODE,said.toString(),"",ABOUT);
        assertTrue("url was "+url.length(),url.length()<=Feedback.URL_MOST);
        assertTrue(url.contains("&kind=Something+is+broken"));
        assertTrue(url.contains("&about=Mininotes"));
    }

    /** Characters a query string would otherwise eat. */
    @Test public void escapesWhatWouldBreakTheAddress() {
        String url=Feedback.url(SOURCE,Feedback.Kind.CONFUSING,Feedback.Area.LOOK,
            "The A+ and A- are 50% too small & I cannot tell #1 from #2","",ABOUT);
        assertFalse(url.contains(" "));
        assertTrue(url.contains("%26"));
        assertTrue(url.contains("%23"));
        assertTrue(url.contains("%25"));
    }

    /** No repository yet means no address, and the app says so instead of opening nothing. */
    @Test public void noRepositoryMeansNoAddress() {
        assertEquals("",Feedback.url("",Feedback.Kind.IDEA,Feedback.Area.WRITING,"Something.","",ABOUT));
        assertEquals("",Feedback.log(""));
    }

    /** A trailing slash in the repository address does not become a double one. */
    @Test public void aTrailingSlashIsNotDoubled() {
        String url=Feedback.url(SOURCE+"/",Feedback.Kind.IDEA,Feedback.Area.WRITING,"Something.","",ABOUT);
        assertTrue(url.startsWith(SOURCE+"/issues/new?"));
        assertFalse(url.contains("//issues"));
    }

    /** The log is every one of them, answered or not, open or closed. */
    @Test public void theLogIsTheWholeLot() {
        assertEquals(SOURCE+"/issues?q=is%3Aissue+label%3Afeedback",Feedback.log(SOURCE));
    }

    /** What is copied is the report, not a summary of it. */
    @Test public void theCopyIsTheWholeReport() {
        String plain=Feedback.plain(Feedback.Kind.MISSING,Feedback.Area.BACKUP,
            "There is no way to restore one note.","MxABC123",ABOUT);
        assertTrue(plain.contains("Something is missing"));
        assertTrue(plain.contains("Backup and restore"));
        assertTrue(plain.contains("There is no way to restore one note."));
        assertTrue(plain.contains("MxABC123"));
        assertTrue(plain.contains(ABOUT));
    }

    /** Every kind and every part of the app survives being put in an address. */
    @Test public void everyChoiceMakesAnAddress() {
        for(Feedback.Kind kind:Feedback.Kind.values())
            for(Feedback.Area area:Feedback.Area.values()) {
                String url=Feedback.url(SOURCE,kind,area,"Something happened.","",ABOUT);
                assertTrue(kind+" "+area,url.contains("&kind=")&&url.contains("&area="));
                assertTrue(kind+" "+area,url.length()<=Feedback.URL_MOST);
            }
    }
}
