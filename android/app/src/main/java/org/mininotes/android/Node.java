// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.content.Context;
import android.content.SharedPreferences;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.codec.MiniData;
import com.eurobuddha.maxima.core.crypto.Hashes;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.session.Bootstrap;
import com.eurobuddha.maxima.core.store.FileStore;
import java.io.File;
import java.util.List;

/**
 * This pad, as a node on the Maxima network.
 *
 * <p>The address was asked of other apps for a long time and the answer was always somebody else's to give:
 * Minima Core has no Maxima in its build at all, and the transport app answers only apps signed with its
 * own key. Both of those are somebody else's decision about this app. So the app stopped asking. The
 * transport is a library — Java 11, no dependencies, made to be embedded — and Mininotes now carries it,
 * the way the transport's own phone app does. The address is not fetched. It is simply known, because this
 * is the thing that has one.
 *
 * <p>Two things the platform will not supply, injected rather than depended on:
 * <ul>
 *   <li><b>SHA3-256</b>, which Android has at no version — see {@link Sha3}. The core checks any hash it
 *       is handed against a published answer before it will use one, because a subtly wrong hash makes this
 *       phone a different phone on the network, quietly.</li>
 *   <li><b>Where to keep things</b>: the app's own folder, so a contact and an address survive the process
 *       being killed, which on a phone is whenever the system feels like it.</li>
 * </ul>
 *
 * <p>Starting means attaching to relays over the network, which takes as long as it takes. Nothing here is
 * ever called from the interface thread: {@link #address} is handed to {@link Background}, like every other
 * slow thing in this app.
 */
final class Node {
    /** What this build calls itself on the wire. */
    private static final String VERSION="mininotes";
    /** How many relays to hold at once. More than one, because one is a single point of failure. */
    private static final int RELAYS=2;
    /** Long enough for a slow phone on a slow network; short enough that nobody waits for ever. */
    private static final int WAITING=30000;

    /**
     * How often the node is looked after, in seconds.
     *
     * <p>The transport does not look after itself: it is a library, and says in as many words that whoever
     * carries it drives its upkeep. A relay stops reading from a client it has not heard from in ten
     * minutes, and a keep-alive is due every two — so left alone, this phone was dropped by every relay it
     * had within ten quiet minutes of opening, and nothing ever went back for another. It looked exactly
     * like a node that was working, until something was sent to it.
     */
    private static final int BEAT=30;

    private static MaximaNode node;
    private static boolean tried;
    private static java.util.concurrent.ScheduledExecutorService keeper;
    /** How many rounds of upkeep there have been. Only the upkeep thread touches it. */
    private static int beats;
    /** Something else that wants doing every round, after the node has been looked after. */
    private static volatile Runnable alsoEachBeat;
    private static final java.util.concurrent.ExecutorService ALSO=
        java.util.concurrent.Executors.newSingleThreadExecutor(work->{
            Thread one=new Thread(work,"mininotes-each-beat");one.setDaemon(true);return one;});

    /**
     * Whatever else should happen every half minute for as long as the process lives. Run on a thread of
     * its own, so that a slow round of it never holds up the keep-alives that come next.
     */
    static void everyBeat(Runnable also){alsoEachBeat=also;}

    private Node(){}

    /**
     * This device's addresses, first the one to hand out. Empty while no relay has been reached — which is
     * an answer, not a failure: a phone with no way out has no address anybody could use.
     *
     * <p>Blocking. Call it on the worker.
     */
    static List<String> addresses(Context where) throws Exception {
        return started(where).myAddresses();
    }

    /** The permanent address, where one exists. It survives the host moving, which an ordinary one does not. */
    static String permanent(Context where) throws Exception {
        return started(where).permanentAddress();
    }

