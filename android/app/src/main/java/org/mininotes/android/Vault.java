// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import com.eurobuddha.maxima.core.identity.Bip39;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The lock on the notebook, when its owner wants one.
 *
 * <p>One key opens the notebook: thirty-two random bytes, made once. It is never written down as it is.
 * It is kept twice, each time sealed: once with the password, once with twelve recovery words. Either
 * opens it. So a forgotten password is not a lost notebook - the words open it and a new password is
 * set - and changing the password seals the same key again without touching the words or the notebook.
 *
 * <p>Nobody else holds anything. There is no server and no email: the words are the only way back, which
 * is why they are shown once, when the lock is set, and asked for back before it is.
 *
 * <p>Holds no Android types; the format and every refusal are unit tested.
 */
final class Vault {
    static final byte[] MAGIC={'M','N','V','1'};
    /** Slow on purpose: every guess at a password costs this much. Kept in the file, so it can rise later. */
    static final int ROUNDS=310_000;
    static final int WORDS=12, KEY=32, SALT=16, NONCE=12;
    private static final SecureRandom RANDOM=new SecureRandom();

    /** Wrong password, wrong words, or a file that is not a lock. One message, so neither is hinted at. */
    static final class Refused extends Exception {
        Refused(String why){super(why);}
    }

    /** A new lock: what to keep on disk, the key it guards, and the words to show once. */
    static final class Made {
        final byte[] kept,key; final List<String> words;
        Made(byte[] kept,byte[] key,List<String> words){this.kept=kept;this.key=key;this.words=words;}
    }

    private Vault(){}

    /** A lock for a new or existing notebook. The key is new; the notebook is encrypted with it after. */
    static Made make(char[] password,int rounds) throws GeneralSecurityException {
        if(password==null||password.length==0)throw new IllegalArgumentException("A password cannot be empty.");
        byte[] key=new byte[KEY];RANDOM.nextBytes(key);
        List<String> words=Bip39.generate(WORDS);
        return new Made(write(rounds,seal(key,password,rounds),seal(key,phrase(words),rounds),keep(key,String.join(" ",words).toLowerCase(java.util.Locale.ROOT))),key,words);
    }
    static Made make(char[] password) throws GeneralSecurityException{return make(password,ROUNDS);}

    /** The key, from the password. */
    static byte[] open(byte[] kept,char[] password) throws Refused {
        if(password==null||password.length==0)throw new Refused("That did not open it.");
        Parts parts=read(kept);
        return unseal(parts.byPassword,password,parts.rounds);
    }

    /** The key, from the recovery words - in any case, with any spacing, as long as they are the twelve. */
    static byte[] recover(byte[] kept,String words) throws Refused {
        List<String> said;
        try {
            said=Arrays.asList(Bip39.cleanSeedPhrase(words==null?"":words).split(" "));
            if(said.size()!=WORDS||!Bip39.checksumValid(said))throw new Refused("Those are not the twelve recovery words.");
        } catch(IllegalArgumentException notWords){throw new Refused("Those are not the twelve recovery words.");}
        Parts parts=read(kept);
        return unseal(parts.byWords,phrase(said),parts.rounds);
    }

    /** A new password for the same key. The words, and so the notebook, stay as they were. */
    static byte[] newPassword(byte[] kept,byte[] key,char[] password) throws Refused,GeneralSecurityException {
        Parts parts=read(kept);
        return write(parts.rounds,seal(key,password,parts.rounds),parts.byWords,parts.wordsByKey);
    }

