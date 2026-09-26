package org.mininotes.android;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.sql.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** What the lock needs from the storage library, on a throwaway file: encrypt in place, and nothing opens it without the key. */
public class CipherProbeTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String HEX="00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static Connection open(Path file,String key) throws SQLException {
        String url="jdbc:sqlite:"+file.toAbsolutePath();
        if(key==null)return DriverManager.getConnection(url);
        // SQLCipher 4's own settings and the raw key, applied by the driver before the first page is read.
        return DriverManager.getConnection(url,org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(bytes(key)).build().toProperties());
    }
    private static byte[] bytes(String hex){byte[] b=new byte[hex.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);return b;}
    private static String read(Connection c) throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT body FROM notes")){r.next();return r.getString(1);}
    }
    @Test public void aPlainNotebookIsEncryptedInPlaceAndOpensOnlyWithItsKey() throws Exception {
        Path file=temp.newFile("probe.db").toPath();
        try(Connection c=open(file,null);Statement s=c.createStatement()) {
            s.execute("CREATE TABLE notes(body TEXT)");s.execute("INSERT INTO notes VALUES('Synthetic grocery list')");
            s.execute("PRAGMA cipher='sqlcipher'");s.execute("PRAGMA legacy=4");s.execute("PRAGMA rekey=\"x'"+HEX+"'\"");
        }
        assertFalse("the words are no longer on disk as written",new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.ISO_8859_1).contains("grocery"));
        try(Connection c=open(file,HEX)){assertEquals("Synthetic grocery list",read(c));}
        try(Connection c=open(file,null)){read(c);fail("opened without a key");}catch(SQLException refused){/* as it should */}
        try(Connection c=open(file,HEX.replace('0','1'))){read(c);fail("opened with the wrong key");}catch(SQLException refused){/* as it should */}
        // And back to plain, which is what switching the lock off does.
        try(Connection c=open(file,HEX);Statement s=c.createStatement()){s.execute("PRAGMA rekey=''");}
        try(Connection c=open(file,null)){assertEquals("Synthetic grocery list",read(c));}
    }
}
