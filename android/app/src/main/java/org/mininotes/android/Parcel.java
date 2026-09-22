// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * What is inside a sealed envelope: a note, and where it lives.
 *
 * <p>A note on its own is not much use to the person receiving it. It arrived out of a book, in a
 * collection, and those are what say what it is <i>about</i> — dropped into whatever book happened to be
 * first on the other phone, the same note means something else entirely. So the shelf it stood on travels
 * with it and is built on the far side.
 *
 * <p>This is the plaintext the envelope seals, so everything here is as private as the note is. The
 * envelope handles who may read it; this only says what it says.
 *
 * <p>Holds no Android types: the wire format and its bounds are unit tested.
 */
final class Parcel {

    /** The format, and the first four bytes. A later shape gets a later magic, never a silent change. */
    static final byte[] MAGIC={'M','N','B','1'};

    /** Bounds on what a stranger can make us allocate while reading one. */
    static final int NAME_MOST=200, ID_MOST=100, TEXT_MOST=200000;
    /** Room for a key in either shape, long or short. */
    static final int KEY_MOST=4096;
    /** A Maxima address is a whole public key and then where to reach it. */
    static final int ADDRESS_MOST=1024;

    /** One device that has this thing, as the list of them travels. */
    static final class Member {
        final String key,address,name; final int level; final long changed;
        Member(String key,String address,String name,int level,long changed) {
            this.key=key==null?"":key;this.address=address==null?"":address;
            this.name=name==null?"":name;this.level=level;this.changed=changed;
        }
    }

    /** A note, the shelf it stood on, and who else has it. */
    static final class Sent {
        final String collection,collectionName,book,bookName,title,body;
        /** What the other end may do with it: false is read, true is read and write it back. */
        final boolean writes;
        /**
         * Everybody who has the thing this note came out of, and at what standing.
         *
         * <p>So that a shared thing is held by the people who hold it rather than by whoever began it.
         * Without this, every copy but one is a copy that cannot say who else is reading, and only the
         * phone that started the sharing can hand it on.
         */
        final java.util.List<Member> members;
        /** Which thing the members are the members of — the level and the id at the sender's end. */
        final String scope,target;
        /**
         * Whether the sender wants to be told this arrived — see {@link Receipt}.
         *
         * <p>Asked for rather than assumed, because of who might be listening. A build from before answers
         * existed reads anything it does not recognise as a note written the old way, as bare text: send
         * it an answer it never asked for and it would write those five bytes over the note. So only a
         * phone that asks is answered, and only a phone that knows what an answer is will ask.
         */
        final boolean answer;
        /**
         * The revision both phones last had, as the sender believes it, or -1 where it did not say.
         *
         * <p>A merge is made against the text two phones last agreed on, and each phone has its own idea
         * of what that was. Either can be wrong in the same direction: thinking the other has more than it
         * does. So both ideas are used, and the older one wins - an agreement takes two, and a base that is
         * too old only makes a merge work harder, where one that is too new throws somebody's writing away
         * as something they must have deleted.
         */
        final long basedOn;
        Sent(String collection,String collectionName,String book,String bookName,
             String title,String body,boolean writes) {
            this(collection,collectionName,book,bookName,title,body,writes,
                java.util.Collections.<Member>emptyList(),"","");
        }
        Sent(String collection,String collectionName,String book,String bookName,
             String title,String body,boolean writes,java.util.List<Member> members,
             String scope,String target) {
            this(collection,collectionName,book,bookName,title,body,writes,members,scope,target,false);
        }
        Sent(String collection,String collectionName,String book,String bookName,
             String title,String body,boolean writes,java.util.List<Member> members,
             String scope,String target,boolean answer) {
            this(collection,collectionName,book,bookName,title,body,writes,members,scope,target,answer,-1L);
        }
        Sent(String collection,String collectionName,String book,String bookName,
             String title,String body,boolean writes,java.util.List<Member> members,
             String scope,String target,boolean answer,long basedOn) {
            this.answer=answer;this.basedOn=basedOn<0?-1L:basedOn;
            this.collection=collection;this.collectionName=collectionName;
            this.book=book;this.bookName=bookName;this.title=title;this.body=body;this.writes=writes;
            this.members=members==null?java.util.Collections.<Member>emptyList():members;
            this.scope=scope==null?"":scope;this.target=target==null?"":target;
        }
    }

    /** The most members one thing may be shared with. A list, not a broadcast network. */
    static final int MEMBERS_MOST=64;

    private Parcel(){}

