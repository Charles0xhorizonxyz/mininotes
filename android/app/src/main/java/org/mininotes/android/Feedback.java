// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * What somebody wants to say about the app, and where it goes.
 *
 * <p>It goes to the repository's issues, as a note anybody can read and anybody can answer. The app does
 * not post it: posting would need a key, and a key inside an app that anybody can download is a key
 * anybody has. What the app does is fill the form in and hand it to the browser, so the person posting is
 * the person who wrote it, under their own name, and can see it afterwards alongside every other one.
 *
 * <p>Nothing here touches the network. It is words in and a web address out, which is why it can be
 * tested without a phone.
 */
final class Feedback {

    private Feedback(){}

    /** What sort of thing is being said, and the label the repository files it under. */
    enum Kind {
        BROKEN("Something is broken","bug"),
        CONFUSING("Something is confusing","confusing"),
        IDEA("An idea","idea"),
        MISSING("Something is missing","missing");

        final String said, label;
        Kind(String said,String label){this.said=said;this.label=label;}
    }

    /**
     * Which part of the app it is about, in the words the app itself uses. A list somebody can answer
     * without thinking: every one of these is a thing you were doing when you decided to say something.
     */
    enum Area {
        WRITING("Writing a note"),
        SHELVES("Collections and books"),
        SHARING("Sharing and syncing"),
        FILES("Attachments"),
        BACKUP("Backup and restore"),
        LOOK("Text size and colour"),
        NODE("The node and addresses"),
        ELSE("Something else");

        final String said;
        Area(String said){this.said=said;}
    }

    /** The label every one of these carries, whatever else it carries, so the whole lot can be found. */
    static final String LABEL="feedback";

    /** As much as a person may write. Long enough for the whole story, short enough to survive a browser. */
    static final int SAID_MOST=4000;

    /** As much as an address may be. A Minima address is around sixty characters; this is room to spare. */
    static final int MINIMA_MOST=120;

    /**
     * How long the address handed to the browser may be.
     *
     * <p>Browsers and the far end both stop reading somewhere around eight thousand characters, and what
     * is past that is not refused, it is quietly dropped — which would take the end off somebody's
     * sentence without telling either of us. Kept well under, and what does not fit is cut here, visibly,
     * rather than there, silently.
     */
    static final int URL_MOST=6000;

    /**
     * The first line of the issue: what it is, where it is, and the beginning of what was said.
     *
     * <p>A list of issues is read down the titles, so a title that only said "Bug" would make the list
     * useless the moment there were two of them.
     */
    static String title(Kind kind,Area area,String said) {
        String first=firstLine(said);
        String start=kind.said+" — "+area.said;
        if(first.isEmpty())return start;
        String whole=start+": "+first;
        return whole.length()<=80?whole:whole.substring(0,79).trim()+"…";
    }

    /**
     * The whole thing as a web address that opens the repository's form already filled in.
     *
     * @param source where the repository is, or empty if there is not one yet
     * @param about  what the app knows about itself — its version, the phone's Android version. Shown to
     *               the person before they send it, because a thing attached without being seen is a thing
     *               sent without being meant.
     * @return the address, or empty when there is no repository to send to
     */
    static String url(String source,Kind kind,Area area,String said,String minima,String about) {
        if(source==null||source.trim().isEmpty())return "";
        String where=source.trim();
        while(where.endsWith("/"))where=where.substring(0,where.length()-1);
        String text=cut(said,SAID_MOST);
        // Built at full length first, then the story alone is shortened until the whole address fits. The
        // story is the only part worth cutting: everything else is a handful of characters that say what
        // the story is about, and losing those would leave a note nobody could file.
        for(int tries=0;tries<40;tries++) {
            String built=build(where,kind,area,text,minima,about);
            if(built.length()<=URL_MOST||text.isEmpty())return built;
            int shorter=Math.max(0,text.length()-Math.max(64,(built.length()-URL_MOST)));
            text=text.substring(0,shorter).trim();
        }
        return build(where,kind,area,"",minima,about);
    }

    private static String build(String where,Kind kind,Area area,String said,String minima,String about) {
        StringBuilder out=new StringBuilder(where).append("/issues/new?template=feedback.yml");
        add(out,"labels",LABEL+","+kind.label);
        add(out,"title",title(kind,area,said));
        add(out,"kind",kind.said);
        add(out,"area",area.said);
        add(out,"what",said);
        String paid=cut(minima,MINIMA_MOST);
        if(!paid.isEmpty())add(out,"minima",paid);
        add(out,"about",about==null?"":about.trim());
        return out.toString();
    }

    private static void add(StringBuilder out,String name,String value) {
        if(value==null||value.isEmpty())return;
        out.append('&').append(name).append('=').append(escape(value));
    }

    private static String escape(String said) {
        try{return URLEncoder.encode(said,"UTF-8");}
        catch(UnsupportedEncodingException never){return "";}
    }

    /**
     * The same thing as plain words.
     *
     * <p>For a phone with no repository to post to yet, and for anybody who would rather send it some
     * other way than through a browser. It is the report, not a summary of it: what is copied is what
     * would have been sent.
     */
    static String plain(Kind kind,Area area,String said,String minima,String about) {
        StringBuilder out=new StringBuilder();
        out.append(title(kind,area,said)).append("\n\n");
        out.append("Kind: ").append(kind.said).append('\n');
        out.append("Part of the app: ").append(area.said).append('\n');
        if(about!=null&&!about.trim().isEmpty())out.append("From: ").append(about.trim()).append('\n');
        String paid=cut(minima,MINIMA_MOST);
        if(!paid.isEmpty())out.append("Minima address: ").append(paid).append('\n');
        out.append('\n').append(cut(said,SAID_MOST));
        return out.toString();
    }

    /** Where the whole conversation is: everything anybody has said, answered or not, open or closed. */
    static String log(String source) {
        if(source==null||source.trim().isEmpty())return "";
        String where=source.trim();
        while(where.endsWith("/"))where=where.substring(0,where.length()-1);
        return where+"/issues?q="+escape("is:issue label:"+LABEL);
    }

    private static String firstLine(String said) {
        if(said==null)return "";
        String tidy=said.trim();
        int stop=tidy.indexOf('\n');
        if(stop>=0)tidy=tidy.substring(0,stop).trim();
        return tidy.length()<=60?tidy:tidy.substring(0,59).trim()+"…";
    }

    private static String cut(String said,int most) {
        if(said==null)return "";
        String tidy=said.trim();
        return tidy.length()<=most?tidy:tidy.substring(0,most).trim();
    }
}
