// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Two people wrote on the same note before either had seen the other. This decides what the note says next.
 *
 * <p>Each side is first read as what it <em>changed</em> about the last text both of them had: a few
 * stretches of lines replaced, removed or added. Where the two sides changed different stretches, both
 * changes are simply taken — you rewrote line three and they rewrote line nine, and the note ends up with
 * both. Only where the two sides wrote over each other is there anything to decide, and then nothing is
 * invented and nothing is thrown away: what each of them wrote stays in the note, both of it — see
 * {@link #both}. A merge that guesses is worse than one that asks, and one that hides half of what was
 * written where nobody looks turned out to be worse than either.
 *
 * <p>Holds no Android types, so what it decides is unit tested without a device.
 */
final class Merge {

    /** What the note says next, and whether anybody has to be told. */
    static final class Result {
        /** The text to keep on the page. */
        final String text;
        /** True when nothing had to be chosen: every change from both sides is in the text. */
        final boolean clean;
        /** How many stretches the two sides wrote over each other in. */
        final int conflicts;
        Result(String text,boolean clean,int conflicts){this.text=text;this.clean=clean;this.conflicts=conflicts;}
    }

    /** One stretch of the old text that a side replaced: lines {@code [from,to)} became {@code said}. */
    private static final class Change {
        final int from,to; final List<String> said;
        Change(int from,int to,List<String> said){this.from=from;this.to=to;this.said=said;}
    }

    /**
     * @param base   the last text both sides had, or null if they never shared one
     * @param mine   what this device says now
     * @param theirs what arrived
     */
    static Result merge(String base,String mine,String theirs) {
        String was=base==null?"":base, here=mine==null?"":mine, there=theirs==null?"":theirs;
        if(here.equals(there))return new Result(here,true,0);
        if(was.equals(here))return new Result(there,true,0);
        if(was.equals(there))return new Result(here,true,0);

        List<String> old=lines(was);
        List<Change> ours=changes(old,lines(here)), yours=changes(old,lines(there));
        List<String> out=new ArrayList<>();
        int conflicts=0, at=0, a=0, b=0;
        while(at<=old.size()) {
            boolean oursHere=a<ours.size()&&ours.get(a).from<=at;
            boolean yoursHere=b<yours.size()&&yours.get(b).from<=at;
            if(!oursHere&&!yoursHere) {
                if(at==old.size())break;
                out.add(old.get(at));at++;continue;
            }
            // A stretch at least one side wrote in, widened until it holds every change that overlaps it.
            // Changes that merely sit next to each other — one side deleted this line, the other rewrote the
            // next — are separate stretches, and both are simply taken. Only writing over the same lines is
            // a thing to decide, and two additions at the very same point count as that.
            int to=at;
            List<Change> mineIn=new ArrayList<>(), theirsIn=new ArrayList<>();
            boolean grew=true;
            while(grew) {
                grew=false;
                while(a<ours.size()&&within(ours.get(a),at,to)) {
                    Change c=ours.get(a++);mineIn.add(c);
                    if(c.to>to){to=c.to;grew=true;}
                }
                while(b<yours.size()&&within(yours.get(b),at,to)) {
                    Change c=yours.get(b++);theirsIn.add(c);
                    if(c.to>to){to=c.to;grew=true;}
                }
            }
            List<String> oursSaid=applied(old,at,to,mineIn), yoursSaid=applied(old,at,to,theirsIn);
            if(mineIn.isEmpty())out.addAll(yoursSaid);
            else if(theirsIn.isEmpty())out.addAll(oursSaid);
            else if(oursSaid.equals(yoursSaid))out.addAll(oursSaid);
            else {out.addAll(both(oursSaid,yoursSaid));conflicts++;}
            at=to;
        }
        return new Result(String.join("\n",out),conflicts==0,conflicts);
    }

    /**
     * Where both wrote over the same lines: what each of them wrote, both of it, in the note.
     *
     * <p>It used to keep this phone's lines on the page and put the other phone's away under Versions. That
     * was careful, and it was wrong in the way that matters: two phones each kept their own, each told the
     * other it had the other's, and the result was two ticks over two different notes, with the difference
     * somewhere nobody looks. A person trying to work out how to make them the same had nothing to press.
     * So nothing is put away. What the two share is there once; what they do not share is there from each;
     * and whoever is reading deletes the line they do not want, which takes a second and needs no teaching.
     *
     * <p>In one order whichever phone works it out. Each phone calls the other "theirs", so "ours first"
     * would give two phones two different texts and the whole thing would start again. The two are put in
     * the order of the words themselves before anything else is done.
     */
    static List<String> both(List<String> ours,List<String> theirs) {
        List<String> first=ours, second=theirs;
        if(String.join("\n",ours).compareTo(String.join("\n",theirs))>0){first=theirs;second=ours;}
        int[] where=matched(first,second);
        List<String> out=new ArrayList<>();
        int a=0, b=0;
        for(int at=0;at<first.size();at++) {
            if(where[at]<0)continue;
            while(a<at)out.add(first.get(a++));
            while(b<where[at])out.add(second.get(b++));
            out.add(first.get(at));
            a=at+1;b=where[at]+1;
        }
        while(a<first.size())out.add(first.get(a++));
        while(b<second.size())out.add(second.get(b++));
        return out;
    }

    /** Whether a change belongs to the stretch being gathered: overlapping it, or starting where it does. */
    private static boolean within(Change c,int at,int to) {
        if(c.from==at&&to==at)return true;
        if(c.from<to)return true;
        // Two additions at the very same point are two people writing in one place, not one after the other.
        return c.from==to&&c.from==c.to&&at==to;
    }

    /** What one side did to the old text, as the stretches of it that side replaced. */
    private static List<Change> changes(List<String> old,List<String> now) {
        int[] where=matched(old,now);
        List<Change> made=new ArrayList<>();
        int side=0, at=0;
        while(at<=old.size()) {
            if(at<old.size()&&where[at]>=0) {
                if(side<where[at])made.add(new Change(at,at,new ArrayList<>(now.subList(side,where[at]))));
                side=where[at]+1;at++;continue;
            }
            int from=at;
            while(at<old.size()&&where[at]<0)at++;
            int upTo=at<old.size()?where[at]:now.size();
            if(from<at||side<upTo)made.add(new Change(from,at,new ArrayList<>(now.subList(Math.min(side,upTo),upTo))));
            side=upTo;
            if(at==old.size())break;
        }
        return made;
    }

    /** The old stretch {@code [from,to)} with one side's changes to it put in. */
    private static List<String> applied(List<String> old,int from,int to,List<Change> changes) {
        List<String> said=new ArrayList<>();
        int at=from;
        for(Change change:changes) {
            while(at<change.from&&at<to)said.add(old.get(at++));
            said.addAll(change.said);
            at=Math.max(at,change.to);
        }
        while(at<to)said.add(old.get(at++));
        return said;
    }

    /**
     * For each line of the old text, where that same line still is on one side, or -1 if it is gone. The
     * longest run the two texts share, so the lines that pair up are the ones that stayed in order.
     */
    private static int[] matched(List<String> old,List<String> now) {
        int[][] same=new int[old.size()+1][now.size()+1];
        for(int a=old.size()-1;a>=0;a--)
            for(int b=now.size()-1;b>=0;b--)
                same[a][b]=old.get(a).equals(now.get(b))?same[a+1][b+1]+1:Math.max(same[a+1][b],same[a][b+1]);
        int[] where=new int[old.size()];
        Arrays.fill(where,-1);
        int a=0,b=0;
        while(a<old.size()&&b<now.size()) {
            if(old.get(a).equals(now.get(b))){where[a]=b;a++;b++;}
            else if(same[a+1][b]>=same[a][b+1])a++;
            else b++;
        }
        return where;
    }

    private static List<String> lines(String text) {
        return text.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(text.split("\n",-1)));
    }

    private Merge(){}
}
