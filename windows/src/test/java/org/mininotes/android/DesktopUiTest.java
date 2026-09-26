package org.mininotes.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.nio.file.*;
import javax.swing.*;

public class DesktopUiTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void typeAutosaveReopenAndRender() throws Exception {
        Path folder=temp.newFolder("ui-pad").toPath();Desktop[] app=new Desktop[1];
        SwingUtilities.invokeAndWait(()->{try{app[0]=new Desktop(folder,true);app[0].show();}catch(Exception e){throw new RuntimeException(e);}});
        Desktop pad=app[0];
        try {
            await(()->pad.page.isEditable()&&pad.store.latest()!=null);
            // Drain the startup callbacks before typing through the real documents.
            pad.disk.flush(10000);SwingUtilities.invokeAndWait(()->{});
            SwingUtilities.invokeAndWait(()->{pad.title.setText("Saturday");pad.page.setText("Pick up fresh bread\nTea for the ferry\n\nLeave room for a little wandering.");});
            await(()->pad.store.latest().body.contains("wandering"));
            // One quiet line once the save settles; the exact times are asked for, not shown.
            await(()->pad.syncDetails.getText().startsWith("Only on this PC · saved "));
            assertTrue(pad.syncDetails.getToolTipText().contains("Saved on this PC: "));
            SwingUtilities.invokeAndWait(()->{
                try {
                    var image=new java.awt.image.BufferedImage(pad.frame.getWidth(),pad.frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var g=image.createGraphics();pad.frame.paint(g);g.dispose();
                    Path shot=Path.of("build","verification","windows-pad.png");Files.createDirectories(shot.getParent());javax.imageio.ImageIO.write(image,"png",shot.toFile());
                }catch(Exception e){throw new RuntimeException(e);}
            });
            assertTrue(Files.isRegularFile(Path.of("build","verification","windows-pad.png")));
            java.util.concurrent.CountDownLatch shared=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> shareFailure=new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeLater(()->{
                javax.swing.Timer capture=new javax.swing.Timer(100,event->{
                    for(java.awt.Window window:pad.frame.getOwnedWindows())if(window instanceof JDialog dialog&&dialog.isVisible()) {
                        ((javax.swing.Timer)event.getSource()).stop();
                        try {
                            var image=new java.awt.image.BufferedImage(dialog.getWidth(),dialog.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                            var g=image.createGraphics();dialog.paint(g);g.dispose();
                            javax.imageio.ImageIO.write(image,"png",Path.of("build","verification","windows-sharing.png").toFile());
                        }catch(Throwable error){shareFailure.set(error);}finally{dialog.dispose();shared.countDown();}
                    }
                });capture.start();findButton(pad.frame,"Share").doClick();
            });
            assertTrue("Sharing dialog did not open",shared.await(30,java.util.concurrent.TimeUnit.SECONDS));
            if(shareFailure.get()!=null)throw new AssertionError(shareFailure.get());
            String id=pad.store.latest().id;pad.disk.flush(10000);
            SwingUtilities.invokeAndWait(()->pad.shutdown(false));await(()->!pad.frame.isDisplayable());
            try(NoteStore reopened=new NoteStore(new org.mininotes.desktop.platform.content.Context(folder.toFile()))){assertEquals("Saturday",reopened.get(id).title);assertTrue(reopened.get(id).body.contains("wandering"));}
        } finally {if(pad.frame.isDisplayable()){SwingUtilities.invokeAndWait(()->pad.shutdown(false));await(()->!pad.frame.isDisplayable());}}
    }
    private static JButton findButton(java.awt.Container parent,String text) {
        for(java.awt.Component child:parent.getComponents()) {
            if(child instanceof JButton button&&text.equals(button.getText()))return button;
            if(child instanceof java.awt.Container container){JButton found=findButton(container,text);if(found!=null)return found;}
        }return null;
    }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){if(condition.call())return;Thread.sleep(40);}fail("Desktop did not finish in time");
    }
}
