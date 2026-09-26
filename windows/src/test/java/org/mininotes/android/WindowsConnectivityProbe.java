package org.mininotes.android;

import java.nio.file.*;
import javax.swing.*;

/** Uses only an explicitly separate fixture notebook, never the default user pad. */
public final class WindowsConnectivityProbe {
    public static void main(String[] args) throws Exception {
        Path fixture=Files.createTempDirectory(Path.of("build/verification"),"connectivity-");Desktop[] pad=new Desktop[1];
        SwingUtilities.invokeAndWait(()->{try{pad[0]=new Desktop(fixture,false);pad[0].show();}catch(Exception e){throw new RuntimeException(e);}});
        try {
            await(()->Node.attached()>0);System.out.println("Production desktop node connected; relay count="+Node.attached());
            String first=Node.addresses(pad[0].context).get(0);String line=pad[0].keys.line(Node.nameHere(pad[0].context),first,"",false,"","");
            if(!Pairing.read(Pairing.line(DesktopQr.read(DesktopQr.draw(Pairing.link(line),380)))).address.equals(first))throw new AssertionError("Live profile QR mismatch");
            System.out.println("Live profile QR round trip passed");
            if(java.awt.SystemTray.isSupported()) {
                SwingUtilities.invokeAndWait(()->pad[0].setTrayListening(true,new JTextField()));await(()->pad[0].listenInTray);
                SwingUtilities.invokeAndWait(()->pad[0].frame.dispatchEvent(new java.awt.event.WindowEvent(pad[0].frame,java.awt.event.WindowEvent.WINDOW_CLOSING)));
                await(()->!pad[0].frame.isVisible());if(Node.attached()==0)throw new AssertionError("Node stopped when window closed");
                SwingUtilities.invokeAndWait(()->{pad[0].frame.setVisible(true);pad[0].setTrayListening(false,new JTextField());});await(()->!pad[0].listenInTray);
                System.out.println("Tray listening, reopen and disabling listening passed");
            }else System.out.println("Tray unavailable on this desktop");
            try{int count=com.github.sarxos.webcam.Webcam.getWebcams(10,java.util.concurrent.TimeUnit.SECONDS).size();System.out.println("Webcam driver loaded; enumerated devices="+count+" (camera not opened)");}
            catch(Exception|LinkageError e){System.out.println("Webcam enumeration unavailable: "+e.getClass().getSimpleName());}
            SwingUtilities.invokeAndWait(()->pad[0].save(()->pad[0].shutdown(true)));
        }catch(Throwable failure){System.err.println("Connectivity probe failed: "+failure.getClass().getSimpleName());System.exit(1);}
    }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        while(System.nanoTime()<until){if(condition.call())return;Thread.sleep(100);}throw new AssertionError("Desktop operation timed out");
    }
}
