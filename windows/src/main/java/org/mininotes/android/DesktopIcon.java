package org.mininotes.android;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Exact geometry and colours of Android res/drawable/ic_note.xml. */
final class DesktopIcon {
    static BufferedImage image(int size) {
        BufferedImage image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(size/48.0,size/48.0);
            g.setColor(new Color(0x285646));g.fill(new Rectangle2D.Double(0,0,48,48));
            g.setColor(new Color(0xF5F0E4));g.fill(new Rectangle2D.Double(13,9,23,31));
            g.setColor(new Color(0x285646));g.setStroke(new BasicStroke(2,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER));
            g.draw(new Line2D.Double(18,9,18,40));
            g.draw(new Line2D.Double(23,18,32,18));
            g.draw(new Line2D.Double(23,24,32,24));
            g.draw(new Line2D.Double(23,30,29,30));
        } finally {g.dispose();}
        return image;
    }

    /** Build a Windows icon with PNG entries at native shell sizes. */
    public static void main(String[] args) throws Exception {
        Path folder=Path.of(args[0]);Files.createDirectories(folder);
        int[] sizes={16,20,24,32,40,48,64,128,256};
        byte[][] entries=new byte[sizes.length][];
        int length=6+16*sizes.length;
        for(int i=0;i<sizes.length;i++) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            ImageIO.write(image(sizes[i]),"png",bytes);entries[i]=bytes.toByteArray();length+=entries[i].length;
        }
        ByteBuffer ico=ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short)0).putShort((short)1).putShort((short)sizes.length);
        int offset=6+16*sizes.length;
        for(int i=0;i<sizes.length;i++) {
            ico.put((byte)sizes[i]).put((byte)sizes[i]).put((byte)0).put((byte)0);
            ico.putShort((short)1).putShort((short)32).putInt(entries[i].length).putInt(offset);
            offset+=entries[i].length;
        }
        for(byte[] entry:entries)ico.put(entry);
        Files.write(folder.resolve("Mininotes.ico"),ico.array());
        ImageIO.write(image(256),"png",folder.resolve("Mininotes.png").toFile());
    }
}
