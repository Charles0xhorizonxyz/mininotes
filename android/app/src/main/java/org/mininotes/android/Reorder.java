// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

/**
 * Where a dragged line belongs while the finger is still over the list. Geometry only, no Android types,
 * so the rule that decides where the gap opens is unit tested rather than only ever seen on a device.
 */
final class Reorder {
    /**
     * The place the dragged line should take, which is simply how many of the others it has passed.
     * {@code middles} is the vertical middle of every line of the level, in the order they are drawn, the
     * dragged one included; its own middle is skipped, since a line is never above or below itself.
     */
    static int slot(float pointer,float[] middles,int dragged) {
        int passed=0;
        for(int line=0;line<middles.length;line++) {
            if(line==dragged)continue;
            if(pointer>middles[line])passed++;
        }
        return passed;
    }

    /**
     * The same question on a desktop, where the things are laid out across as well as down: the place a
     * dragged thing should take is how many it has passed in reading order — everything on the rows above,
     * then everything to its left on its own row.
     */
    static int slot(float x,float y,float[] middlesX,float[] middlesY,float halfRow,int dragged) {
        int passed=0;
        for(int at=0;at<middlesX.length;at++) {
            if(at==dragged)continue;
            if(past(x,y,middlesX[at],middlesY[at],halfRow))passed++;
        }
        return passed;
    }

    /** Whether the finger is past one thing in reading order: below its row, or to the right of it on it. */
    static boolean past(float x,float y,float middleX,float middleY,float halfRow) {
        if(y>middleY+halfRow)return true;
        if(y<middleY-halfRow)return false;
        return x>middleX;
    }

    private Reorder(){}
}
