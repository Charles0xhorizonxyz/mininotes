package org.mininotes.desktop.platform.database.sqlite;

import java.sql.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.mininotes.desktop.platform.database.Cursor;
import org.mininotes.desktop.platform.content.ContentValues;

/** The bounded SQLite API used by NoteStore, backed by one serialized connection. */
public final class SQLiteDatabase implements AutoCloseable {
    public static final int CONFLICT_REPLACE=5, CONFLICT_IGNORE=4;
    private final Connection connection;
    private final ReentrantLock lock=new ReentrantLock(true);
    private final Deque<Boolean> transactions=new ArrayDeque<>();
    private boolean rollback;
    SQLiteDatabase(Path file){this(file,null);}
    /** Opened plain, or - with a key - as SQLCipher 4 with that raw key, before its first page is read. */
    SQLiteDatabase(Path file,byte[] key) {
        try {
            String url="jdbc:sqlite:"+file.toAbsolutePath();
            connection=key==null?DriverManager.getConnection(url)
                :DriverManager.getConnection(url,org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build().toProperties());
            execSQL("PRAGMA busy_timeout=10000");
            execSQL("PRAGMA journal_mode=WAL");
            execSQL("PRAGMA synchronous=FULL");
        } catch(SQLException e){throw failed(e);}
    }
    /**
     * The notebook encrypted with a key where it lies, or back to plain with null. Out of WAL first: the
     * key of a WAL database cannot be changed, and leaving WAL writes back and removes the -wal file, which
     * would otherwise keep plain pages beside an encrypted notebook.
     */
    public void rekey(byte[] key) {
        lock.lock();
        try(Statement s=connection.createStatement()) {
            s.execute("PRAGMA journal_mode=DELETE");
            if(key!=null){s.execute("PRAGMA cipher='sqlcipher'");s.execute("PRAGMA legacy=4");s.execute("PRAGMA rekey=\"x'"+hex(key)+"'\"");}
            else s.execute("PRAGMA rekey=''");
            s.execute("PRAGMA journal_mode=WAL");
        } catch(SQLException e){throw failed(e);}
        finally{lock.unlock();}
    }
    private static String hex(byte[] key){StringBuilder h=new StringBuilder();for(byte b:key)h.append(String.format("%02x",b&0xff));return h.toString();}
    private static IllegalStateException failed(SQLException e){return new IllegalStateException("Notebook storage failed (SQLite "+e.getErrorCode()+")",e);}
    public void beginTransaction() {
        lock.lock();
        try {if(transactions.isEmpty()){connection.setAutoCommit(false);rollback=false;}transactions.push(false);}
        catch(SQLException e){lock.unlock();throw failed(e);}
    }
    public void setTransactionSuccessful() {
        if(!lock.isHeldByCurrentThread()||transactions.isEmpty())throw new IllegalStateException("No transaction");
        transactions.pop();transactions.push(true);
    }
    public void endTransaction() {
        if(!lock.isHeldByCurrentThread()||transactions.isEmpty())throw new IllegalStateException("No transaction");
        try {
            if(!transactions.pop())rollback=true;
            if(transactions.isEmpty()) {
                try {if(rollback)connection.rollback();else connection.commit();}
                catch(SQLException e){connection.rollback();throw e;}
                finally {connection.setAutoCommit(true);}
            }
        } catch(SQLException e){throw failed(e);}finally{lock.unlock();}
    }
    private PreparedStatement prepared(String sql,Object[] args) throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);
        try {if(args!=null)for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;}
        catch(SQLException e){statement.close();throw e;}
    }
    public Cursor rawQuery(String sql,String[] args) {
        lock.lock();
        try(PreparedStatement statement=prepared(sql,args);ResultSet result=statement.executeQuery()) {return new Cursor(result);}
        catch(SQLException e){throw failed(e);}finally{lock.unlock();}
    }
    public Cursor query(String table,String[] columns,String where,String[] args,String group,String having,String order) {
        return query(table,columns,where,args,group,having,order,null);
    }
    public Cursor query(String table,String[] columns,String where,String[] args,String group,String having,String order,String limit) {
        String sql="SELECT "+(columns==null?"*":String.join(",",columns))+" FROM "+table;
        if(where!=null&&!where.isEmpty())sql+=" WHERE "+where;
        if(group!=null)sql+=" GROUP BY "+group;
        if(having!=null)sql+=" HAVING "+having;
        if(order!=null)sql+=" ORDER BY "+order;
        if(limit!=null)sql+=" LIMIT "+limit;
        return rawQuery(sql,args);
    }
    public void execSQL(String sql){execSQL(sql,null);}
    public void execSQL(String sql,Object[] args) {
        lock.lock();
        try(PreparedStatement statement=prepared(sql,args)){statement.execute();}
        catch(SQLException e){throw failed(e);}finally{lock.unlock();}
    }
    public long insert(String table,String nullable,ContentValues values){return insertWithOnConflict(table,nullable,values,0);}
    public long insertWithOnConflict(String table,String nullable,ContentValues values,int conflict) {
        lock.lock();
        try {
            String mode=conflict==CONFLICT_REPLACE?" OR REPLACE":conflict==CONFLICT_IGNORE?" OR IGNORE":"";
            String sql="INSERT"+mode+" INTO "+table+" ("+String.join(",",values.keySet())+") VALUES ("
                +String.join(",",Collections.nCopies(values.size(),"?"))+")";
            try(PreparedStatement statement=prepared(sql,values.values().toArray())) {
                if(statement.executeUpdate()==0)return -1;
            }
            try(Cursor row=rawQuery("SELECT last_insert_rowid()",null)){row.moveToFirst();return row.getLong(0);}
        } catch(SQLException e){throw failed(e);}finally{lock.unlock();}
    }
    public int update(String table,ContentValues values,String where,String[] args) {
        List<Object> bindings=new ArrayList<>(values.values());if(args!=null)Collections.addAll(bindings,args);
        String set=String.join(",",values.keySet().stream().map(k->k+"=?").toList());
        return change("UPDATE "+table+" SET "+set+(where==null?"":" WHERE "+where),bindings.toArray());
    }
    public int delete(String table,String where,String[] args){return change("DELETE FROM "+table+(where==null?"":" WHERE "+where),args);}
    private int change(String sql,Object[] args) {
        lock.lock();try(PreparedStatement statement=prepared(sql,args)){return statement.executeUpdate();}
        catch(SQLException e){throw failed(e);}finally{lock.unlock();}
    }
    public void close(){lock.lock();try{connection.close();}catch(SQLException e){throw failed(e);}finally{lock.unlock();}}
}
