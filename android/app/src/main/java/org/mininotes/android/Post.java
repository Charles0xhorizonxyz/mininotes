// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.content.Context;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.MaximaSender;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Notes actually going somewhere, and notes actually arriving.
 *
 * <p>Two layers, and they are not the same thing. Maxima carries the bytes and knows nothing of what is in
 * them. Inside that, every note is sealed for one recipient with {@link Envelope} — agreement, a key nobody
 * else can derive, and a signature that says who wrote it. A relay handles the envelope; it does not open
 * it, and could not.
 *
 * <p><b>A mark is cleared by the far phone saying it has the note, and by nothing else.</b> That rule is
 * the whole reason the marks are worth looking at. For a long time it was cleared when the network said it
 * had taken the message, which is a different thing: the network will take a message for a phone that is
 * asleep, switched off, or no longer taking that note. So a note that goes asks to be answered, the phone
 * that gets it answers once it is written down — see {@link Receipt} — and what is never answered is sent
 * again, a minute later and then less and less often, for as long as it takes.
 */
final class Post {
    /** The Maxima application string this app owns. Its own traffic, nobody else's. */
    static final String APPLICATION="mininotes.v1";

    /** How long an answer is given before the same note may be handed to the network again. */
    private static final long JUST_NOW=20_000L;

    /** What one send did, in words a person can be shown. */
    static final class Done {
        final int sent, failed;
        final String why;
        Done(int sent,int failed,String why){this.sent=sent;this.failed=failed;this.why=why;}
    }

    private Post(){}

    /** What one arriving message turned out to be. */
    static final class Landed {
        /** What to tell the reader, or null where there is nothing to say. */
        final String said;
        /** Somebody taking up an offer, which is a question for the reader rather than a note. */
        final Hello.Said accepted;
        /**
         * Which note changed, or empty where none did. The page that is open has to know when it is the one:
         * it is holding the text as it was, and the next word typed would write that back over what arrived.
         */
        final String note;
        /** Somebody said they have something. Nothing to tell the reader; the marks want drawing again. */
        final boolean answered;
        /** Who may do what can have changed, though no word of the note did. A box showing it is drawn again. */
        final boolean people;
        Landed(String said,Hello.Said accepted){this(said,accepted,"");}
        Landed(String said,Hello.Said accepted,String note){this(said,accepted,note,false);}
        Landed(String said,Hello.Said accepted,String note,boolean answered){this(said,accepted,note,answered,false);}
        private Landed(String said,Hello.Said accepted,String note,boolean answered,boolean people) {
            this.said=said;this.accepted=accepted;this.note=note==null?"":note;this.answered=answered;
            this.people=people;
        }
        /** A note that arrived saying what it said already: nothing to tell, and the marks and the box to ask again. */
        static Landed people(String note){return new Landed(null,null,note,true,true);}
        /** Somebody left. That is worth saying, and whoever is looking at the list is looking at an old one. */
        static Landed left(String said,String note){return new Landed(said,null,note,false,true);}
    }

    /**
     * Somebody's offer, taken up.
     *
     * <p>Sent back down the same road their code came along: sealed for them, signed by this phone, and
     * carrying what they need to hand the thing over - who this is, where to reach it, and the keys.
     * Blocking: the worker calls it.
     */
    static void accept(Context where,Keys keys,Pairing.Said them,String myName,String myAddress)
            throws Exception {
        say(where,keys,them.address,them.agreement,them.scope,them.target,them.writes,myName,myAddress,null);
    }

    /**
     * Paired back: the answer to somebody who scanned this device's code, with nothing offered.
     *
     * <p>A plain code used to be read in one direction only. The phone that scanned it kept the other
     * device; the device that showed it never heard, and dropped everything the phone then sent as coming
     * from a stranger - the phone listed the PC on a shared note, and the note never reached it. So scanning
     * a plain code says hello too, and this is the hello coming back once the owner has said yes.
     */
    static void helloBack(Context where,Keys keys,Hello.Said them,String myName,String myAddress,String contact) throws Exception {
        say(where,keys,them.address,them.agreement,"","",false,myName,myAddress,contact);
    }

    /**
     * The same thing said again.
     *
     * <p>An acceptance is one message to a phone that may be asleep, in a drawer, or being updated, and a
     * message to a node nobody is listening on is gone. So it is kept and repeated rather than sent once
     * and hoped over: otherwise accepting something works when the two phones happen to be awake together
     * and does nothing at all when they are not, with no sign either way.
     */
    static void sayAgain(Context where,NoteStore store,Keys keys,NoteStore.Accepting again,
                         String myName,String myAddress) throws Exception {
        NoteStore.Contact them=store.address(again.address);
        if(them==null||them.agreement.length==0)return;
        say(where,keys,again.address,them.agreement,again.scope,again.target,again.writes,myName,myAddress,them.contact);
    }

    private static void say(Context where,Keys keys,String address,byte[] agreement,String scope,
                            String target,boolean writes,String myName,String myAddress,String contact) throws Exception {
        MaximaNode node=Node.node(where);
        if(node==null)throw new IllegalStateException("The node is not running.");
        if(myAddress==null||myAddress.trim().isEmpty())
            throw new IllegalStateException("This phone has no address to be reached at yet.");
        byte[] plain=Hello.wrap(new Hello.Said(myName,myAddress.trim(),
            Point.shorten(keys.agreement().getPublic()),Point.shorten(keys.signing().getPublic()),
            scope,target,writes));
        byte[] sealed=Envelope.seal(new byte[16],0,System.currentTimeMillis(),plain,
            keys.signing(),Keys.publicKey(agreement));
        // To the device, where the network knows it: an address is a snapshot, and a PC restarted since
        // its code was scanned is somewhere else. The address on the code only when nothing better is known.
        com.eurobuddha.maxima.core.contacts.Contact reach=Node.known(where,contact);
        MaximaSender.Result said=reach!=null?node.sendToContact(reach,APPLICATION,sealed):node.sendRaw(routable(node,address),APPLICATION,sealed);
        if(said==null||!said.isOk())throw new IllegalStateException("They could not be reached just now.");
    }

