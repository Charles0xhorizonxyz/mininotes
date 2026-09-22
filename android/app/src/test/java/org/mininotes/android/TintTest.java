package org.mininotes.android;
import org.junit.Test;
import static org.junit.Assert.*;

public class TintTest {
    private static final int PAPER=0xFFFBFAF6, DARK=0xFF121413;

    @Test public void noColourIsTheDefaultAndDrawsNothing() {
        assertEquals(0,Tint.NONE);
        assertFalse(Tint.known(Tint.NONE));
        assertEquals(PAPER,Tint.over(Tint.NONE,PAPER,1f,false));
    }
    @Test public void aNumberFromAnOlderOrNewerBuildIsSimplyNoColour() {
        for(int unknown:new int[]{-3,Tint.count(),Tint.count()+7}) {
            assertFalse(Tint.known(unknown));
            assertEquals(PAPER,Tint.over(unknown,PAPER,0.5f,false));
        }
    }
    @Test public void everyColourHasBothStrengthsAndAName() {
        assertEquals(Tint.count(),Tint.NAMES.length);
        for(int colour=1;colour<Tint.count();colour++) {
            assertTrue(Tint.NAMES[colour],Tint.known(colour));
            assertNotEquals(0,Tint.of(colour,false));
            assertNotEquals(0,Tint.of(colour,true));
            assertNotEquals("Same on both papers: "+Tint.NAMES[colour],Tint.of(colour,false),Tint.of(colour,true));
        }
    }
    @Test public void aColourOnDarkPaperIsTheLighterOfTheTwo() {
        for(int colour=1;colour<Tint.count();colour++)
            assertTrue(Tint.NAMES[colour],brightness(Tint.of(colour,true))>brightness(Tint.of(colour,false)));
    }
    @Test public void noWeightLeavesTheSurfaceAsItWas() {
        for(int colour=1;colour<Tint.count();colour++)assertEquals(PAPER,Tint.over(colour,PAPER,0f,false));
    }
    @Test public void fullWeightIsTheColourItself() {
        for(int colour=1;colour<Tint.count();colour++)
            assertEquals(Tint.of(colour,true),Tint.over(colour,DARK,1f,true));
    }
    @Test public void aWashLandsBetweenTheSurfaceAndTheColour() {
        int washed=Tint.over(4,PAPER,0.2f,false);   // Teal on the pad's own paper
        assertTrue(brightness(washed)<brightness(PAPER));
        assertTrue(brightness(washed)>brightness(Tint.of(4,false)));
        assertEquals(0xFF,(washed>>>24));           // always opaque: it is a surface, not a veil
    }
    @Test public void weightsOutsideTheRangeAreHeldAtTheEnds() {
        assertEquals(Tint.over(2,PAPER,0f,false),Tint.over(2,PAPER,-4f,false));
        assertEquals(Tint.over(2,PAPER,1f,false),Tint.over(2,PAPER,9f,false));
    }
    private static int brightness(int colour) {
        return ((colour>>16)&0xFF)*299+((colour>>8)&0xFF)*587+(colour&0xFF)*114;
    }

    @Test public void theTonesRunFromPastelToTheColourItself() {
        for(int at=1;at<Tint.TONES.length;at++)
            assertTrue("tone "+at+" is not louder than "+(at-1),Tint.TONES[at]>Tint.TONES[at-1]);
        // A pad starts loud enough to tell eight colours apart, with quieter rungs still below it.
        assertTrue(Tint.TONES[Tint.FIRST_TONE]>1f);
        assertTrue(Tint.FIRST_TONE>0&&Tint.FIRST_TONE<Tint.TONES.length-1);
    }

    @Test public void aWashNeverDrownsWhatIsWrittenOnIt() {
        assertEquals(0.12f*Tint.TONES[Tint.FIRST_TONE],Tint.weigh(0.12f,Tint.FIRST_TONE,0.6f),0.0001f);
        // The loudest tone now asks for more than the surface will give, which is what the cap is for:
        // the scale reaches past every surface's limit so that no surface is the thing holding it back.
        assertTrue(0.12f*Tint.TONES[Tint.TONES.length-1]>0.6f);
        assertEquals(0.6f,Tint.weigh(0.12f,Tint.TONES.length-1,0.6f),0.0001f);
        // However loud the tone, the cap is the cap.
        assertEquals(0.6f,Tint.weigh(0.5f,Tint.TONES.length-1,0.6f),0.0001f);
        assertEquals(0f,Tint.weigh(-1f,Tint.FIRST_TONE,0.6f),0.0001f);
    }

    @Test public void aToneFromAnotherBuildIsSimplyTheNearestEnd() {
        assertEquals(Tint.weigh(0.2f,0,1f),Tint.weigh(0.2f,-5,1f),0.0001f);
        assertEquals(Tint.weigh(0.2f,Tint.TONES.length-1,1f),Tint.weigh(0.2f,99,1f),0.0001f);
    }

    @Test public void eightColoursAreEightDifferentColours() {
        // Told apart by hue rather than by shade: no two of them sit within a hair of each other.
        java.util.Set<Integer> seen=new java.util.HashSet<>();
        for(int colour=1;colour<Tint.count();colour++) {
            int light=Tint.of(colour,false), dark=Tint.of(colour,true);
            assertTrue("colour "+colour+" repeats",seen.add(light)&&seen.add(dark));
            int red=(light>>16)&255, green=(light>>8)&255, blue=light&255;
            int most=Math.max(red,Math.max(green,blue)), least=Math.min(red,Math.min(green,blue));
            assertTrue(Tint.NAMES[colour]+" is not a colour",most-least>60||colour==0);
        }
    }
}
