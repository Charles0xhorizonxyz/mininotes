package org.mininotes.android;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class SharingTest {
    private static final String WORK="collection-work", HOME="collection-home";
    private static final String DIARY="book-diary", RECIPES="book-recipes";
    private static final String PAGE="page-1", OTHER_PAGE="page-2";
    private static final String TABLET="MxA..tablet", FRIEND="MxB..friend", STRANGER="MxC..stranger";

    private static Sharing.Rule rule(Sharing.Scope scope,String target,String address,boolean mine) {
        return new Sharing.Rule(scope,target,address,mine);
    }
    private static Map<String,Boolean> reached(List<Sharing.Rule> rules) {
        return Sharing.audience(rules,HOME,DIARY,PAGE);
    }

    @Test public void sharingEverythingReachesEveryPage() {
        List<Sharing.Rule> rules=List.of(rule(Sharing.Scope.LIBRARY,Sharing.EVERYTHING,TABLET,true));
        assertTrue(Sharing.audience(rules,HOME,DIARY,PAGE).containsKey(TABLET));
        assertTrue(Sharing.audience(rules,WORK,RECIPES,OTHER_PAGE).containsKey(TABLET));
    }
    @Test public void aCollectionRuleReachesItsOwnPagesOnly() {
        List<Sharing.Rule> rules=List.of(rule(Sharing.Scope.COLLECTION,HOME,FRIEND,false));
        assertTrue(reached(rules).containsKey(FRIEND));
        assertFalse("A page in another collection must not leak",Sharing.audience(rules,WORK,RECIPES,OTHER_PAGE).containsKey(FRIEND));
    }
    @Test public void aBookRuleReachesItsOwnPagesOnly() {
        List<Sharing.Rule> rules=List.of(rule(Sharing.Scope.BOOK,DIARY,FRIEND,false));
        assertTrue(reached(rules).containsKey(FRIEND));
        assertFalse(Sharing.audience(rules,HOME,RECIPES,OTHER_PAGE).containsKey(FRIEND));
    }
    @Test public void aPageRuleReachesThatPageOnly() {
        List<Sharing.Rule> rules=List.of(rule(Sharing.Scope.PAGE,PAGE,FRIEND,false));
        assertTrue(reached(rules).containsKey(FRIEND));
        assertFalse("The next page in the same book must not go with it",Sharing.audience(rules,HOME,DIARY,OTHER_PAGE).containsKey(FRIEND));
    }
    @Test public void anUnsharedPadReachesNobody() {
        assertTrue(reached(List.of()).isEmpty());
    }
    @Test public void anAddressReachedAtSeveralLevelsIsListedOnce() {
        List<Sharing.Rule> rules=Arrays.asList(
            rule(Sharing.Scope.LIBRARY,Sharing.EVERYTHING,FRIEND,false),
            rule(Sharing.Scope.COLLECTION,HOME,FRIEND,false),
            rule(Sharing.Scope.PAGE,PAGE,FRIEND,false));
        assertEquals(1,reached(rules).size());
    }
    @Test public void yourOwnDeviceStaysTwoWayEvenWhenAlsoNamedAsSomeoneElse() {
        assertTrue(reached(Arrays.asList(
            rule(Sharing.Scope.COLLECTION,HOME,TABLET,false),
            rule(Sharing.Scope.LIBRARY,Sharing.EVERYTHING,TABLET,true))).get(TABLET));
        assertTrue("Order must not decide whether your own device can write back",reached(Arrays.asList(
            rule(Sharing.Scope.LIBRARY,Sharing.EVERYTHING,TABLET,true),
            rule(Sharing.Scope.COLLECTION,HOME,TABLET,false))).get(TABLET));
    }
    @Test public void someoneElseNeverBecomesADeviceOfYours() {
        assertFalse(reached(List.of(rule(Sharing.Scope.BOOK,DIARY,FRIEND,false))).get(FRIEND));
    }
    @Test public void aRuleForAnotherTargetNeverApplies() {
        List<Sharing.Rule> rules=Arrays.asList(
            rule(Sharing.Scope.COLLECTION,WORK,STRANGER,false),
            rule(Sharing.Scope.BOOK,RECIPES,STRANGER,false),
            rule(Sharing.Scope.PAGE,OTHER_PAGE,STRANGER,false));
        assertTrue("Nothing set on another collection, book or page may reach this one",reached(rules).isEmpty());
    }
    @Test public void everyLevelCanBeDescribedToTheReader() {
        for(Sharing.Scope scope:Sharing.Scope.values())assertFalse(Sharing.describe(scope,"Recipes").isEmpty());
        assertTrue(Sharing.describe(Sharing.Scope.BOOK,"Recipes").contains("Recipes"));
    }
    @Test public void draggingAPageIntoASharedBookDisclosesIt() {
        Sharing.Change change=Sharing.moving(List.of(rule(Sharing.Scope.BOOK,RECIPES,FRIEND,false)),
            HOME,DIARY,HOME,RECIPES,PAGE);
        assertTrue("Moving into a shared book must be reported as a disclosure",change.gained.containsKey(FRIEND));
        assertTrue(change.lost.isEmpty());
        assertTrue(change.any());
    }
    @Test public void draggingAPageOutOfASharedBookWithdrawsIt() {
        Sharing.Change change=Sharing.moving(List.of(rule(Sharing.Scope.BOOK,DIARY,FRIEND,false)),
            HOME,DIARY,HOME,RECIPES,PAGE);
        assertTrue(change.lost.containsKey(FRIEND));
        assertTrue(change.gained.isEmpty());
    }
    @Test public void aMoveThatChangesNobodyNeedsNoConfirmation() {
        Sharing.Change change=Sharing.moving(List.of(rule(Sharing.Scope.LIBRARY,Sharing.EVERYTHING,TABLET,true)),
            HOME,DIARY,HOME,RECIPES,PAGE);
        assertFalse("A pad shared as a whole is unaffected by where a page sits",change.any());
    }
    @Test public void aRuleOnThePageItselfFollowsItAndIsNotReported() {
        Sharing.Change change=Sharing.moving(List.of(rule(Sharing.Scope.PAGE,PAGE,FRIEND,false)),
            HOME,DIARY,WORK,RECIPES,PAGE);
        assertFalse(change.gained.containsKey(FRIEND));
        assertFalse(change.lost.containsKey(FRIEND));
    }
    @Test public void draggingABookBetweenCollectionsSwapsTheirAudiences() {
        Sharing.Change change=Sharing.moving(Arrays.asList(
                rule(Sharing.Scope.COLLECTION,HOME,FRIEND,false),
                rule(Sharing.Scope.COLLECTION,WORK,STRANGER,false)),
            HOME,DIARY,WORK,DIARY,"");
        assertTrue(change.gained.containsKey(STRANGER));
        assertTrue(change.lost.containsKey(FRIEND));
    }
    @Test public void aMoveTellsTheReaderWhetherTheAudienceIsTheirOwnDevice() {
        Sharing.Change change=Sharing.moving(List.of(rule(Sharing.Scope.BOOK,RECIPES,TABLET,true)),
            HOME,DIARY,HOME,RECIPES,PAGE);
        assertTrue("A device of yours must be named as yours in the warning",change.gained.get(TABLET));
    }
    @Test public void anUnsharedPadNeverAsksToConfirmAMove() {
        assertFalse(Sharing.moving(List.of(),HOME,DIARY,WORK,RECIPES,PAGE).any());
    }

    @Test public void theSameAddressAtTheSameLevelIsOneRule() {
        assertEquals(rule(Sharing.Scope.BOOK,DIARY,FRIEND,false),rule(Sharing.Scope.BOOK,DIARY,FRIEND,true));
        assertNotEquals(rule(Sharing.Scope.BOOK,DIARY,FRIEND,false),rule(Sharing.Scope.BOOK,RECIPES,FRIEND,false));
    }

    @Test public void anOfferIsNamedSoItMeansSomethingOnTheOtherPhone() {
        // "This note" is true where you are standing on it and nowhere else.
        assertEquals("this note",Sharing.shortly(Sharing.Scope.PAGE,"Chain wax"));
        assertEquals("the note Chain wax",Sharing.travelling(Sharing.Scope.PAGE,"Chain wax"));
        assertEquals("the collection Allotment",Sharing.travelling(Sharing.Scope.COLLECTION,"Allotment"));
        assertEquals("the book Seeds",Sharing.travelling(Sharing.Scope.BOOK,"Seeds"));
    }
}