    /**
     * Whether this app is allowed on the network at all.
     *
     * <p>On most Android phones this is granted at install and never thought about again. GrapheneOS lets
     * the owner refuse it per app, and a refused app is not told: sockets simply fail. A node with no way
     * out reaches no relay, has no address, and looks exactly like a node that is still starting — which
     * is the one thing it must not look like, because one of those is worth waiting for and the other
     * never will be.
     */
    static boolean allowedOnTheNetwork(Context where) {
        return where.checkSelfPermission(android.Manifest.permission.INTERNET)
            ==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Whether the node is up, without starting one. */
    static synchronized boolean running(){return node!=null;}

    /** The node itself, started if it is not. Null only where starting failed. */
    static MaximaNode node(Context where) {
        try{return started(where);}catch(Exception e){return null;}
    }

    /**
     * The one thing here that is done under a lock: making the node, once.
     *
     * <p>Everything else used to be as well, and that was a fault. Telling contacts where this phone is can
     * take a minute and a half - the transport gives a round of it that long - and it was done holding the
     * same lock a note needs in order to be sent. So for the first ninety seconds after the pad was opened,
     * nothing written in it went anywhere. The node is made to be used from several threads at once; only
     * the making of it has to happen one at a time.
     */
    private static synchronized MaximaNode started(Context where) throws Exception {
        start(where);
        return node;
    }

    /**
     * Who to tell when something arrives for this app. Set once, before anything is sent, so a reply to
     * the first thing sent is not the one message that lands with nobody listening.
     */
    static boolean listen(Context where,java.util.function.Consumer<byte[]> heard) {
        MaximaNode up=node(where);
        if(up==null)return false;
        up.setMessageListener((message,id)->{
            if(message==null)return;
            if(message.mApplication==null||!APPLICATION.equals(message.mApplication.toString()))return;
            if(message.mData==null)return;
            // That something came, and how big. Whether the transport brought nothing or the app dropped
            // what it brought are two different faults, and from the outside they look the same.
            android.util.Log.i("Mininotes/Node","heard "+message.mData.getBytes().length+" bytes for this app");
            heard.accept(message.mData.getBytes());
        });
        return true;
    }

    /**
     * Tell another node about this one, and learn it back.
     *
     * <p>An address is a snapshot. A node that restarts, or moves to another relay, is at a different one
     * within the minute - key and host both - so a code scanned at nine o'clock is a wrong number by ten,
     * and a note sent to it is accepted by a relay on behalf of nobody. That is not a fault to work around:
     * the transport already has the answer, and this app was not using it.
     *
     * <p>An introduction makes each node a <i>contact</i> of the other: a stable identity key, every address
     * it is currently reachable at, and the directory to ask when none of them answer. From then on the
     * pair keep each other current by themselves.
     *
     * @return the peer's identity key, to be kept beside their address, or empty if they did not answer
     */
    static String introduce(Context where,String address) throws Exception {
        MaximaNode up=node(where);
        if(up==null||address==null||address.trim().isEmpty())return "";
        // One introduction at a time, because "whoever is new" is how the peer is recognised - but under
        // a lock of its own, so that meeting somebody does not hold up a note on its way to somebody else.
        synchronized(MEETING) {
            java.util.Set<String> before=new java.util.HashSet<>();
            for(Contact known:up.contacts())before.add(known.publicKey);
            up.introduce(address.trim(),true);
            // Whoever is new is the one we just met.
            for(Contact known:up.contacts())if(!before.contains(known.publicKey))return known.publicKey;
            // Met before: find them by the address we dialled, which they may since have added to.
            for(Contact known:up.contacts())
                if(known.addresses.contains(address.trim()))return known.publicKey;
            return "";
        }
    }
    private static final Object MEETING=new Object();

    /** One peer, as the transport knows them now, or null if it does not. */
    static Contact known(Context where,String key) {
        if(key==null||key.trim().isEmpty())return null;
        MaximaNode up=node(where);
        return up==null?null:up.contact(key.trim());
    }

    /**
     * Everybody told where this phone is now.
     *
     * <p>Done when the app opens, because that is when this node has just been given an address it did not
     * have a minute ago. Without it the other end goes on sending to where we were.
     */
    static void tellEverybody(Context where) {
        MaximaNode up=node(where);
        if(up==null)return;
        up.refreshContacts();
    }

    /** The application string this app owns, kept beside the node that filters on it. */
    private static final String APPLICATION=Post.APPLICATION;

    private static void start(Context where) throws Exception {
        if(node!=null)return;
        // Once. A second attempt after a real failure is the reader's to ask for, not something to retry
        // behind their back while they wait.
        if(tried&&node==null)throw new IllegalStateException("The node could not be started.");
        tried=true;
        Hashes.setSha3(Sha3::of);
        MaximaIdentity me=identity(where);
        MaximaNode made=new MaximaNode(me,VERSION,RELAYS);
        made.setStore(new FileStore(new File(where.getFilesDir(),"node")));
        made.setName(nameHere(where));
        // The bootstrap list is the floor discovery starts from, never the whole of it: a relay that
        // answers gossips its own, and the pool keeps the ones that work.
        int attached=made.start(Bootstrap.RELAYS,WAITING);
        // Counts, not the address itself: an address is not a secret but it does name this phone, and a
        // log is read by more things than the person who owns it.
        android.util.Log.i("Mininotes/Node","attached to "+attached+" of "+Bootstrap.RELAYS.size()
            +" relays, holding "+made.myAddresses().size()+" address(es)");
        node=made;
        keeper=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(work->{
            Thread one=new Thread(work,"mininotes-node-upkeep");one.setDaemon(true);return one;});
        keeper.scheduleWithFixedDelay(Node::lookAfter,BEAT,BEAT,java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * One round of upkeep: keep-alives, relays that have gone quiet swapped for ones that answer, everybody
     * told if that moved this phone, and the transport's own twenty-minute round when it falls due.
     *
     * <p>Outside the lock, because finding a new relay takes as long as the network takes and a note being
     * sent should not have to wait behind it. And nothing thrown here gets out: a scheduled task that
     * throws once is never run again, which would be the old fault back again, silently, an hour in.
     */
    private static void lookAfter() {
        MaximaNode up;
        synchronized(Node.class){up=node;}
        if(up==null)return;
        try {
            int before=up.myAddresses().size();
            up.maintain(WAITING);
            int after=up.myAddresses().size();
            // Said when it changes, and otherwise once in ten minutes: enough to see afterwards that the
            // node was being looked after through a quiet hour, without a line every thirty seconds.
            if(before!=after||++beats%20==0)
                android.util.Log.i("Mininotes/Node","upkeep: holding "+after+" address(es)"
                    +(before!=after?", was "+before:""));
        } catch(Throwable notNow){android.util.Log.w("Mininotes/Node","upkeep failed: "+notNow.getClass().getSimpleName());}
        final Runnable also=alsoEachBeat;
        if(also!=null)try{ALSO.execute(()->{try{also.run();}catch(Throwable notNow){/* next round */}});}
            catch(RuntimeException full){/* next round */}
    }

    /** How many relays this phone is attached to now, without starting anything. */
    static int attached() {
        MaximaNode up;
        synchronized(Node.class){up=node;}
        return up==null?0:up.myAddresses().size();
    }

    /**
     * Who this phone is, the same every time. The seed is twenty-four words' worth of entropy, made once
     * and kept: a new one each start would be a new identity each start, and everybody who had the old
     * address would be writing to nobody.
     */
    private static MaximaIdentity identity(Context where) {
        SharedPreferences kept=where.getSharedPreferences("node",Context.MODE_PRIVATE);
        String seed=kept.getString("seed","");
        if(!seed.isEmpty())return MaximaIdentity.fromSeed(new MiniData(seed));
        MaximaIdentity.Created made=MaximaIdentity.create();
        kept.edit().putString("seed",made.identity.seed().to0xString()).apply();
        return made.identity;
    }

    /** Renamed while running, so a name changed in the profile is the name the network sees from then on. */
    static synchronized void called(Context where,String said) {
        if(node!=null&&said!=null&&!said.trim().isEmpty())node.setName(said.trim());
    }

    /** What this pad is called: what its owner chose, or what the phone is called until they have. */
    static String nameHere(Context where) {
        String kept=where.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("me","");
        return kept.trim().isEmpty()?name(where):kept.trim();
    }

    /** What the phone is called, for anybody who has to recognise it in a list. */
    private static String name(Context where) {
        String said=android.os.Build.MODEL;
        return said==null||said.trim().isEmpty()?"A phone":said.trim();
    }
}
