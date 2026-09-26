package org.mininotes.android;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import javax.swing.text.JTextComponent;

/**
 * Emoji in colour, drawn from Windows' own emoji font.
 *
 * <p>Java draws text in one colour: an emoji comes out as its black outline. Segoe UI Emoji keeps each emoji
 * as a stack of flat shapes, each with a colour from its palette (the font's COLR and CPAL tables). Java can
 * draw any one shape of the font; it just never colours them. So after the text is drawn, each emoji in view
 * has its outline painted out and its shapes painted in, one colour at a time, in the place Java put it.
 *
 * <p>Nothing is downloaded or bundled: without the font (not Windows, or an old Windows) this does nothing
 * and emoji stay in one colour.
 */
final class ColourEmoji {
    private static volatile ColourEmoji loaded;
    private static volatile boolean tried;
    private static final Font PRIMARY=new Font("Segoe UI",Font.PLAIN,14);

    private final Font font;
    /** Base glyph → layer glyphs and their palette entries, in pairs. */
    private final Map<Integer,int[]> layers;
    private final int[] palette;

    private ColourEmoji(Font font,Map<Integer,int[]> layers,int[] palette){this.font=font;this.layers=layers;this.palette=palette;}

    /** The font's colours, read once; null where there is no such font. */
    static ColourEmoji get() {
        if(tried)return loaded;
        synchronized(ColourEmoji.class) {
            if(tried)return loaded;tried=true;
            try{loaded=read(Path.of(System.getenv().getOrDefault("WINDIR","C:\\Windows"),"Fonts","seguiemj.ttf"));}
            catch(Exception | Error none){loaded=null;}
            return loaded;
        }
    }

    static ColourEmoji read(Path file) throws Exception {
        byte[] bytes=Files.readAllBytes(file);ByteBuffer data=ByteBuffer.wrap(bytes);
        int tables=data.getShort(4)&0xffff,colr=-1,cpal=-1;
        for(int i=0;i<tables;i++) {
            int at=12+16*i;String tag=new String(bytes,at,4,java.nio.charset.StandardCharsets.US_ASCII);
            int offset=data.getInt(at+8);
            if(tag.equals("COLR"))colr=offset;else if(tag.equals("CPAL"))cpal=offset;
        }
        if(colr<0||cpal<0)return null;
        // COLR, the version 0 part: base glyph records (glyph, first layer, how many) and layer records (glyph, palette entry).
        int bases=data.getShort(colr+2)&0xffff,baseAt=colr+data.getInt(colr+4),layerAt=colr+data.getInt(colr+8);
        Map<Integer,int[]> layers=new HashMap<>(bases*2);
        for(int i=0;i<bases;i++) {
            int r=baseAt+6*i,glyph=data.getShort(r)&0xffff,first=data.getShort(r+2)&0xffff,count=data.getShort(r+4)&0xffff;
            int[] pairs=new int[count*2];
            for(int k=0;k<count;k++){int l=layerAt+4*(first+k);pairs[2*k]=data.getShort(l)&0xffff;pairs[2*k+1]=data.getShort(l+2)&0xffff;}
            layers.put(glyph,pairs);
        }
        // CPAL: the first palette, colours kept as blue, green, red, alpha.
        int entries=data.getShort(cpal+2)&0xffff,records=cpal+data.getInt(cpal+8),firstIndex=data.getShort(cpal+12)&0xffff;
        int[] palette=new int[entries];
        for(int i=0;i<entries;i++){int c=records+4*(firstIndex+i);palette[i]=((bytes[c+3]&0xff)<<24)|((bytes[c+2]&0xff)<<16)|((bytes[c+1]&0xff)<<8)|(bytes[c]&0xff);}
        Font font=Font.createFont(Font.TRUETYPE_FONT,file.toFile());
        return new ColourEmoji(font,layers,palette);
    }

    /** Emoji the text font does not have itself, which the emoji font drew: the only ones painted over. */
    static boolean starts(int cp){return cp>=0x2000&&!PRIMARY.canDisplay(cp)&&!joins(cp);}
    static boolean joins(int cp){return cp==0x200D||cp==0xFE0F||(cp>=0x1F3FB&&cp<=0x1F3FF)||(cp>=0xE0020&&cp<=0xE007F)||cp==0x20E3;}

    /** Every emoji of this component that is in view, in colour. Called after the text itself is painted. */
    void paint(JTextComponent text,Graphics g0) {
        String all=text.getText();if(all==null||all.isEmpty())return;
        Rectangle view=text.getVisibleRect();
        int from,to;
        try {
            from=Math.max(0,text.viewToModel2D(new java.awt.Point(view.x,view.y))-1);
            to=Math.min(all.length(),text.viewToModel2D(new java.awt.Point(view.x+view.width,view.y+view.height))+2);
        } catch(RuntimeException notLaidOut){return;}
        Graphics2D g=(Graphics2D)g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            FontRenderContext frc=g.getFontRenderContext();
            Font sized=font.deriveFont(text.getFont().getSize2D());
            FontMetrics metrics=text.getFontMetrics(text.getFont());
            int i=from;
            while(i<to) {
                int cp=all.codePointAt(i);
                if(!starts(cp)){i+=Character.charCount(cp);continue;}
                int end=i+Character.charCount(cp);
                while(end<all.length()){int next=all.codePointAt(end);if(joins(next)||(all.codePointBefore(end)==0x200D&&next>=0x2000)){end+=Character.charCount(next);}else break;}
                paintRun(text,g,frc,sized,metrics,all,i,end);
                i=end;
            }
        } finally{g.dispose();}
    }

    private void paintRun(JTextComponent text,Graphics2D g,FontRenderContext frc,Font sized,FontMetrics metrics,String all,int start,int end) {
        Rectangle2D at;
        try{at=text.modelToView2D(start);}catch(Exception e){return;}
        if(at==null)return;
        char[] chars=all.toCharArray();
        GlyphVector shaped=sized.layoutGlyphVector(frc,chars,start,end,Font.LAYOUT_LEFT_TO_RIGHT);
        float x=(float)at.getX(),y=(float)at.getY()+metrics.getAscent();
        boolean chosen=text.getSelectionStart()!=text.getSelectionEnd()&&start>=text.getSelectionStart()&&start<text.getSelectionEnd();
        Color ground=chosen?text.getSelectionColor():text.getBackground();
        for(int n=0;n<shaped.getNumGlyphs();n++) {
            int[] pairs=layers.get(shaped.getGlyphCode(n));
            if(pairs==null)continue;
            Point2D place=shaped.getGlyphPosition(n);
            float gx=x+(float)place.getX();
            // The one-colour outline Java drew, painted out: its own shape, filled with what is behind it.
            g.setColor(ground);g.fill(shaped.getGlyphOutline(n,x,y));
            g.setStroke(new BasicStroke(Math.max(2.2f,sized.getSize2D()/8f),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.draw(shaped.getGlyphOutline(n,x,y));
            for(int k=0;k<pairs.length;k+=2) {
                int entry=pairs[k+1];
                g.setColor(entry==0xFFFF||entry>=palette.length?text.getForeground():new Color(palette[entry],true));
                g.fill(sized.createGlyphVector(frc,new int[]{pairs[k]}).getGlyphOutline(0,gx,y));
            }
        }
    }
}