    static byte[] wrap(Sent sent) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeBoolean(sent.writes);
        put(out,sent.collection,ID_MOST);
        put(out,sent.collectionName,NAME_MOST);
        put(out,sent.book,ID_MOST);
        put(out,sent.bookName,NAME_MOST);
        put(out,sent.title,NAME_MOST);
        put(out,sent.body,TEXT_MOST);
        // Everything after this point is what a build before memberships never wrote. One that never saw
        // it reads the six fields above and stops, which is exactly what it did before.
        put(out,sent.scope,ID_MOST);
        put(out,sent.target,ID_MOST);
        int many=Math.min(sent.members.size(),MEMBERS_MOST);
        out.writeInt(many);
        for(int at=0;at<many;at++) {
            Member one=sent.members.get(at);
            put(out,one.key,KEY_MOST);
            put(out,one.address,ADDRESS_MOST);
            put(out,one.name,NAME_MOST);
            out.writeInt(one.level);
            out.writeLong(one.changed);
        }
        // And after that, what a build before answers never wrote. Same rule: the older reader stops
        // where it always stopped.
        out.writeBoolean(sent.answer);
        out.writeLong(sent.basedOn);
        out.flush();
        return bytes.toByteArray();
    }

    /**
     * What arrived, or null when this is not one.
     *
     * <p>Notes sent by a build that had no shelf to send were the text and nothing else. One of those still
     * reads: it comes back with no collection and no book, and the caller puts it where it puts anything
     * whose shelf it was not told.
     */
    static Sent open(byte[] said) {
        if(said==null)return null;
        if(said.length<MAGIC.length)return null;
        for(int at=0;at<MAGIC.length;at++)if(said[at]!=MAGIC[at])return null;
        try(DataInputStream in=new DataInputStream(new java.io.ByteArrayInputStream(said,MAGIC.length,
                said.length-MAGIC.length))) {
            boolean writes=in.readBoolean();
            String collection=get(in,ID_MOST), collectionName=get(in,NAME_MOST);
            String book=get(in,ID_MOST), bookName=get(in,NAME_MOST);
            String title=get(in,NAME_MOST), body=get(in,TEXT_MOST);
            // A note sent by a build that knew nothing of memberships simply ends here.
            if(in.available()<=0)return new Sent(collection,collectionName,book,bookName,title,body,writes);
            String scope=get(in,ID_MOST), target=get(in,ID_MOST);
            int many=in.readInt();
            if(many<0||many>MEMBERS_MOST)throw new IllegalArgumentException("A list of "+many);
            java.util.List<Member> members=new java.util.ArrayList<>(many);
            for(int at=0;at<many;at++)
                members.add(new Member(get(in,KEY_MOST),get(in,ADDRESS_MOST),get(in,NAME_MOST),
                    in.readInt(),in.readLong()));
            boolean answer=in.available()>0&&in.readBoolean();
            // Not said at all, or said whole. A few bytes of a number is a parcel that was cut short, and
            // half a parcel is not a parcel wherever the cut fell.
            long basedOn=-1L;
            if(in.available()>0) {
                if(in.available()<8)throw new IllegalArgumentException("Cut short");
                basedOn=in.readLong();
            }
            return new Sent(collection,collectionName,book,bookName,title,body,writes,members,scope,target,
                answer,basedOn);
        } catch(IOException | IllegalArgumentException broken) {
            // Half a parcel is not a parcel. Nothing partly read is handed back.
            return null;
        }
    }

    private static void put(DataOutputStream out,String said,int most) throws IOException {
        byte[] bytes=(said==null?"":said).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>most)throw new IOException("Too long to send: "+bytes.length+" of "+most);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String get(DataInputStream in,int most) throws IOException {
        int length=in.readInt();
        if(length<0||length>most)throw new IllegalArgumentException("A field said it was "+length+" long");
        byte[] bytes=new byte[length];
        in.readFully(bytes);
        return new String(bytes,StandardCharsets.UTF_8);
    }

    /**
     * What to call somebody else's collection or book on this phone.
     *
     * <p>Their id cannot be used as it stands. Every pad is made with the same first collection and the
     * same first book, under the same two ids — so a note shared out of somebody's first book, filed under
     * the id it arrived with, would land inside <i>your</i> first book and look like something you wrote.
     * Worse, two people's collections would become one.
     *
     * <p>So the local name for a shelf of theirs is made from who they are and what they call it: the same
     * every time, so the second note lands beside the first, and different for every person, so no two
     * people's shelves can ever be confused for each other.
     */
    /** How every id made by {@link #localId} begins. No id made on a phone for its own shelf begins so. */
    static final String FROM="from-";

    /**
     * Where a shelf named in something that arrived is on this phone.
     *
     * <p>A shelf of somebody else's is filed under an id made from who they are and what they call it.
     * That was worked out afresh from whoever sent the note, which is right while notes only ever come from
     * the shelf's owner and wrong the moment anybody else sends one: a note that came back to the owner
     * from somebody it was shared with named the owner's own book by the made-up id, and the owner's phone
     * made up another from that and built a second book beside the first; and a third phone given the book
     * by an admin filed it under a different id from the one it would have had from the owner. So an id
     * that is already made up is not made up again. It is one of this phone's own shelves, come home - or
     * it is the name everybody but the owner already knows the shelf by, and is kept as it is.
     *
     * @param myKey the key this phone signs with, as the others write it
     * @param own   this phone's own shelves of that kind
     * @param by    who sent it: the key they sign with, or their address where no key is known
     */
    static String shelfHere(String myKey,java.util.Collection<String> own,String by,String theirs) {
        if(theirs==null||theirs.trim().isEmpty())return "";
        if(!theirs.startsWith(FROM))return localId(by,theirs);
        if(myKey!=null&&!myKey.isEmpty()&&own!=null)
            for(String mine:own)if(localId(myKey,mine).equals(theirs))return mine;
        return theirs;
    }

    static String localId(String address,String theirs) {
        if(theirs==null||theirs.trim().isEmpty())return "";
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest((address+"\0"+theirs).getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(FROM);
            for(int at=0;at<12;at++)out.append(String.format(Locale.ROOT,"%02x",digest[at]));
            return out.toString();
        } catch(Exception noDigest) {
            // Cannot happen: SHA-256 is required of every Java. Named rather than swallowed all the same.
            throw new IllegalStateException("SHA-256 is missing",noDigest);
        }
    }
}
