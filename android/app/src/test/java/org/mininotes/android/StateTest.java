package org.mininotes.android;
import org.junit.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class StateTest {
    private static Map<String,Boolean> reaching(Object... pairs) {
        Map<String,Boolean> audience=new LinkedHashMap<>();
        for(int at=0;at<pairs.length;at+=2)audience.put((String)pairs[at],(Boolean)pairs[at+1]);
        return audience;
    }

    @Test public void nothingSharedStaysOnThisDevice() {
        assertEquals(Sharing.State.HERE,Sharing.state(reaching(),false));
        assertEquals(Sharing.State.HERE,Sharing.state(null,false));
        assertEquals("On this device only",Sharing.describe(Sharing.State.HERE,0));
    }
    @Test public void onlyYourOwnDevicesIsTwoWay() {
        assertEquals(Sharing.State.DEVICES,Sharing.state(reaching("MxA",true,"MxB",true),false));
        assertEquals("On 2 devices of yours",Sharing.describe(Sharing.State.DEVICES,2));
    }
    @Test public void oneAddressThatIsNotYoursMakesItSharedWithOthers() {
        assertEquals(Sharing.State.OTHERS,Sharing.state(reaching("MxA",true,"MxAna",false),false));
        assertEquals(Sharing.State.OTHERS,Sharing.state(reaching("MxAna",false),false));
    }
    /** What arrived may be from your own tablet as easily as from a friend, so it is named as a device. */
    @Test public void whatArrivedSaysSoWhateverItsAudience() {
        assertEquals(Sharing.State.THEIRS,Sharing.state(reaching(),true));
        assertEquals(Sharing.State.THEIRS,Sharing.state(reaching("MxAna",false),true));
        assertEquals("Shared with you by another device",Sharing.describe(Sharing.State.THEIRS,0));
    }
    @Test public void everyStateHasItsOwnMark() {
        String here=Sharing.mark(Sharing.State.HERE), devices=Sharing.mark(Sharing.State.DEVICES);
        String others=Sharing.mark(Sharing.State.OTHERS), theirs=Sharing.mark(Sharing.State.THEIRS);
        for(String mark:new String[]{here,devices,others,theirs})assertEquals(1,mark.length());
        assertNotEquals(here,devices);assertNotEquals(devices,others);
        assertNotEquals(others,theirs);assertNotEquals(theirs,here);
    }
    @Test public void oneAddressReadsAsOne() {
        assertEquals("Shared with one address",Sharing.describe(Sharing.State.OTHERS,1));
        assertEquals("On one device of yours",Sharing.describe(Sharing.State.DEVICES,1));
    }

    @Test public void whereAThingStandsIsSaidInWords() {
        assertNull("a thing on this phone alone says nothing",Sharing.says(Sharing.State.HERE,0,false));
        assertEquals("Shared · up to date",Sharing.says(Sharing.State.OTHERS,0,false));
        assertEquals("Shared · 2 waiting",Sharing.says(Sharing.State.OTHERS,2,false));
        assertEquals("My devices · up to date",Sharing.says(Sharing.State.DEVICES,0,false));
        assertEquals("From another device",Sharing.says(Sharing.State.THEIRS,0,false));
    }

    @Test public void aNarrowTileTakesTheShortOfIt() {
        assertEquals("Shared",Sharing.says(Sharing.State.OTHERS,0,true));
        assertEquals("Shared · 2",Sharing.says(Sharing.State.OTHERS,2,true));
        assertEquals("My devices",Sharing.says(Sharing.State.DEVICES,0,true));
        assertNull(Sharing.says(Sharing.State.HERE,3,true));
    }
}
