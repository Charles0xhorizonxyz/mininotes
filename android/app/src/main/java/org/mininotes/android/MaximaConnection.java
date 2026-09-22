// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The other node on this phone: the Maxima transport app, asked for what Minima Core cannot say.
 *
 * <p>Minima Core carries no Maxima at all — its build has no {@code maxima} command and no Maxima
 * service — so it can neither name this device's address nor carry a message to anybody. The transport
 * app can do both, and publishes a broadcast surface shaped like the one Core speaks, so nothing here is
 * a new idea: register, then ask.
 *
 * <p><b>Only a signed app gets in.</b> The surface is guarded by a signature-level permission, and the
 * transport additionally checks that the package a caller claims is signed with the transport's own key.
 * A build of Mininotes signed with another key is refused at the door, silently, as it should be — the
 * refusal is a locked door, not a fault. Until this app is signed into that family the checklist says so
 * rather than leaving somebody to wonder.
 *
 * <p>Read only, as things stand: the address, and whether the transport will answer at all. Sending is
 * the same surface and goes in when there is something to send it to.
 */
final class MaximaConnection implements AutoCloseable {
    static final String TRANSPORT="com.eurobuddha.maxima.app";
    /** The Maxima application string this app owns. Namespaced, so nobody else's traffic is ours. */
    static final String APPLICATION="mininotes.v1";

    private static final String REGISTER=TRANSPORT+".REGISTER";
    private static final String IDENTITY=TRANSPORT+".IDENTITY";
    private static final String RESPONSE=TRANSPORT+".RESPONSE";
    private static final String EXTRA_PACKAGE="package", EXTRA_CLASS="class", EXTRA_REQUEST_ID="requestid";
    private static final String EXTRA_ENABLED="enabled", EXTRA_RESULT="result", EXTRA_ERROR="error";
    private static final String EXTRA_ADDRESSES="addresses";

    private final Context context;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,Consumer<Intent>> asked=new HashMap<>();
    private boolean closed;

