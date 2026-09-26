// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Who receives a page. Sharing is set at four levels — everything, one collection, one book, one page — and
 * a page is reached by every rule that covers it, wherever that rule was set. Setting a rule high up is the
 * point: share a collection once and the books you add to it later are already shared.
 *
 * <p>An address named as another device of yours is two-way, because writing on your tablet has to come back.
 * An address named as someone else is one-way. If the same address is reached at two levels with different
 * roles, two-way wins: it is still your device, whichever level named it.
 *
 * <p>Holds no Android types, so what a rule reaches — and what it must never reach — is unit tested.
 */
final class Sharing {
    enum Scope { LIBRARY, COLLECTION, BOOK, PAGE }
    /** The target of a library rule: there is only one library, so it needs no id. */
    static final String EVERYTHING="*";

    /**
     * What a share lets the other end do.
     *
     * <p>Three, because two was not enough to say the thing people actually mean. Reading is a copy that
     * keeps itself up to date. Writing is the same notebook in two places. <b>Admin</b> is the one that
     * makes a shared thing a shared thing rather than a broadcast: whoever has it may hand it on, and the
     * list of who has it is kept by everybody rather than by whoever happened to start it.
     *
     * <p>Ordered, so "at least this much" is a comparison and not a table.
     */
    enum Level {
        GONE, READ, WRITE, ADMIN;
        boolean writes(){return ordinal()>=WRITE.ordinal();}
        boolean shares(){return this==ADMIN;}
        /** What travels, and what comes back — a number, so an older build reading a newer one is not lost. */
        int said(){return ordinal();}
        static Level of(int said){
            Level[] all=values();
            return said<0?GONE:said>=all.length?ADMIN:all[said];
        }
        /** On the chip, where there is room for two words at most. */
        String words() {
            switch(this) {
                case ADMIN: return "Admin";
                case WRITE: return "Can write";
                case READ: return "Can read";
                default: return "Not shared";
            }
        }

        /** Said in full, for anybody who cannot see the chip. "admin this" is not a sentence. */
        String saying() {
            switch(this) {
                case ADMIN: return "is an admin of this, and can hand it on";
                case WRITE: return "reads and writes this";
                case READ: return "reads this";
                default: return "does not get this";
            }
        }
    }

    static final class Rule {
        /**
         * {@code mine} is what this share lets the other end do: false is read — they receive what you
         * write and cannot change it — and true is read and write, where what they write comes back and is
         * merged into yours. It is the one thing a share decides beyond who, and it is decided per share:
         * the same person can have a book of yours to read and another to work in.
         */
        final Scope scope; final String target,address; final boolean mine;
        /** What they may do. {@code mine} is this, read as "may write", and kept because much reads it. */
        final Level level;
        /**
         * When this was last decided, by the phone that decided it.
         *
         * <p>Two people with admin can change the same person at the same time, on two phones that cannot
         * see each other. Whichever change was made later is the one that stands — not whichever arrived
         * later, which would depend on the weather. It is the only rule that settles by itself.
         */
        final long changed;
        /** The device this is about, by the key it signs with: an address moves, a key does not. */
        final String key;
        Rule(Scope scope,String target,String address,boolean mine) {
            this(scope,target,address,mine?Level.WRITE:Level.READ,0L,"");
        }
        Rule(Scope scope,String target,String address,Level level,long changed,String key) {
            this.scope=Objects.requireNonNull(scope);this.target=Objects.requireNonNull(target);
            this.address=Objects.requireNonNull(address);
            this.level=level==null?Level.READ:level;this.mine=this.level.writes();
            this.changed=changed;this.key=key==null?"":key;
        }
        @Override public boolean equals(Object other) {
            if(!(other instanceof Rule))return false;
            Rule r=(Rule)other;return scope==r.scope&&target.equals(r.target)&&address.equals(r.address);
        }
        @Override public int hashCode(){return Objects.hash(scope,target,address);}
        @Override public String toString(){return scope+" "+target+" -> "+address+(mine?" (my device)":"");}
    }

    /**
     * Every address this page reaches, in the order the rules were given, mapped to whether that address is
     * another device of yours. An address reached at several levels appears once.
     */
    static Map<String,Boolean> audience(Collection<Rule> rules,String collection,String book,String page) {
        Map<String,Boolean> reached=new LinkedHashMap<>();
        for(Rule rule:rules) {
            if(!covers(rule,collection,book,page))continue;
            Boolean mine=reached.get(rule.address);
            reached.put(rule.address,rule.mine||(mine!=null&&mine));
        }
        return reached;
    }

    /**
     * What one device may do with a page, by every rule that reaches it: the rule that says the most, or
     * null where none names them at all.
     *
     * <p>Unlike {@link #audience}, this is given the rows that say somebody is off a thing, and answers
     * with one: taken off is not the same as never given, and a phone hearing from somebody taken off has
     * something to tell them. The device is named by any address it has been at, or by the key it signs
     * with, because an address moves and a rule written last month may still hold the old one.
     */
    static Rule standing(Collection<Rule> rules,String collection,String book,String page,
                         Collection<String> addresses,String key) {
        Rule most=null;
        for(Rule rule:rules) {
            if(!covers(rule,collection,book,page))continue;
            boolean them=(addresses!=null&&addresses.contains(rule.address))
                ||(key!=null&&!key.isEmpty()&&key.equals(rule.key));
            if(!them)continue;
            if(most==null||rule.level.ordinal()>most.level.ordinal())most=rule;
        }
        return most;
    }

