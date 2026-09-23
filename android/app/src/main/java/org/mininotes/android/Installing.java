// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * What Android says back about a build handed to it.
 *
 * <p>The pad fetches a newer build and checks it, and then it is Android's turn: the installer asks the
 * person whether to go ahead, and answers here. Nearly always the answer is "ask them", which comes with
 * the screen to show; that screen is shown and the rest is Android's. Success is not answered to, because
 * by then this process has been replaced by the new one - the new build says so itself when it opens.
 * What is left is refusal, which is said in a line and no more.
 */
public final class Installing extends BroadcastReceiver {
    static final String STATUS="org.mininotes.android.INSTALLING";

    @SuppressWarnings("deprecation")
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null||!STATUS.equals(intent.getAction()))return;
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        switch(status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent ask=intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if(ask==null)return;
                ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(ask);
                return;
            }
            case PackageInstaller.STATUS_SUCCESS: return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                Toast.makeText(context,"Not installed. Nothing was changed.",Toast.LENGTH_LONG).show();
                return;
            default: {
                String said=intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context,"Android did not install it"+(said==null||said.isEmpty()?".":": "+said),
                    Toast.LENGTH_LONG).show();
            }
        }
    }
}
