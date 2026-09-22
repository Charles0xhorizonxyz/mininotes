// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lending one kept file to whatever app can open it. A file in the app's own folder cannot be handed over
 * as a path — the phone refuses that, and rightly — so it is handed over as an address that grants the
 * reader one look at one file, for as long as that app is open.
 *
 * <p>Read only, never exported, and it will only open a file the notebook has a row for: an address made
 * up by something else opens nothing.
 */
public final class Lending extends ContentProvider {
    static final String AUTHORITY="org.mininotes.android.files";

    /** The address for one kept file. */
    static Uri of(String id){return Uri.parse("content://"+AUTHORITY+"/"+id);}

    private NoteStore store;

    @Override public boolean onCreate(){return true;}

    /** Opened lazily and left open: the provider lives as long as the app does. */
    private synchronized NoteStore store() {
        if(store==null)store=new NoteStore(getContext());
        return store;
    }

    private NoteStore.Held asked(Uri uri) throws FileNotFoundException {
        String id=uri==null?null:uri.getLastPathSegment();
        if(id==null||Attachment.idOf(Attachment.entry(id))==null)throw new FileNotFoundException("Not a file of ours");
        NoteStore.Held held=store().file(id);
        if(held==null)throw new FileNotFoundException("No such file");
        return held;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(mode!=null&&!mode.equals("r"))throw new FileNotFoundException("Read only");
        NoteStore.Held held=asked(uri);
        File file=store().fileFor(held.id);
        if(!file.isFile())throw new FileNotFoundException("The file is gone");
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try{return asked(uri).kind;}catch(FileNotFoundException e){return null;}
    }

    /** What every app asks before opening something: what it is called and how big it is. */
    @Override public Cursor query(Uri uri,String[] wanted,String where,String[] args,String order) {
        NoteStore.Held held;
        try{held=asked(uri);}catch(FileNotFoundException e){return null;}
        String[] columns=wanted==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:wanted;
        MatrixCursor answer=new MatrixCursor(columns,1);
        Object[] row=new Object[columns.length];
        for(int at=0;at<columns.length;at++) {
            if(OpenableColumns.DISPLAY_NAME.equals(columns[at]))row[at]=held.name;
            else if(OpenableColumns.SIZE.equals(columns[at]))row[at]=held.bytes;
        }
        answer.addRow(row);
        return answer;
    }

    // Nothing is written, deleted or renamed through here: this is a way out of the pad, not a way in.
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String where,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new UnsupportedOperationException();}
}
