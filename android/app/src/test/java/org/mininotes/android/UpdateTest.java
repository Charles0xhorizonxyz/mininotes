package org.mininotes.android;
import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateTest {

    // ---- is it really later -------------------------------------------------------------------------------

    @Test public void aLaterBuildIsNewer() {
        assertTrue(Update.newer("0.0.107","0.0.106"));
    }

    @Test public void theSameBuildIsNotNewer() {
        assertFalse(Update.newer("0.0.106","0.0.106"));
    }

    @Test public void aBuildAheadOfTheRepositoryIsNotToldAnOlderOneWasPublished() {
        // What the first check got wrong: it asked "different?", and a phone running 0.0.106 against a
        // repository still saying 0.0.55 was told that v0.0.55 "has been published".
        assertFalse(Update.newer("0.0.55","0.0.106"));
    }

    @Test public void numbersAreComparedAsNumbers() {
        // As text, "0.0.99" sorts after "0.0.107".
        assertTrue(Update.newer("0.0.107","0.0.99"));
        assertFalse(Update.newer("0.0.99","0.0.107"));
        assertTrue(Update.newer("0.1.0","0.0.999"));
        assertTrue(Update.newer("1.0.0","0.99.99"));
    }

    @Test public void aShorterNameEndsInZeros() {
        assertFalse(Update.newer("0.1","0.1.0"));
        assertTrue(Update.newer("0.1.1","0.1"));
        assertTrue(Update.newer("0.2","0.1.9"));
    }

    @Test public void aTagIsReadLikeAVersion() {
        assertTrue(Update.newer("v0.0.107","0.0.106"));
        assertTrue(Update.newer("0.0.107\n","0.0.106"));
    }

    @Test public void somethingThatIsNotAVersionAnnouncesNothing() {
        // A sign-in wall or an error page in place of the file, an empty file, a name with words in it.
        assertFalse(Update.newer("<!DOCTYPE html>","0.0.106"));
        assertFalse(Update.newer("","0.0.106"));
        assertFalse(Update.newer(null,"0.0.106"));
        assertFalse(Update.newer("0.0.107-beta","0.0.106"));
        assertFalse(Update.newer("404: Not Found","0.0.106"));
        assertFalse(Update.newer("99999999999999999999999.0","0.0.106"));
    }

    @Test public void aBuildThatCannotNameItselfIsNotToldAnything() {
        // version() answers "unknown" when the package cannot be read.
        assertFalse(Update.newer("0.0.107","unknown"));
    }

    // ---- what the line says -----------------------------------------------------------------------------

    @Test public void theLineIsReducedToAVersion() {
        assertEquals("0.0.107",Update.read(" 0.0.107 \r\n"));
        assertEquals("0.0.107",Update.read("v0.0.107"));
        assertEquals("",Update.read("0.0.107 and some words"));
        assertEquals("",Update.read("0..1"));
        assertEquals("",Update.read(".1"));
        assertEquals("",Update.read("1."));
        assertEquals("",Update.read("123456789012345678901234567890123"));
    }

    // ---- is it time to look -----------------------------------------------------------------------------

    @Test public void aPadThatHasNeverLookedLooks() {
        assertTrue(Update.due(1_000_000L,0));
    }

    @Test public void aPadOpenedFortyTimesADayAsksOnce() {
        long looked=1_000_000_000L;
        assertFalse(Update.due(looked+1,looked));
        assertFalse(Update.due(looked+Update.EVERY-1,looked));
        assertTrue(Update.due(looked+Update.EVERY,looked));
    }

    @Test public void aClockSetBackDoesNotMeanNeverAskingAgain() {
        assertTrue(Update.due(1_000L,5_000L));
    }
}
