// SPDX-License-Identifier: LicenseRef-Mininotes-NoPaidProducts
// Apache-2.0 with the Commons Clause and a paid-product condition. See LICENSE.
package org.mininotes.android;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Whether what you are looking at has got to where it is going, drawn rather than spelled.
 *
 * <p>It was a full stop, a tick and an arrow borrowed from the font, which meant three marks in three
 * different weights that changed shape with whatever typeface the phone happened to use. This draws them:
 * one ring, the same size and the same stroke every time, with a different thing inside it.
 *
 * <ul>
 *   <li><b>Here</b> — an empty ring. Nothing leaves this phone, and nothing is wrong with that.</li>
 *   <li><b>Gone</b> — a tick. Everybody it reaches has what it says now.</li>
 *   <li><b>Waiting</b> — an arrow going up, and the ring filled behind it, because this is the one of the
 *       three that is asking for something.</li>
 *   <li><b>Paused</b> — two bars. This phone has stopped taking the thing in. It used to wear the tick, since
 *       it owed nobody anything, which was true and said the opposite of what mattered.</li>
 * </ul>
 *
 * <p>Drawn from the size it is given, so it follows the reading ladder like everything else.
 */
final class Mark extends Drawable {
    enum What { HERE, GONE, WAITING, PAUSED }

    private final What what;
    private final int ink;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);

    Mark(What what,int ink){this.what=what;this.ink=ink;}

    @Override public void draw(Canvas canvas) {
        android.graphics.Rect where=getBounds();
        float across=Math.min(where.width(),where.height());
        if(across<=0)return;
        float cx=where.exactCenterX(), cy=where.exactCenterY();
        float r=across*0.42f;
        // One stroke for the whole mark: a ring and a tick of different weights read as two drawings.
        float stroke=Math.max(1.5f,across*0.09f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(ink);

        if(what==What.WAITING) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx,cy,r,paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            canvas.drawCircle(cx,cy,r-stroke/2f,paint);
        }

        if(what==What.HERE)return;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        Path path=new Path();
        if(what==What.PAUSED) {
            // Two bars, the sign every player uses for the same thing.
            float h=r*0.42f, apart=r*0.26f;
            path.moveTo(cx-apart,cy-h);path.lineTo(cx-apart,cy+h);
            path.moveTo(cx+apart,cy-h);path.lineTo(cx+apart,cy+h);
        } else if(what==What.GONE) {
            // A tick, sized off the ring so it never touches it.
            float w=r*0.92f;
            path.moveTo(cx-w*0.55f,cy+w*0.04f);
            path.lineTo(cx-w*0.14f,cy+w*0.44f);
            path.lineTo(cx+w*0.58f,cy-w*0.42f);
        } else {
            // An arrow going up, drawn in the paper so it reads out of the filled ring.
            paint.setColor(PAPER_HOLE);
            float h=r*0.86f;
            path.moveTo(cx,cy+h*0.62f);
            path.lineTo(cx,cy-h*0.62f);
            path.moveTo(cx-h*0.46f,cy-h*0.16f);
            path.lineTo(cx,cy-h*0.64f);
            path.lineTo(cx+h*0.46f,cy-h*0.16f);
        }
        canvas.drawPath(path,paint);
    }

    /**
     * What the arrow is cut out in. Set once by the app when the paper changes, because a mark drawn in
     * a colour that is not the paper behind it is a mark with a smudge in the middle.
     */
    static int PAPER_HOLE=0xFFFFFFFF;

    /**
     * A size of its own, because a drawable that has none is given none: an ImageView asks a drawable how
     * big it is and draws nothing at all when the answer is "I do not know".
     */
    @Override public int getIntrinsicWidth(){return side;}
    @Override public int getIntrinsicHeight(){return side;}
    private int side=48;
    void sized(int px){side=Math.max(8,px);}

    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
