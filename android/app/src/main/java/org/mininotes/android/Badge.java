// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * Which way something is being shared, drawn on the thing itself.
 *
 * <p>Words under a tile say it once you read them; a mark says it while you are looking at the shelf. Both
 * are here — the mark on the picture, the words under it — because one of them is for the glance and the
 * other is for the person who wants to be sure.
 *
 * <ul>
 *   <li><b>Out</b> — an arrow leaving, outlined. You are sending this to somebody.</li>
 *   <li><b>Between</b> — arrows both ways, outlined. It is going to another device of yours and coming
 *       back: two directions, so two arrows.</li>
 *   <li><b>In</b> — an arrow arriving, filled. This came from another device. Filled because it is the one
 *       of the three that is not yours, and that is worth seeing without having to look twice.</li>
 * </ul>
 *
 * <p>Drawn rather than taken from the font. A glyph changes shape with whatever typeface the phone
 * happens to use, and three marks that have to be told apart at twenty pixels cannot be left to that.
 */
final class Badge extends Drawable {
    enum Way { OUT, BETWEEN, IN }

    private final Way way;
    private final int ink, behind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);

    /** @param behind the paper under it, which the filled mark's arrow is cut out in */
    Badge(Way way,int ink,int behind){this.way=way;this.ink=ink;this.behind=behind;}

    @Override public void draw(Canvas canvas) {
        android.graphics.Rect where=getBounds();
        float across=Math.min(where.width(),where.height());
        if(across<=0)return;
        float cx=where.exactCenterX(), cy=where.exactCenterY();
        float r=across*0.46f;
        float stroke=Math.max(1.2f,across*0.10f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        // A mark sits on top of a tile, so it carries its own ground: an outline over a ruled page is a
        // mark you have to hunt for.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(way==Way.IN?ink:behind);
        canvas.drawCircle(cx,cy,r,paint);
        if(way!=Way.IN) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(ink);
            canvas.drawCircle(cx,cy,r-stroke/2f,paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(way==Way.IN?behind:ink);
        float reach=r*0.52f;
        if(way==Way.BETWEEN) {
            // Two half-length arrows, one each way, side by side rather than crossing.
            arrow(canvas,cx,cy-r*0.20f,reach,true,r);
            arrow(canvas,cx,cy+r*0.20f,reach,false,r);
        } else arrow(canvas,cx,cy,reach,way==Way.OUT,r);
    }

    /**
     * One arrow through the middle, up-and-right for leaving and down-and-left for arriving.
     *
     * <p>The two directions are opposites rather than mirror images of each other, so the pair can be told
     * apart at a glance and upside down.
     */
    private void arrow(Canvas canvas,float cx,float cy,float reach,boolean out,float r) {
        float dx=out?0.70f:-0.70f, dy=out?-0.70f:0.70f;
        float tipX=cx+dx*reach, tipY=cy+dy*reach;
        float tailX=cx-dx*reach, tailY=cy-dy*reach;
        Path path=new Path();
        path.moveTo(tailX,tailY);
        path.lineTo(tipX,tipY);
        // The head, two strokes back from the tip, turned a little either side of the way it points.
        float head=r*0.40f;
        path.moveTo(tipX-dx*head-dy*head*0.85f,tipY-dy*head+dx*head*0.85f);
        path.lineTo(tipX,tipY);
        path.lineTo(tipX-dx*head+dy*head*0.85f,tipY-dy*head-dx*head*0.85f);
        canvas.drawPath(path,paint);
    }

    /**
     * A size of its own, because a drawable with none is given none: an ImageView asks how big it is and
     * draws nothing at all when the answer is "I do not know".
     */
    @Override public int getIntrinsicWidth(){return side;}
    @Override public int getIntrinsicHeight(){return side;}
    private int side=48;
    void sized(int px){side=Math.max(8,px);}

    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}

    /** Which mark a thing's state calls for, or null for a thing that is only on this phone. */
    static Way of(Sharing.State state) {
        switch(state) {
            case OTHERS: return Way.OUT;
            case DEVICES: return Way.BETWEEN;
            case THEIRS: return Way.IN;
            default: return null;
        }
    }
}
