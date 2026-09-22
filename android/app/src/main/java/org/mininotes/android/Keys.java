// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * This device's own two keys — one to sign with, one to agree with — made once and kept.
 *
 * <p>Android's Keystore cannot hold an ECDH key below API 31 and this app supports API 28, so the pair lives
 * in the app's own storage sealed under an AES-GCM key that the Keystore <em>does</em> hold. The sealed file
 * is useless off the phone, and the app's storage is already excluded from OS backup and cloud transfer.
 *
 * <p>Everything here blocks on disk or on the Keystore: it belongs on {@link Background}, not the interface
 * thread. The keys themselves never leave this class except as public keys.
 */
final class Keys {
    private static final String KEYSTORE="AndroidKeyStore", SEAL="mininotes.seal";
    private static final String SIGNING="signing.key", AGREEMENT="agreement.key";
    private static final int NONCE=12, TAG=128;

    private final Context where;
    private KeyPair signing, agreement;

    Keys(Context c){where=c.getApplicationContext();}

    /** The one set this process has, for the screen and for whatever listens when there is no screen. */
    private static Keys only;
    static synchronized Keys of(Context c) {
        if(only==null)only=new Keys(c);
        return only;
    }

    /** The pair this device signs with: what a message is signed by, and what its fingerprint is taken from. */
    synchronized KeyPair signing() throws GeneralSecurityException, IOException {
        if(signing==null)signing=kept(SIGNING);
        return signing;
    }

    /** The pair others seal to: their message is agreed with this and can be opened by nothing else. */
    synchronized KeyPair agreement() throws GeneralSecurityException, IOException {
        if(agreement==null)agreement=kept(AGREEMENT);
        return agreement;
    }

    /** The line this device hands another so it can be reached and sealed to. */
    String line(String name,String address) throws GeneralSecurityException, IOException {
        return line(name,address,"",false);
    }

    /** The same line, offering one thing at one level. */
    String line(String name,String address,String offer,boolean writes)
            throws GeneralSecurityException, IOException {
        return line(name,address,offer,writes,"","");
    }

    String line(String name,String address,String offer,boolean writes,String scope,String target)
            throws GeneralSecurityException, IOException {
        // The short form of each key. Ninety-one bytes apiece became thirty-three, which takes about a
        // hundred and eighty characters out of the picture somebody has to hold a camera against.
        return Pairing.write(name,address,Point.shorten(agreement().getPublic()),
            Point.shorten(signing().getPublic()),offer,writes,scope,target);
    }

    /** A public key as it arrived from somebody else: refused rather than trusted if it is not a key. */
    /** A public key as it arrived, long form or short. See {@link Point}. */
    static PublicKey publicKey(byte[] encoded) throws GeneralSecurityException {
        return Point.read(encoded);
    }

    /** The pair under this name, made and sealed the first time it is asked for. */
    private KeyPair kept(String name) throws GeneralSecurityException, IOException {
        File file=new File(shed(),name);
        if(file.isFile()) {
            byte[] sealed=Files.readAllBytes(file.toPath());
            byte[] raw=unseal(sealed);
            PrivateKey priv=KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(clip(raw,4,length(raw))));
            PublicKey pub=publicKey(clip(raw,4+length(raw),raw.length-4-length(raw)));
            return new KeyPair(pub,priv);
        }
        KeyPair made=Envelope.keys();
        byte[] priv=made.getPrivate().getEncoded(), pub=made.getPublic().getEncoded();
        byte[] both=new byte[4+priv.length+pub.length];
        both[0]=(byte)(priv.length>>>24);both[1]=(byte)(priv.length>>>16);
        both[2]=(byte)(priv.length>>>8);both[3]=(byte)priv.length;
        System.arraycopy(priv,0,both,4,priv.length);
        System.arraycopy(pub,0,both,4+priv.length,pub.length);
        byte[] sealed=seal(both);
        File landing=new File(shed(),name+".new");
        try(FileOutputStream out=new FileOutputStream(landing)){out.write(sealed);out.getFD().sync();}
        if(!landing.renameTo(file))throw new IOException("Could not keep this device's key");
        return made;
    }

    private static int length(byte[] raw) {
        if(raw.length<4)throw new IllegalStateException("This device's key is damaged");
        int n=((raw[0]&255)<<24)|((raw[1]&255)<<16)|((raw[2]&255)<<8)|(raw[3]&255);
        if(n<0||n>raw.length-4)throw new IllegalStateException("This device's key is damaged");
        return n;
    }
    private static byte[] clip(byte[] raw,int from,int many) {
        if(from<0||many<0||from+many>raw.length)throw new IllegalStateException("This device's key is damaged");
        byte[] part=new byte[many];System.arraycopy(raw,from,part,0,many);return part;
    }

    private File shed() {
        File shed=new File(where.getFilesDir(),"keys");
        if(!shed.isDirectory()&&!shed.mkdirs())throw new IllegalStateException("Could not make room for this device's keys");
        return shed;
    }

    /** The AES-GCM key the Keystore holds for us, made on first use and never leaving it. */
    private SecretKey sealer() throws GeneralSecurityException, IOException {
        KeyStore store=KeyStore.getInstance(KEYSTORE);
        store.load(null);
        KeyStore.Entry there=store.getEntry(SEAL,null);
        if(there instanceof KeyStore.SecretKeyEntry)return ((KeyStore.SecretKeyEntry)there).getSecretKey();
        KeyGenerator maker=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,KEYSTORE);
        maker.init(new KeyGenParameterSpec.Builder(SEAL,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return maker.generateKey();
    }

    private byte[] seal(byte[] raw) throws GeneralSecurityException, IOException {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,sealer());
        byte[] nonce=cipher.getIV(), body=cipher.doFinal(raw);
        if(nonce.length!=NONCE)throw new GeneralSecurityException("Unexpected nonce");
        byte[] sealed=new byte[NONCE+body.length];
        System.arraycopy(nonce,0,sealed,0,NONCE);
        System.arraycopy(body,0,sealed,NONCE,body.length);
        return sealed;
    }

    private byte[] unseal(byte[] sealed) throws GeneralSecurityException, IOException {
        if(sealed.length<=NONCE)throw new GeneralSecurityException("This device's key is damaged");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,sealer(),new GCMParameterSpec(TAG,sealed,0,NONCE));
        return cipher.doFinal(sealed,NONCE,sealed.length-NONCE);
    }
}
