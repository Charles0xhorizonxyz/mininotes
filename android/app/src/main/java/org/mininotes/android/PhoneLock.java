// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

/**
 * The password lock on this phone's notebook, as the notebook and its files see it.
 *
 * <p>On disk: the notebook, encrypted by SQLCipher with a key nobody types, and {@code vault.key}, that key
 * sealed with the password and with the twelve words ({@link Vault}). Attachments are sealed with the same
 * key ({@link Sealed}). Nothing leaves the phone.
 *
 * <p>Putting the lock on copies the notebook into an encrypted file, checks the copy holds every note, and
 * only then puts it in the plain one's place; taking it off is the same the other way. Notes that arrive
 * while this happens, or while the notebook is locked and nobody has opened it, wait in an inbox as they
 * came - sealed for this phone - and are taken in when it is next opened.
 */
final class PhoneLock {
    static final String KEPT="vault.key",PENDING="vault.key.new",INBOX="inbox";
    static final String WARNING="If you lose both the backup password and the 12 recovery words, nobody can open this notebook. Not you, and not the people who make Mininotes. There is no email reset and no copy anywhere else.";
    /** While the notebook is being swapped, whatever arrives goes to the inbox rather than into it. */
    static volatile boolean busy;

    private PhoneLock(){}

    static File file(Context c,String name){return new File(c.getFilesDir(),name);}
    static boolean locked(Context c){return file(c,KEPT).isFile();}
    /** Whether the notebook can be read now: it has no lock, or its key is in hand. */
    static boolean open(Context c){return !locked(c)||NoteStore.key()!=null;}
    static byte[] kept(Context c) throws IOException{return read(file(c,KEPT));}

    /** What a lock that never finished going on or coming off left behind, cleared at opening. */
    static void tidy(Context c) {
        if(busy)return;
        if(!locked(c))file(c,PENDING).delete();
        forget(c.getDatabasePath("mininotes-next.db"));forget(c.getDatabasePath("mininotes-next.db-journal"));
    }

    // ---- locking again, and the phone's own unlock -----------------------------------------------------------

    static final String[] AFTER_NAMES={"Never","After 1 minute","After 5 minutes","After 15 minutes","After 1 hour"};
    static final int[] AFTER_MINUTES={0,1,5,15,60};
    /** Minutes without use before a locked notebook locks again; 0 is never. Five until chosen. */
    static int minutes(Context c){return c.getSharedPreferences("settings",Context.MODE_PRIVATE).getInt("autoLock",5);}

    /** The notebook closed and its key let go; what arrives from now waits sealed in the inbox. */
    static void relock(Context c) {
        NoteStore.lockAgain();
        Listening.relock(c);
    }

    /**
     * Unlocking with fingerprint, face or the phone's own screen lock: the notebook's key, sealed once more with
     * a key that lives in the phone's secure hardware and is let out only after Android's own prompt says so.
     * The password and the words still open it; this is a third copy, and the easiest one to remove.
     */
    static final String BIO="vault.bio",ALIAS="mininotes-unlock";
    static boolean hasBio(Context c){return file(c,BIO).isFile();}

