package org.mininotes.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.swing.*;

public class DesktopProfileTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void profileSavesNameAndRendersQrAddressesAndConnection() throws Exception {
        Path folder=temp.newFolder("profile").toPath();Desktop[] app=new Desktop[1];
        SwingUtilities.invokeAndWait(()->{try{app[0]=new Desktop(folder,true);app[0].show();}catch(Exception e){throw new RuntimeException(e);}});
        Desktop pad=app[0];JDialog[] profile=new JDialog[1];
        try {
            pad.disk.flush(15000);SwingUtilities.invokeAndWait(()->{});
            SwingUtilities.invokeAndWait(()->{DesktopProfile.open(pad);for(Window w:pad.frame.getOwnedWindows())if(w instanceof JDialog d&&d.getTitle().equals("Profile"))profile[0]=d;});
            pad.disk.flush(15000);SwingUtilities.invokeAndWait(()->{});
            SwingUtilities.invokeAndWait(()->{assertNull(find(profile[0],"Save name",true));((JTextField)find(profile[0],"profileName",false)).setText("Desk fixture");});
            pad.disk.flush(15000);SwingUtilities.invokeAndWait(()->{});
            assertEquals("Desk fixture",new org.mininotes.desktop.platform.content.Context(folder.toFile()).getSharedPreferences("settings",0).getString("me",""));
            String line=Pairing.write("Desk fixture","MxSynthetic@127.0.0.1:9001",Point.shorten(Envelope.keys().getPublic()),Point.shorten(Envelope.keys().getPublic()));
            SwingUtilities.invokeAndWait(()->{
                JLabel qr=(JLabel)find(profile[0],"profileQr",false),state=(JLabel)find(profile[0],"connectionState",false);
                JTextArea address=(JTextArea)find(profile[0],"maximaAddress",false),permanent=(JTextArea)find(profile[0],"permanentAddress",false);
                DesktopProfile.render(new DesktopProfile.Connection("Desk fixture","MxSynthetic@127.0.0.1:9001","MAX#synthetic#fixture",2,line),qr,address,permanent,state);
                assertEquals("MxSynthetic@127.0.0.1:9001",address.getText());assertEquals("Connected · 2 relays",state.getText());assertNotNull(qr.getIcon());
                profile[0].validate();
                try{BufferedImage image=new BufferedImage(profile[0].getWidth(),profile[0].getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();profile[0].paint(g);g.dispose();Path shot=Path.of("build/verification/windows-profile.png");Files.createDirectories(shot.getParent());javax.imageio.ImageIO.write(image,"png",shot.toFile());}catch(Exception e){throw new RuntimeException(e);}
                DesktopProfile.render(new DesktopProfile.Connection("Desk fixture","","",0,""),qr,address,permanent,state);
                assertNull(qr.getIcon());assertEquals("",address.getText());assertTrue(state.getText().startsWith("Not connected"));
            });
            // Refreshing read-only addresses must not make their caret scroll the profile.
            for(int position:new int[]{0,180}) {
                SwingUtilities.invokeAndWait(()->{
                    JScrollPane scroll=(JScrollPane)find(profile[0],"profileScroll",false);scroll.getViewport().setViewPosition(new java.awt.Point(0,position));
                    JLabel qr=(JLabel)find(profile[0],"profileQr",false),state=(JLabel)find(profile[0],"connectionState",false);
                    JTextArea address=(JTextArea)find(profile[0],"maximaAddress",false),permanent=(JTextArea)find(profile[0],"permanentAddress",false);
                    DesktopProfile.render(new DesktopProfile.Connection("Desk fixture","MxSynthetic".repeat(30),"MAX#synthetic#fixture",2,line),qr,address,permanent,state);
                    profile[0].validate();
                });
                SwingUtilities.invokeAndWait(()->{});
                SwingUtilities.invokeAndWait(()->assertEquals(position,((JScrollPane)find(profile[0],"profileScroll",false)).getViewport().getViewPosition().y));
            }
            SwingUtilities.invokeAndWait(()->{((JTextField)find(profile[0],"profileName",false)).setText("Saved when leaving");profile[0].dispose();});
            pad.disk.flush(15000);SwingUtilities.invokeAndWait(()->{});
            assertEquals("Saved when leaving",new org.mininotes.desktop.platform.content.Context(folder.toFile()).getSharedPreferences("settings",0).getString("me",""));
        }finally {SwingUtilities.invokeAndWait(()->{if(profile[0]!=null)profile[0].dispose();pad.shutdown(false);});pad.disk.flush(15000);SwingUtilities.invokeAndWait(()->{});}
    }
    private static Component find(Container root,String value,boolean button){
        for(Component c:root.getComponents()){
            if(button&&c instanceof JButton b&&value.equals(b.getText())||!button&&value.equals(c.getName()))return c;
            if(c instanceof Container child){Component found=find(child,value,button);if(found!=null)return found;}
        }return null;
    }
}
