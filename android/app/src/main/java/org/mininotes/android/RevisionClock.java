// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Sync-domain primitive; deliberately not wired to transport until pairing and persistence exist. */
public final class RevisionClock {
    public enum Relation { SAME, BEFORE, AFTER, CONCURRENT }
    public static Relation compare(Map<String,Long> a,Map<String,Long> b) {
        Set<String> devices=new HashSet<>(a.keySet());devices.addAll(b.keySet());boolean less=false,more=false;
        for(String device:devices){long x=a.getOrDefault(device,0L),y=b.getOrDefault(device,0L);if(x<0||y<0)throw new IllegalArgumentException("Negative revision");less|=x<y;more|=x>y;}
        return less&&more?Relation.CONCURRENT:less?Relation.BEFORE:more?Relation.AFTER:Relation.SAME;
    }
    private RevisionClock(){}
}
