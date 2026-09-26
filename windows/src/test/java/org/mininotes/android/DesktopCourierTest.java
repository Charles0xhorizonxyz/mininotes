package org.mininotes.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.security.KeyPair;
import java.util.*;
import org.mininotes.desktop.platform.content.Context;

/**
 * Three synthetic devices: a writer that is gone, a carrier that is on, and a phone that was away. The
 * network is not used: each message is sealed and handed to the device it is for, as a relay would.
 */
public class DesktopCourierTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final List<NoteStore> open=new ArrayList<>();
    @After public void close(){for(NoteStore one:open)one.close();}

    private static final String WRITER="MxWriterFixture@127.0.0.1:9101",CARRIER="MxCarrierFixture@127.0.0.1:9102",AWAY="MxAwayFixture@127.0.0.1:9103";

    private final class Device {
        final Context context;final NoteStore store;final Keys keys;
        Device(String name) throws Exception {
            context=new Context(temp.newFolder(name));store=new NoteStore(context);store.getWritableDatabase();open.add(store);
            keys=new Keys(context);store.mySigningKey=Base64.getEncoder().encodeToString(keys.signing().getPublic().getEncoded());
        }
        void pair(String address,String name,Device other) throws Exception {
            store.pairedWith(address,name,false,other.keys.agreement().getPublic().getEncoded(),other.keys.signing().getPublic().getEncoded());
        }
        /** A note from {@code from} saying its build carries, so this device will carry for it and bring it things. */
        void heardCarries(Device from) throws Exception {
            byte[] parcel=Parcel.wrap(new Parcel.Sent("","","","","","",false,Collections.emptyList(),"","",false,-1,true));
            Post.arrived(context,store,keys,Envelope.seal(new byte[16],0,1,parcel,from.keys.signing(),keys.agreement().getPublic()));
        }
        byte[] fingerprint() throws Exception {return Envelope.fingerprint(keys.signing().getPublic());}
        Post.Landed hear(byte[] sealed){return Post.arrived(context,store,keys,sealed);}
    }

    private static byte[] page(String id){UUID u=UUID.fromString(id);return java.nio.ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();}

    @Test public void aNoteLeftWithACarrierReachesThePhoneThatWasAwayAndIsLetGo() throws Exception {
        Device writer=new Device("writer"),carrier=new Device("carrier"),away=new Device("away");
        carrier.pair(WRITER,"Writer",writer);carrier.pair(AWAY,"Away",away);
        away.pair(WRITER,"Writer",writer);away.pair(CARRIER,"Carrier",carrier);
        carrier.heardCarries(away);carrier.heardCarries(writer);

        // The writer seals a note for the phone that is away, and leaves it with the carrier.
        String id=UUID.randomUUID().toString();
        away.store.addShare(new Sharing.Rule(Sharing.Scope.PAGE,id,WRITER,true));
        byte[] note=Parcel.wrap(new Parcel.Sent("c","Home","b","Lists","Groceries","Bread\nMilk",true));
        byte[] inner=Envelope.seal(page(id),3,1,note,writer.keys.signing(),away.keys.agreement().getPublic());
        byte[] left=Envelope.seal(page(id),3,2,Courier.leave(Courier.NOTE,away.fingerprint(),inner),writer.keys.signing(),carrier.keys.agreement().getPublic());
        assertEquals("",carrier.hear(left).note);
        String awayKey=Courier.hex(away.fingerprint());
        List<NoteStore.Carried> held=carrier.store.carried(awayKey);
        assertEquals(1,held.size());assertArrayEquals(inner,held.get(0).bytes);assertEquals(3,held.get(0).revision);
        assertNull("the carrier cannot read it",carrier.store.get(id));

        // Left again at an older revision: the newer one stays. At a newer one: it replaces the older.
        byte[] older=Envelope.seal(page(id),2,3,Courier.leave(Courier.NOTE,away.fingerprint(),inner),writer.keys.signing(),carrier.keys.agreement().getPublic());
        carrier.hear(older);assertEquals(3,carrier.store.carried(awayKey).get(0).revision);
        byte[] newer=Envelope.seal(page(id),4,4,Courier.leave(Courier.NOTE,away.fingerprint(),inner),writer.keys.signing(),carrier.keys.agreement().getPublic());
        carrier.hear(newer);assertEquals(1,carrier.store.carried(awayKey).size());assertEquals(4,carrier.store.carried(awayKey).get(0).revision);

        // The phone comes back and is brought it: it lands as if it had come from the writer.
        byte[] brought=Envelope.seal(page(id),4,5,Courier.bring(Courier.NOTE,inner),carrier.keys.signing(),away.keys.agreement().getPublic());
        Post.Landed landed=away.hear(brought);
        assertEquals(id,landed.note);assertEquals("Bread\nMilk",away.store.get(id).body);

        // It says it has it, and the carrier lets go - of the note, and of nothing else.
        byte[] answerFor=Envelope.seal(page(id),1,6,Courier.leave(Courier.ANSWER,away.fingerprint(),inner),writer.keys.signing(),carrier.keys.agreement().getPublic());
        carrier.hear(answerFor);assertEquals(2,carrier.store.carried(awayKey).size());
        carrier.hear(Envelope.seal(page(id),4,7,Receipt.wrap(Receipt.COLLECTED),away.keys.signing(),carrier.keys.agreement().getPublic()));
        List<NoteStore.Carried> after=carrier.store.carried(awayKey);
        assertEquals(1,after.size());assertEquals(Courier.ANSWER,after.get(0).sort);
    }

    @Test public void nothingIsCarriedForAStrangerOrForABuildThatWouldNotKnowIt() throws Exception {
        Device writer=new Device("writer"),carrier=new Device("carrier"),away=new Device("away"),stranger=new Device("stranger");
        carrier.pair(WRITER,"Writer",writer);carrier.pair(AWAY,"Away",away);
        byte[] inner=Envelope.seal(new byte[16],1,1,"x".getBytes(),writer.keys.signing(),away.keys.agreement().getPublic());
        // For a device paired here, but whose build has never said it carries: it would read the bytes as a note.
        carrier.hear(Envelope.seal(new byte[16],1,2,Courier.leave(Courier.NOTE,away.fingerprint(),inner),writer.keys.signing(),carrier.keys.agreement().getPublic()));
        assertTrue(carrier.store.carried(null).isEmpty());
        // For a device not paired here at all.
        carrier.hear(Envelope.seal(new byte[16],1,3,Courier.leave(Courier.NOTE,stranger.fingerprint(),inner),writer.keys.signing(),carrier.keys.agreement().getPublic()));
        assertTrue(carrier.store.carried(null).isEmpty());
        // Left by a device not paired here.
        carrier.heardCarries(away);
        carrier.hear(Envelope.seal(new byte[16],1,4,Courier.leave(Courier.NOTE,away.fingerprint(),inner),stranger.keys.signing(),carrier.keys.agreement().getPublic()));
        assertTrue(carrier.store.carried(null).isEmpty());
        // And from a paired writer, for the away phone that now carries: kept.
        carrier.hear(Envelope.seal(new byte[16],1,5,Courier.leave(Courier.NOTE,away.fingerprint(),inner),writer.keys.signing(),carrier.keys.agreement().getPublic()));
        assertEquals(1,carrier.store.carried(null).size());
    }

    @Test public void aBroughtThingCannotCarryAnotherInside() throws Exception {
        Device writer=new Device("writer"),carrier=new Device("carrier"),away=new Device("away");
        away.pair(WRITER,"Writer",writer);away.pair(CARRIER,"Carrier",carrier);away.heardCarries(carrier);
        // The writer may write this note here, so anything read as a note would be written over it.
        String id=UUID.randomUUID().toString();away.store.addShare(new Sharing.Rule(Sharing.Scope.PAGE,id,WRITER,true));
        byte[] nested=Envelope.seal(page(id),1,1,Courier.leave(Courier.NOTE,carrier.fingerprint(),new byte[]{1}),writer.keys.signing(),away.keys.agreement().getPublic());
        away.hear(Envelope.seal(page(id),1,2,Courier.bring(Courier.NOTE,nested),carrier.keys.signing(),away.keys.agreement().getPublic()));
        assertTrue(away.store.carried(null).isEmpty());
        assertNull(away.store.get(id));
    }
}
