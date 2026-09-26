// SPDX-License-Identifier: GPL-3.0-or-later
// Mininotes is free software: GNU General Public License, version 3 or later. See LICENSE.
package org.mininotes.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.widget.EditText;

/** The page: an editor ruled like paper, so a blank note reads as something to write on. */
// Built only in code, like every view in this app; there is no layout XML for a tool to inflate it from.
@android.annotation.SuppressLint("ViewConstructor")
final class Pad extends EditText {
    private final Paint rule=new Paint();
    private final Rect line=new Rect();
    private final int drop;

    Pad(Context c,int colour) {
        super(c);
        rule.setColor(colour);rule.setStrokeWidth(1f);
        drop=(int)(getResources().getDisplayMetrics().density*4);
        setBackground(null);
    }

    // A tap that opened an address is a click, and is reported as one so a screen reader sees it happen.
    @Override public boolean performClick(){return super.performClick();}

    /** The rules follow the paper: a darker page needs a rule the writing can still be read against. */
    void rules(int colour){rule.setColor(colour);}

    @Override protected void onDraw(Canvas canvas) {
        int height=getLineHeight();
        if(height>0) {
            // Ruled to the bottom of the page like a paper pad, not only under the lines already written.
            int bottom=getScrollY()+getHeight();
            for(int y=getLineBounds(0,line)+drop;y<bottom;y+=height)canvas.drawLine(0,y,getWidth(),y,rule);
        }
        super.onDraw(canvas);
    }
}
