package org.mininotes.desktop.platform.database;

import java.sql.*;
import java.util.*;

/** A detached result: callers may query again while walking it. */
public final class Cursor implements AutoCloseable {
    private final List<String> columns=new ArrayList<>();
    private final List<Object[]> rows=new ArrayList<>();
    private int position=-1;
    public Cursor(ResultSet results) throws SQLException {
        int count=results.getMetaData().getColumnCount();
        for(int i=1;i<=count;i++)columns.add(results.getMetaData().getColumnLabel(i));
        while(results.next()) {
            Object[] row=new Object[count];
            for(int i=0;i<count;i++)row[i]=results.getObject(i+1);
            rows.add(row);
        }
    }
    public boolean moveToFirst(){position=0;return !rows.isEmpty();}
    public boolean moveToNext(){return ++position<rows.size();}
    public int getColumnIndex(String name){return columns.indexOf(name);}
    public int getColumnIndexOrThrow(String name){int i=getColumnIndex(name);if(i<0)throw new IllegalArgumentException("Missing column "+name);return i;}
    private Object value(int index){return rows.get(position)[index];}
    public boolean isNull(int index){return value(index)==null;}
    public String getString(int index){Object v=value(index);return v==null?null:v.toString();}
    public long getLong(int index){Object v=value(index);return v==null?0:v instanceof Number?((Number)v).longValue():Long.parseLong(v.toString());}
    public int getInt(int index){return (int)getLong(index);}
    public void close(){rows.clear();}
}
