package org.mininotes.desktop.platform.content;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.mininotes.desktop.platform.AtomicFile;

/** DPAPI binds preferences (including the node seed) to this Windows user. */
public final class SharedPreferences {
    private final Path file;
    private JSONObject values;
    SharedPreferences(Path file) {
        this.file=file;
        try {
            values=Files.exists(file)?new JSONObject(new String(
                Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)),StandardCharsets.UTF_8)):new JSONObject();
        } catch(Exception failure){throw new IllegalStateException("Could not open this device's settings",failure);}
    }
    public synchronized String getString(String key,String fallback){return values.optString(key,fallback);}
    public synchronized Set<String> getStringSet(String key,Set<String> fallback) {
        JSONArray array=values.optJSONArray(key);
        if(array==null)return new HashSet<>(fallback);
        Set<String> result=new HashSet<>();
        for(int i=0;i<array.length();i++)result.add(array.getString(i));
        return result;
    }
    public Editor edit(){return new Editor();}
    public final class Editor {
        private final Map<String,Object> changes=new LinkedHashMap<>();
        public Editor putString(String key,String value){changes.put(key,value);return this;}
        public Editor putStringSet(String key,Set<String> value){changes.put(key,new JSONArray(value));return this;}
        public void apply() {
            synchronized(SharedPreferences.this) {
                JSONObject next=new JSONObject(values.toString());
                changes.forEach(next::put);
                try {
                    AtomicFile.write(file,Crypt32Util.cryptProtectData(next.toString().getBytes(StandardCharsets.UTF_8)));
                    values=next;
                } catch(Exception failure){throw new IllegalStateException("Could not save this device's settings",failure);}
            }
        }
    }
}
