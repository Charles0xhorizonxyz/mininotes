package org.mininotes.android;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class PairingTest {
    private static final byte[] AGREE="agreement-key-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIGN="signing-key-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String ADDRESS="MxG18TTN0S8BJTCJ4YHR@89.32.7.4:9001";

    @Test public void aLineSaysWhoItIsAndComesBackTheSame() {
        String line=Pairing.write("My tablet",ADDRESS,AGREE,SIGN);
        assertTrue(line.startsWith(Pairing.MARK));
        Pairing.Said said=Pairing.read(line);
        assertEquals("My tablet",said.name);
        assertEquals(ADDRESS,said.address);
        assertArrayEquals(AGREE,said.agreement);
        assertArrayEquals(SIGN,said.signing);
    }

    @Test public void aLineSurvivesBeingPastedWithSpaceAroundIt() {
        String line=Pairing.write("My tablet",ADDRESS,AGREE,SIGN);
        assertEquals(ADDRESS,Pairing.read("  "+line+"\n").address);
    }

    @Test public void aLineCarriesNoLineBreaksOfItsOwn() {
        String line=Pairing.write("two\nnames",ADDRESS,AGREE,SIGN);
        assertEquals(-1,line.indexOf('\n'));
        assertEquals("two names",Pairing.read(line).name);
    }

    @Test public void anUnnamedDeviceIsStillADevice() {
        assertEquals("Their device",Pairing.read(Pairing.write("",ADDRESS,AGREE,SIGN)).name);
        assertEquals("Their device",Pairing.read(Pairing.write(null,ADDRESS,AGREE,SIGN)).name);
    }

    @Test public void aDeviceWithNoKeysIsRefused() {
        try{Pairing.write("A",ADDRESS,new byte[0],SIGN);fail("Accepted no agreement key");}catch(IllegalArgumentException expected){}
        try{Pairing.write("A",ADDRESS,AGREE,null);fail("Accepted no signing key");}catch(IllegalArgumentException expected){}
    }

    @Test public void aDeviceThatDoesNotYetKnowWhereItIsCanStillBePairedWith() {
        // Its node may not have been asked yet, or may not be running. The keys are what pairing is for.
        Pairing.Said said=Pairing.read(Pairing.write("My tablet","",AGREE,SIGN));
        assertEquals("",said.address);
        assertArrayEquals(AGREE,said.agreement);
    }

    @Test public void somethingElseEntirelyIsNotHalfRead() {
        for(String bad:new String[]{null,"","hello","MxG18TTN0S8BJTCJ4YHR@89.32.7.4:9001",
                                    Pairing.MARK+"!!!not base64!!!","MN2.abcd"})
            try{Pairing.read(bad);fail("Accepted "+bad);}catch(IllegalArgumentException expected){}
    }

    @Test public void aLineThatIsNotWholeIsRefused() {
        String half=Pairing.MARK+Base64.getUrlEncoder().withoutPadding()
            .encodeToString("name\naddress".getBytes(StandardCharsets.UTF_8));
        try{Pairing.read(half);fail("Accepted half a line");}catch(IllegalArgumentException expected){}
    }

    @Test public void aStrangerCannotMakeTheAppReadSomethingEnormous() {
        String huge=Pairing.MARK+"A".repeat(Pairing.MOST);
        try{Pairing.read(huge);fail("Accepted something enormous");}catch(IllegalArgumentException expected){}
    }

    @Test public void aNameTooLongToReadIsCutRatherThanRefused() {
        Pairing.Said said=Pairing.read(Pairing.write("n".repeat(500),ADDRESS,AGREE,SIGN));
        assertEquals(Pairing.NAME_MOST,said.name.length());
    }

    @Test public void aWholeMaximaAddressSurvivesTheLine() {
        // A real contact address is the whole public key and then the host: about four hundred characters.
        StringBuilder key=new StringBuilder("Mx");
        for(int i=0;i<380;i++)key.append((char)('A'+(i%26)));
        String address=key+"@45.77.57.24:9501";
        Pairing.Said said=Pairing.read(Pairing.write("Pixel",address,new byte[]{1},new byte[]{2}));
        assertEquals("an address cut short looks right and reaches nobody",address,said.address);
    }

    @Test public void anAddressWithNowhereToReachItIsNotAnAddress() {
        assertTrue(Pairing.reachable("MxG18HGG6FJ0386@45.77.57.24:9501"));
        assertFalse("a key is not an address",Pairing.reachable("MxG18HGG6FJ0386"));
        assertFalse(Pairing.reachable("MxG18HGG6FJ0386@"));
        assertFalse(Pairing.reachable("0x4413AB"));
        assertFalse(Pairing.reachable(null));
        assertFalse(Pairing.reachable(""));
    }

    @Test public void aLineFromTheOlderFormatIsStillRead() {
        // MN1 wrapped everything in a second Base64. A phone paired before that changed still has such a
        // line saved, and a format change that quietly stops reading one is a device that stops working.
        String inner="Old phone"+"\n"+"Mx0LD@10.0.0.4:9001"+"\n"
            +java.util.Base64.getEncoder().encodeToString(new byte[]{1,2,3})+"\n"
            +java.util.Base64.getEncoder().encodeToString(new byte[]{4,5,6});
        String old="MN1."+java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(inner.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Pairing.Said said=Pairing.read(old);
        assertEquals("Old phone",said.name);
        assertEquals("Mx0LD@10.0.0.4:9001",said.address);
        assertArrayEquals(new byte[]{1,2,3},said.agreement);
    }

    @Test public void theNewFormatIsShorterThanTheOldOne() {
        StringBuilder address=new StringBuilder("Mx");
        for(int i=0;i<380;i++)address.append((char)('A'+(i%26)));
        address.append("@45.77.57.24:9501");
        byte[] key=new byte[91];
        String now=Pairing.write("Pixel 7 Pro",address.toString(),key,key);
        int wrappedAgain=4+(int)Math.ceil((now.length()-4)/3.0)*4;
        assertTrue("the second layer of Base64 cost a third of the line",now.length()<wrappedAgain*0.8);
        assertTrue(now.startsWith("MN2."));
    }

    @Test public void aLineCanOfferOneThingAtOneLevel() {
        String line=Pairing.write("Ana",ADDRESS,AGREE,SIGN,"the collection Allotment",true);
        Pairing.Said said=Pairing.read(line);
        assertEquals("the collection Allotment",said.offer);
        assertTrue(said.writes);
        assertEquals(ADDRESS,said.address);
    }

    @Test public void anOfferWithNoLevelSaidIsReadOnly() {
        Pairing.Said said=Pairing.read(Pairing.write("Ana",ADDRESS,AGREE,SIGN,"a note",false));
        assertEquals("a note",said.offer);
        assertFalse("reading is what an offer means unless it says otherwise",said.writes);
    }

    @Test public void aLineOfferingNothingIsStillFourFields() {
        // A phone that has only ever seen four fields must go on reading every line this one writes.
        String line=Pairing.write("Ana",ADDRESS,AGREE,SIGN,"   ",true);
        assertEquals(3,line.chars().filter(c->c=='|').count());
        Pairing.Said said=Pairing.read(line);
        assertEquals("",said.offer);
        assertFalse(said.writes);
    }

    @Test public void aDamagedLevelMarkMeansTheLesserPermission() {
        String line=Pairing.write("Ana",ADDRESS,AGREE,SIGN,"a book",true);
        Pairing.Said said=Pairing.read(line.substring(0,line.length()-1)+"?");
        assertEquals("a book",said.offer);
        assertFalse("an unknown mark must never be read as the greater permission",said.writes);
    }

    @Test public void aPermanentAddressIsAnAddressToo() {
        // MAX#<key>#<where to ask>. Not routable itself - the sender resolves it - but it is the better
        // thing to hand somebody, because it survives the host moving.
        assertTrue(Pairing.reachable("MAX#0xC0FFEE#MxRELAY@45.77.57.24:9501"));
        assertFalse("half of one is not one",Pairing.reachable("MAX#0xC0FFEE"));
        assertFalse(Pairing.reachable("MAX##MxRELAY@1.2.3.4:9001"));
        assertFalse(Pairing.reachable("MAX#0xC0FFEE#"));
    }

    @Test public void aPermanentAddressSurvivesTheLine() {
        String permanent="MAX#0x"+"AB".repeat(60)+"#MxRELAY0123456789@45.77.57.24:9501";
        Pairing.Said said=Pairing.read(Pairing.write("Pixel",permanent,AGREE,SIGN));
        assertEquals(permanent,said.address);
    }

    // ---- a code the phone's own camera knows what to do with ---------------------------------------------

    private static String offered() {
        byte[] agreement=new byte[91],signing=new byte[91];
        for(int at=0;at<91;at++){agreement[at]=(byte)(at*7+3);signing[at]=(byte)(250-at*5);}
        return Pairing.write("Zo\u00eb's Pixel 7","MAX#0x30819F300D06#Mx1234@45.77.57.24:9501",agreement,signing,
            "the Recipes book | all of it",true,"BOOK","0b9c1f1e-52a1-4a4e-9d0e-6a3f3a7f2c11");
    }

    @Test public void aCodeDressedAsALinkIsTheSameCodeUnderneath() {
        String line=offered();
        String link=Pairing.link(line);
        assertTrue(link.startsWith("mininotes://pair/"));
        assertEquals(line,Pairing.line(link));
        Pairing.Said back=Pairing.read(Pairing.line(link));
        assertEquals("Zo\u00eb's Pixel 7",back.name);
        assertEquals("BOOK",back.scope);
        assertTrue(back.writes);
    }

    @Test public void theLinkHoldsNothingALinkCannotOrThatSomethingMightTidy() {
        String dressed=Pairing.link(offered()).substring(Pairing.LINK.length());
        for(char no:new char[]{' ','|','#','+','/','?','\'','"','<','>'})
            assertTrue("holds "+no,dressed.indexOf(no)<0);
        for(int at=0;at<dressed.length();at++)assertTrue(dressed.charAt(at)<128);
    }

    @Test public void aLineThatIsNotDressedIsLeftAsItIs() {
        String line=offered();
        assertFalse(Pairing.isLink(line));
        assertEquals(line,Pairing.line("  "+line+"  "));
        assertEquals("",Pairing.line(null));
    }

    @Test public void theSchemeIsReadWhateverCaseACameraHandsItOverIn() {
        String link=Pairing.link(offered());
        assertEquals(offered(),Pairing.line("MININOTES://PAIR/"+link.substring(Pairing.LINK.length())));
    }

    @Test public void aDamagedLinkIsRefusedAsALineAndNotHalfRead() {
        String link=Pairing.link(offered());
        for(String broken:new String[]{link.substring(0,link.length()-1)+"%",link+"%4",link+"%zz"}) {
            try{Pairing.read(Pairing.line(broken));fail("read a damaged link");}
            catch(IllegalArgumentException refused){/* as it should be */}
        }
    }
}