    /** A cipher over the hardware key, to be handed to Android's prompt. A new hardware key when sealing. */
    static javax.crypto.Cipher bioCipher(Context c,boolean seal) throws Exception {
        java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(seal) {
            javax.crypto.KeyGenerator make=javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            android.security.keystore.KeyGenParameterSpec.Builder spec=new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
            if(android.os.Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(0,
                android.security.keystore.KeyProperties.AUTH_BIOMETRIC_STRONG|android.security.keystore.KeyProperties.AUTH_DEVICE_CREDENTIAL);
            make.init(spec.build());make.generateKey();
        }
        javax.crypto.SecretKey hardware=(javax.crypto.SecretKey)store.getKey(ALIAS,null);
        if(hardware==null)throw new IOException("The phone's unlock key is gone.");
        javax.crypto.Cipher aes=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        if(seal)aes.init(javax.crypto.Cipher.ENCRYPT_MODE,hardware);
        else{byte[] kept=read(file(c,BIO));aes.init(javax.crypto.Cipher.DECRYPT_MODE,hardware,new javax.crypto.spec.GCMParameterSpec(128,kept,0,12));}
        return aes;
    }

    /** The notebook's key sealed by the cipher the prompt let through. */
    static void keepBio(Context c,javax.crypto.Cipher allowed,byte[] key) throws Exception {
        byte[] sealed=allowed.doFinal(key),iv=allowed.getIV();
        byte[] all=new byte[12+sealed.length];System.arraycopy(iv,0,all,0,12);System.arraycopy(sealed,0,all,12,sealed.length);
        write(file(c,BIO),all);
    }

    /** The notebook's key, from the cipher the prompt let through. */
    static byte[] openBio(Context c,javax.crypto.Cipher allowed) throws Exception {
        byte[] kept=read(file(c,BIO));
        return allowed.doFinal(kept,12,kept.length-12);
    }

    /** The third copy gone, and the hardware key with it. */
    static void forgetBio(Context c) {
        file(c,BIO).delete();
        try{java.security.KeyStore store=java.security.KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(ALIAS);}
        catch(Exception gone){/* nothing to remove */}
    }

    // ---- the notebook ------------------------------------------------------------------------------------

    /** The lock put on: the notebook encrypted, checked, swapped in; its files sealed. Blocking. */
    static void encrypt(Context c,Vault.Made made) throws Exception {
        busy=true;
        try {
            write(file(c,PENDING),made.kept);
            try{swap(c,null,made.key);}catch(Exception failed){file(c,PENDING).delete();throw failed;}
            if(!file(c,PENDING).renameTo(file(c,KEPT)))throw new IOException("The lock could not be kept.");
            NoteStore.unlock(made.key);
            every(c,made.key,true);
        } finally{busy=false;}
    }

    /** The lock taken off: the notebook plain again, checked, swapped in; its files opened. Blocking. */
    static void decrypt(Context c,byte[] key) throws Exception {
        busy=true;
        try {
            every(c,key,false);
            swap(c,key,null);
            if(!file(c,KEPT).delete())throw new IOException("The lock could not be removed.");
            forgetBio(c);
            NoteStore.unlock(null);
        } finally{busy=false;}
    }

    /**
     * The notebook copied, whole, into a file opened with the other key, and put in its place once it is
     * shown to hold every note the old one did. The old one goes only then.
     */
    private static void swap(Context c,byte[] from,byte[] to) throws Exception {
        File now=c.getDatabasePath("mininotes.db"),next=c.getDatabasePath("mininotes-next.db"),old=c.getDatabasePath("mininotes-old.db");
        try{copy(c,from,to,now,next,old);}
        catch(Exception failed) {
            // Nothing was put in place: the notebook is the one it was, opened with the key it had.
            NoteStore.unlock(from);forget(next);forget(new File(next.getPath()+"-journal"));
            throw failed;
        }
    }

    private static void copy(Context c,byte[] from,byte[] to,File now,File next,File old) throws Exception {
        forget(next);forget(old);
        NoteStore source=NoteStore.of(c);
        SQLiteDatabase db=source.getWritableDatabase();
        int notes;
        try(android.database.Cursor count=db.rawQuery("SELECT COUNT(*) FROM notes",null)){count.moveToFirst();notes=count.getInt(0);}
        int version;
        try(android.database.Cursor v=db.rawQuery("PRAGMA user_version",null)){v.moveToFirst();version=v.getInt(0);}
        db.execSQL("ATTACH DATABASE '"+next.getPath().replace("'","''")+"' AS next KEY \""+(to==null?"":Vault.pragma(to))+"\"");
        try(android.database.Cursor done=db.rawQuery("SELECT sqlcipher_export('next')",null)){done.moveToFirst();}
        db.execSQL("PRAGMA next.user_version="+version);
        db.execSQL("DETACH DATABASE next");
        source.close();NoteStore.unlock(to);
        // Checked before anything is thrown away: the new file opens with its key and holds every note.
        NoteStore check=new NoteStore(c,to,"mininotes-next.db");
        try(android.database.Cursor count=check.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM notes",null)) {
            count.moveToFirst();
            if(count.getInt(0)!=notes)throw new IOException("The copy is missing notes; nothing was changed.");
        } finally{check.close();}
        if(!now.renameTo(old))throw new IOException("The notebook could not be moved aside; nothing was changed.");
        forget(new File(now.getPath()+"-wal"));forget(new File(now.getPath()+"-shm"));forget(new File(now.getPath()+"-journal"));
        if(!next.renameTo(now)){old.renameTo(now);throw new IOException("The new notebook could not be put in place; nothing was changed.");}
        forget(old);forget(new File(old.getPath()+"-wal"));forget(new File(old.getPath()+"-shm"));forget(new File(old.getPath()+"-journal"));
        forget(new File(next.getPath()+"-journal"));
    }

    private static void forget(File f){if(f.exists()&&!f.delete())f.deleteOnExit();}

    // ---- the notebook's files ----------------------------------------------------------------------------

    static boolean sealed(File f) {
        byte[] head=new byte[Sealed.MAGIC.length];
        try(InputStream in=new FileInputStream(f)){int n=0,got;while(n<head.length&&(got=in.read(head,n,head.length-n))>0)n+=got;return n==head.length&&Sealed.is(head);}
        catch(IOException e){return false;}
    }

    /** Every attachment sealed (lock on) or opened (lock off), each replaced whole. */
    static void every(Context c,byte[] key,boolean seal) throws IOException {
        NoteStore store=NoteStore.of(c);
        for(NoteStore.Held held:store.everyFile()) {
            File f=store.fileFor(held.id);
            if(!f.isFile()||sealed(f)==seal)continue;
            File next=new File(f.getPath()+".next");
            try(InputStream in=new BufferedInputStream(new FileInputStream(f));OutputStream out=new BufferedOutputStream(new FileOutputStream(next))) {
                if(seal)Sealed.seal(key,in,out);else Sealed.open(key,in,out);
            } catch(Vault.Refused refused){next.delete();throw new IOException("An attachment could not be opened: "+refused.getMessage());}
            catch(IOException e){next.delete();throw e;}
            if(!next.renameTo(f)){next.delete();throw new IOException("An attachment could not be replaced.");}
        }
    }

    /** One kept file's own bytes: opened on the way out when it is sealed. */
    static void copyOut(File kept,OutputStream out) throws IOException {
        try(InputStream in=new BufferedInputStream(new FileInputStream(kept))) {
            if(!sealed(kept)){byte[] part=new byte[16384];int n;while((n=in.read(part))!=-1)out.write(part,0,n);return;}
            byte[] key=NoteStore.key();
            if(key==null)throw new IOException("This attachment is locked, and the notebook is not open.");
            try{Sealed.open(key,in,out);}catch(Vault.Refused refused){throw new IOException("This attachment could not be opened: "+refused.getMessage());}
        }
    }

    // ---- what arrives while the notebook cannot be written ---------------------------------------------------

    /** Kept as it came - sealed for this phone - to be taken in when the notebook is next open. */
    static void keepArriving(Context c,byte[] message) {
        try {
            File inbox=file(c,INBOX);if(!inbox.isDirectory())inbox.mkdirs();
            write(new File(inbox,System.currentTimeMillis()+"-"+Integer.toHexString(Arrays.hashCode(message))),message);
        } catch(IOException full){android.util.Log.w("Mininotes/Lock","could not keep an arriving message: "+full.getMessage());}
    }

    /** Everything that waited, taken in, oldest first. Blocking; returns what landed, for the screen to say. */
    static java.util.List<Post.Landed> takeIn(Context c,NoteStore store,Keys keys) {
        java.util.List<Post.Landed> landed=new java.util.ArrayList<>();
        File[] waiting=file(c,INBOX).listFiles();
        if(waiting==null)return landed;
        Arrays.sort(waiting);
        for(File one:waiting) {
            try{Post.Landed said=Post.arrived(c,store,keys,read(one));if(said!=null)landed.add(said);}
            catch(Exception unreadable){/* not one of ours after all */}
            one.delete();
        }
        return landed;
    }

    // ---- backups -----------------------------------------------------------------------------------------

    /** The start of a backup written while the notebook was locked; the same as the PC's, so each opens the other's. */
    static final byte[] LOCKED={'M','N','B','1'};

    /** The lock a locked backup carries, or null for a plain one. The stream is left just past it. */
    static byte[] backupLock(InputStream in) throws IOException {
        in.mark(LOCKED.length+2);
        byte[] head=new byte[LOCKED.length];int n=0,got;
        while(n<head.length&&(got=in.read(head,n,head.length-n))>0)n+=got;
        if(n<head.length||!Arrays.equals(head,LOCKED)){in.reset();return null;}
        DataInputStream data=new DataInputStream(in);
        byte[] lock=new byte[data.readUnsignedShort()];data.readFully(lock);
        return lock;
    }

    static void writeBackupHead(OutputStream out,byte[] lock) throws IOException {
        DataOutputStream data=new DataOutputStream(out);data.write(LOCKED);data.writeShort(lock.length);data.write(lock);data.flush();
    }

    // ---- small files -------------------------------------------------------------------------------------

    static byte[] read(File f) throws IOException {
        try(InputStream in=new FileInputStream(f)){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] part=new byte[8192];int n;while((n=in.read(part))!=-1)out.write(part,0,n);return out.toByteArray();}
    }

    /** Written beside, then put in place, so a half-written file never stands where the real one was. */
    static void write(File f,byte[] bytes) throws IOException {
        File next=new File(f.getPath()+".writing");
        try(FileOutputStream out=new FileOutputStream(next)){out.write(bytes);out.getFD().sync();}
        if(!next.renameTo(f)){next.delete();throw new IOException("Could not write "+f.getName());}
    }
}