    /** True when a rule set at its level applies to this page. A rule for another target must never apply. */
    static boolean covers(Rule rule,String collection,String book,String page) {
        switch(rule.scope) {
            case LIBRARY: return true;
            case COLLECTION: return rule.target.equals(collection);
            case BOOK: return rule.target.equals(book);
            case PAGE: return rule.target.equals(page);
            default: return false;
        }
    }

    /** What moving something would do to its audience: who starts receiving it, and who stops. */
    static final class Change {
        final Map<String,Boolean> gained,lost;
        Change(Map<String,Boolean> gained,Map<String,Boolean> lost){this.gained=gained;this.lost=lost;}
        boolean any(){return !gained.isEmpty()||!lost.isEmpty();}
    }

    /**
     * The audience a page or book would gain and lose by moving. Dragging something into a book its owner
     * shares widely is a disclosure, and dragging it out is a withdrawal; both have to be said out loud
     * before the move, not discovered afterwards. Rules set on the moved thing itself follow it and so
     * appear in neither list.
     *
     * <p>Pass the page id when moving a page, or "" when moving a book, whose own pages keep their own rules.
     */
    static Change moving(Collection<Rule> rules,String fromCollection,String fromBook,
                         String toCollection,String toBook,String page) {
        Map<String,Boolean> before=audience(rules,fromCollection,fromBook,page);
        Map<String,Boolean> after=audience(rules,toCollection,toBook,page);
        Map<String,Boolean> gained=new LinkedHashMap<>(),lost=new LinkedHashMap<>();
        for(Map.Entry<String,Boolean> reached:after.entrySet())
            if(!before.containsKey(reached.getKey()))gained.put(reached.getKey(),reached.getValue());
        for(Map.Entry<String,Boolean> reached:before.entrySet())
            if(!after.containsKey(reached.getKey()))lost.put(reached.getKey(),reached.getValue());
        return new Change(gained,lost);
    }

    /**
     * Where a thing stands: on this device alone, going to your own devices, going to somebody else, or
     * having come from somebody else. It is said on the thing itself rather than kept in a list somewhere,
     * because the one moment it matters is the moment you are looking at the thing.
     *
     * <p>Reaching somebody else outranks reaching your own devices: of the two, that is the one worth
     * seeing at a glance, and the mark's own description says what the whole audience is.
     */
    enum State { HERE, DEVICES, OTHERS, THEIRS }

    /** {@code theirs} is a thing that arrived from another device rather than being written here. */
    static State state(Map<String,Boolean> audience,boolean theirs) {
        if(theirs)return State.THEIRS;
        if(audience==null||audience.isEmpty())return State.HERE;
        for(Boolean mine:audience.values())if(!Boolean.TRUE.equals(mine))return State.OTHERS;
        return State.DEVICES;
    }

    /** One mark per state, in the same vocabulary as the rest: an arrow out, an arrow in, both ways, or none. */
    static String mark(State state) {
        switch(state) {
            case DEVICES: return "⇄";
            case OTHERS: return "↗";
            case THEIRS: return "↙";
            default: return "▫";
        }
    }

    /**
     * Where a thing stands and whether it is up to date, in words rather than in marks. A symbol has to be
     * learnt and then remembered; "Shared · 2 waiting" is read once and understood. Nothing is said about a
     * thing that is only on this phone: that is what everything is until it is shared.
     *
     * @param owed how many notes inside it an address has not been given yet
     * @param brief tiles are narrow, so they take the first half and leave the counting to the cards
     */
    static String says(State state,int owed,boolean brief) {
        String where;
        switch(state) {
            case DEVICES: where="My devices"; break;
            case OTHERS: where="Shared"; break;
            // "Another device" rather than "somebody else": what arrives may be from your own tablet as
            // easily as from a friend, and calling both of them somebody else was wrong half the time.
            case THEIRS: return brief?"From a device":"From another device";
            default: return null;
        }
        if(brief)return owed>0?where+" · "+owed:where;
        return owed>0?where+" · "+owed+" waiting":where+" · up to date";
    }

    /** What is being shared, in as few words as a title can carry — the thing, and what kind of thing. */
    static String shortly(Scope scope,String name) {
        switch(scope) {
            case COLLECTION: return name+" collection";
            case BOOK: return name+" book";
            case PAGE: return "this note";
            default: return "everything";
        }
    }

    /**
     * The same thing, named so that the name still means something on somebody else's phone. "This note"
     * is true where you are standing on it and nowhere else, so an offer that has to travel carries what
     * the thing is called instead.
     */
    static String travelling(Scope scope,String name) {
        switch(scope) {
            case COLLECTION: return "the collection "+name;
            case BOOK: return "the book "+name;
            case PAGE: return "the note "+name;
            default: return "everything on their pad";
        }
    }

    /** What that mark means, said in full for anybody who cannot see it. */
    static String describe(State state,int addresses) {
        switch(state) {
            case DEVICES: return addresses==1?"On one device of yours":"On "+addresses+" devices of yours";
            case OTHERS: return addresses==1?"Shared with one address":"Shared with "+addresses+" addresses";
            case THEIRS: return "Shared with you by another device";
            default: return "On this device only";
        }
    }

    /** What the reader is told they are sharing, at each level. */
    static String describe(Scope scope,String name) {
        switch(scope) {
            case LIBRARY: return "every collection, book and note";
            case COLLECTION: return "the collection "+name+", and every book in it";
            case BOOK: return "the book "+name+", and every note in it";
            default: return "this note";
        }
    }

    private Sharing(){}
}
