package org.mininotes.android;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class SchemaMigrationsTest {
    private static final Pattern INDEX=Pattern.compile("CREATE INDEX (?:IF NOT EXISTS )?([A-Za-z0-9_]+)");
    private static Set<String> indexes(List<String> statements) {
        Set<String> names=new TreeSet<>();
        for(String statement:statements){Matcher m=INDEX.matcher(statement);while(m.find())names.add(m.group(1));}
        return names;
    }

    @Test public void everyReleasedVersionCanStepForward() {
        for(int version=1;version<SchemaMigrations.VERSION;version++)
            assertFalse("Version "+version+" has no upgrade step",SchemaMigrations.upgrade(version,version+1).isEmpty());
    }
    @Test public void anOldInstallUpgradesThroughEveryStepInOrder() {
        List<String> stepByStep=new ArrayList<>();
        for(int version=1;version<SchemaMigrations.VERSION;version++)stepByStep.addAll(SchemaMigrations.upgrade(version,version+1));
        assertEquals(stepByStep,SchemaMigrations.upgrade(1,SchemaMigrations.VERSION));
    }
    @Test public void aCurrentInstallHasNothingToApply() {
        assertTrue(SchemaMigrations.upgrade(SchemaMigrations.VERSION,SchemaMigrations.VERSION).isEmpty());
    }
    @Test public void aFreshInstallEndsWithTheIndexesAnUpgradedOneHas() {
        assertEquals(indexes(SchemaMigrations.create()),indexes(SchemaMigrations.upgrade(1,SchemaMigrations.VERSION)));
    }
    @Test public void freshInstallCreatesTheNotesTable() {
        assertTrue(SchemaMigrations.create().get(0).startsWith("CREATE TABLE notes("));
    }
    @Test public void filesKeptWithANoteExistBothWaysRound() {
        String fresh=String.join("\n",SchemaMigrations.create());
        String upgraded=String.join("\n",SchemaMigrations.upgrade(1,SchemaMigrations.VERSION));
        for(String sql:new String[]{fresh,upgraded}) {
            assertTrue("no files table",sql.contains("CREATE TABLE IF NOT EXISTS files("));
            for(String column:new String[]{"note TEXT NOT NULL","name TEXT NOT NULL","kind TEXT NOT NULL",
                                           "bytes INTEGER NOT NULL","added INTEGER NOT NULL"})
                assertTrue(column,sql.contains(column));
        }
    }

    @Test public void whatWasHandedOverIsKeptApartFromWhatArrivedBothWaysRound() {
        String fresh=String.join("\n",SchemaMigrations.create());
        String step=String.join("\n",SchemaMigrations.upgrade(17,18));
        for(String sql:new String[]{fresh,step}) {
            assertTrue("no handed table",sql.contains("CREATE TABLE IF NOT EXISTS handed("));
            for(String column:new String[]{"address TEXT NOT NULL","page TEXT NOT NULL","revision INTEGER NOT NULL",
                                           "at INTEGER NOT NULL","tries INTEGER NOT NULL","PRIMARY KEY(address,page)"})
                assertTrue(column,sql.contains(column));
        }
        // What was already recorded as delivered is left alone: most of it is true.
        assertFalse(step.toUpperCase(java.util.Locale.ROOT).contains("SENT"));
    }

    @Test public void whatWasAgreedIsKeptApartFromWhatWasDeliveredBothWaysRound() {
        String fresh=String.join("\n",SchemaMigrations.create());
        String step=String.join("\n",SchemaMigrations.upgrade(18,19));
        for(String sql:new String[]{fresh,step})
            assertTrue(sql.contains("ALTER TABLE sent ADD COLUMN agreed INTEGER NOT NULL DEFAULT 0"));
    }

    @Test public void thisPhonesOwnStandingHasSomewhereToLiveBothWaysRound() {
        String fresh=String.join("\n",SchemaMigrations.create());
        String step=String.join("\n",SchemaMigrations.upgrade(19,20));
        for(String sql:new String[]{fresh,step}) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS standing("));
            for(String column:new String[]{"scope TEXT NOT NULL","target TEXT NOT NULL","level INTEGER NOT NULL",
                                           "changed INTEGER NOT NULL","PRIMARY KEY(scope,target)"})
                assertTrue(column,sql.contains(column));
        }
    }

    @Test public void leavingIsToldApartFromStoppingForNowBothWaysRound() {
        String fresh=String.join("\n",SchemaMigrations.create());
        String step=String.join("\n",SchemaMigrations.upgrade(20,21));
        for(String sql:new String[]{fresh,step})
            assertTrue(sql.contains("ALTER TABLE refused ADD COLUMN gone INTEGER NOT NULL DEFAULT 0"));
        // Everything refused before this was stopped for now, and still is.
        assertFalse(step.contains("UPDATE"));
    }

    @Test public void upgradingNeverDiscardsStoredNotes() {
        for(String statement:SchemaMigrations.upgrade(1,SchemaMigrations.VERSION)) {
            String sql=statement.toUpperCase(java.util.Locale.ROOT);
            assertFalse(statement,sql.contains("DROP TABLE")||sql.contains("DELETE FROM")||sql.contains("DROP COLUMN"));
        }
    }
    @Test public void unknownVersionsAreRejected() {
        for(int[] range:new int[][]{{0,1},{-1,2},{1,SchemaMigrations.VERSION+1},{SchemaMigrations.VERSION+1,SchemaMigrations.VERSION+1}})
            try{SchemaMigrations.upgrade(range[0],range[1]);fail("Accepted "+range[0]+" to "+range[1]);}catch(IllegalArgumentException expected){}
    }
    @Test public void downgradeIsRefusedRatherThanRewritingTheNotebook() {
        try{SchemaMigrations.upgrade(SchemaMigrations.VERSION,1);fail("Accepted a downgrade");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("newer version"));}
    }
}
