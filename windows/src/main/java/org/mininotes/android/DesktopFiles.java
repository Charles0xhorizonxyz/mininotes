package org.mininotes.android;

import java.io.*;
import java.nio.file.*;
import org.mininotes.desktop.platform.content.Context;

/**
 * Attachments as they lie on this PC: sealed with the notebook's key while it has a lock, plain otherwise.
 * Whatever reads or writes one comes through here, so no file of a locked notebook is written plain.
 */
final class DesktopFiles {
    private DesktopFiles(){}

    // ---- what arrives while the notebook is locked again ----------------------------------------------------

    /** Kept as it came - sealed for this PC - to be taken in when the notebook is opened. */
    static void keepArriving(Path folder,byte[] message) {
        try{Path inbox=folder.resolve("inbox");Files.createDirectories(inbox);Files.write(inbox.resolve(System.currentTimeMillis()+"-"+Integer.toHexString(java.util.Arrays.hashCode(message))),message);}
        catch(IOException full){/* nothing to say it to: the sender tries again until it is answered */}
    }

    /** Everything that waited, taken in, oldest first. How many landed. */
    static int takeIn(Context context,NoteStore store,Keys keys) {
        Path inbox=context.getFilesDir().toPath().resolve("inbox");int landed=0;
        if(!Files.isDirectory(inbox))return 0;
        try(var list=Files.list(inbox)) {
            for(Path one:list.sorted().toList()) {
                try{if(Post.arrived(context,store,keys,Files.readAllBytes(one))!=null)landed++;}catch(Exception unreadable){/* not ours */}
                Files.deleteIfExists(one);
            }
        } catch(IOException e){/* the next opening tries again */}
        return landed;
    }

    static boolean sealed(Path file) {
        byte[] head=new byte[Sealed.MAGIC.length];
        try(InputStream in=Files.newInputStream(file)){return in.readNBytes(head,0,head.length)==head.length&&Sealed.is(head);}
        catch(IOException unreadable){return false;}
    }

    /** A file taken in: sealed on the way when the notebook is locked. */
    static void keep(Context context,Path source,Path kept) throws IOException {
        byte[] key=context.databaseKey();
        if(key==null){Files.copy(source,kept);return;}
        try(InputStream in=new BufferedInputStream(Files.newInputStream(source));OutputStream out=new BufferedOutputStream(Files.newOutputStream(kept,StandardOpenOption.CREATE_NEW))){Sealed.seal(key,in,out);}
        catch(IOException e){Files.deleteIfExists(kept);throw e;}
    }

    /** A kept file's own bytes, written out: opened on the way if it is sealed. */
    static void copyOut(Context context,Path kept,OutputStream out) throws IOException {
        if(!sealed(kept)){Files.copy(kept,out);return;}
        byte[] key=context.databaseKey();
        if(key==null)throw new IOException("This attachment is locked, and the notebook is not open.");
        try(InputStream in=new BufferedInputStream(Files.newInputStream(kept))){Sealed.open(key,in,out);}
        catch(Vault.Refused refused){throw new IOException("This attachment could not be opened: "+refused.getMessage());}
    }

    /** Every attachment sealed (lock on) or opened (lock off), each replaced whole, never left half done. */
    static void every(NoteStore store,byte[] key,boolean seal) throws IOException {
        for(NoteStore.Held held:store.everyFile()) {
            Path file=store.fileFor(held.id).toPath();
            if(!Files.isRegularFile(file)||sealed(file)==seal)continue;
            Path next=file.resolveSibling(file.getFileName()+".next");
            try(InputStream in=new BufferedInputStream(Files.newInputStream(file));OutputStream out=new BufferedOutputStream(Files.newOutputStream(next))) {
                if(seal)Sealed.seal(key,in,out);else Sealed.open(key,in,out);
            } catch(Vault.Refused refused){Files.deleteIfExists(next);throw new IOException("An attachment could not be opened: "+refused.getMessage());}
            catch(IOException e){Files.deleteIfExists(next);throw e;}
            Files.move(next,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
