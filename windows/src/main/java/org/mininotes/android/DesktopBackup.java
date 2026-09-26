package org.mininotes.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class DesktopBackup {
    private static final String TEXT="mininotes-backup.json";
    private static final long TEXT_LIMIT=32L*1024*1024;
    /** The start of a backup written while the notebook was locked: this, then its lock, then the sealed zip. */
    static final byte[] LOCKED={'M','N','B','1'};

    static void write(NoteStore store,Path target) throws Exception{write(store,target,null,null);}
    /**
     * With a key and its lock, the backup is sealed whole, and carries the lock so it opens with the same
     * password or recovery words anywhere - on a new PC, after this one is gone. The zip inside is never
     * written to disk plain: it is sealed as it is made.
     */
    static void write(NoteStore store,Path target,byte[] key,byte[] lock) throws Exception {
        Path temp=Files.createTempFile(target.toAbsolutePath().getParent(),".mininotes-backup-",".tmp");
        var db=store.getWritableDatabase();db.beginTransaction();
        try {
            try(FileOutputStream file=new FileOutputStream(temp.toFile())) {
                // Read from the notebook here, on the thread holding it: the zip is made on another.
                List<NoteStore.Held> held=store.everyFile();
                if(key==null){zip(store,store.backup(),held,file,null);}
                else {
                    DataOutputStream head=new DataOutputStream(file);head.write(LOCKED);head.writeShort(lock.length);head.write(lock);head.flush();
                    PipedInputStream plain=new PipedInputStream(1<<16);PipedOutputStream into=new PipedOutputStream(plain);
                    String text=store.backup();Exception[] failed={null};
                    Thread making=new Thread(()->{try(into){zip(store,text,held,into,key);}catch(Exception e){failed[0]=e;}},"mininotes-backup");
                    making.start();
                    try{Sealed.seal(key,plain,file);}finally{making.join();}
                    if(failed[0]!=null)throw failed[0];
                }
                file.flush();file.getFD().sync();
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            db.setTransactionSuccessful();
        } finally{db.endTransaction();Files.deleteIfExists(temp);}
    }
    private static void zip(NoteStore store,String text,List<NoteStore.Held> held,OutputStream out,byte[] key) throws IOException {
        ZipOutputStream zip=new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry(TEXT));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        for(NoteStore.Held one:held) {
            if(Attachment.idOf(Attachment.entry(one.id))==null)throw new IOException("Invalid attachment identifier");
            zip.putNextEntry(new ZipEntry(Attachment.entry(one.id)));
            // Plain inside the backup, which is itself sealed when locked: it opens anywhere with its own lock.
            Path kept=store.fileFor(one.id).toPath();
            if(DesktopFiles.sealed(kept)){try(InputStream in=new BufferedInputStream(Files.newInputStream(kept))){Sealed.open(key,in,zip);}catch(Vault.Refused r){throw new IOException("An attachment could not be opened: "+r.getMessage());}}
            else Files.copy(kept,zip);
            zip.closeEntry();
        }
        zip.finish();zip.flush();
    }

    /** What opens a locked backup: given its lock, the key, or null if they gave up. */
    interface Unlock{byte[] key(byte[] lock) throws Exception;}

    static boolean locked(Path source) {
        byte[] head=new byte[LOCKED.length];
        try(InputStream in=Files.newInputStream(source)){return in.readNBytes(head,0,head.length)==head.length&&Arrays.equals(head,LOCKED);}
        catch(IOException e){return false;}
    }

    static int add(NoteStore store,Path source) throws Exception{return add(store,source,null);}
    static int add(NoteStore store,Path source,Unlock unlock) throws Exception {
        if(Files.size(source)>Attachment.PLENTY+TEXT_LIMIT+(1<<20))throw new IOException("That backup is too large");
        Map<String,Path> files=new LinkedHashMap<>();String text=null;
        Path staging=Files.createTempDirectory(store.landing().toPath(),"backup-");
        Thread[] opening={null};Exception[] failed={null};
        try {
        InputStream raw=new BufferedInputStream(Files.newInputStream(source));
        if(locked(source)) {
            DataInputStream head=new DataInputStream(raw);head.readFully(new byte[LOCKED.length]);
            byte[] lock=new byte[head.readUnsignedShort()];head.readFully(lock);
            byte[] key=unlock==null?null:unlock.key(lock);
            if(key==null){raw.close();throw new IOException("That backup is locked, and was not opened.");}
            PipedInputStream plain=new PipedInputStream(1<<16);PipedOutputStream into=new PipedOutputStream(plain);
            InputStream sealed=raw;
            opening[0]=new Thread(()->{try(into;sealed){Sealed.open(key,sealed,into);}catch(Exception e){failed[0]=e;}},"mininotes-backup-open");
            opening[0].start();raw=plain;
        }
        try(BufferedInputStream in=new BufferedInputStream(raw)) {
            in.mark(4);boolean zip=in.read()=='P'&&in.read()=='K';in.reset();
            if(!zip)text=new String(bounded(in,TEXT_LIMIT),StandardCharsets.UTF_8);
            else try(ZipInputStream archive=new ZipInputStream(in)) {
                Set<String> entries=new HashSet<>();long total=0;ZipEntry entry;
                while((entry=archive.getNextEntry())!=null) {
                    if(!entries.add(entry.getName()))throw new IOException("Duplicate backup entry");
                    if(entry.getName().equals(TEXT))text=new String(bounded(archive,TEXT_LIMIT),StandardCharsets.UTF_8);
                    else {
                        String id=Attachment.idOf(entry.getName());
                        if(id==null)throw new IOException("Unexpected backup entry");
                        Path pending=staging.resolve(id);files.put(id,pending);
                        try(OutputStream out=Files.newOutputStream(pending)){total+=copyBounded(archive,out,Attachment.LIMIT);}
                        if(total+store.weight()>Attachment.PLENTY)throw new IOException("Not enough attachment space");
                    }
                }
                // Read to the very end: a locked backup's last piece carries the check that it is whole.
                in.transferTo(OutputStream.nullOutputStream());
            }
        }
        if(opening[0]!=null){opening[0].join();if(failed[0]!=null)throw new IOException(failed[0] instanceof Vault.Refused?"That backup could not be opened: "+failed[0].getMessage():"That backup could not be opened.",failed[0]);}
        if(text==null)throw new IOException("That file is not a Mininotes backup");
        // Validate before writing any staged attachment. The store owns ID remapping.
        new org.json.JSONObject(text).getJSONArray("notes");
        try {
            for(var entry:files.entrySet())Files.move(entry.getValue(),store.landingFor(entry.getKey()).toPath(),StandardCopyOption.REPLACE_EXISTING);
            return store.importBackup(text,false);
        } finally {for(String id:files.keySet())Files.deleteIfExists(store.landingFor(id).toPath());}
        } finally {for(Path path:files.values())Files.deleteIfExists(path);Files.deleteIfExists(staging);}
    }
    private static byte[] bounded(InputStream in,long limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();copyBounded(in,out,limit);
        return out.toByteArray();
    }
    private static long copyBounded(InputStream in,OutputStream out,long limit) throws IOException {
        byte[] buffer=new byte[8192];int count;long total=0;
        while((count=in.read(buffer))!=-1) {total+=count;if(total>limit)throw new IOException("Backup entry is too large");out.write(buffer,0,count);}
        return total;
    }
}
