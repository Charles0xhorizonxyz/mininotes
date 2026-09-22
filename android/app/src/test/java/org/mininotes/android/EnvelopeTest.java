package org.mininotes.android;
import org.junit.BeforeClass;
import org.junit.Test;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class EnvelopeTest {
    private static KeyPair me,myTablet,someoneElse,intruder;
    private static final byte[] PAGE=new byte[16];
    private static final byte[] TEXT="Call the garage about the brakes".getBytes(StandardCharsets.UTF_8);

    @BeforeClass public static void keys() throws Exception {
        new SecureRandom().nextBytes(PAGE);
        me=Envelope.keys();myTablet=Envelope.keys();someoneElse=Envelope.keys();intruder=Envelope.keys();
    }
    private static byte[] sealed() throws Exception { return Envelope.seal(PAGE,7,1_700_000_000_000L,TEXT,me,myTablet.getPublic()); }

    @Test public void theAddressItWasSealedForReadsThePage() throws Exception {
        Envelope.Opened opened=Envelope.open(sealed(),myTablet.getPrivate());
        assertArrayEquals(TEXT,opened.text);
        assertArrayEquals(PAGE,opened.page);
        assertEquals(7,opened.revision);
        assertEquals(1_700_000_000_000L,opened.moment);
        assertArrayEquals("The reader must be able to tell which device wrote it",Envelope.fingerprint(me.getPublic()),opened.sender);
    }
    @Test public void anotherAddressCannotRead() throws Exception {
        try{Envelope.open(sealed(),someoneElse.getPrivate());fail("A page opened for the wrong address");}
        catch(GeneralSecurityException expected){}
    }
    @Test public void theSealedBytesDoNotContainThePage() throws Exception {
        String wire=new String(sealed(),StandardCharsets.ISO_8859_1);
        assertFalse("A relaying node must not be able to read the page",wire.contains("Call the garage"));
    }
    @Test public void changingAnyByteBreaksTheSeal() throws Exception {
        byte[] message=sealed();
        for(int at:new int[]{4,20,40,120,message.length-40,message.length-1}) {
            byte[] tampered=message.clone();tampered[at]^=0x40;
            try{Envelope.open(tampered,myTablet.getPrivate());fail("Accepted a page altered at byte "+at);}
            catch(GeneralSecurityException expected){}
        }
    }
    @Test public void aStrangerCannotPassThemselvesOffAsADeviceYouKnow() throws Exception {
        // The intruder can produce a valid envelope of their own; what they cannot do is carry your fingerprint.
        byte[] forged=Envelope.seal(PAGE,7,1_700_000_000_000L,TEXT,intruder,myTablet.getPublic());
        Envelope.Opened opened=Envelope.open(forged,myTablet.getPrivate());
        assertArrayEquals(Envelope.fingerprint(intruder.getPublic()),opened.sender);
        assertFalse(Arrays.equals(Envelope.fingerprint(me.getPublic()),opened.sender));
    }
    @Test public void thesameTextSealsDifferentlyEveryTime() throws Exception {
        assertFalse("Repeated sends must not be linkable or replayable byte for byte",Arrays.equals(sealed(),sealed()));
    }
    @Test public void anotherFormatIsRefused() throws Exception {
        byte[] message=sealed();message[1]='X';
        try{Envelope.open(message,myTablet.getPrivate());fail("Accepted a foreign format");}
        catch(GeneralSecurityException expected){assertTrue(expected.getMessage().contains("Not a Mininotes page"));}
    }
    @Test public void aTruncatedMessageIsRefusedRatherThanCrashing() throws Exception {
        byte[] message=sealed();
        for(int length:new int[]{0,3,30,message.length/2,message.length-1}) {
            try{Envelope.open(Arrays.copyOf(message,length),myTablet.getPrivate());fail("Accepted "+length+" bytes");}
            catch(GeneralSecurityException expected){}
        }
    }
    @Test public void anAbsurdFieldLengthIsRefusedRatherThanAllocated() throws Exception {
        byte[] message=sealed();
        int at=Envelope.MAGIC.length+16+8+8;                 // the sender key length, the first field on the wire
        message[at]=0x7f;message[at+1]=(byte)0xff;message[at+2]=(byte)0xff;message[at+3]=(byte)0xff;
        try{Envelope.open(message,myTablet.getPrivate());fail("Accepted a two-gigabyte field");}
        catch(GeneralSecurityException expected){}
    }
    @Test public void aPageTooLargeToShareIsRefusedBeforeSending() throws Exception {
        try{Envelope.seal(PAGE,1,1,new byte[Envelope.MAX_TEXT+1],me,myTablet.getPublic());fail("Sealed an oversized page");}
        catch(IllegalArgumentException expected){}
    }
    @Test public void bothDevicesShowTheSamePairingCode() throws Exception {
        assertEquals(Envelope.code(me.getPublic(),myTablet.getPublic()),Envelope.code(myTablet.getPublic(),me.getPublic()));
    }
    @Test public void aSwappedKeyShowsADifferentPairingCode() throws Exception {
        String honest=Envelope.code(me.getPublic(),myTablet.getPublic());
        assertNotEquals("A relay that substituted a key must not be able to match the digits",
            honest,Envelope.code(me.getPublic(),intruder.getPublic()));
        assertEquals(6,honest.length());
    }
}