    /**
     * Everything one thing owes, sent. Blocking: the worker calls it.
     *
     * <p>Each note goes once per address that is owed it. A note that reaches nobody is not an error — it
     * is a note shared with nobody, which is most of them.
     */
    static Done send(Context where,NoteStore store,Keys keys,NoteStore.Branch.Kind kind,String id)
            throws Exception {
        return send(where,store,keys,kind,id,null);
    }

    /**
     * @param only one address to send to and nobody else, or null for everybody who is owed. Given when
     *             that address has just asked: it is plainly there, so it is sent what it is owed at once,
     *             whenever it was last tried.
     */
    static Done send(Context where,NoteStore store,Keys keys,NoteStore.Branch.Kind kind,String id,String only)
            throws Exception {
        return send(where,store,keys,kind,id,only,false);
    }

    /**
     * @param whole for one note: send it to everybody who has it whether or not they are thought to be
     *              owed it. What somebody pressing Sync on a note means is "make these the same", and two
     *              phones can each believe the other is up to date while holding different words.
     */
    static Done send(Context where,NoteStore store,Keys keys,NoteStore.Branch.Kind kind,String id,String only,
                     boolean whole) throws Exception {
        List<Outbox.Wait> owed=store.owed(kind,id);
        if(whole&&kind==NoteStore.Branch.Kind.PAGE) {
            NoteStore.Note all=store.get(id);
            owed=new ArrayList<>();
            // Not from a reader: what it would send, everybody would refuse.
            if(all!=null&&!store.onlyReads(id))for(String address:store.everybodyIn(kind,id))
                owed.add(new Outbox.Wait(id,address,all.revision,false));
        }
        if(only!=null) {
            List<Outbox.Wait> theirs=new ArrayList<>();
            for(Outbox.Wait wait:owed)if(only.equals(wait.address))theirs.add(wait);
            owed=theirs;
        }
        if(owed.isEmpty())return new Done(0,0,"");
        MaximaNode node=Node.node(where);
        if(node==null)return new Done(0,owed.size(),"The node is not running.");

        // Who each address is, and the keys to seal for it. An address with no keys has never been paired,
        // and a note sealed for nobody is a note sent nowhere: those are counted as failed, not skipped.
        java.util.Map<String,NoteStore.Contact> known=new java.util.HashMap<>();
        for(NoteStore.Contact contact:store.addresses())known.put(contact.address,contact);

        KeyPair mine=keys.signing();
        // What went a moment ago and has not had time to be answered. Two things can ask for the same note
        // within a breath of each other - opening the pad sends what is owed, and so does the writing
        // having just stopped - and the second would otherwise send it again before the first could
        // possibly have been answered.
        java.util.Map<String,Outbox.Handed> lately=new java.util.HashMap<>();
        for(Outbox.Handed one:store.handed())lately.put(Outbox.mark(one.address,one.page),one);
        long clock=System.currentTimeMillis();
        int sent=0, failed=0; String why="";
        for(Outbox.Wait wait:owed) {
            NoteStore.Contact them=known.get(wait.address);
            if(them==null||them.agreement.length==0) {
                failed++;
                if(why.isEmpty())why="Somebody has not been paired yet, so nothing can be sealed for them.";
                continue;
            }
            NoteStore.Note note=store.get(wait.page);
            if(note==null){failed++;continue;}
            Outbox.Handed before=lately.get(Outbox.mark(wait.address,wait.page));
            if(only==null&&!whole&&before!=null&&before.revision==note.revision&&clock>=before.at
                &&clock-before.at<JUST_NOW)continue;
            try {
                PublicKey theirs=Keys.publicKey(them.agreement);
                // The shelf goes with the note. A note filed into whatever book happened to be first on
                // the other phone has lost most of what it meant; and because the book travels by its id
                // rather than by its name, the second note out of it lands beside the first instead of
                // building another book that looks the same.
                String book=note.book, collection=store.collectionOfBook(book);
                // And who else has it. A shared thing held only by whoever began it is a broadcast; a
                // thing every holder knows the holders of is something people can pass on between them.
                Sharing.Scope held=store.sharedAt(collection,book,note.id);
                String what=held==Sharing.Scope.COLLECTION?collection
                           :held==Sharing.Scope.BOOK?book:note.id;
                byte[] text=Parcel.wrap(new Parcel.Sent(collection,store.nameOf(collection,true),
                    book,store.nameOf(book,false),note.title,note.body,
                    store.mayWrite(them.address,collection,book,note.id),
                    store.travelling(held,what),held==null?"":held.name(),what,true,
                    store.agreedAt(note.id,them.address),true));
                byte[] sealed=Envelope.seal(sixteen(wait.page),
                    note.revision,System.currentTimeMillis(),text,mine,theirs);
                // Handed to the peer, not to an address they used to be at. The transport tries the local
                // network first, then every address it knows for them, then asks their directory where
                // they went - which is the whole reason a phone that moved is still reachable. Only a peer
                // this node has never been introduced to falls back to dialling the address off their code.
                com.eurobuddha.maxima.core.contacts.Contact reach=Node.known(where,them.contact);
                MaximaSender.Result said=reach!=null
                    ?node.sendToContact(reach,APPLICATION,sealed)
                    :node.sendRaw(routable(node,them.address),APPLICATION,sealed);
                // Which road it took and what the relay said, and nothing else: no address, no key, no
                // word of the note. A send that fails quietly while somebody is writing has to be findable
                // afterwards by whoever is holding the phone and a cable.
                android.util.Log.i("Mininotes/Post","sent "+(reach!=null?"to a contact":"to an address")
                    +", revision "+note.revision+": "+(said==null?"no answer":said.statusName));
                // Not heard from lately, so perhaps not there: a copy goes with whoever can carry it, in
                // case they are back only after this device has gone.
                if(!there(them))leaveWithCarriers(where,store,keys,node,them,Courier.NOTE,sixteen(wait.page),note.revision,sealed);
                if(said!=null&&said.isOk()) {
                    // Handed over, and no more than that. The mark means somebody has it, and the only one
                    // who can say so is them: it stays until their answer comes back.
                    store.handedOver(them.address,wait.page,note.revision);
                    // What went is written down as it went. It is what the next thing they write will be
                    // weighed against, and a text that was only ever on the wire cannot be weighed against.
                    store.keepVersion(wait.page,"");
                    sent++;
                } else {
                    failed++;
                    if(why.isEmpty())why="The relay would not take it. It stays waiting.";
                }
            } catch(Exception e) {
                failed++;
                if(why.isEmpty())why=e.getMessage()==null?"Something went wrong sending it.":e.getMessage();
            }
        }
        return new Done(sent,failed,why);
    }

