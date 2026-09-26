package org.mininotes.android;

import static org.junit.Assert.*;

import org.junit.Test;

public class CourierTest {
    private static byte[] bytes(int n,int fill){byte[] b=new byte[n];java.util.Arrays.fill(b,(byte)fill);return b;}

    @Test public void whatIsLeftReadsBackAsItWasLeft() {
        byte[] whom=bytes(32,7),inner=bytes(300,9);
        Courier.Said said=Courier.open(Courier.leave(Courier.NOTE,whom,inner));
        assertEquals(Courier.LEAVE,said.kind);assertEquals(Courier.NOTE,said.sort);
        assertArrayEquals(whom,said.forWhom);assertArrayEquals(inner,said.inner);
        Courier.Said brought=Courier.open(Courier.bring(Courier.ANSWER,inner));
        assertEquals(Courier.BRING,brought.kind);assertEquals(Courier.ANSWER,brought.sort);
        assertEquals(0,brought.forWhom.length);assertArrayEquals(inner,brought.inner);
    }

    @Test public void anythingElseIsNotOne() {
        assertNull(Courier.open(null));
        assertNull(Courier.open(Receipt.wrap(Receipt.TOOK)));
        byte[] good=Courier.leave(Courier.NOTE,bytes(32,1),bytes(40,2));
        // Cut short, one byte too many, a kind nobody knows, a Bring that names somebody: each refused whole.
        assertNull(Courier.open(java.util.Arrays.copyOf(good,good.length-1)));
        assertNull(Courier.open(java.util.Arrays.copyOf(good,good.length+1)));
        byte[] kind=good.clone();kind[4]=9;assertNull(Courier.open(kind));
        byte[] sort=good.clone();sort[5]=9;assertNull(Courier.open(sort));
        byte[] bring=good.clone();bring[4]=Courier.BRING;assertNull(Courier.open(bring));
        byte[] huge=good.clone();huge[6+1+32]=(byte)0x7f;assertNull(Courier.open(huge));
    }

    @Test public void aNoteAndAParcelAreNeverTakenForEachOther() throws Exception {
        byte[] parcel=Parcel.wrap(new Parcel.Sent("c","C","b","B","t","body",true));
        assertNull(Courier.open(parcel));
        assertNull(Parcel.open(Courier.leave(Courier.NOTE,bytes(32,1),bytes(40,2))));
    }

    @Test public void onlyWhatFitsInAnEnvelopeIsCarried() {
        assertTrue(Courier.fits(bytes(Courier.INNER_MOST,1)));
        assertFalse(Courier.fits(bytes(Courier.INNER_MOST+1,1)));
        assertFalse(Courier.fits(new byte[0]));
        try{Courier.leave(Courier.NOTE,bytes(31,1),bytes(10,1));fail();}catch(IllegalArgumentException expected){}
    }

    @Test public void broughtAgainLessAndLessOftenAndNeverNever() {
        long minute=60_000L,now=1_000_000_000L;
        assertTrue(Courier.due(0,0,now));
        assertFalse(Courier.due(now,1,now+minute-1));assertTrue(Courier.due(now,1,now+minute));
        assertFalse(Courier.due(now,2,now+2*minute-1));assertTrue(Courier.due(now,2,now+2*minute));
        assertFalse(Courier.due(now,4,now+8*minute-1));assertTrue(Courier.due(now,4,now+8*minute));
        assertFalse(Courier.due(now,50,now+10*minute-1));assertTrue(Courier.due(now,50,now+10*minute));
        assertTrue("a clock put back",Courier.due(now,50,now-1));
        assertFalse(Courier.expired(now,now+Courier.KEPT_FOR));assertTrue(Courier.expired(now,now+Courier.KEPT_FOR+1));
    }

    @Test public void aParcelSaysItsBuildCarriesAndAnOlderOneDoesNot() throws Exception {
        Parcel.Sent sent=new Parcel.Sent("c","C","b","B","t","body",true,java.util.Collections.emptyList(),"","",true,4,true);
        assertTrue(Parcel.open(Parcel.wrap(sent)).carries);
        assertEquals(4,Parcel.open(Parcel.wrap(sent)).basedOn);
        Parcel.Sent older=new Parcel.Sent("c","C","b","B","t","body",true,java.util.Collections.emptyList(),"","",true,4);
        assertFalse(Parcel.open(Parcel.wrap(older)).carries);
    }
}
