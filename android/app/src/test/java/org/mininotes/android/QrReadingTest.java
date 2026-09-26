// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the scanner does with a frame, measured rather than guessed at.
 *
 * <p>The reading half of {@link Qr} holds no Android types, so the thing the camera hands it can be made
 * here: a code drawn into a brightness buffer the size of a real camera frame. A phone that reads a code in
 * its own camera app and not in this one is a fault in these few lines, and this is where to find it.
 */
public class QrReadingTest {

    /** A pairing line about the length the app really makes: a routable address and two short keys. */
    private static String line() {
        StringBuilder address=new StringBuilder("Mx");
        for(int at=0;at<270;at++)address.append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".charAt(at%32));
        address.append("@45.77.246.226:9501");
        StringBuilder key=new StringBuilder();
        for(int at=0;at<44;at++)key.append("QWERTYUIOPASDFGHJKLZXCVBNM0123456789+/".charAt(at%38));
        return Pairing.MARK+"Pixel 7|"+address+"|"+key+"|"+key+"|the book Text|w";
    }

    /**
     * A code drawn into a camera-sized brightness buffer.
     *
     * @param modules how many frame pixels one square of the code gets
     */
    private static byte[] frame(String said,int wide,int high,int modules,boolean dim) throws Exception {
        Map<EncodeHintType,Object> how=new EnumMap<>(EncodeHintType.class);
        how.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.L);
        how.put(EncodeHintType.MARGIN,4);
        how.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        BitMatrix matrix=new QRCodeWriter().encode(said,BarcodeFormat.QR_CODE,1,1,how);
        int across=matrix.getWidth()*modules;
        assertTrue("the code does not fit the frame: "+across+" in "+Math.min(wide,high),
            across<=Math.min(wide,high));
        byte[] brightness=new byte[wide*high];
        byte light=(byte)(dim?150:235), dark=(byte)(dim?60:20);
        java.util.Arrays.fill(brightness,light);
        int left=(wide-across)/2, top=(high-across)/2;
        for(int y=0;y<across;y++)
            for(int x=0;x<across;x++)
                if(matrix.get(x/modules,y/modules))brightness[(top+y)*wide+left+x]=dark;
        return brightness;
    }

    /** The plain case: a whole code, filling most of a camera frame, in good light. */
    @Test public void aCodeFillingTheFrameIsRead() throws Exception {
        String said=line();
        assertEquals(said,Qr.inside(frame(said,1280,960,10,false),1280,960));
    }

    /**
     * How small it can get before it stops being read.
     *
     * <p>This is the number that matters: the app reads the whole frame, so a code held at arm's length is
     * a code taking up a few pixels per square. Printed here so a change that makes it worse is visible.
     */
    @Test public void smallestThatStillReads() throws Exception {
        String said=line();
        int smallest=-1;
        for(int modules=1;modules<=10;modules++) {
            byte[] frame;
            try{frame=frame(said,1600,1200,modules,false);}catch(AssertionError tooBig){break;}
            if(Qr.inside(frame,1600,1200)!=null){smallest=modules;break;}
        }
        System.out.println("smallest pixels-per-square that still reads: "+smallest);
        assertTrue("a code needs more than 4 pixels a square, which is a lot to ask of a hand",
            smallest>0&&smallest<=4);
    }

    /** Dim light, which is most rooms in the evening. */
    @Test public void aDimCodeIsRead() throws Exception {
        String said=line();
        assertEquals(said,Qr.inside(frame(said,1280,960,8,true),1280,960));
    }

    /** Nothing in the frame is not a code, and does not throw. */
    @Test public void anEmptyFrameSaysNothing() {
        byte[] blank=new byte[1280*960];
        java.util.Arrays.fill(blank,(byte)200);
        assertNull(Qr.inside(blank,1280,960));
    }

    /** The line survives the round trip, not just the pixels. */
    @Test public void whatIsReadIsWhatWasWritten() throws Exception {
        String said=line();
        String back=Qr.inside(frame(said,1280,960,9,false),1280,960);
        assertNotNull(back);
        Pairing.Said read=Pairing.read(back);
        assertEquals("Pixel 7",read.name);
        assertTrue(read.writes);
        assertEquals("the book Text",read.offer);
    }

    /**
     * What a frame costs to look at, which is what decides whether a scanner works in the hand.
     *
     * <p>A phone hands over about thirty frames a second. Anything slower than that is frames thrown away,
     * and a code is only readable in the ones where the hand happened to be still and the lens in focus —
     * so a scanner examining three frames a second is mostly not looking.
     */
    @Test public void whatAFrameCostsToLookAt() throws Exception {
        String said=line();
        byte[] whole=frame(said,1600,1200,12,false);
        long at=System.nanoTime();
        for(int go=0;go<5;go++)assertNotNull(Qr.inside(whole,1600,1200));
        long full=(System.nanoTime()-at)/5_000_000L;

        // The middle of the frame, which is all the square window on screen was ever showing.
        int side=1200;
        byte[] middle=new byte[side*side];
        int left=(1600-side)/2;
        for(int y=0;y<side;y++)System.arraycopy(whole,y*1600+left,middle,y*side,side);
        at=System.nanoTime();
        for(int go=0;go<5;go++)assertNotNull(Qr.inside(middle,side,side));
        long centre=(System.nanoTime()-at)/5_000_000L;

        // The same middle, every other pixel.
        int half=side/2;
        byte[] smaller=new byte[half*half];
        for(int y=0;y<half;y++)for(int x=0;x<half;x++)smaller[y*half+x]=middle[(y*2)*side+x*2];
        at=System.nanoTime();
        String found=null;
        for(int go=0;go<5;go++)found=Qr.inside(smaller,half,half);
        long shrunk=(System.nanoTime()-at)/5_000_000L;

        System.out.println("frame cost: whole "+full+"ms, middle "+centre+"ms, middle at half "+shrunk
            +"ms; half-size still read: "+(found!=null));
    }
}
