package org.mininotes.android;

import org.junit.*;
import static org.junit.Assert.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

public class DesktopQrTest {
    private String line() throws Exception {return Pairing.link(Pairing.write("Windows fixture","MxFixture@127.0.0.1:9001",Point.shorten(Envelope.keys().getPublic()),Point.shorten(Envelope.keys().getPublic()),"a test note",true,"PAGE",java.util.UUID.randomUUID().toString()));}
    @Test public void pairingCodeRoundTrips() throws Exception {String line=line();assertEquals(line,DesktopQr.read(DesktopQr.draw(line,380)));assertEquals("Windows fixture",Pairing.read(Pairing.line(DesktopQr.read(DesktopQr.draw(line,380)))).name);}
    /** Every code this PC draws reads back: once in a few runs one did not, so many are drawn, not one. */
    @Test public void manyRandomCodesAllReadBack() throws Exception {
        int missed=0;for(int i=0;i<200;i++){String line=line();try{if(!line.equals(DesktopQr.read(DesktopQr.draw(line,380))))missed++;}catch(java.io.IOException no){missed++;}}
        assertEquals("codes not read back, of 200",0,missed);
    }
    @Test public void readsImageFile() throws Exception {String line=line();Path file=Files.createTempFile("mininotes-qr-test-",".png");try{ImageIO.write(DesktopQr.draw(line,420),"png",file.toFile());assertEquals(line,DesktopQr.read(file));}finally{Files.delete(file);}}
    @Test public void readsInvertedQr() throws Exception {String line=line();BufferedImage image=DesktopQr.draw(line,380);for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,image.getRGB(x,y)^0xffffff);assertEquals(line,DesktopQr.read(image));}
    @Test public void blankImageHasHelpfulFailure(){assertThrows(java.io.IOException.class,()->DesktopQr.read(new BufferedImage(200,200,BufferedImage.TYPE_INT_RGB)));}
    @Test public void overlargeImageIsRefused(){assertThrows(java.io.IOException.class,()->DesktopQr.read(new BufferedImage(4100,4000,BufferedImage.TYPE_BYTE_GRAY)));}
    @Test public void verificationCodeAgreesBothWays() throws Exception {var first=Envelope.keys();var second=Envelope.keys();assertEquals(Envelope.code(first.getPublic(),second.getPublic()),Envelope.code(second.getPublic(),first.getPublic()));}
    @Test public void previewKeepsTheCamerasShape() {
        var frame=new java.awt.image.BufferedImage(1280,720,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var shown=DesktopScanner.fitted(frame,560,320);
        org.junit.Assert.assertEquals(560,shown.getWidth(null));org.junit.Assert.assertEquals(315,shown.getHeight(null));
    }
    @Test public void theShareCodeOpensTheDownloadPage() throws Exception {
        org.junit.Assert.assertEquals(Desktop.DOWNLOAD,DesktopQr.read(DesktopQr.draw(Desktop.DOWNLOAD,220)));
        org.junit.Assert.assertTrue(Desktop.INVITE.split("\n").length<=3);
    }
}