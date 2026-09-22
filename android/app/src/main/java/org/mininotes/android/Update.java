// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

/**
 * Whether a newer build has been published, and when it is time to look again.
 *
 * <p>The repository keeps one line of text, {@code dist/latest.txt}, holding the newest version's name.
 * That line is all that is ever read: nothing is sent with the request, nothing is downloaded, and the
 * app never installs anything. It only says so, and the person decides.
 *
 * <p>Two questions are answered here, away from the screen so they can be tested. Is the published version
 * really later than this one - not merely different, which is what the first check asked, and which told
 * a phone running a build newer than the repository that an older one "has been published". And has it
 * been long enough since the last look, so that a pad opened forty times a day asks once.
 *
 * <p>Holds no Android types, so it is unit tested.
 */
final class Update {
    /** How long between looks that nobody asked for. A day: a build is published far less often than that. */
    static final long EVERY=24L*60*60*1000;
    /** The longest a version's name is taken to be. The line is somebody else's text until proved otherwise. */
    private static final int LONGEST=32;

    private Update(){}

    /**
     * The line as it was read, reduced to a version's name or to nothing.
     *
     * <p>A leading "v" is allowed because a tag is written that way and a person keeping the file by hand
     * will sooner or later paste one. Anything that is not numbers and dots is not a version: a page that
     * came back in place of the file - a sign-in wall, an error in HTML - is nothing, rather than a
     * "version" the person is then told has been published.
     */
    static String read(String line) {
        if(line==null)return "";
        String said=line.trim();
        if(said.startsWith("v")||said.startsWith("V"))said=said.substring(1);
        if(said.isEmpty()||said.length()>LONGEST)return "";
        if(!said.matches("[0-9]+(\\.[0-9]+)*"))return "";
        return said;
    }

    /**
     * Whether {@code published} is a later version than {@code installed}.
     *
     * <p>Number by number, so 0.0.107 is later than 0.0.99, which as text it is not. A shorter name is read
     * as if it ended in zeros. Either name being unreadable is "no": saying nothing about an update is a
     * small loss, and announcing one that does not exist sends somebody to a page with nothing on it.
     */
    static boolean newer(String published,String installed) {
        long[] theirs=numbers(read(published)),ours=numbers(read(installed));
        if(theirs==null||ours==null)return false;
        for(int i=0;i<Math.max(theirs.length,ours.length);i++) {
            long a=i<theirs.length?theirs[i]:0,b=i<ours.length?ours[i]:0;
            if(a!=b)return a>b;
        }
        return false;
    }

    /**
     * Whether it is time to look again.
     *
     * <p>Never looked is time. A last look in the future is time too: a clock that was set back would
     * otherwise mean never asking again, and the cost of being wrong here is one line of text.
     */
    static boolean due(long now,long lastLooked) {
        return lastLooked<=0||lastLooked>now||now-lastLooked>=EVERY;
    }

    private static long[] numbers(String version) {
        if(version.isEmpty())return null;
        String[] parts=version.split("\\.");
        long[] out=new long[parts.length];
        try{for(int i=0;i<parts.length;i++)out[i]=Long.parseLong(parts[i]);}
        catch(NumberFormatException tooLong){return null;}
        return out;
    }
}
