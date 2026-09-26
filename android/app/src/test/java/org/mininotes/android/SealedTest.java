package org.mininotes.android;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class SealedTest {
    private static byte[] key(int seed){byte[] k=new byte[32];new Random(seed).nextBytes(k);return k;}
    private static byte[] seal(byte[] key,byte[] plain) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();Sealed.seal(key,new ByteArrayInputStream(plain),out);return out.toByteArray();}
    private static byte[] open(byte[] key,byte[] sealed) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();Sealed.open(key,new ByteArrayInputStream(sealed),out);return out.toByteArray();}

    @Test public void anySizeComesBackAsItWent() throws Exception {
        for(int size:new int[]{0,1,Sealed.PIECE-1,Sealed.PIECE,Sealed.PIECE+1,3*Sealed.PIECE+17}) {
            byte[] plain=new byte[size];new Random(size).nextBytes(plain);
            byte[] sealed=seal(key(1),plain);
            assertTrue(Sealed.is(sealed));
            assertArrayEquals("size "+size,plain,open(key(1),sealed));
        }
    }

    @Test public void theWrongKeyAChangeOrACutIsRefused() throws Exception {
        byte[] plain=new byte[2*Sealed.PIECE+5];new Random(7).nextBytes(plain);
        byte[] sealed=seal(key(1),plain);
        assertThrows(Vault.Refused.class,()->open(key(2),sealed));
        byte[] changed=sealed.clone();changed[changed.length/2]^=1;
        assertThrows(Vault.Refused.class,()->open(key(1),changed));
        // Cut at the end of a whole piece: what is left still reads, but is not marked last.
        int firstPiece=4+8+4+Sealed.PIECE+16;
        assertThrows(Vault.Refused.class,()->open(key(1),Arrays.copyOf(sealed,firstPiece)));
        assertThrows(Vault.Refused.class,()->open(key(1),Arrays.copyOf(sealed,sealed.length-3)));
        assertThrows(Vault.Refused.class,()->open(key(1),"not sealed".getBytes()));
        assertFalse(Sealed.is("PK\3\4".getBytes()));
    }
}
