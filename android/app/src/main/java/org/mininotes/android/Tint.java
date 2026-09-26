// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

/**
 * The colours a single collection, book or page can be given. Eight of them and none, each in two strengths
 * so the same choice reads on the brightest paper and on the darkest: what is stored is which colour, never
 * a pixel value, so a thing keeps its colour when the paper under it moves. Holds no Android types, so the
 * blending and the bounds are unit tested without a device.
 */
final class Tint {
    /** 0 is no colour of its own: the thing takes the paper like everything else. */
    static final int NONE=0;

    static final String[] NAMES={"No colour","Red","Orange","Yellow","Green","Teal","Blue","Purple","Pink"};

    /**
     * How strongly a colour lands on the paper: pastel at one end, the colour itself at the other. The hue
     * says which colour a thing is, the tone says how loudly — two questions, because one answer cannot be
     * both. Kept for the whole pad rather than per thing: it is a matter of taste, not of which note this is.
     */
    /**
     * How much of a colour reaches the paper, as multiples of each surface's own base weight. The first six
     * are the scale as it was, so a pad that already had a tone kept looks exactly as it did; the four after
     * them are new room at the loud end, for people who want the colour to be the point rather than a hint.
     */
    static final float[] TONES={0.4f,0.7f,1.0f,1.4f,1.9f,2.5f,3.2f,4.0f,5.0f,6.2f};
    /**
     * Where a pad starts: loud enough that eight colours are eight colours at a glance, with two quieter
     * rungs below for anyone who would rather have a hint of one.
     */
    static final int FIRST_TONE=3;

    /** A wash at the tone in use, never so strong that what is written on it stops reading. */
    static float weigh(float base,int tone,float most) {
        float much=base*TONES[Math.max(0,Math.min(TONES.length-1,tone))];
        return much<0?0:Math.min(much,most);
    }

    /**
     * One row per colour: how it is drawn on a light paper, then on a dark one. Row 0 is never drawn.
     * Eight plain colours as far apart from each other as eight can be — red, orange, yellow, green, teal,
     * blue, purple, pink — so that two things never have to be told apart by a shade. Each is the colour
     * itself; how much of it reaches the paper is the tone's business, not the palette's.
     */
    private static final int[][] COLOURS={
        {0x00000000,0x00000000},
        {0xFFE02B20,0xFFFF6B5E},
        {0xFFF07800,0xFFFF9E3D},
        {0xFFD9A400,0xFFFFCB3D},
        {0xFF1DA02C,0xFF52D96A},
        {0xFF00A89B,0xFF2FE0CE},
        {0xFF1464E0,0xFF5AA6FF},
        {0xFF7A3CE0,0xFFB085FF},
        {0xFFE02B8A,0xFFFF6FB5},
    };

    static int count(){return COLOURS.length;}

    /** Whether a stored number still names a colour, so an unknown one is simply no colour. */
    static boolean known(int colour){return colour>NONE&&colour<COLOURS.length;}

    /** The colour itself, at the strength the paper calls for. */
    static int of(int colour,boolean darkPaper) {
        if(!known(colour))return 0;
        return COLOURS[colour][darkPaper?1:0];
    }

    /**
     * A colour laid over a surface at the given weight, 0 leaving the surface alone and 1 replacing it.
     * Cards and paper are washed rather than filled, so the writing on them keeps its contrast.
     */
    static int over(int colour,int ground,float weight,boolean darkPaper) {
        if(!known(colour))return ground;
        int paint=of(colour,darkPaper);
        float much=weight<0?0:weight>1?1:weight;
        int red=Math.round(part(ground,16)+(part(paint,16)-part(ground,16))*much);
        int green=Math.round(part(ground,8)+(part(paint,8)-part(ground,8))*much);
        int blue=Math.round(part(ground,0)+(part(paint,0)-part(ground,0))*much);
        return 0xFF000000|(red<<16)|(green<<8)|blue;
    }

    private static int part(int colour,int shift){return (colour>>shift)&0xFF;}

    private Tint(){}
}