    /** Where a reply lands. Exported because the transport addresses it by name, and empty otherwise. */
    public static final class Answers extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){/* the live one is registered below */}
    }

    private final BroadcastReceiver hearing=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            String id=intent==null?null:intent.getStringExtra(EXTRA_REQUEST_ID);
            if(id==null)return;
            Consumer<Intent> waiting=asked.remove(id);
            if(waiting!=null)waiting.accept(intent);
        }
    };

    MaximaConnection(Context c) {
        context=c.getApplicationContext();
        IntentFilter what=new IntentFilter(RESPONSE);
        // Exported on purpose: the reply comes from another app, so a receiver that refused outside
        // broadcasts would refuse the only one it exists for. It carries nothing but a request id it
        // must already be waiting on, so a stranger shouting into it is heard and dropped.
        context.registerReceiver(hearing,what,Context.RECEIVER_EXPORTED);
    }

    /** Whether the transport app is on this phone at all. */
    boolean installed() {
        try{context.getPackageManager().getPackageInfo(TRANSPORT,0);return true;}
        catch(Exception e){return false;}
    }

    /**
     * Whether the transport will answer this app: installed, and this app approved in it.
     *
     * <p>A refusal at the signature gate is silence, not an answer, so the wait has an end to it — the
     * question is asked once and answered no if nothing comes back.
     */
    void answers(Consumer<Boolean> said) {
        if(!installed()){said.accept(false);return;}
        ask(REGISTER,null,reply->said.accept(reply!=null&&reply.getBooleanExtra(EXTRA_ENABLED,false)));
    }

    /**
     * This device's Maxima address, as the transport knows it. The transport may hold more than one — a
     * node reachable through two hosts has two — and the first is the one to hand out.
     */
    void address(Consumer<String> found,Consumer<String> failed) {
        if(!installed()) {
            failed.accept("The Maxima transport app is not on this phone. Minima Core cannot supply an "
                +"address: its build has no Maxima in it.");
            return;
        }
        ask(REGISTER,null,registered->{
            if(registered==null) {
                failed.accept("The Maxima app did not answer. Mininotes has to be signed with the same key "
                    +"as it before it is allowed to ask.");
                return;
            }
            if(!registered.getBooleanExtra(EXTRA_ENABLED,false)) {
                String how=registered.getStringExtra(EXTRA_RESULT);
                failed.accept(how!=null&&!how.isEmpty()?capital(how)+"."
                    :"Approve Mininotes in the Maxima app, then ask again.");
                return;
            }
            ask(IDENTITY,null,reply->{
                if(reply==null){failed.accept("The Maxima app did not answer.");return;}
                String wrong=reply.getStringExtra(EXTRA_ERROR);
                if(wrong!=null&&!wrong.isEmpty()){failed.accept(capital(wrong)+".");return;}
                String all=reply.getStringExtra(EXTRA_ADDRESSES);
                if(all==null||all.trim().isEmpty()) {
                    failed.accept("The Maxima app knows no address for this device yet. Open it and wait "
                        +"for it to find a host.");
                    return;
                }
                // More than one host means more than one way to be reached, and any of them works. One of
                // them is handed out, and it is the same one the transport's own screen would hand out: the
                // host that is already an IP, because a name can stop meaning what it meant.
                String first=null;
                for(String one:all.split(",")) {
                    String said=one.trim();
                    if(!Pairing.reachable(said))continue;
                    if(first==null)first=said;
                    if(anIp(hostOf(said))){found.accept(said);return;}
                }
                if(first!=null){found.accept(first);return;}
                failed.accept("The Maxima app gave no address with a host on it, so nothing could reach "
                    +"this phone through it yet.");
            });
        });
    }

    private void ask(String action,String said,Consumer<Intent> back) {
        if(closed){back.accept(null);return;}
        final String id=random();
        asked.put(id,back);
        Intent out=new Intent(action).setPackage(TRANSPORT);
        out.putExtra(EXTRA_PACKAGE,context.getPackageName());
        out.putExtra(EXTRA_CLASS,Answers.class.getName());
        out.putExtra(EXTRA_REQUEST_ID,id);
        if(said!=null)out.putExtra("application",APPLICATION);
        try{context.sendBroadcast(out);}
        catch(Exception e){asked.remove(id);back.accept(null);return;}
        // A door that is locked does not say so. Without an end to the wait, a refused app would sit on
        // a spinner for ever, which is the one answer worse than no.
        handler.postDelayed(()->{
            Consumer<Intent> late=asked.remove(id);
            if(late!=null)late.accept(null);
        },6000);
    }

    /** What follows the @, or nothing where there is no @ — a permanent address has none. */
    private static String hostOf(String address) {
        int at=address.lastIndexOf('@');
        if(at<0)return "";
        String rest=address.substring(at+1);
        int port=rest.lastIndexOf(':');
        return port<0?rest:rest.substring(0,port);
    }

    /** Four numbers and three dots. A host that is already a number cannot stop meaning what it meant. */
    private static boolean anIp(String host) {
        if(host==null||host.isEmpty())return false;
        String[] parts=host.split("\\.",-1);
        if(parts.length!=4)return false;
        for(String part:parts) {
            if(part.isEmpty()||part.length()>3)return false;
            for(int at=0;at<part.length();at++)if(!Character.isDigit(part.charAt(at)))return false;
            if(Integer.parseInt(part)>255)return false;
        }
        return true;
    }

    private static String capital(String said) {
        return said==null||said.isEmpty()?"":Character.toUpperCase(said.charAt(0))+said.substring(1);
    }

    private static String random() {
        byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);
        StringBuilder b=new StringBuilder("0x");
        for(byte v:bytes)b.append(String.format("%02x",v&255));
        return b.toString();
    }

    @Override public void close() {
        if(closed)return;
        closed=true;
        try{context.unregisterReceiver(hearing);}catch(Exception e){/* never registered, or already gone */}
        asked.clear();
    }
}