    /**
     * The twelve words again, for somebody who has the notebook open and says the password once more. They
     * are kept sealed with the notebook's own key, so they come out only for whoever already holds it.
     */
    static List<String> words(byte[] kept,byte[] key) throws Refused {
        Parts parts=read(kept);
        if(parts.wordsByKey.length==0)throw new Refused("This lock was made without its words kept.");
        try {
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(parts.wordsByKey,0,NONCE)));
            aes.updateAAD(MAGIC);
            String said=new String(aes.doFinal(parts.wordsByKey,NONCE,parts.wordsByKey.length-NONCE),java.nio.charset.StandardCharsets.UTF_8);
            return Arrays.asList(said.split(" "));
        } catch(GeneralSecurityException wrong){throw new Refused("That did not open it.");}
    }

    private static byte[] keep(byte[] key,String words) throws GeneralSecurityException {
        byte[] nonce=new byte[NONCE];RANDOM.nextBytes(nonce);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        aes.updateAAD(MAGIC);
        byte[] sealed=aes.doFinal(words.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] all=new byte[NONCE+sealed.length];System.arraycopy(nonce,0,all,0,NONCE);System.arraycopy(sealed,0,all,NONCE,sealed.length);
        return all;
    }

    /** The key written the way SQLCipher takes a raw key, so it is never run through a second derivation. */
    static String pragma(byte[] key) {
        StringBuilder hex=new StringBuilder("x'");
        for(byte b:key)hex.append(String.format("%02x",b&0xff));
        return hex.append("'").toString();
    }

    private static char[] phrase(List<String> words){return Bip39.canonical(words).toCharArray();}

    // ---- sealing one copy of the key -------------------------------------------------------------------

    private static byte[] seal(byte[] key,char[] secret,int rounds) throws GeneralSecurityException {
        byte[] salt=new byte[SALT],nonce=new byte[NONCE];RANDOM.nextBytes(salt);RANDOM.nextBytes(nonce);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE,derive(secret,salt,rounds),new GCMParameterSpec(128,nonce));
        aes.updateAAD(MAGIC);
        byte[] sealed=aes.doFinal(key);
        byte[] all=new byte[SALT+NONCE+sealed.length];
        System.arraycopy(salt,0,all,0,SALT);System.arraycopy(nonce,0,all,SALT,NONCE);
        System.arraycopy(sealed,0,all,SALT+NONCE,sealed.length);
        return all;
    }

    private static byte[] unseal(byte[] all,char[] secret,int rounds) throws Refused {
        if(all.length!=SALT+NONCE+KEY+16)throw new Refused("This is not a Mininotes lock.");
        try {
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE,derive(secret,Arrays.copyOfRange(all,0,SALT),rounds),
                new GCMParameterSpec(128,Arrays.copyOfRange(all,SALT,SALT+NONCE)));
            aes.updateAAD(MAGIC);
            return aes.doFinal(all,SALT+NONCE,all.length-SALT-NONCE);
        } catch(javax.crypto.AEADBadTagException wrong) {
            throw new Refused("That did not open it.");
        } catch(GeneralSecurityException broken) {
            throw new Refused("This lock could not be read.");
        }
    }

    private static SecretKeySpec derive(char[] secret,byte[] salt,int rounds) throws GeneralSecurityException {
        return new SecretKeySpec(pbkdf2(secret,salt,rounds),"AES");
    }

    /**
     * PBKDF2-HMAC-SHA256, one 32-byte block, exactly as the standard defines it and as Java's own
     * SecretKeyFactory computes it (a test holds the two together). Written out because Android's own
     * factory is a slow one: it took minutes where this, on the phone's native HMAC, takes a moment.
     */
    static byte[] pbkdf2(char[] secret,byte[] salt,int rounds) throws GeneralSecurityException {
        // The password as UTF-8, which is what the standard factory feeds to HMAC too.
        java.nio.ByteBuffer encoded=java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(secret));
        byte[] exact=new byte[encoded.remaining()];encoded.get(exact);
        if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        try {
            // The first round through the platform's HMAC; every later one hashes a 32-byte value, which is
            // two SHA-256 blocks from states fixed by the password - done here, with nothing allocated.
            mac.init(new SecretKeySpec(exact,"HmacSHA256"));
            mac.update(salt);mac.update(new byte[]{0,0,0,1});
            byte[] first=mac.doFinal(),out=first.clone();
            byte[] block=exact.length>64?java.security.MessageDigest.getInstance("SHA-256").digest(exact):exact;
            int[] inner=padState(block,0x36),outer=padState(block,0x5c);
            int[] u=new int[8],w=new int[64],s=new int[8],acc=new int[8];
            for(int k=0;k<8;k++){u[k]=((first[4*k]&0xff)<<24)|((first[4*k+1]&0xff)<<16)|((first[4*k+2]&0xff)<<8)|(first[4*k+3]&0xff);acc[k]=u[k];}
            for(int i=1;i<rounds;i++) {
                // inner = SHA256(ipad-state, u || 0x80 || zeros || bit length of 64+32 bytes)
                System.arraycopy(inner,0,s,0,8);lastBlock(u,w);compress(s,w);
                System.arraycopy(s,0,u,0,8);
                System.arraycopy(outer,0,s,0,8);lastBlock(u,w);compress(s,w);
                System.arraycopy(s,0,u,0,8);
                for(int k=0;k<8;k++)acc[k]^=u[k];
            }
            for(int k=0;k<8;k++){out[4*k]=(byte)(acc[k]>>>24);out[4*k+1]=(byte)(acc[k]>>>16);out[4*k+2]=(byte)(acc[k]>>>8);out[4*k+3]=(byte)acc[k];}
            return out;
        } finally{Arrays.fill(exact,(byte)0);}
    }

    private static final int[] K256={
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,
        0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,
        0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,
        0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    private static final int[] IV256={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};

    /** The SHA-256 state after one block: the key padded to 64 bytes and XORed with the pad byte. */
    private static int[] padState(byte[] key,int pad) {
        int[] s=IV256.clone(),w=new int[64];
        for(int k=0;k<16;k++){int v=0;for(int b=0;b<4;b++){int at=4*k+b;v=(v<<8)|(((at<key.length?key[at]:0)^pad)&0xff);}w[k]=v;}
        compress(s,w);return s;
    }

    /** A 32-byte message after 64 bytes already hashed: the value, the end mark, and 96 bytes as bits. */
    private static void lastBlock(int[] value,int[] w) {
        System.arraycopy(value,0,w,0,8);w[8]=0x80000000;for(int k=9;k<15;k++)w[k]=0;w[15]=(64+32)*8;
    }

    private static void compress(int[] s,int[] w) {
        for(int t=16;t<64;t++) {
            int a=w[t-15],b=w[t-2];
            int s0=Integer.rotateRight(a,7)^Integer.rotateRight(a,18)^(a>>>3),s1=Integer.rotateRight(b,17)^Integer.rotateRight(b,19)^(b>>>10);
            w[t]=w[t-16]+s0+w[t-7]+s1;
        }
        int a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
        for(int t=0;t<64;t++) {
            int t1=h+(Integer.rotateRight(e,6)^Integer.rotateRight(e,11)^Integer.rotateRight(e,25))+((e&f)^(~e&g))+K256[t]+w[t];
            int t2=(Integer.rotateRight(a,2)^Integer.rotateRight(a,13)^Integer.rotateRight(a,22))+((a&b)^(a&c)^(b&c));
            h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
        }
        s[0]+=a;s[1]+=b;s[2]+=c;s[3]+=d;s[4]+=e;s[5]+=f;s[6]+=g;s[7]+=h;
    }

    // ---- the file ------------------------------------------------------------------------------------------

    private static final class Parts {
        final int rounds; final byte[] byPassword,byWords,wordsByKey;
        Parts(int rounds,byte[] byPassword,byte[] byWords,byte[] wordsByKey){this.rounds=rounds;this.byPassword=byPassword;this.byWords=byWords;this.wordsByKey=wordsByKey;}
    }

    private static byte[] write(int rounds,byte[] byPassword,byte[] byWords,byte[] wordsByKey) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.write(MAGIC);out.writeInt(rounds);
            out.writeShort(byPassword.length);out.write(byPassword);out.writeShort(byWords.length);out.write(byWords);
            out.writeShort(wordsByKey.length);out.write(wordsByKey);
            out.flush();return bytes.toByteArray();
        } catch(IOException never){throw new IllegalStateException(never);}
    }

    private static Parts read(byte[] kept) throws Refused {
        if(kept==null||kept.length<MAGIC.length+4)throw new Refused("This is not a Mininotes lock.");
        for(int i=0;i<MAGIC.length;i++)if(kept[i]!=MAGIC[i])throw new Refused("This is not a Mininotes lock.");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(kept,MAGIC.length,kept.length-MAGIC.length))) {
            int rounds=in.readInt();
            if(rounds<10_000||rounds>10_000_000)throw new Refused("This lock could not be read.");
            byte[] a=new byte[in.readUnsignedShort()];in.readFully(a);
            byte[] b=new byte[in.readUnsignedShort()];in.readFully(b);
            byte[] c=new byte[0];if(in.available()>=2){c=new byte[in.readUnsignedShort()];in.readFully(c);}
            return new Parts(rounds,a,b,c);
        } catch(IOException broken){throw new Refused("This lock could not be read.");}
    }
}