    /**
     * Who may do what, told to everybody who has the thing. Blocking.
     *
     * <p>The list of who has a thing travels with its notes, and with nothing else. So changing what
     * somebody may do, and writing nothing, told nobody: the change sat on this phone until the next word
     * was typed, which for a note that is finished is never. What is owed goes first - somebody just added
     * is owed all of it - and then one note out of the thing goes whole to everybody, carrying the list.
     */
    static Done changed(Context where,NoteStore store,Keys keys,NoteStore.Branch.Kind kind,String id)
            throws Exception {
        Done done=send(where,store,keys,kind,id);
        Sharing.Scope scope=kind==NoteStore.Branch.Kind.PAGE?Sharing.Scope.PAGE
            :kind==NoteStore.Branch.Kind.BOOK?Sharing.Scope.BOOK
            :kind==NoteStore.Branch.Kind.COLLECTION?Sharing.Scope.COLLECTION:null;
        if(scope==null)return done;
        // A note carries one list: its own if it has one, or else its book's, or else its collection's.
        // So the note to send is one that carries this list, and not any note that happens to be inside.
        String carrier=null;
        for(Outbox.Page page:store.pagesUnder(kind,id))
            if(store.sharedAt(page.collection,page.book,page.id)==scope){carrier=page.id;break;}
        if(carrier==null)return done;
        Done carried=send(where,store,keys,NoteStore.Branch.Kind.PAGE,carrier,null,true);
        return new Done(done.sent,done.failed+carried.failed,done.why.isEmpty()?carried.why:done.why);
    }

    /** One at a time, off the thread the node brought the note on: an answer is a network call too. */
    private static final java.util.concurrent.ExecutorService ANSWERS=
        java.util.concurrent.Executors.newSingleThreadExecutor(work->{
            Thread one=new Thread(work,"mininotes-answers");one.setDaemon(true);return one;});

    /**
     * "I have it", said to whoever sent it.
     *
     * <p>Nothing is done about one that does not get through. They will send the note again when no
     * answer comes, and be answered again; an answer is never itself answered, so nothing goes round.
     */
    private static void answer(final Context where,final NoteStore store,final Keys keys,final NoteStore.Contact them,
                               final byte[] page,final long revision,final boolean took) {
        if(where==null||them.agreement.length==0)return;
        ANSWERS.execute(()->{
            try {
                MaximaNode node=Node.node(where);
                if(node==null)return;
                byte[] sealed=Envelope.seal(page,revision,System.currentTimeMillis(),
                    Receipt.wrap(took?Receipt.TOOK:Receipt.HAVE),keys.signing(),Keys.publicKey(them.agreement));
                com.eurobuddha.maxima.core.contacts.Contact reach=Node.known(where,them.contact);
                MaximaSender.Result said=reach!=null
                    ?node.sendToContact(reach,APPLICATION,sealed)
                    :node.sendRaw(routable(node,them.address),APPLICATION,sealed);
                android.util.Log.i("Mininotes/Post","answered them: revision "+revision+": "
                    +(said==null?"no answer":said.statusName));
                // A note that was carried here comes from a device that may be gone by now; its answer
                // goes back the same way, or it would send the note again for ever.
                if(!there(them))leaveWithCarriers(where,store,keys,node,them,Courier.ANSWER,page,revision,sealed);
            } catch(Exception notNow) {
                android.util.Log.w("Mininotes/Post","could not answer: "+notNow.getClass().getSimpleName());
            }
        });
    }

    /** Whether what arrived gives this phone the thing again, later than it left. */
    private static boolean givenAgain(NoteStore store,Parcel.Sent parcel,long leftAt) {
        if(parcel==null||store.mySigningKey.isEmpty())return false;
        for(Parcel.Member one:parcel.members)
            if(store.mySigningKey.equals(one.key)&&one.level>Sharing.Level.GONE.said()&&one.changed>leftAt)return true;
        return false;
    }

    /**
     * "I have left this" - or "you are off this" - to one device, off the thread the node brought their
     * note on. Which of the two is {@code what}, one of the numbers in {@link Receipt}.
     */
    private static void tellOff(final Context where,final Keys keys,final NoteStore.Contact them,
                                final byte[] page,final long when,final int what) {
        if(where==null||them.agreement.length==0||what==0)return;
        ANSWERS.execute(()->{
            try{saidOff(where,keys,them,page,when,what);}
            catch(Exception notNow) {
                android.util.Log.w("Mininotes/Post","could not say who is off what: "+notNow.getClass().getSimpleName());
            }
        });
    }

    /**
     * @param when when it was decided, carried where a note's revision is: see {@link NoteStore#left} and
     *             {@link NoteStore#takenOff}
     */
    private static boolean saidOff(Context where,Keys keys,NoteStore.Contact them,byte[] page,long when,
                                   int what) throws Exception {
        MaximaNode node=Node.node(where);
        if(node==null)return false;
        byte[] sealed=Envelope.seal(page,when,System.currentTimeMillis(),
            Receipt.wrap(what),keys.signing(),Keys.publicKey(them.agreement));
        com.eurobuddha.maxima.core.contacts.Contact reach=Node.known(where,them.contact);
        MaximaSender.Result said=reach!=null
            ?node.sendToContact(reach,APPLICATION,sealed)
            :node.sendRaw(routable(node,them.address),APPLICATION,sealed);
        android.util.Log.i("Mininotes/Post",(Receipt.removedScope(what)!=null?"told them they are off this: "
            :"told them this phone has left: ")+(said==null?"no answer":said.statusName));
        return said!=null&&said.isOk();
    }

