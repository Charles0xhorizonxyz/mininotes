// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/**
 * A pairing line as something to point a camera at. The line is three hundred and fifty characters, which is
 * a thing to show and read rather than a thing to type across a room — and a photograph of a screen carries
 * it exactly, where reading it aloud does not.
 *
 * <p>A code is not a secret: it says where to send and what keys to seal with, and anybody who photographs
 * it can write to you but cannot read what you send. The six digits after it are what decides trust.
 */
final class Qr {
    /** Drawn dark on light whatever the paper is: a camera reads contrast, not taste. */
    private static final int DARK=Color.BLACK, LIGHT=Color.WHITE;

    /**
     * The line as a square of black and white. Ask for {@code size} 1 and it comes back at its own size —
     * one pixel per square — which is the one to draw from: a code shrunk to fit somewhere loses squares
     * along the way, where a code blown up from its own size only ever gains whole pixels.
     */
    static Bitmap of(String line,int size) throws Exception {
        Map<EncodeHintType,Object> how=new EnumMap<>(EncodeHintType.class);
        // A phone screen is close and steady, so the lowest correction keeps the squares as large as possible.
        how.put(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.L);
        // The quiet zone the format asks for. One module of it looked tidier and cost nothing on paper; a
        // camera finds a code by the clear ground around it, so tidier meant a code that would not be read.
        how.put(EncodeHintType.MARGIN,4);
        how.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        BitMatrix matrix=new QRCodeWriter().encode(line,BarcodeFormat.QR_CODE,size,size,how);
        int wide=matrix.getWidth(),high=matrix.getHeight();
        int[] pixels=new int[wide*high];
        for(int y=0;y<high;y++) {
            int row=y*wide;
            for(int x=0;x<wide;x++)pixels[row+x]=matrix.get(x,y)?DARK:LIGHT;
        }
        Bitmap drawn=Bitmap.createBitmap(wide,high,Bitmap.Config.ARGB_8888);
        drawn.setPixels(pixels,0,wide,0,0,wide,high);
        return drawn;
    }

    /**
     * What a camera frame says, or null if it says nothing yet. Given the frame as the camera hands it over
     * — brightness first, which is all a code needs — so nothing is copied or converted to look for one.
     */
    static String inside(byte[] brightness,int wide,int high) {
        try {
            PlanarYUVLuminanceSource source=new PlanarYUVLuminanceSource(
                brightness,wide,high,0,0,wide,high,false);
            Map<DecodeHintType,Object> how=new EnumMap<>(DecodeHintType.class);
            how.put(DecodeHintType.POSSIBLE_FORMATS,java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
            how.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
            Result found=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)),how);
            return found==null?null:found.getText();
        } catch(NotFoundException none){return null;}
        catch(Exception e){return null;}
    }

    private Qr(){}
}
