// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A public key as the thirty-three bytes it really is, and back again. */
public class PointTest {

    /** The whole point: a key survives being shortened, whichever half of the curve it is on. */
    @Test public void aKeySurvivesTheRoundTrip() throws Exception {
        // Enough keys that both parities turn up: y is even about half the time.
        for(int at=0;at<40;at++) {
            KeyPair pair=Envelope.keys();
            byte[] shortened=Point.shorten(pair.getPublic());
            assertEquals(Point.SHORT,shortened.length);
            PublicKey back=Point.widen(shortened);
            assertEquals(((ECPublicKey)pair.getPublic()).getW(),((ECPublicKey)back).getW());
            assertArrayEquals(pair.getPublic().getEncoded(),back.getEncoded());
        }
    }

    /** Both halves of the curve are actually exercised by the loop above. */
    @Test public void bothParitiesHappen() throws Exception {
        boolean even=false, odd=false;
        for(int at=0;at<40&&!(even&&odd);at++) {
            int first=Point.shorten(Envelope.keys().getPublic())[0]&0xff;
            if(first==0x02)even=true; else if(first==0x03)odd=true; else fail("bad prefix "+first);
        }
        assertTrue("no even y in forty keys",even);
        assertTrue("no odd y in forty keys",odd);
    }

    /** It is worth doing: the short form is a third of the long one. */
    @Test public void theShortFormIsMuchShorter() throws Exception {
        PublicKey key=Envelope.keys().getPublic();
        assertEquals(33,Point.shorten(key).length);
        assertTrue("long form was "+key.getEncoded().length,key.getEncoded().length>=88);
    }

    /** A code made before this still reads. */
    @Test public void theLongFormStillReads() throws Exception {
        PublicKey key=Envelope.keys().getPublic();
        assertArrayEquals(key.getEncoded(),Point.read(key.getEncoded()).getEncoded());
        assertArrayEquals(key.getEncoded(),Point.read(Point.shorten(key)).getEncoded());
    }

    /**
     * A point that is not on the curve is refused.
     *
     * <p>Not tidiness: handing an off-curve point to a key agreement is how a stranger gets the private
     * key it was made with, one bit at a time.
     */
    @Test public void aPointOffTheCurveIsRefused() {
        byte[] notOnIt=new byte[Point.SHORT];
        notOnIt[0]=0x02;
        Arrays.fill(notOnIt,1,Point.SHORT,(byte)0x11);
        try{Point.widen(notOnIt);fail("an off-curve point was accepted");}
        catch(GeneralSecurityException expected){
            assertTrue(expected.getMessage(),expected.getMessage().contains("curve"));
        }
    }

    /** Nonsense of the right length, and of the wrong length, are both refused. */
    @Test public void nonsenseIsRefused() {
        byte[] wrongPrefix=new byte[Point.SHORT];
        wrongPrefix[0]=0x07;
        for(byte[] bad:new byte[][]{wrongPrefix,new byte[0],new byte[32],new byte[34],null}) {
            try{Point.widen(bad);fail("accepted "+(bad==null?"null":bad.length+" bytes"));}
            catch(GeneralSecurityException expected){/* the whole point */}
        }
    }

    /** x at the very top of the field is not quietly wrapped round. */
    @Test public void anXOffTheFieldIsRefused() {
        byte[] huge=new byte[Point.SHORT];
        huge[0]=0x02;
        Arrays.fill(huge,1,Point.SHORT,(byte)0xff);
        try{Point.widen(huge);fail("x past the prime was accepted");}
        catch(GeneralSecurityException expected){/* either reason is a refusal */}
    }

    /** A sealed note still opens when the key it was sealed for travelled in the short form. */
    @Test public void aNoteSealedForAShortenedKeyOpens() throws Exception {
        KeyPair sender=Envelope.keys(), recipient=Envelope.keys();
        PublicKey travelled=Point.read(Point.shorten(recipient.getPublic()));
        byte[] page=new byte[16];
        byte[] sealed=Envelope.seal(page,3,1000L,"hello".getBytes("UTF-8"),sender,travelled);
        Envelope.Opened opened=Envelope.open(sealed,recipient.getPrivate());
        assertEquals("hello",new String(opened.text,"UTF-8"));
        assertEquals(3,opened.revision);
    }
}