    /**
     * Unfollow: everybody who has the thing is told this phone has left it, and then it is let go. Blocking.
     *
     * <p>Told first, while this phone still knows who they are. Let go whether or not anybody could be
     * told - a phone with no signal can still leave - because whoever did not hear will send the thing
     * again one day, and is told then. See {@link NoteStore#letGo}.
     *
     * @return how many were told
     */
    static int leave(Context where,NoteStore store,Keys keys,NoteStore.Branch.Kind kind,String id) {
        Sharing.Scope scope=kind==NoteStore.Branch.Kind.PAGE?Sharing.Scope.PAGE
            :kind==NoteStore.Branch.Kind.BOOK?Sharing.Scope.BOOK
            :kind==NoteStore.Branch.Kind.COLLECTION?Sharing.Scope.COLLECTION:null;
        if(scope==null)return 0;
        java.util.Set<String> who=store.everybodyIn(kind,id);
        // Any note out of it names it: the phone that hears finds the shelf from its own shelves.
        byte[] about=null;final long now=System.currentTimeMillis();
        for(Outbox.Page page:store.pagesUnder(kind,id)) {
            try{about=sixteen(page.id);break;}
            catch(IllegalArgumentException older){/* a note from before sharing names nothing */}
        }
        int told=0;
        if(about!=null)for(NoteStore.Contact them:store.addresses()) {
            if(!who.contains(them.address)||them.agreement.length==0||!doesSpeak(where,them))continue;
            try{if(saidOff(where,keys,them,about,now,Receipt.left(scope)))told++;}
            catch(Exception notNow) {
                android.util.Log.w("Mininotes/Post","could not say this phone has left: "+notNow.getClass().getSimpleName());
            }
        }
        store.letGo(kind,id,who,now);
        return told;
    }

    /** Whatever this phone has left lately, said again to whoever was told. Blocking. See {@link NoteStore#leavings}. */
    static void leftAgain(Context where,NoteStore store,Keys keys) {
        for(NoteStore.Leaving one:store.leavings()) {
            try {
                NoteStore.Contact them=null;
                for(NoteStore.Contact known:store.addresses())if(known.address.equals(one.address))them=known;
                if(them==null||them.agreement.length==0||!doesSpeak(where,them))continue;
                saidOff(where,keys,them,sixteen(one.note),one.at,Receipt.left(one.scope));
            } catch(Exception notNow) {
                android.util.Log.w("Mininotes/Post","could not say again that this phone has left: "
                    +notNow.getClass().getSimpleName());
            }
        }
    }

    /**
     * Somebody taken off something, told so: by the same road as leaving, the other way. Blocking.
     *
     * @param when when it was decided here, which their phone then makes its copy its own as of
     * @return whether they could be told now; they are told again at every opening for a week either way
     */
    static boolean removed(Context where,NoteStore store,Keys keys,Sharing.Scope scope,String target,
                           String address,long when) {
        if(Receipt.removed(scope)==0)return false;
        NoteStore.Contact them=null;
        for(NoteStore.Contact known:store.addresses())if(known.address.equals(address))them=known;
        if(them==null||them.agreement.length==0||!doesSpeak(where,them))return false;
        // Any note out of it names it: the phone that hears finds the shelf from its own shelves.
        byte[] about=null;
        for(Outbox.Page page:store.pagesUnder(NoteStore.kindFor(scope),target)) {
            try{about=sixteen(page.id);break;}
            catch(IllegalArgumentException older){/* a note from before sharing names nothing */}
        }
        if(about==null)return false;
        try{return saidOff(where,keys,them,about,when,Receipt.removed(scope));}
        catch(Exception notNow) {
            android.util.Log.w("Mininotes/Post","could not say they are off this: "+notNow.getClass().getSimpleName());
            return false;
        }
    }

    /** Whoever this phone has taken off something lately, told again. Blocking. See {@link NoteStore#removals}. */
    static void removedAgain(Context where,NoteStore store,Keys keys) {
        for(NoteStore.Leaving one:store.removals()) {
            try {
                NoteStore.Contact them=null;
                for(NoteStore.Contact known:store.addresses())if(known.address.equals(one.address))them=known;
                if(them==null||them.agreement.length==0||!doesSpeak(where,them))continue;
                saidOff(where,keys,them,sixteen(one.note),one.at,Receipt.removed(one.scope));
            } catch(Exception notNow) {
                android.util.Log.w("Mininotes/Post","could not say again that they are off this: "
                    +notNow.getClass().getSimpleName());
            }
        }
    }

    /**
     * Devices known to understand an answer, by the key they sign with.
     *
     * <p>A question is five bytes no note ever asked for, and a build from before answers existed would
     * read them as a note written the oldest way and write them over somebody's writing. An answer is
     * safe because it only goes to a phone that asked for one; a question has to be safe some other way,
     * so it goes only to a phone that has already shown it knows what these bytes are.
     */
    private static void speaks(Context where,NoteStore.Contact them) {
        if(where==null||them==null||them.signing.length==0)return;
        android.content.SharedPreferences kept=where.getSharedPreferences("post",Context.MODE_PRIVATE);
        java.util.Set<String> all=new java.util.HashSet<>(kept.getStringSet("answers",new java.util.HashSet<String>()));
        if(all.add(android.util.Base64.encodeToString(them.signing,android.util.Base64.NO_WRAP)))
            kept.edit().putStringSet("answers",all).apply();
    }

    /** Whether anything signed by them has ever been answered here. */
    static boolean heardFrom(Context where,NoteStore.Contact them){return doesSpeak(where,them);}

    private static boolean doesSpeak(Context where,NoteStore.Contact them) {
        if(where==null||them==null||them.signing.length==0)return false;
        return where.getSharedPreferences("post",Context.MODE_PRIVATE)
            .getStringSet("answers",new java.util.HashSet<String>())
            .contains(android.util.Base64.encodeToString(them.signing,android.util.Base64.NO_WRAP));
    }

    // ---- carrying for devices that are not on at the same time: see Courier ------------------------------------

    /** When each device was last heard from directly, by the fingerprint of its key. For this run only. */
    private static final java.util.Map<String,Long> HEARD=new java.util.concurrent.ConcurrentHashMap<>();

