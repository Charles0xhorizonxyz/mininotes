// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.content.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Source-built, read-only adapter for the pinned Core IPC contract. No wallet/admin commands.
 *
 * <p>Two commands are allowed and both only read: {@code status}, and {@code maxima} with no action, which
 * says what this device's own Maxima address is. Sending is an action on that same command and is not
 * allowed here — it is added when there is something to send it to, not before.
 */
final class CoreConnection implements AutoCloseable {
    static final String CORE="org.minimarex.minimacore";
    private final Context context;
    private final String appId, nodeId;
    /** True only in a build you can attach to: what the Core says about a request is then readable. */
    private final boolean debuggable;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,Consumer<String>> requests=new HashMap<>();
    private boolean closed;
    private static String random() {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder b=new StringBuilder("0x");for(byte v:bytes)b.append(String.format("%02x",v&255));return b.toString();}
    CoreConnection(Context c) {
        context=c.getApplicationContext();SharedPreferences prefs=context.getSharedPreferences("core_pairing",Context.MODE_PRIVATE);
        appId=prefs.getString("app",random());nodeId=prefs.getString("node",random());
        debuggable=(context.getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        if(!prefs.edit().putString("app",appId).putString("node",nodeId).commit())throw new IllegalStateException("Could not save Core pairing");
        IntentFilter f=new IntentFilter(CORE+".RESPONSE");
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);else registerLegacy(f);
    }
    // Android 9–12 have no RECEIVER_EXPORTED flag. Their legacy registration is
    // exported by default, which is required for this cross-application API.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacy(IntentFilter filter) { context.registerReceiver(receiver,filter); }
    boolean installed(){try{context.getPackageManager().getPackageInfo(CORE,0);return true;}catch(Exception e){return false;}}
    /** Whether Core will answer this app at all: registered, and the package enabled in Core's own list. */
    void answers(Consumer<Boolean> said) {
        if(!installed()){said.accept(false);return;}
        request("REGISTER",null,registered->{
            if(!answered(registered)){said.accept(false);return;}
            request("CMD","status",reply->said.accept(answered(reply)));
        });
    }

    void check(Consumer<String> result) {
        if(!installed()){result.accept("Install Minima Core to connect. Your notes work offline.");return;}
        request("REGISTER",null,r->{
            try {JSONObject json=new JSONObject(r);if(!json.optBoolean("status")){result.accept("Enable Mininotes in Core → Apps, then check again.");return;}}
            catch(Exception e){result.accept("Core registration did not return a valid response. Check Core and retry.");return;}
            request("CMD","status",reply->{try{JSONObject json=new JSONObject(reply);if(!json.optBoolean("status")){result.accept("Enable Mininotes in Core → Apps, then check again.");return;}
                result.accept("Core connected. Device sync is not implemented in this build.");
            }catch(Exception e){result.accept("Core status is unavailable. Check Core and retry.");}});
        });
    }
    /** Whether the Core answered a command at all. */
    private static boolean answered(String reply) {
        try{return new JSONObject(reply).optBoolean("status");}catch(Exception e){return false;}
    }

