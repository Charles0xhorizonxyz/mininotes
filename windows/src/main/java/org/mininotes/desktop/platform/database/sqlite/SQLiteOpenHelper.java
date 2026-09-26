package org.mininotes.desktop.platform.database.sqlite;

import org.mininotes.desktop.platform.content.Context;
import org.mininotes.desktop.platform.database.Cursor;

public abstract class SQLiteOpenHelper implements AutoCloseable {
    private final Context context;
    private final String name;
    private final int version;
    private SQLiteDatabase database;
    protected SQLiteOpenHelper(Context context,String name,Object factory,int version){this.context=context;this.name=name;this.version=version;}
    /** SQLCipher's shape, as the shared notebook calls it. The PC takes its key from the Context instead. */
    protected SQLiteOpenHelper(Context context,String name,byte[] password,Object factory,int version,int oldest,Object errors,Object hook,boolean wal){this(context,name,factory,version);}
    public synchronized SQLiteDatabase getReadableDatabase(){return getWritableDatabase();}
    public synchronized SQLiteDatabase getWritableDatabase() {
        if(database!=null)return database;
        SQLiteDatabase opened=new SQLiteDatabase(context.getFilesDir().toPath().resolve(name),context.databaseKey());
        try {
            int before;
            try(Cursor row=opened.rawQuery("PRAGMA user_version",null)){row.moveToFirst();before=row.getInt(0);}
            if(before!=version) {
                opened.beginTransaction();
                try {
                    if(before==0)onCreate(opened);else if(before<version)onUpgrade(opened,before,version);else onDowngrade(opened,before,version);
                    opened.execSQL("PRAGMA user_version="+version);opened.setTransactionSuccessful();
                } finally{opened.endTransaction();}
            }
            database=opened;return database;
        } catch(RuntimeException e){opened.close();throw e;}
    }
    public abstract void onCreate(SQLiteDatabase db);
    public abstract void onUpgrade(SQLiteDatabase db,int old,int next);
    public abstract void onDowngrade(SQLiteDatabase db,int old,int next);
    public synchronized void close(){if(database!=null){database.close();database=null;}}
}
