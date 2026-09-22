// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The node kept up while the pad is closed, so a note can arrive when nobody is looking.
 *
 * <p>Maxima is not a shelf: nothing waits anywhere for this phone to come and fetch it. A note sent while
 * nobody is attached is a note a relay took in on behalf of nobody. So for as long as the node lived only
 * while the pad was on screen, sharing worked when two people happened to have the app open together and
 * did nothing at all when they did not — demonstrable rather than usable.
 *
 * <p>Android will let a process stay up for this on one condition, which is that it says so: a notification
 * for as long as it lasts. That is the right condition. It is up only while there is somebody to hear from —
 * a pad that has never been paired with anything has nothing to listen for and keeps nothing running — and
 * it can be switched off from Profile, or from the notification itself, which is where a person looking at
 * it and wondering is already standing.
 *
 * <p>The node itself is {@link Node}'s, and belongs to the process rather than to this. What this holds is
 * the process.
 */
public final class Listening extends Service {
    private static final String STAYING="listening", ARRIVED="arrived";
    private static final String STOP="org.mininotes.android.STOP_LISTENING";
    /** Carried by a notification that is about one note, so tapping it opens that note. */
    static final String NOTE="note";
    private static final int ONGOING=1, LANDED=2, ACCEPTED=3;

    /** Whoever is on screen, told as things land. Null while nobody is, and then the phone says it instead. */
    private static volatile Consumer<Post.Landed> watcher;
    /** Offers taken up while nobody was there to be asked. Put to them the moment somebody is. */
    private static final List<Hello.Said> unanswered=new ArrayList<>();
    private static boolean hearing;

    /** Whether the person wants this at all. They do until they say otherwise. */
    static boolean switchedOn(Context any) {
        return any.getSharedPreferences("settings",Context.MODE_PRIVATE).getBoolean("listening",true);
    }

    static void switchOn(Context any,boolean on) {
        any.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putBoolean("listening",on).apply();
    }

    /**
     * Started where it should be running and stopped where it should not. Blocking: it asks the notebook
     * whether there is anybody to hear from.
     *
     * <p>Called while the pad is on screen, because that is the one moment Android allows something like
     * this to be started — which is also the honest moment, since it is when the person can see it begin.
     */
    static void settle(Context any) {
        Context app=any.getApplicationContext();
        Intent service=new Intent(app,Listening.class);
        try {
            if(switchedOn(app)&&NoteStore.of(app).anybodyPaired())app.startForegroundService(service);
            else app.stopService(service);
        } catch(RuntimeException notAllowed){/* not on screen after all; the next opening settles it */}
    }

    /** Who is on screen, or null when nobody is any more. */
    static void watch(Consumer<Post.Landed> there){watcher=there;}

    /**
     * Taken down - but only by whoever put it up. A screen that is going away after another has already
     * come up must not leave the one people are looking at with nobody telling it anything.
     */
    static synchronized void unwatch(Consumer<Post.Landed> there){if(watcher==there)watcher=null;}

    /** Every offer taken up while nobody was looking, handed over once. */
    static List<Hello.Said> unanswered() {
        synchronized(unanswered) {
            List<Hello.Said> all=new ArrayList<>(unanswered);
            unanswered.clear();
            return all;
        }
    }

    /**
     * The node told where to bring what arrives. Blocking — it starts the node if nothing has — and safe to
     * ask for twice.
     *
     * <p>It belongs to the process and not to a screen. A screen used to set it, holding its own notebook,
     * and a screen goes away: what arrived afterwards was handed to a notebook that had been closed.
     */
    static synchronized void hear(Context any) {
        final Context app=any.getApplicationContext();
        final NoteStore store=NoteStore.of(app);
        final Keys keys=Keys.of(app);
        // The notebook names this phone in every membership it sends, and leaves this phone out of every
        // one that arrives. Without being told who that is, it would take its own entry in somebody's list
        // for a stranger and start sharing with itself. Said again every time, because the name and the
        // address are both things that change.
        try {
            store.mySigningKey=android.util.Base64.encodeToString(
                keys.signing().getPublic().getEncoded(),android.util.Base64.NO_WRAP);
            store.myName=Node.nameHere(app);
            store.myAddress=app.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("address","");
        } catch(Exception notNow){return;}
        if(hearing)return;
        // What was handed over and never answered is sent again from the node's own upkeep, so it goes on
        // happening for as long as the process lives, screen or no screen.
        Node.everyBeat(()->Post.again(app,store,keys));
        hearing=Node.listen(app,message->{
            Post.Landed landed=Post.arrived(app,store,keys,message);
            if(landed==null)return;
            Consumer<Post.Landed> there=watcher;
            if(there!=null){there.accept(landed);return;}
            // Somebody saying they have something is for the marks, not for the person.
            if(landed.answered)return;
            if(landed.accepted!=null) {
                synchronized(unanswered){unanswered.add(landed.accepted);}
                say(app,ACCEPTED,landed.accepted.name+" accepted what you offered",
                    "Open the pad to give it to them, or not.","");
            } else if(landed.said!=null)say(app,LANDED,landed.said,"",landed.note);
        });
    }