    /**
     * This device's own Maxima address — the one thing another device needs before it can be told anything.
     * Read only: it asks what the address is and never asks the Core to carry anything.
     */
    void address(Consumer<String> found,Consumer<String> failed) {
        if(!installed()){failed.accept("Install Minima Core to sync between devices. Your notes work offline.");return;}
        request("REGISTER",null,registered->{
            if(!answered(registered)){failed.accept("Enable Mininotes in Core → Apps, then try again.");return;}
            request("CMD","maxima",reply->{
                if(!answered(reply)) {
                    // Told apart because the two need opposite things of a person. A node that is merely
                    // asleep is worth waking; one built without Maxima in it will never answer however
                    // long you wait, and saying "try again" to that is an instruction to waste an evening.
                    failed.accept(reply!=null&&reply.contains("Command not found")
                        ?"This build of Minima Core has no Maxima in it, so it cannot be asked for an "
                            +"address. Update Core to a build with Maxima, or paste the address by hand."
                        :"Core would not answer for Maxima. Open Core, check it is running, and try again.");
                    return;
                }
                try {
                    JSONObject about=new JSONObject(reply).optJSONObject("response");
                    String said=null;
                    // The contact address is the thing another node is told: it starts Mx and carries where
                    // to reach it. Which key holds it has moved between Core versions, so the first one that
                    // is there is taken — but a public key is not an address and is never offered as one,
                    // because handing one out would look right and reach nobody.
                    // "contact" is the one to hand out: the key, and the host somebody else can reach it
                    // through. "localidentity" is that same key behind this node's own address, which only
                    // works on this network, so it is only fallen back to. A public key is never offered as
                    // an address: it would look right on a screen and reach nobody.
                    for(String name:new String[]{"contact","contactaddress","maximaaddress","localidentity"}) {
                        String value=about==null?null:about.optString(name,"");
                        if(Pairing.reachable(value)){said=value;break;}
                    }
                    if(said==null){
                        failed.accept("Core answered, but not with a contact address. Open Core and copy the "
                            +"Maxima contact address it shows, then paste it here.");
                        return;
                    }
                    found.accept(said);
                } catch(Exception e){failed.accept("Core's answer could not be read. Check Core and try again.");}
            });
        });
    }

    /** Debug builds only: the shape of an answer, so a refusal can be read. Never its contents. */
    private void said(String raw) {
        try {
            JSONObject json=new JSONObject(raw);
            StringBuilder about=new StringBuilder("status=").append(json.optBoolean("status"));
            for(String key:new String[]{"command","error","message","pending","response"}) {
                if(!json.has(key))continue;
                String value=json.opt(key)==null?"":String.valueOf(json.opt(key));
                about.append(' ').append(key).append('=').append(value.length()>90?value.substring(0,90)+"…":value);
            }
            android.util.Log.i("Mininotes/Core",about.toString());
        } catch(Exception e){android.util.Log.i("Mininotes/Core","unreadable answer, "+raw.length()+" characters");}
    }

    private void request(String type,String command,Consumer<String> callback) {
        if(closed)return;
        boolean reading=type.equals("CMD")&&("status".equals(command)||"maxima".equals(command));
        if(!type.equals("REGISTER")&&!reading)throw new IllegalArgumentException("Command not allowed");
        String id=random();Intent i=new Intent(CORE+"."+type).setPackage(CORE);
        i.putExtra(CORE+".PACKAGE_CLASS",context.getPackageName());i.putExtra(CORE+".APP_UID",appId);i.putExtra(CORE+".REGISTER_MINIMAID",nodeId);i.putExtra(CORE+".RESPONSE_ID",id);
        if(command!=null)i.putExtra(CORE+".CMD_ACTION",command);
        requests.put(id,callback);context.sendBroadcast(i);
        handler.postDelayed(()->{Consumer<String> pending=requests.remove(id);if(pending!=null)pending.accept("Core did not reply. Open Core, enable Mininotes, and retry.");},15000);
    }
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(closed||!((CORE+".RESPONSE").equals(i.getAction()))||!nodeId.equals(i.getStringExtra(CORE+".REGISTER_MINIMAID")))return;
        // Pairing-token authentication is the upstream contract. Never log tokens or payloads.
        String id=i.getStringExtra(CORE+".RESPONSE_ID"),raw=i.getStringExtra(CORE+".RESPONSE_RESULT");
        if(id==null||raw==null||raw.length()>65536)return;
        // In a debug build only, and never the payload: what the Core said about the request itself, so a
        // refusal can be read rather than guessed at.
        if(debuggable)said(raw);
        Consumer<String> callback=requests.remove(id);if(callback!=null)callback.accept(raw);
    }};
    @Override public void close(){closed=true;requests.clear();handler.removeCallbacksAndMessages(null);context.unregisterReceiver(receiver);}
}
