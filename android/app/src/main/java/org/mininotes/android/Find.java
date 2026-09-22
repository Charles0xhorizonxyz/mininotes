// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import java.util.Locale;

/**
 * Looking for a word, and showing where it was found.
 *
 * <p>A list of names that match tells you a note matched and nothing else — which note it was, and whether
 * it is the one you meant, is in the line it was found on. So a result carries the words around the match,
 * on one line, with the rest of the note left out.
 *
 * <p>Holds no Android types, so what is found and how it reads are unit tested.
 */
final class Find {

    /** How much of the line around a match to show. Enough to recognise it, short enough for one row. */
    static final int AROUND=64;

    private Find(){}

    /** A word, tidied for looking with: nothing to look for is nothing to look for. */
    static String tidy(String term) {
        return term==null?"":term.trim();
    }

    /** Whether this text holds that word, ignoring case. */
    static boolean holds(String text,String term) {
        if(text==null||term==null)return false;
        String what=tidy(term);
        if(what.isEmpty())return false;
        return text.toLowerCase(Locale.ROOT).contains(what.toLowerCase(Locale.ROOT));
    }

    /**
     * The words around the first match, on one line.
     *
     * <p>Cut at the match rather than at the start, because a word four hundred characters into a note is
     * not shown by the first forty. Broken lines become spaces: a result is one row, and a note that
     * happened to have a paragraph break in the middle of the match should not become three rows.
     */
    static String around(String text,String term) {
        if(text==null)return "";
        String flat=text.replace('\n',' ').replace('\r',' ').replace('\t',' ');
        while(flat.contains("  "))flat=flat.replace("  "," ");
        flat=flat.trim();
        String what=tidy(term);
        int at=what.isEmpty()?-1:flat.toLowerCase(Locale.ROOT).indexOf(what.toLowerCase(Locale.ROOT));
        if(at<0)return flat.length()<=AROUND?flat:flat.substring(0,AROUND).trim()+"…";
        // A little before the word, so it is read in its sentence rather than at the edge of the row.
        int from=Math.max(0,at-AROUND/3);
        int to=Math.min(flat.length(),from+AROUND);
        String piece=flat.substring(from,to).trim();
        return (from>0?"…":"")+piece+(to<flat.length()?"…":"");
    }

    /** What to put in a LIKE, with the characters SQLite would otherwise read as wildcards escaped. */
    static String like(String term) {
        String what=tidy(term);
        StringBuilder out=new StringBuilder("%");
        for(int at=0;at<what.length();at++) {
            char one=what.charAt(at);
            if(one=='%'||one=='_'||one=='\\')out.append('\\');
            out.append(one);
        }
        return out.append('%').toString();
    }
}
