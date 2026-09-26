package org.mininotes.desktop.platform.content;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

/** The storage locations needed by shared Mininotes code, not an Android runtime. */
public final class Context {
    public static final int MODE_PRIVATE=0;
    private final File directory;
    private final ConcurrentHashMap<String,SharedPreferences> preferences=new ConcurrentHashMap<>();
    public Context(File directory) {
        this.directory=directory;
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Cannot open the notebook folder");
    }
    /** The notebook's key while it is unlocked, or null when it has no lock. Held only in memory. */
    private volatile byte[] databaseKey;
    public void unlock(byte[] key){databaseKey=key==null?null:key.clone();}
    public byte[] databaseKey(){byte[] key=databaseKey;return key==null?null:key.clone();}
    public Context getApplicationContext(){return this;}
    public File getFilesDir(){return directory;}
    public SharedPreferences getSharedPreferences(String name,int mode) {
        if(!name.matches("[a-zA-Z0-9_-]+"))throw new IllegalArgumentException("Invalid preference name");
        return preferences.computeIfAbsent(name,n->new SharedPreferences(new File(directory,n+".protected").toPath()));
    }
}
