// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One page of the pad, sealed for one address. The app encrypts and signs it itself rather than trusting
 * the transport, so a node that relays the message — including the recipient's own Core — carries bytes it
 * cannot read and cannot alter undetected.
 *
 * <p>Ephemeral ECDH (P-256) to the recipient's agreement key, HKDF-SHA256, AES-256-GCM, then ECDSA over the
 * whole thing by the sender. The sender's public key travels inside so the signature can be checked, which
 * proves the message is intact and self-consistent — <b>not</b> that the sender is anyone you know. Deciding
 * whether {@link Opened#sender} is a device you paired with or a person you chose is the caller's job.
 *
 * <p>Holds no Android types: the wire format, the bounds and the failure behaviour are unit tested.
 */
final class Envelope {
    /** The format, and the first four bytes on the wire. A later format gets a later magic, never a silent change. */
    static final byte[] MAGIC={'M','N','P','1'};
    static final String CURVE="secp256r1";
    /** A page is text; this bounds what a stranger can make us allocate while parsing. */
    static final int MAX_TEXT=200000, MAX_FIELD=8192;

    /** What came out of a sealed page once it verified. */
    static final class Opened {
        final byte[] page, text, sender;
        final long revision, moment;
        /** {@code sender} is the SHA-256 of the sender's signing key: match it against a known device yourself. */
        Opened(byte[] page,long revision,long moment,byte[] text,byte[] sender) {
            this.page=page;this.revision=revision;this.moment=moment;this.text=text;this.sender=sender;
        }
    }

    static KeyPair keys() throws GeneralSecurityException {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(CURVE));
        return generator.generateKeyPair();
    }

    /** SHA-256 of an encoded public key: what a person compares, and what a device list stores. */
    static byte[] fingerprint(PublicKey key) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
    }

    /**
     * The six digits both devices show while pairing. Same for either order of the pair, so each side can read
     * it aloud, and different for any other pair: a relay that swapped a key cannot make the digits agree.
     */
    static String code(PublicKey one,PublicKey other) throws GeneralSecurityException {
        byte[] a=fingerprint(one),b=fingerprint(other);
        boolean first=compare(a,b)<=0;
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(join(first?a:b,first?b:a));
        int digits=((digest[0]&0x7f)<<16|(digest[1]&255)<<8|(digest[2]&255))%1000000;
        return String.format(Locale.ROOT,"%06d",digits);
    }

    static byte[] seal(byte[] page,long revision,long moment,byte[] text,KeyPair sender,PublicKey recipient) throws GeneralSecurityException {
        if(page.length!=16)throw new IllegalArgumentException("A page id is 16 bytes");
        if(revision<0||moment<0)throw new IllegalArgumentException("Revision and moment count up from zero");
        if(text.length>MAX_TEXT)throw new IllegalArgumentException("Page is too large to share");
        KeyPair ephemeral=keys();
        byte[] ephemeralKey=ephemeral.getPublic().getEncoded(),senderKey=sender.getPublic().getEncoded();
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        // Fresh key material per message: the same page sealed twice shares no key and no nonce.
        byte[] secret=agree(ephemeral.getPrivate(),recipient);
        byte[] key=hkdf(secret,ephemeralKey,MAGIC);
        byte[] header=header(page,revision,moment,senderKey,ephemeralKey,nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        cipher.updateAAD(header);
        byte[] sealed=cipher.doFinal(text);
        Signature signature=Signature.getInstance("SHA256withECDSA");
        signature.initSign(sender.getPrivate());signature.update(header);signature.update(sealed);
        return join(header,field(sealed),field(signature.sign()));
    }

    /** Verifies and decrypts, or throws. Nothing partly parsed is ever returned to a caller. */
    static Opened open(byte[] message,PrivateKey recipient) throws GeneralSecurityException {
        try {
            DataInputStream in=new DataInputStream(new java.io.ByteArrayInputStream(message));
            byte[] magic=new byte[MAGIC.length];in.readFully(magic);
            if(!Arrays.equals(magic,MAGIC))throw new GeneralSecurityException("Not a Mininotes page");
            byte[] page=new byte[16];in.readFully(page);
            long revision=in.readLong(),moment=in.readLong();
            byte[] senderKey=field(in),ephemeralKey=field(in),nonce=field(in);
            if(nonce.length!=12)throw new GeneralSecurityException("Wrong nonce length");
            if(revision<0||moment<0)throw new GeneralSecurityException("Negative revision");
            byte[] sealed=field(in),signed=field(in);
            if(sealed.length>MAX_TEXT+64)throw new GeneralSecurityException("Page is too large to accept");
            byte[] header=header(page,revision,moment,senderKey,ephemeralKey,nonce);
            PublicKey sender=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(senderKey));
            Signature signature=Signature.getInstance("SHA256withECDSA");
            signature.initVerify(sender);signature.update(header);signature.update(sealed);
            if(!signature.verify(signed))throw new GeneralSecurityException("Signature does not match");
            PublicKey ephemeral=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(ephemeralKey));
            byte[] key=hkdf(agree(recipient,ephemeral),ephemeralKey,MAGIC);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(header);
            return new Opened(page,revision,moment,cipher.doFinal(sealed),fingerprint(sender));
        } catch(IOException|IllegalArgumentException e) {
            // A truncated or absurd length is a rejection, never a crash on a stranger's bytes.
            throw new GeneralSecurityException("Malformed page",e);
        }
    }

    private static byte[] agree(PrivateKey mine,PublicKey theirs) throws GeneralSecurityException {
        KeyAgreement agreement=KeyAgreement.getInstance("ECDH");
        agreement.init(mine);agreement.doPhase(theirs,true);
        return agreement.generateSecret();
    }

    /** HKDF-SHA256, extract then one expand block: the shared secret is never used as a key directly. */
    private static byte[] hkdf(byte[] secret,byte[] salt,byte[] info) throws GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt,"HmacSHA256"));
        byte[] extracted=mac.doFinal(secret);
        mac.init(new SecretKeySpec(extracted,"HmacSHA256"));
        mac.update(info);mac.update((byte)1);
        return mac.doFinal();
    }

    private static byte[] header(byte[] page,long revision,long moment,byte[] senderKey,byte[] ephemeralKey,byte[] nonce) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(DataOutputStream data=new DataOutputStream(out)) {
            data.write(MAGIC);data.write(page);data.writeLong(revision);data.writeLong(moment);
            data.writeInt(senderKey.length);data.write(senderKey);
            data.writeInt(ephemeralKey.length);data.write(ephemeralKey);
            data.writeInt(nonce.length);data.write(nonce);
        } catch(IOException e){throw new IllegalStateException("Cannot build an envelope in memory",e);}
        return out.toByteArray();
    }

    private static byte[] field(byte[] value) {
        byte[] out=new byte[4+value.length];
        out[0]=(byte)(value.length>>>24);out[1]=(byte)(value.length>>>16);out[2]=(byte)(value.length>>>8);out[3]=(byte)value.length;
        System.arraycopy(value,0,out,4,value.length);return out;
    }
    private static byte[] field(DataInputStream in) throws IOException {
        int length=in.readInt();
        if(length<0||length>MAX_TEXT+MAX_FIELD)throw new IOException("Field length out of bounds");
        byte[] value=new byte[length];in.readFully(value);return value;
    }
    private static byte[] join(byte[]... parts) {
        int size=0;for(byte[] part:parts)size+=part.length;
        byte[] all=new byte[size];int at=0;
        for(byte[] part:parts){System.arraycopy(part,0,all,at,part.length);at+=part.length;}
        return all;
    }
    private static int compare(byte[] a,byte[] b) {
        for(int i=0;i<Math.min(a.length,b.length);i++){int d=(a[i]&255)-(b[i]&255);if(d!=0)return d;}
        return a.length-b.length;
    }
    private Envelope(){}
}
