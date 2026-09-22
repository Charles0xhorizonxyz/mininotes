// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Who has a thing, as it travels and as it is merged.
 *
 * <p>The merging itself needs a notebook and is exercised on a device; what can be pinned here is the
 * shape of a level, the rule that settles two people deciding at once, and the wire format that carries a
 * membership — including that a build which never heard of memberships still reads the note.
 */
public class MembershipTest {

    /** Ordered, so "at least this much" is a comparison and not a table. */
    @Test public void levelsAreOrdered() {
        assertTrue(Sharing.Level.ADMIN.ordinal()>Sharing.Level.WRITE.ordinal());
        assertTrue(Sharing.Level.WRITE.ordinal()>Sharing.Level.READ.ordinal());
        assertTrue(Sharing.Level.READ.ordinal()>Sharing.Level.GONE.ordinal());
    }

    /** An admin may write; somebody who may write may not share. */
    @Test public void whatEachLevelAllows() {
        assertTrue(Sharing.Level.ADMIN.writes());
        assertTrue(Sharing.Level.ADMIN.shares());
        assertTrue(Sharing.Level.WRITE.writes());
        assertFalse("writing is not sharing on",Sharing.Level.WRITE.shares());
        assertFalse(Sharing.Level.READ.writes());
        assertFalse(Sharing.Level.GONE.writes());
    }

    /** A number from a newer build is read as the most this one knows, never as nothing. */
    @Test public void anUnknownLevelIsNotNothing() {
        assertEquals(Sharing.Level.GONE,Sharing.Level.of(0));
        assertEquals(Sharing.Level.ADMIN,Sharing.Level.of(3));
        assertEquals("a level from a later build reads as the highest this one has",
            Sharing.Level.ADMIN,Sharing.Level.of(9));
        assertEquals(Sharing.Level.GONE,Sharing.Level.of(-1));
    }

    /** The old two-state constructor still means what it meant. */
    @Test public void theOldWayStillMeansTheSame() {
        assertEquals(Sharing.Level.WRITE,
            new Sharing.Rule(Sharing.Scope.BOOK,"b","MxA",true).level);
        assertEquals(Sharing.Level.READ,
            new Sharing.Rule(Sharing.Scope.BOOK,"b","MxA",false).level);
        assertTrue(new Sharing.Rule(Sharing.Scope.BOOK,"b","MxA",true).mine);
    }

    /** A membership travels whole, and comes back the same. */
    @Test public void aMembershipTravels() throws IOException {
        List<Parcel.Member> members=Arrays.asList(
            new Parcel.Member("keyAna","MxAna@h:1","Ana",Sharing.Level.ADMIN.said(),1000L),
            new Parcel.Member("keyBob","MxBob@h:1","Bob",Sharing.Level.READ.said(),2000L),
            new Parcel.Member("keyOld","MxOld@h:1","Old",Sharing.Level.GONE.said(),3000L));
        Parcel.Sent in=Parcel.open(Parcel.wrap(new Parcel.Sent("c","Work","b","Meetings","T","body",
            true,members,"BOOK","b")));
        assertNotNull(in);
        assertEquals("BOOK",in.scope);
        assertEquals("b",in.target);
        assertEquals(3,in.members.size());
        assertEquals("Ana",in.members.get(0).name);
        assertEquals(Sharing.Level.ADMIN.said(),in.members.get(0).level);
        assertEquals(2000L,in.members.get(1).changed);
        assertEquals("somebody taken off travels as gone, not as absent",
            Sharing.Level.GONE.said(),in.members.get(2).level);
    }

    /**
     * A removal has to travel, or it comes undone.
     *
     * <p>If somebody taken off simply vanished from the list, the next copy of the list from a phone that
     * had not heard would put them back — for ever, since nothing would ever say they were removed.
     */
    @Test public void beingTakenOffIsSomethingRatherThanNothing() {
        assertEquals(Sharing.Level.GONE,Sharing.Level.of(Sharing.Level.GONE.said()));
        assertEquals("Not shared",Sharing.Level.GONE.words());
    }

    /** A note from a build that never heard of memberships still reads, and carries none. */
    @Test public void anOlderNoteStillReads() throws IOException {
        Parcel.Sent in=Parcel.open(Parcel.wrap(
            new Parcel.Sent("c","Work","b","Meetings","T","body here",false)));
        assertNotNull(in);
        assertEquals("body here",in.body);
        assertTrue(in.members.isEmpty());
        assertEquals("",in.scope);
    }

    /** A list longer than a list is refused rather than allocated. */
    @Test public void tooManyMembersIsRefused() {
        byte[] lie=new byte[Parcel.MAGIC.length+1+6*4+4];
        System.arraycopy(Parcel.MAGIC,0,lie,0,Parcel.MAGIC.length);
        // writes=false, then six empty strings, then a member count of 0x7fffffff
        int at=Parcel.MAGIC.length+1+6*4;
        lie[at]=0x7f;lie[at+1]=(byte)0xff;lie[at+2]=(byte)0xff;lie[at+3]=(byte)0xff;
        // Whatever this parses as, it must not be a parcel with two billion members.
        Parcel.Sent said=Parcel.open(lie);
        if(said!=null)assertTrue(said.members.size()<=Parcel.MEMBERS_MOST);
    }

    /** Half a membership is not a membership. */
    @Test public void aTruncatedMembershipIsRefused() throws IOException {
        List<Parcel.Member> members=new ArrayList<>();
        members.add(new Parcel.Member("k","MxA@h:1","Ana",Sharing.Level.WRITE.said(),1L));
        byte[] whole=Parcel.wrap(new Parcel.Sent("c","W","b","M","T","body",true,members,"BOOK","b"));
        // Nine bytes come after the membership now - one saying whether the sender wants answering, eight
        // saying what the note was written on top of. A parcel that ends cleanly before either of them is
        // not truncated: it is what an earlier build wrote, and it carries the whole membership.
        final int tail=9;
        for(int cut:new int[]{whole.length-8,whole.length-tail}) {
            Parcel.Sent before=Parcel.open(java.util.Arrays.copyOf(whole,cut));
            assertNotNull("cut to "+cut,before);
            assertEquals(1,before.members.size());
            assertEquals("Ana",before.members.get(0).name);
        }
        // One that ends part of the way through a number is cut short, and is refused like any other.
        for(int cut=whole.length-1;cut>whole.length-8;cut--)
            assertNull("cut to "+cut,Parcel.open(java.util.Arrays.copyOf(whole,cut)));
        for(int cut=whole.length-tail-1;cut>whole.length-tail-20&&cut>0;cut--) {
            byte[] part=new byte[cut];
            System.arraycopy(whole,0,part,0,cut);
            Parcel.Sent said=Parcel.open(part);
            // Either it is refused outright, or it stops before the membership - never half a member.
            if(said!=null)assertTrue("cut to "+cut+" gave "+said.members.size()+" members",
                said.members.isEmpty());
        }
    }
}
