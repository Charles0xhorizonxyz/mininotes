// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * The node looked after while the phone sleeps.
 *
 * <p>A sleeping phone runs no threads, the upkeep thread's included. So a phone in a drawer sent no
 * keep-alives, every relay stopped reading from it within ten minutes, and a note sent to it in the night
 * was taken by a relay on behalf of nobody. An alarm that is allowed to go off while the phone is idle is
 * the one thing that still runs. Android is free to deliver it late, by up to three quarters of the time
 * asked for, so it is asked for every five minutes: at its latest that is under nine, still inside the
 * relays' ten. One short wake-up in five to nine minutes, and nothing more.
 */
public final class Waking extends BroadcastReceiver {
    /** Seen on the Pro: an eight-minute ask was given a window to thirteen and a half, past the relays' ten. */
    private static final long EVERY=5*60_000L;

    /** Set, or set again: the next wake-up, while the pad is listening. */
    static void again(Context any) {
        AlarmManager alarms=any.getSystemService(AlarmManager.class);
        if(alarms==null)return;
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime()+EVERY,pending(any));
    }

    /** No more wake-ups: the pad has stopped listening. */
    static void stop(Context any) {
        AlarmManager alarms=any.getSystemService(AlarmManager.class);
        if(alarms!=null)alarms.cancel(pending(any));
    }

    private static PendingIntent pending(Context any) {
        Context app=any.getApplicationContext();
        return PendingIntent.getBroadcast(app,0,new Intent(app,Waking.class),PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onReceive(Context context,Intent intent) {
        final Context app=context.getApplicationContext();
        if(!Listening.switchedOn(app))return;
        // The phone stays awake until this says it is finished, and not a moment longer.
        final PendingResult finished=goAsync();
        new Thread(()->{
            try{if(Node.running())Node.roundNow();}
            catch(Throwable notNow){/* the next one */}
            finally{again(app);finished.finish();}
        },"mininotes-waking").start();
    }
}
