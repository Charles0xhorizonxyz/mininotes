package org.mininotes.android;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Two phones, passing one note back and forth until neither owes the other anything.
 *
 * <p>Each rule about an arriving note is tested on its own elsewhere. This is about what they do together,
 * because the fault this was written for was in none of them singly: every step was right, and the steps
 * went round for ever. A phone here is what the notebook keeps about one note and one other device — the
 * text, the revision, what the other is known to have, and every version kept — and nothing else.
 */
public class ConvergenceTest {

    private static final class Phone {
        String text; long revision;
        /** The revision the other phone is known to have: it said so, or the note came from it. */
        long theyHave;
        final Map<Long,String> mine=new HashMap<>(), theirs=new HashMap<>();
        Phone(String text,long revision,long theyHave){this.text=text;this.revision=revision;this.theyHave=theyHave;}
        boolean owes(){return revision>theyHave;}
        void writes(String now){text=now;revision++;}

        /** The text both last had: exactly that revision where it was kept, else the nearest before. */
        String at(long revision) {
            if(mine.containsKey(revision))return mine.get(revision);
            if(theirs.containsKey(revision))return theirs.get(revision);
            String found=null; long best=-1;
            for(Map<Long,String> kept:java.util.Arrays.asList(mine,theirs))
                for(Map.Entry<Long,String> one:kept.entrySet())
                    if(one.getKey()<=revision&&one.getKey()>best){best=one.getKey();found=one.getValue();}
            return found;
        }
    }

    /** One note sent and answered, the way Post and NoteStore do it. */
    private static void send(Phone from,Phone to) {
        long revision=from.revision; String text=from.text; long basedOn=from.theyHave;
        from.mine.put(revision,text);                                   // what went is kept as it went
        long base=Arriving.agreed(to.theyHave,basedOn);
        Arriving.Decision said=Arriving.weigh(to.text,to.revision,to.at(base),base,text,revision);
        to.theirs.put(revision,text);                                   // what arrived is kept as it arrived
        if(said.what!=Arriving.What.OLDER){to.text=said.text;to.revision=said.revision;}
        if(said.what==Arriving.What.NEW||said.what==Arriving.What.NEWER)
            to.theyHave=Math.max(to.theyHave,revision);                 // whoever sent it has it
        from.theyHave=Math.max(from.theyHave,revision);                 // and they answered
    }

    /** Both phones press Sync until neither owes anything, or until it is plain they never will stop. */
    private static int settle(Phone a,Phone b) {
        int rounds=0;
        while((a.owes()||b.owes())&&rounds<40) {
            if(a.owes())send(a,b);
            if(b.owes())send(b,a);
            rounds++;
        }
        return rounds;
    }

    private static Phone[] agreedOn(String text,long revision) {
        Phone a=new Phone(text,revision,revision), b=new Phone(text,revision,revision);
        a.mine.put(revision,text);b.theirs.put(revision,text);
        return new Phone[]{a,b};
    }

    @Test public void oneSideWritingSimplyArrives() {
        Phone[] both=agreedOn("Milk\nBread",5);
        both[0].writes("Milk\nBread\nEggs");
        assertTrue(settle(both[0],both[1])<40);
        assertEquals("Milk\nBread\nEggs",both[1].text);
        assertEquals(both[0].text,both[1].text);
    }

    @Test public void differentLinesEndUpTheSameOnBothPhones() {
        Phone[] both=agreedOn("Milk\nBread\nEggs",5);
        both[0].writes("Oat milk\nBread\nEggs");
        both[1].writes("Milk\nBread\nSix eggs");
        int rounds=settle(both[0],both[1]);
        assertTrue("never settled",rounds<40);
        assertEquals("Oat milk\nBread\nSix eggs",both[0].text);
        assertEquals(both[0].text,both[1].text);
    }

    @Test public void theSameLineEndsUpTheSameOnBothPhonesWithBothLinesInIt() {
        // The case on the two phones this was written on: both wrote on the last line. It ended with two
        // ticks over two different notes, and its owner could not find anything to press that made them
        // the same. They are the same now, by themselves, and nobody's line is anywhere but in the note.
        Phone[] both=agreedOn("Test sharing\n\nend",13);
        both[0].writes("Test sharing\n\nendP3B2Q4");
        both[1].writes("Test sharing\n\nendB2V9");
        int rounds=settle(both[0],both[1]);
        assertTrue("two phones went on owing each other for ever",rounds<40);
        assertEquals("two ticks over two different notes",both[0].text,both[1].text);
        assertTrue(both[0].text.contains("endP3B2Q4"));
        assertTrue(both[0].text.contains("endB2V9"));
        assertFalse(both[0].owes());
        assertFalse(both[1].owes());
    }

    @Test public void deletingTheSpareLineThenArrivesLikeAnyOtherEdit() {
        Phone[] both=agreedOn("Test sharing\n\nend",13);
        both[0].writes("Test sharing\n\nendP3B2Q4");
        both[1].writes("Test sharing\n\nendB2V9");
        settle(both[0],both[1]);
        both[1].writes("Test sharing\n\nendP3B2Q4");
        assertTrue(settle(both[0],both[1])<40);
        assertEquals("Test sharing\n\nendP3B2Q4",both[0].text);
        assertEquals(both[0].text,both[1].text);
    }

    @Test public void twoNotesThatDriftedApartWithNothingAgreedComeTogether() {
        // Where the two phones stand today: each believes the other has its latest, the texts differ, and
        // nothing either of them remembers agreeing on can be trusted. Treated as never having agreed.
        Phone a=new Phone("Title\n\nshared line\n\ntest test test\n\nendP3B2Q4",60,0);
        Phone b=new Phone("Title\n\nshared line\n\ntest tesU8t test\n\nendB2V9\nkm",59,0);
        int rounds=settle(a,b);
        assertTrue("never settled",rounds<40);
        assertEquals(a.text,b.text);
        for(String line:new String[]{"test test test","test tesU8t test","endP3B2Q4","endB2V9","km"})
            assertTrue(line+" went missing",a.text.contains(line));
        assertEquals("what they share is there once",1,a.text.split("shared line",-1).length-1);
    }

    @Test public void aWrongBeliefAboutWhatTheOtherHasStillSettles() {
        // The first phone believes the second has its revision 20. It never got it: every one was taken
        // by the network and none arrived. The second has gone on writing on top of 13.
        Phone a=new Phone("Test sharing\n\nendP3B2Q4",20,20);
        Phone b=new Phone("Test sharing\nline six\n\nend",17,17);
        a.mine.put(13L,"Test sharing\n\nend");a.mine.put(20L,a.text);
        b.theirs.put(13L,"Test sharing\n\nend");b.mine.put(17L,b.text);
        a.theyHave=20;b.theyHave=13;
        b.writes("Test sharing\nline six!\n\nend");
        int rounds=settle(a,b);
        assertTrue("never settled",rounds<40);
        assertTrue("what the second phone wrote was set aside as old news",a.text.contains("line six!"));
        assertTrue(a.text.contains("endP3B2Q4"));
    }
}
