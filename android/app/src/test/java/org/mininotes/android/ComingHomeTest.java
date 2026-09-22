package org.mininotes.android;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** A shelf is the same shelf whoever names it: see {@link Parcel#shelfHere}. */
public class ComingHomeTest {
    private static final String OWNER="owner-key", MEMBER="member-key", THIRD="third-key";
    private static final String BOOK="0b9c1f1e-52a1-4a4e-9d0e-6a3f3a7f2c11";

    @Test public void aShelfOfTheirsIsFiledUnderWhoTheyAreAndWhatTheyCallIt() {
        assertEquals(Parcel.localId(OWNER,BOOK),
            Parcel.shelfHere(MEMBER,Collections.<String>emptyList(),OWNER,BOOK));
    }

    @Test public void theOwnersBookComesHomeAsTheOwnersBook() {
        // The member calls it by the made-up id, and that is what it sends back.
        String atTheMembers=Parcel.shelfHere(MEMBER,Collections.<String>emptyList(),OWNER,BOOK);
        List<String> own=Arrays.asList("another-book",BOOK);
        assertEquals(BOOK,Parcel.shelfHere(OWNER,own,MEMBER,atTheMembers));
    }

    @Test public void aThirdPhoneCallsItWhatTheMemberDoesWhoeverItCameFrom() {
        String atTheMembers=Parcel.shelfHere(MEMBER,Collections.<String>emptyList(),OWNER,BOOK);
        String fromTheOwner=Parcel.shelfHere(THIRD,Collections.<String>emptyList(),OWNER,BOOK);
        String fromTheMember=Parcel.shelfHere(THIRD,Collections.<String>emptyList(),MEMBER,atTheMembers);
        assertEquals(fromTheOwner,fromTheMember);
        assertEquals(atTheMembers,fromTheMember);
    }

    @Test public void somebodyElsesMadeUpIdIsNotTakenForOneOfOurs() {
        String atTheMembers=Parcel.shelfHere(MEMBER,Collections.<String>emptyList(),OWNER,BOOK);
        assertEquals(atTheMembers,Parcel.shelfHere(THIRD,Arrays.asList("a","b"),MEMBER,atTheMembers));
    }

    @Test public void nothingNamedIsNowhere() {
        assertEquals("",Parcel.shelfHere(OWNER,null,MEMBER,""));
        assertEquals("",Parcel.shelfHere(OWNER,null,MEMBER,null));
    }
}
