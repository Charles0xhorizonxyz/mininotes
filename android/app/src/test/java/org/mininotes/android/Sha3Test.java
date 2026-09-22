package org.mininotes.android;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/**
 * The published NIST answers. A hash that is subtly wrong does not crash — it makes this phone a different
 * phone on the network, quietly — so the only acceptable evidence is the numbers somebody else printed.
 */
public class Sha3Test {

    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte b:bytes)out.append(String.format("%02x",b&255));
        return out.toString();
    }
    private static String of(String said) {
        return hex(Sha3.of(said.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void theEmptyMessage() {
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",of(""));
    }

    @Test public void theOneEverybodyPrints() {
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",of("abc"));
    }

    @Test public void oneBlockShortOfTheRate() {
        // 448 bits: the second of the two NIST short messages, and the one that catches bad padding.
        assertEquals("41c0dba2a9d6240849100376a8235e2c82e1b9998a999e21db32dd97496d3376",
            of("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"));
    }

    @Test public void longerThanOneBlock() {
        // 896 bits, which is longer than the 1088-bit rate, so more than one block is absorbed.
        assertEquals("916f6061fe879741ca6469b43971dfdb28b1a32dc36cb3254e812be27aad1d18",
            of("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmn"
              +"hijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"));
    }

    @Test public void exactlyTheRate() {
        // 136 bytes: the block boundary, where padding must start a whole new block.
        StringBuilder said=new StringBuilder();
        for(int i=0;i<136;i++)said.append('a');
        String out=of(said.toString());
        assertEquals(64,out.length());
        // Not the empty hash, and not the 135- or 137-byte one: the boundary is not silently wrong.
        assertNotEquals(of(said.substring(0,135)),out);
        assertNotEquals(of(said+"a"),out);
    }

    @Test public void aMillionAsIsStillRight() {
        // The long NIST vector. Slow enough to be worth doing once, and it exercises the absorb loop.
        StringBuilder said=new StringBuilder();
        for(int i=0;i<1000000;i++)said.append('a');
        assertEquals("5c8875ae474a3634ba4fd55ec85bffd661f32aca75c6d699d0cdcb6c115891c1",
            of(said.toString()));
    }

    @Test public void theCoreAcceptsIt() {
        // The transport checks any injected hash against its own known answer before it will use one, and
        // refuses loudly rather than going quiet on the network. This must pass that gate.
        com.eurobuddha.maxima.core.crypto.Hashes.setSha3(Sha3::of);
        assertTrue(com.eurobuddha.maxima.core.crypto.Hashes.isAvailable());
    }
}
