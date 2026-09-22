package org.mininotes.android;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
public class RevisionClockTest {
    @Test public void offlineEditsOnTwoDevicesConflict(){assertEquals(RevisionClock.Relation.CONCURRENT,RevisionClock.compare(Map.of("phone",2L,"tablet",1L),Map.of("phone",1L,"tablet",2L)));}
    @Test public void duplicateIsNotAnotherEdit(){assertEquals(RevisionClock.Relation.SAME,RevisionClock.compare(Map.of("phone",2L),Map.of("phone",2L)));}
    @Test public void delayedEditCannotSupersedeDescendant(){assertEquals(RevisionClock.Relation.BEFORE,RevisionClock.compare(Map.of("phone",1L),Map.of("phone",2L)));}
    @Test public void unseenDeviceCreatesConcurrentHistory(){assertEquals(RevisionClock.Relation.CONCURRENT,RevisionClock.compare(Map.of("phone",1L),Map.of("tablet",1L)));}
    @Test public void wallClockDoesNotDecideOrdering(){assertEquals(RevisionClock.Relation.AFTER,RevisionClock.compare(Map.of("phone",2L,"tablet",1L),Map.of("phone",1L)));}
}
