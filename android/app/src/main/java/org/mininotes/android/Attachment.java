// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

/**
 * What a file kept with a note is allowed to be. A file arrives from another app, so nothing about it can
 * be trusted: the name is whatever that app said, the type may be missing, and the size may be more than a
 * pad should swallow. Holds no Android types, so these rules are unit tested without a device.
 */
final class Attachment {
    /** One file, at most. Big enough for a photograph or a scan, small enough to copy without a wait. */
    static final long LIMIT=25L*1024*1024;
    /** What a pad will hold in files altogether before it says so. */
    static final long PLENTY=500L*1024*1024;
    /** What a file is called when the app it came from would not say. */
    static final String UNNAMED="file";
    /** What a file is when nothing says what it is: something to hand to whatever will take it. */
    static final String ANYTHING="application/octet-stream";

    /**
     * The name to keep. Another app's name for a file can carry a path, a newline, or three hundred
     * characters; none of that is a name, and a path is how a backup writes outside the folder it was
     * unpacked into. What is left is the last part of it, on one line, short enough to read.
     */
    static String named(String given) {
        if(given==null)return UNNAMED;
        String one=given.replace('\\','/');
        int last=one.lastIndexOf('/');
        if(last>=0)one=one.substring(last+1);
        StringBuilder kept=new StringBuilder();
        for(int at=0;at<one.length();at++) {
            char c=one.charAt(at);
            if(c=='\n'||c=='\r'||c=='\t'||c<' ')kept.append(' ');
            else kept.append(c);
        }
        String name=kept.toString().trim();
        while(name.startsWith("."))name=name.substring(1).trim();
        if(name.length()>120)name=name.substring(0,120).trim();
        return name.isEmpty()?UNNAMED:name;
    }

    /** What the file is, as a type something else can open, or the type that means "anything". */
    static String kind(String given) {
        if(given==null)return ANYTHING;
        String said=given.trim();
        if(said.isEmpty()||said.indexOf('/')<0)return ANYTHING;
        return said.length()>120?ANYTHING:said;
    }

    /** How big it is, said the way a person would say it. */
    static String size(long bytes) {
        if(bytes<0)return "0 bytes";
        if(bytes<1024)return bytes+(bytes==1?" byte":" bytes");
        if(bytes<1024*1024)return Math.round(bytes/1024f)+" KB";
        double mb=bytes/(1024d*1024d);
        if(mb<10)return Math.round(mb*10)/10d+" MB";
        return Math.round(mb)+" MB";
    }

    /** Whether one file is more than a pad will take. */
    static boolean tooBig(long bytes){return bytes>LIMIT;}

    /**
     * Where a file sits inside a backup. The name is the row's own id, which the app made, so a backup
     * written by this app can never name a path; one written by anything else is refused on the way in.
     */
    static String entry(String id){return "files/"+id;}

    /**
     * The id a backup entry is for, or null if that entry is not a file of ours. A zip can name any path
     * it likes, including one climbing out of the folder it is unpacked into, so only a plain id is taken.
     */
    static String idOf(String entry) {
        if(entry==null||!entry.startsWith("files/"))return null;
        String id=entry.substring("files/".length());
        if(id.isEmpty()||id.length()>64)return null;
        for(int at=0;at<id.length();at++) {
            char c=id.charAt(at);
            boolean plain=(c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-';
            if(!plain)return null;
        }
        return id;
    }

    private Attachment(){}
}