    private static String fingerprint(NoteStore.Contact them) {
        try{return them==null||them.signing.length==0?"":Courier.hex(Envelope.fingerprint(Keys.publicKey(them.signing)));}
        catch(Exception unreadable){return "";}
    }

    /** Whether a device was heard from so lately that it is taken to be there. */
    private static boolean there(NoteStore.Contact them) {
        Long at=HEARD.get(fingerprint(them));
        return at!=null&&System.currentTimeMillis()-at<Courier.THERE;
    }

    /** A device whose notes have said its build carries - and so may be left things, and brought them. */
    private static void carries(Context where,NoteStore.Contact them) {
        if(where==null||them==null||them.signing.length==0)return;
        android.content.SharedPreferences kept=where.getSharedPreferences("post",Context.MODE_PRIVATE);
        java.util.Set<String> all=new java.util.HashSet<>(kept.getStringSet("carries",new java.util.HashSet<String>()));
        if(all.add(android.util.Base64.encodeToString(them.signing,android.util.Base64.NO_WRAP)))
            kept.edit().putStringSet("carries",all).apply();
    }

    private static boolean doesCarry(Context where,NoteStore.Contact them) {
        if(where==null||them==null||them.signing.length==0)return false;
        return where.getSharedPreferences("post",Context.MODE_PRIVATE)
            .getStringSet("carries",new java.util.HashSet<String>())
            .contains(android.util.Base64.encodeToString(them.signing,android.util.Base64.NO_WRAP));
    }

    /** Sealed bytes handed to a device, where the network knows it. Whether the network took them. */
    private static boolean hand(Context where,MaximaNode node,NoteStore.Contact them,byte[] sealed) throws Exception {
        com.eurobuddha.maxima.core.contacts.Contact reach=Node.known(where,them.contact);
        MaximaSender.Result said=reach!=null
            ?node.sendToContact(reach,APPLICATION,sealed)
            :node.sendRaw(routable(node,them.address),APPLICATION,sealed);
        return said!=null&&said.isOk();
    }

    /**
     * A copy of something sealed for {@code them}, left with the devices that can carry it: every other
     * paired device whose build has said it carries, those heard from lately first. Nothing is waited for
     * and nothing counted - this is on top of the sending, which goes on as it did.
     */
    private static void leaveWithCarriers(Context where,NoteStore store,Keys keys,MaximaNode node,NoteStore.Contact them,
                                          int sort,byte[] page,long revision,byte[] inner) {
        if(store==null||!Courier.fits(inner))return;
        try {
            byte[] forWhom=Envelope.fingerprint(Keys.publicKey(them.signing));
            List<NoteStore.Contact> carriers=new ArrayList<>();
            for(NoteStore.Contact one:store.addresses())
                if(one.paired()&&!one.address.equals(them.address)&&!java.util.Arrays.equals(one.signing,them.signing)&&doesCarry(where,one))carriers.add(one);
            carriers.sort((a,b)->Boolean.compare(there(b),there(a)));
            int left=0;
            for(NoteStore.Contact carrier:carriers) {
                if(left>=Courier.CARRIERS)break;
                try {
                    byte[] sealed=Envelope.seal(page,revision,System.currentTimeMillis(),Courier.leave(sort,forWhom,inner),
                        keys.signing(),Keys.publicKey(carrier.agreement));
                    if(hand(where,node,carrier,sealed))left++;
                } catch(Exception notThisOne){/* the next carrier, or none */}
            }
            if(left>0)android.util.Log.i("Mininotes/Post","left a copy with "+left+" device(s) that can carry it");
        } catch(Exception notNow) {
            android.util.Log.w("Mininotes/Post","could not leave a copy to be carried: "+notNow.getClass().getSimpleName());
        }
    }