    // ---- the service itself ----------------------------------------------------------------------------

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&STOP.equals(intent.getAction())) {
            // From the notification: the way out is where the thing is. Remembered, so it stays off.
            switchOn(this,false);
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if(Build.VERSION.SDK_INT>=34)
                startForeground(ONGOING,ongoing(),ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            else startForeground(ONGOING,ongoing());
        } catch(RuntimeException refused) {
            // Brought back by the system with nobody on screen, which newer Android does not always allow.
            // Nothing is lost by stopping: the next time the pad is opened it is started again.
            stopSelf();
            return START_NOT_STICKY;
        }
        // Off this thread: starting a node means reaching relays, which takes as long as it takes.
        new Thread(()->{
            try {
                if(!switchedOn(this)||!NoteStore.of(this).anybodyPaired()){stopSelf();return;}
                hear(this);
            } catch(Exception notNow){/* the process is up, and the next opening tries again */}
        },"mininotes-listening").start();
        return START_STICKY;
    }

    private Notification ongoing() {
        channels(this);
        PendingIntent stop=PendingIntent.getService(this,0,
            new Intent(this,Listening.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,STAYING)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle("Listening for notes")
            .setContentText("So a note can arrive while the pad is closed.")
            .setContentIntent(opening(this,""))
            .setOngoing(true).setShowWhen(false)
            .addAction(new Notification.Action.Builder(null,"Stop listening",stop).build())
            .build();
    }

    // ---- saying that something arrived -----------------------------------------------------------------

    /**
     * One line, saying who and what kind of thing — never what the note says. A notification is read by
     * whoever is holding the phone, locked or not, and by anything else allowed to read notifications.
     */
    private static void say(Context app,int id,String title,String text,String note) {
        channels(app);
        Notification.Builder said=new Notification.Builder(app,ARRIVED)
            .setSmallIcon(R.drawable.ic_listening)
            .setContentTitle(title)
            .setContentIntent(opening(app,note))
            .setAutoCancel(true);
        if(!text.isEmpty())said.setContentText(text);
        NotificationManager all=app.getSystemService(NotificationManager.class);
        // Refused permission is an answer: the note still landed, and the shelves will show it.
        if(all!=null)try{all.notify(id,said.build());}catch(RuntimeException refused){/* as above */}
    }

    private static PendingIntent opening(Context app,String note) {
        Intent open=new Intent(app,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(note!=null&&!note.isEmpty())open.putExtra(NOTE,note);
        // One per note, or the second notification's note would quietly replace the first one's.
        return PendingIntent.getActivity(app,note==null?0:note.hashCode(),open,
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Two channels, because they are two different things to want less of: the line that says the pad is
     * listening, and the line that says something came. Both quiet. A shared list being written in sends
     * every few seconds, and a pad that chimed each time would be switched off within the hour; anybody
     * who wants a sound can turn that one channel up, which is what channels are for.
     */
    private static void channels(Context app) {
        NotificationManager all=app.getSystemService(NotificationManager.class);
        if(all==null)return;
        NotificationChannel staying=new NotificationChannel(STAYING,"Listening for notes",
            NotificationManager.IMPORTANCE_LOW);
        staying.setDescription("Shown for as long as the pad is listening while closed.");
        staying.setShowBadge(false);
        NotificationChannel arrived=new NotificationChannel(ARRIVED,"A note arrived",
            NotificationManager.IMPORTANCE_LOW);
        arrived.setDescription("Somebody shared a note with you, or wrote in one you share.");
        all.createNotificationChannel(staying);
        all.createNotificationChannel(arrived);
    }
}
