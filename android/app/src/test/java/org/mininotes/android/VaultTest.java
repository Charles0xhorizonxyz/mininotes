package org.mininotes.android;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class VaultTest {
    /** Fewer rounds than the real lock, so the tests are quick; the file carries the number either way. */
    private static final int QUICK=20_000;

    @Test public void thePasswordAndTheWordsBothOpenTheSameKey() throws Exception {
        Vault.Made made=Vault.make("correct horse".toCharArray(),QUICK);
        assertEquals(Vault.WORDS,made.words.size());assertEquals(Vault.KEY,made.key.length);
        assertArrayEquals(made.key,Vault.open(made.kept,"correct horse".toCharArray()));
        assertArrayEquals(made.key,Vault.recover(made.kept,String.join(" ",made.words)));
        // However the words are typed: capitals, extra spaces.
        assertArrayEquals(made.key,Vault.recover(made.kept,"  "+String.join("   ",made.words).toUpperCase()+" "));
    }

    @Test public void aWrongPasswordOrWrongWordsOpenNothing() throws Exception {
        Vault.Made made=Vault.make("correct horse".toCharArray(),QUICK);
        assertThrows(Vault.Refused.class,()->Vault.open(made.kept,"Correct horse".toCharArray()));
        assertThrows(Vault.Refused.class,()->Vault.open(made.kept,new char[0]));
        assertThrows(IllegalArgumentException.class,()->Vault.make(new char[0],QUICK));
        Vault.Made other=Vault.make("x".toCharArray(),QUICK);
        assertThrows(Vault.Refused.class,()->Vault.recover(made.kept,String.join(" ",other.words)));
        assertThrows(Vault.Refused.class,()->Vault.recover(made.kept,"only three words"));
        String[] swapped=made.words.toArray(new String[0]);String first=swapped[0];swapped[0]=swapped[1];swapped[1]=first;
        if(!Arrays.asList(swapped).equals(made.words))assertThrows(Vault.Refused.class,()->Vault.recover(made.kept,String.join(" ",swapped)));
    }

    @Test public void aNewPasswordKeepsTheKeyAndTheWords() throws Exception {
        Vault.Made made=Vault.make("old".toCharArray(),QUICK);
        byte[] kept=Vault.newPassword(made.kept,made.key,"new".toCharArray());
        assertArrayEquals(made.key,Vault.open(kept,"new".toCharArray()));
        assertThrows(Vault.Refused.class,()->Vault.open(kept,"old".toCharArray()));
        assertArrayEquals(made.key,Vault.recover(kept,String.join(" ",made.words)));
    }

    @Test public void aDamagedOrForeignFileIsRefused() throws Exception {
        Vault.Made made=Vault.make("pw".toCharArray(),QUICK);
        // A byte inside the password's copy of the key: that copy no longer opens, and nothing else is tried.
        byte[] bent=made.kept.clone();bent[Vault.MAGIC.length+4+2+Vault.SALT+Vault.NONCE+3]^=1;
        assertThrows(Vault.Refused.class,()->Vault.open(bent,"pw".toCharArray()));
        assertThrows(Vault.Refused.class,()->Vault.open("not a lock".getBytes(),"pw".toCharArray()));
        assertThrows(Vault.Refused.class,()->Vault.open(null,"pw".toCharArray()));
        assertThrows(Vault.Refused.class,()->Vault.open(Arrays.copyOf(made.kept,10),"pw".toCharArray()));
    }

    @Test public void theWordsCanBeShownAgainOnlyWithTheKey() throws Exception {
        Vault.Made made=Vault.make("pw-for-words".toCharArray(),QUICK);
        assertEquals(made.words,Vault.words(made.kept,made.key));
        byte[] renewed=Vault.newPassword(made.kept,made.key,"another-pw".toCharArray());
        assertEquals(made.words,Vault.words(renewed,Vault.open(renewed,"another-pw".toCharArray())));
        byte[] wrong=made.key.clone();wrong[0]^=1;
        assertThrows(Vault.Refused.class,()->Vault.words(made.kept,wrong));
    }

    /** The written-out PBKDF2 gives what Java's own does, byte for byte: a lock made on the phone opens on the PC. */
    @Test public void theDerivationIsStandardPbkdf2() throws Exception {
        byte[] salt="0123456789abcdef".getBytes();
        for(String password:new String[]{"password","correct horse battery","çay ☕ 日本語 é","a much longer password than the sixty-four bytes of one SHA-256 block, to be hashed first"})
            for(int rounds:new int[]{1,2,3,1000}) {
                javax.crypto.spec.PBEKeySpec spec=new javax.crypto.spec.PBEKeySpec(password.toCharArray(),salt,rounds,256);
                byte[] standard=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                assertArrayEquals(password+" x"+rounds,standard,Vault.pbkdf2(password.toCharArray(),salt,rounds));
            }
    }

    @Test public void theKeyIsHandedToSqlCipherRaw() {
        assertEquals("x'00ff10'",Vault.pragma(new byte[]{0,(byte)0xff,0x10}));
    }
}
