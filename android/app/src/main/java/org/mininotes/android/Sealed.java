// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A file sealed with the notebook's key, when the notebook has a lock: attachments on this device, and
 * backups written while it is locked.
 *
 * <p>In pieces of a megabyte, each sealed on its own (AES-GCM) with its number and whether it is the last
 * written into what is checked. So a file of any size goes through a little memory at a time, and one that
 * has been changed, cut short or had pieces swapped does not open - it is refused, never handed back
 * damaged.
 *
 * <p>Holds no Android types; unit tested.
 */
final class Sealed {
    static final byte[] MAGIC={'M','N','S','1'};
    static final int PIECE=1<<20;
    private static final SecureRandom RANDOM=new SecureRandom();

    private Sealed(){}

    /** Whether these first bytes are the start of a sealed file. */
    static boolean is(byte[] head){
        if(head==null||head.length<MAGIC.length)return false;
        for(int i=0;i<MAGIC.length;i++)if(head[i]!=MAGIC[i])return false;
        return true;
    }

    static void seal(byte[] key,InputStream in,OutputStream out) throws IOException {
        byte[] prefix=new byte[8];RANDOM.nextBytes(prefix);
        DataOutputStream to=new DataOutputStream(out);
        to.write(MAGIC);to.write(prefix);
        byte[] piece=new byte[PIECE],next=new byte[PIECE];
        int have=fill(in,piece),count=0;
        while(true) {
            int after=have==PIECE?fill(in,next):0;
            boolean last=after==0;
            byte[] sealed=crypt(Cipher.ENCRYPT_MODE,key,prefix,count,last,piece,0,have);
            to.writeInt(sealed.length);to.write(sealed);
            if(last)break;
            byte[] swap=piece;piece=next;next=swap;have=after;count++;
        }
        to.flush();
    }

    /** The plain bytes, or a refusal: wrong key, changed, reordered or cut short. */
    static void open(byte[] key,InputStream in,OutputStream out) throws IOException,Vault.Refused {
        DataInputStream from=new DataInputStream(in.markSupported()?in:new java.io.BufferedInputStream(in));
        byte[] head=new byte[MAGIC.length];
        try{from.readFully(head);}catch(EOFException empty){throw new Vault.Refused("This is not a sealed file.");}
        if(!is(head))throw new Vault.Refused("This is not a sealed file.");
        byte[] prefix=new byte[8];from.readFully(prefix);
        for(int count=0;;count++) {
            int length;
            try{length=from.readInt();}catch(EOFException cut){throw new Vault.Refused("This file has been cut short.");}
            if(length<16||length>PIECE+16)throw new Vault.Refused("This file has been changed.");
            byte[] sealed=new byte[length];
            try{from.readFully(sealed);}catch(EOFException cut){throw new Vault.Refused("This file has been cut short.");}
            // Tried as the last piece first when nothing follows, else as one in the middle: the check says which.
            boolean more=from.available()>0||peekMore(from);
            byte[] plain=tryOpen(key,prefix,count,!more,sealed);
            if(plain==null)throw new Vault.Refused(count==0?"That key does not open this file.":"This file has been changed.");
            out.write(plain);
            if(!more)return;
        }
    }

    private static boolean peekMore(DataInputStream from) throws IOException {
        from.mark(1);
        int b=from.read();
        if(b<0)return false;
        from.reset();return true;
    }

    private static byte[] tryOpen(byte[] key,byte[] prefix,int count,boolean last,byte[] sealed) {
        try{return crypt(Cipher.DECRYPT_MODE,key,prefix,count,last,sealed,0,sealed.length);}
        catch(IOException wrong){return null;}
    }

    private static byte[] crypt(int mode,byte[] key,byte[] prefix,int count,boolean last,byte[] bytes,int from,int length) throws IOException {
        try {
            byte[] nonce=ByteBuffer.allocate(12).put(prefix).putInt(count).array();
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            aes.updateAAD(ByteBuffer.allocate(9).put(MAGIC).putInt(count).array());
            aes.updateAAD(new byte[]{(byte)(last?1:0)});
            return aes.doFinal(bytes,from,length);
        } catch(GeneralSecurityException e){throw new IOException("Could not seal or open this file.",e);}
    }

    private static int fill(InputStream in,byte[] into) throws IOException {
        int have=0,got;
        while(have<into.length&&(got=in.read(into,have,into.length-have))>0)have+=got;
        return have;
    }
}