    /**
     * Something left here to be carried to somebody else, or brought here by whoever carried it.
     *
     * <p>Left: kept, if it is for a device paired here whose build knows what it will be brought - and
     * brought at once if they are there. Brought: opened as if it had come straight from whoever wrote it,
     * which is who signed it, and then the device that brought it is told it can let go.
     */
    private static Landed carried(Context where,NoteStore store,Keys keys,NoteStore.Contact from,
                                  Envelope.Opened opened,Courier.Said said) {
        if(said.kind==Courier.LEAVE) {
            final String forWhom=Courier.hex(said.forWhom);
            NoteStore.Contact them=null;
            for(NoteStore.Contact one:store.addresses())if(one.paired()&&forWhom.equals(fingerprint(one)))them=one;
            if(them==null||forWhom.equals(fingerprint(from))||!doesCarry(where,them)) {
                android.util.Log.i("Mininotes/Post","not carried: for a device this one cannot bring it to");
                return new Landed(null,null);
            }
            boolean kept=store.carry(fingerprint(from),forWhom,Courier.hex(opened.page),said.sort,opened.revision,said.inner);
            android.util.Log.i("Mininotes/Post",kept?"carrying something for another device":"not carried: something newer is held, or there is no room");
            if(kept&&there(them))ANSWERS.execute(()->bring(where,store,keys,forWhom));
            return new Landed(null,null);
        }
        Landed landed=arrived(where,store,keys,said.inner,true);
        // Opened, written down, or found not to be anything: either way there is nothing more to bring.
        final NoteStore.Contact carrier=from;final int collected=Receipt.collected(said.sort);
        final byte[] page=opened.page;final long revision=opened.revision;
        ANSWERS.execute(()->{
            try {
                // Something just arrived, so the node is up; where it is not, nothing is started for this.
                MaximaNode node=Node.running()?Node.node(where):null;
                if(node==null)return;
                hand(where,node,carrier,Envelope.seal(page,revision,System.currentTimeMillis(),Receipt.wrap(collected),
                    keys.signing(),Keys.publicKey(carrier.agreement)));
            } catch(Exception notNow){/* it is brought again, and collected again */}
        });
        return landed;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean BRINGING=new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * What this device holds for others, brought to them. Blocking.
     *
     * @param only one device's fingerprint - which has just been heard from, so everything held for it goes
     *             now - or null for everything whose turn it is
     */
    static void bring(Context where,NoteStore store,Keys keys,String only) {
        if(!BRINGING.compareAndSet(false,true))return;
        try {
            MaximaNode node=Node.node(where);
            if(node==null)return;
            List<NoteStore.Carried> held=store.carried(only);
            if(held.isEmpty())return;
            java.util.Map<String,NoteStore.Contact> byKey=new java.util.HashMap<>();
            for(NoteStore.Contact one:store.addresses())if(one.paired())byKey.put(fingerprint(one),one);
            long now=System.currentTimeMillis();int brought=0;
            for(NoteStore.Carried one:held) {
                NoteStore.Contact them=byKey.get(one.recipient);
                if(them==null||!doesCarry(where,them))continue;
                // Just heard from: now, unless it went a moment ago. Otherwise, when its turn comes.
                if(only!=null?now-one.tried<JUST_NOW&&now>=one.tried:!Courier.due(one.tried,one.tries,now))continue;
                try {
                    byte[] page=new byte[16];
                    for(int at=0;at<16;at++)page[at]=(byte)Integer.parseInt(one.page.substring(at*2,at*2+2),16);
                    byte[] sealed=Envelope.seal(page,one.revision,now,Courier.bring(one.sort,one.bytes),
                        keys.signing(),Keys.publicKey(them.agreement));
                    hand(where,node,them,sealed);
                    store.broughtAgain(one);brought++;
                } catch(Exception notThisOne){/* its turn comes again */}
            }
            if(brought>0)android.util.Log.i("Mininotes/Post","brought "+brought+" thing(s) carried for another device");
        } catch(Exception notNow) {
            android.util.Log.w("Mininotes/Post","could not bring what is carried: "+notNow.getClass().getSimpleName());
        } finally {BRINGING.set(false);}
    }

    /**
     * Everybody who has anything in this thing, asked to send whatever they have for this phone. Blocking.
     *
     * @return how many were asked
     */
    static int ask(Context where,NoteStore store,Keys keys,NoteStore.Branch.Kind kind,String id) {
        int asked=0;
        try {
            MaximaNode node=Node.node(where);
            if(node==null)return 0;
            java.util.Set<String> who=store.everybodyIn(kind,id);
            for(NoteStore.Contact them:store.addresses()) {
                if(!who.contains(them.address)||them.agreement.length==0||!doesSpeak(where,them))continue;
                try {
                    // About this one note where it is a note that is being synced, so they send it whole.
                    byte[] about=new byte[16];
                    if(kind==NoteStore.Branch.Kind.PAGE)try{about=sixteen(id);}catch(IllegalArgumentException old){/* everything */}
                    byte[] sealed=Envelope.seal(about,0,System.currentTimeMillis(),
                        Receipt.wrap(Receipt.ASK),keys.signing(),Keys.publicKey(them.agreement));
                    com.eurobuddha.maxima.core.contacts.Contact reach=Node.known(where,them.contact);
                    MaximaSender.Result said=reach!=null
                        ?node.sendToContact(reach,APPLICATION,sealed)
                        :node.sendRaw(routable(node,them.address),APPLICATION,sealed);
                    android.util.Log.i("Mininotes/Post","asked them for what they have: "
                        +(said==null?"no answer":said.statusName));
                    if(said!=null&&said.isOk())asked++;
                } catch(Exception notNow) {
                    android.util.Log.w("Mininotes/Post","could not ask: "+notNow.getClass().getSimpleName());
                }
            }
        } catch(Exception notNow){/* nobody asked; what they owe still comes when its turn does */}
        return asked;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean TRYING=
        new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Whatever was handed over and never answered, sent again if it is time. Blocking, and never two at
     * once: called from the node's own upkeep, every half minute, for as long as the process lives.
     *
     * <p>{@link Outbox#due} decides what and when. Only a revision that already went once is sent again -
     * a note written in since is the business of whatever that note is set to, which may be "when I ask".
     */
    static void again(Context where,NoteStore store,Keys keys) {
        if(!TRYING.compareAndSet(false,true))return;
        try {
            List<Outbox.Handed> due=Outbox.due(store.handed(),store.sent(),store.revisions(),
                System.currentTimeMillis());
            java.util.Set<String> pages=new java.util.LinkedHashSet<>();
            for(Outbox.Handed one:due)pages.add(one.page);
            for(String page:pages) {
                Done done=send(where,store,keys,NoteStore.Branch.Kind.PAGE,page);
                android.util.Log.i("Mininotes/Post","not answered, so sent again: "+done.sent+" went, "
                    +done.failed+" did not");
            }
            // And whatever is carried for others, brought again when its turn comes.
            if(!store.carried(null).isEmpty())bring(where,store,keys,null);
        } catch(Exception notNow) {
            android.util.Log.w("Mininotes/Post","could not try again: "+notNow.getClass().getSimpleName());
        } finally {TRYING.set(false);}
    }

    /**
     * A note id as the sixteen bytes an envelope carries. Every note this app makes is named by a UUID,
     * which is those sixteen bytes written out — so the id goes over the wire as what it already is, and
     * comes back the same on the other side. Anything else was not made here and cannot travel.
     */
    private static byte[] sixteen(String id) {
        java.util.UUID said;
        try{said=java.util.UUID.fromString(id);}
        catch(IllegalArgumentException e) {
            throw new IllegalArgumentException("That note is older than sharing and has no id that travels.");
        }
        java.nio.ByteBuffer out=java.nio.ByteBuffer.allocate(16);
        out.putLong(said.getMostSignificantBits());out.putLong(said.getLeastSignificantBits());
        return out.array();
    }

    /** And back again, so the note that arrives is the same note it left as. */
    private static String idFrom(byte[] sixteen) {
        java.nio.ByteBuffer in=java.nio.ByteBuffer.wrap(sixteen);
        return new java.util.UUID(in.getLong(),in.getLong()).toString();
    }

    /**
     * An address to send to now. A permanent address is not routable itself — it is looked up, which is
     * what makes it survive a host move — so it is resolved before anything is handed to the transport.
     */
    private static String routable(MaximaNode node,String address) throws Exception {
        if(!address.startsWith("MAX#"))return address;
        String live=node.resolvePermanent(address);
        if(live==null||live.trim().isEmpty())throw new IllegalStateException(
            "That permanent address could not be looked up, so there is nowhere to send to yet.");
        return live;
    }

    /**
     * Something arrived. Opened, checked against a device we know, and handed to the notebook, which
     * decides what the note says next — see {@link Arriving}.
     *
     * @return what to tell the reader, or null where the message was not ours to read
     */
    static Landed arrived(Context where,NoteStore store,Keys keys,byte[] message){return arrived(where,store,keys,message,false);}

    /**
     * @param brought whether it was carried here by another device rather than coming from its sender: the
     *                sender was not heard from, and may be long gone, and what it carries is only a note or an
     *                answer - never something else to carry
     */
    private static Landed arrived(Context where,NoteStore store,Keys keys,byte[] message,boolean brought) {
        try {
            Envelope.Opened opened=Envelope.open(message,keys.agreement().getPrivate());
            // Somebody taking up an offer, which arrives from a phone this one has never heard of - that
            // is the whole point of it, so it is read before the check that everything else must pass.
            // The keys inside are checked against the signature the envelope was opened with, so the
            // sender is at least who these bytes say. Whether they may have anything is not decided here.
            Hello.Said accepted=Hello.open(opened.text);
            if(accepted!=null) {
                if(!java.util.Arrays.equals(Envelope.fingerprint(Keys.publicKey(accepted.signing)),
                    opened.sender))return new Landed(null,null);
                // A plain hello from a device already paired here is its answer to ours: nothing to ask
                // anybody, only to stop saying hello to it.
                if(accepted.target.isEmpty())for(NoteStore.Contact known:store.addresses())
                    if(known.signing.length>0&&java.util.Arrays.equals(Envelope.fingerprint(Keys.publicKey(known.signing)),opened.sender)) {
                        store.answered(known.address);
                        return new Landed(null,null);
                    }
                return new Landed(null,accepted);
            }
            // Who sent it: the envelope names a signing key by its fingerprint, and only a device already
            // paired with counts. An unsigned stranger's note is not put on anybody's shelves.
            NoteStore.Contact from=null;
            for(NoteStore.Contact contact:store.addresses()) {
                if(contact.signing.length==0)continue;
                if(java.util.Arrays.equals(Envelope.fingerprint(Keys.publicKey(contact.signing)),
                    opened.sender)){from=contact;break;}
            }
            if(from==null) {
                android.util.Log.w("Mininotes/Post","dropped an arriving message: signed by no device paired here");
                return new Landed(null,null);
            }
            if(!brought) {
                // Heard from, so there: nothing sent to them for a while is left with anybody else, and
                // whatever was being carried for them goes now.
                final String who=fingerprint(from);
                HEARD.put(who,System.currentTimeMillis());
                if(!store.carried(who).isEmpty())ANSWERS.execute(()->bring(where,store,keys,who));
                Courier.Said carried=Courier.open(opened.text);
                if(carried!=null)return carried(where,store,keys,from,opened,carried);
            } else if(Courier.open(opened.text)!=null) {
                // Something to carry, inside something carried: never. Read as a note, it would be bytes
                // written over somebody's words.
                return new Landed(null,null);
            }
            String id=idFrom(opened.page);
            // An answer: they have this note, at this revision. Which note and which revision are in the
            // envelope's header, sealed and signed with the rest, so nobody in between can say it for them.
            int about=Receipt.open(opened.text);
            if(about!=0)speaks(where,from);
            if(about==Receipt.ASK) {
                // They are there, and asking. Whatever they are owed goes now rather than when its turn
                // next comes round, which after a long silence can be a quarter of an hour away.
                android.util.Log.i("Mininotes/Post","asked for whatever they are owed");
                final String them=from.address;
                // About one note, where the envelope names one: that note whole, whatever is thought to be
                // owed. About none: whatever they are owed of everything.
                boolean named=false;
                for(byte one:opened.page)if(one!=0)named=true;
                final String which=named?id:null;
                ANSWERS.execute(()->{
                    try {
                        Done done=which!=null
                            ?send(where,store,keys,NoteStore.Branch.Kind.PAGE,which,them,true)
                            :send(where,store,keys,NoteStore.Branch.Kind.LIBRARY,Sharing.EVERYTHING,them);
                        android.util.Log.i("Mininotes/Post","sent what they asked for: "+done.sent+" went, "
                            +done.failed+" did not");
                    } catch(Exception notNow) {
                        android.util.Log.w("Mininotes/Post","could not send what they asked for: "
                            +notNow.getClass().getSimpleName());
                    }
                });
                return new Landed(null,null);
            }
            Sharing.Scope leftAt=Receipt.leftScope(about);
            if(leftAt!=null) {
                // When they left rides where a note's revision does: sealed and signed with the rest.
                boolean any=store.left(from.address,id,leftAt,opened.revision);
                android.util.Log.i("Mininotes/Post","they have left a "+leftAt.name().toLowerCase(java.util.Locale.ROOT)
                    +(any?"":" - which they were not on here, so it changes nothing"));
                if(!any)return new Landed(null,null);
                return Landed.left(from.name+" unfollowed a "+(leftAt==Sharing.Scope.PAGE?"note"
                    :leftAt==Sharing.Scope.BOOK?"book":"collection")+".",id);
            }
            Sharing.Scope offAt=Receipt.removedScope(about);
            if(offAt!=null) {
                // Somebody says this phone is off a thing of theirs. Whether they may say so is the
                // notebook's to check; when they decided it rides where a revision does.
                boolean any=store.takenOff(from.address,id,offAt,opened.revision);
                android.util.Log.i("Mininotes/Post","they say this phone is off a "+offAt.name().toLowerCase(java.util.Locale.ROOT)
                    +(any?"":" - which changes nothing here"));
                if(!any)return new Landed(null,null);
                return Landed.left(from.name+" removed you from a "+(offAt==Sharing.Scope.PAGE?"note"
                    :offAt==Sharing.Scope.BOOK?"book":"collection")+". Your copy stays on this phone.",id);
            }
            if((about==Receipt.COLLECTED||about==Receipt.COLLECTED_ANSWER)&&!brought) {
                // They have what was being carried for them: that note, or that answer, up to that revision.
                int gone=store.collected(fingerprint(from),Courier.hex(opened.page),
                    about==Receipt.COLLECTED_ANSWER?Courier.ANSWER:Courier.NOTE,opened.revision);
                android.util.Log.i("Mininotes/Post","collected: "+gone+" thing(s) carried for them let go");
                return new Landed(null,null);
            }
            if(about!=0&&about!=Receipt.HAVE&&about!=Receipt.TOOK)return new Landed(null,null);   // a later build
            if(about!=0) {
                boolean believed=store.acknowledged(from.address,id,opened.revision,about==Receipt.TOOK);
                android.util.Log.i("Mininotes/Post","answered: revision "+opened.revision
                    +(about==Receipt.TOOK?", and they now say the same":", and they put it with their own")
                    +(believed?"":" - which this phone never sent, so it changes nothing"));
                return new Landed(null,null,id,believed);
            }
            Parcel.Sent parcel=Parcel.open(opened.text);
            // Their build carries: from now on they may be left things, and brought them.
            if(parcel!=null&&parcel.carries)carries(where,from);
            // Something sent before notes carried their shelf: the text and nothing else. Still readable,
            // and it goes wherever anything whose shelf we were not told goes.
            if(parcel==null) {
                String text=new String(opened.text,StandardCharsets.UTF_8);
                int line=text.indexOf('\n');
                parcel=new Parcel.Sent("","","","",line<0?"":text.substring(0,line),
                    line<0?text:text.substring(line+1),false);
            }
            // Unsubscribed. They can still send it; this phone has stopped taking it in, and says nothing
            // about a thing somebody asked not to hear about again.
            NoteStore.Refusal refused=store.refusal(from.address,id,parcel);
            if(refused!=null&&refused.gone&&givenAgain(store,parcel,refused.at)) {
                // Left, and then given it again: the later decision stands, as it does for anybody.
                android.util.Log.i("Mininotes/Post","given again what this phone had left, so it is taken in");
                store.followAgain(from.address,id,refused);
                refused=null;
            }
            if(refused!=null) {
                // Nothing is said to the person, who asked not to hear. The log is told, because from
                // the outside this looks exactly like a note that never arrived.
                android.util.Log.i("Mininotes/Post",refused.gone
                    ?"not taken in: this phone has left it, and they are told again"
                    :"not taken in: this phone has unsubscribed from it");
                // They had not heard, or it went before they did. Said again, or they go on sending.
                if(refused.gone&&doesSpeak(where,from))
                    tellOff(where,keys,from,opened.page,refused.at,Receipt.left(refused.scope()));
                return new Landed(null,null);
            }
            // Who else has it, folded in before the note itself: the list is what says whether this phone
            // may hand it on, and it should not be one note behind the thing it describes.
            store.tookMembership(from.address,id,parcel);
            // Something of theirs is here, so whatever was accepted has been answered and need not be
            // said again.
            store.answered(from.address);
            // What they may do with it here decides what becomes of what they sent. A reader's words are
            // not written down, and neither are those of somebody taken off - who is told again, since
            // they plainly had not heard. Both are answered all the same, or they would send the same
            // thing every quarter of an hour for ever, which is the fault Pause still has.
            Sharing.Rule may=store.standingOf(from.address,id,parcel);
            if(may==null||!may.level.writes()) {
                android.util.Log.i("Mininotes/Post","not taken in: "+(may==null?"they were never given this"
                    :may.level==Sharing.Level.GONE?"they were taken off this, and are told again":"they may only read this"));
                if(parcel.answer){speaks(where,from);answer(where,store,keys,from,opened.page,opened.revision,false);}
                if(may!=null&&may.level==Sharing.Level.GONE&&store.saysWhoHas(may.scope,may.target)&&doesSpeak(where,from))
                    tellOff(where,keys,from,opened.page,may.changed,Receipt.removed(may.scope));
                return Landed.people(id);
            }
            // What the note said before, so that one which arrives saying the same - sent only to carry
            // a change in who may do what - is not announced as "updated a note".
            NoteStore.Note before=store.get(id);
            boolean same=before!=null&&before.body.equals(parcel.body)
                &&(before.title==null?"":before.title).equals(parcel.title==null?"":parcel.title);
            Arriving.Decision said=store.landed(id,from.address,opened.revision,parcel,store.someBook());
            // Written down, so now it can be said. Whatever was decided about it - taken, put together,
            // or set aside as older than what is here - they are told that what they sent has arrived:
            // that is the question they asked. Only a phone that asked is answered; see Parcel.answer.
            if(parcel.answer) {
                speaks(where,from);
                // Whether this phone's note now says exactly what they sent - which is what lets them
                // count this revision as one both phones have, and not only one that arrived.
                boolean took=said.what==Arriving.What.NEW||said.what==Arriving.What.NEWER;
                answer(where,store,keys,from,opened.page,opened.revision,took);
            }
            // Nothing in it changed. The marks and the box are asked again, because who may do what can have.
            if(same&&said.what!=Arriving.What.MERGED)return Landed.people(id);
            switch(said.what) {
                case NEW: return new Landed(from.name+" shared a note with you.",null,id);
                case NEWER: return new Landed(from.name+" updated a note.",null,id);
                case MERGED: return new Landed(said.keepTheirs
                    ? from.name+" wrote in the same place you did. Both are in the note."
                    : from.name+" wrote in a note you had also written in. Both are in it.",null,id);
                default: return new Landed(null,null);
            }
        } catch(Exception e) {
            // A message that cannot be opened is not ours, or has been tampered with. Either way it is
            // dropped in silence: saying so would tell a stranger their guess was close. Silence towards
            // them, that is. This phone's own log is told what kind of thing went wrong, because a fault
            // of this app's own making lands here too and would otherwise look like nothing arriving.
            android.util.Log.w("Mininotes/Post","dropped an arriving message: "+e.getClass().getSimpleName());
            return new Landed(null,null);
        }
    }
}
