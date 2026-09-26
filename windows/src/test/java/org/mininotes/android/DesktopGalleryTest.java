package org.mininotes.android;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Every window of the pad, drawn with made-up notes and people, saved to look at. Nobody real is in them. */
public class DesktopGalleryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final Path SHOTS=Path.of("build","verification","gallery");

    @Test public void everyWindowDraws() throws Exception {
        Files.createDirectories(SHOTS);
        Path folder=temp.newFolder("gallery").toPath();Desktop[] app=new Desktop[1];
        SwingUtilities.invokeAndWait(()->{try{app[0]=new Desktop(folder,true);app[0].show();}catch(Exception e){throw new RuntimeException(e);}});
        Desktop pad=app[0];
        try {
            await(()->pad.page.isEditable()&&pad.store.latest()!=null);pad.disk.flush(10000);SwingUtilities.invokeAndWait(()->{});
            // A pad with something in it: two notes, a second collection, a person, a share, and a version.
            NoteStore store=pad.store;
            var agreement=Envelope.keys();var signing=Envelope.keys();
            store.pairedWith("MxGallery@127.0.0.1:9001","Ana's phone",false,agreement.getPublic().getEncoded(),signing.getPublic().getEncoded());
            store.pairedWith("MxGallery2@127.0.0.1:9002","Work laptop",true,Envelope.keys().getPublic().getEncoded(),Envelope.keys().getPublic().getEncoded());
            store.addCollection("Kitchen");
            SwingUtilities.invokeAndWait(()->{pad.title.setText("Saturday");pad.page.setText("Pick up fresh bread\nTea for the ferry\n\nLeave room for a little wandering.");});
            await(()->pad.store.latest().body.contains("wandering"));
            String id=store.latest().id;store.keepVersion(id,"");
            SwingUtilities.invokeAndWait(()->pad.page.setText("Pick up fresh bread 🥖\nTea for the ferry ☕\nPostcards 📮❤️\n\nLeave room for a little wandering. 😊"));
            await(()->pad.store.latest().body.contains("Postcards"));
            store.setLevel(Sharing.Scope.PAGE,id,"MxGallery@127.0.0.1:9001",Sharing.Level.WRITE,null);
            NoteStore.Note other=new NoteStore.Note();other.book=store.someBook();other.title="Old list";other.body="Batteries";store.save(other);
            store.putAway(NoteStore.Branch.Kind.PAGE,other.id,true,true);
            pad.disk.flush(10000);SwingUtilities.invokeAndWait(pad::refresh);pad.disk.flush(10000);Thread.sleep(400);

            shoot(pad.frame,"01-pad");
            // The cards: everything from the top, then one book.
            SwingUtilities.invokeAndWait(()->pad.showShelf(Desktop.library()));Thread.sleep(300);shoot(pad.frame,"18-cards-top");
            SwingUtilities.invokeAndWait(()->pad.showShelf(new NoteStore.Branch(NoteStore.Branch.Kind.BOOK,store.someBook(),store.collectionOfBook(store.someBook()),"Notes","",0,0,true)));Thread.sleep(300);shoot(pad.frame,"19-cards-book");
            SwingUtilities.invokeAndWait(()->pad.open(id));pad.disk.flush(10000);Thread.sleep(200);
            // The side panel folded away: the note has the whole window. Then back, for the pictures after.
            SwingUtilities.invokeAndWait(()->pad.showTree(false));Thread.sleep(300);shoot(pad.frame,"21-folded");
            SwingUtilities.invokeAndWait(()->pad.showTree(true));pad.disk.flush(10000);Thread.sleep(200);
            dialog(pad,"02-share",pad::share);
            dialog(pad,"03-profile",()->DesktopProfile.open(pad));
            dialog(pad,"04-people",()->pad.people(null));
            dialog(pad,"05-versions",pad::versions);
            dialog(pad,"06-bin",()->pad.restore(true));
            dialog(pad,"07-scanner",()->DesktopScanner.open(pad.frame,code->{}));
            dialog(pad,"08-about",pad::about);
            dialog(pad,"09-new-collection",()->DesktopUi.ask(pad.frame,"New collection","Name",null));
            dialog(pad,"10-confirm",()->DesktopUi.confirm(pad.frame,"Unfollow?","Stop receiving Saturday? Your copy stays on this PC.","Unfollow",true));
            dialog(pad,"11-choose",()->DesktopUi.choose(pad.frame,"Share “Saturday”","What may the other device do with it?",new String[]{"Read and write","Read only"},0,"Show my code"));
            java.util.List<String> sample=java.util.List.of("orbit","velvet","harbor","maple","quiet","lantern","pepper","ribbon","summit","canvas","meadow","ticket");
            dialog(pad,"13-lock-password",()->DesktopLock.choosePassword(pad.frame));
            dialog(pad,"14-lock-words",()->DesktopLock.showWords(pad.frame,sample));
            dialog(pad,"15-lock-check",()->DesktopLock.checkWords(pad.frame,sample));
            dialog(pad,"16-words-again",()->DesktopLock.showWords(pad.frame,sample,false));
            dialog(pad,"17-security",()->DesktopLock.settings(pad));
            dialog(pad,"22-share-app",pad::shareApp);
            // A newer version out: the bar says so beside the name, and its box.
            SwingUtilities.invokeAndWait(()->{pad.newestKnown="0.0.999";pad.updateShown();});Thread.sleep(200);shoot(pad.frame,"23-update-bar");
            dialog(pad,"24-update",()->pad.updateBox("0.0.999"));
            dialog(pad,"25-updates-current",()->pad.updatesBox(null));
            // The menu under the three dots: its lines start near its left edge.
            SwingUtilities.invokeAndWait(()->{JButton more=find(pad.frame.getContentPane(),"More");pad.menu(more);});Thread.sleep(400);
            SwingUtilities.invokeAndWait(()->{try{
                MenuElement[] open=javax.swing.MenuSelectionManager.defaultManager().getSelectedPath();
                JComponent menu=open.length>0?(JComponent)open[0].getComponent():null;
                if(menu==null)for(Window w:Window.getWindows())if(w.isShowing()&&w!=pad.frame&&w instanceof RootPaneContainer r)for(Component c:r.getContentPane().getComponents())if(c instanceof JPopupMenu m)menu=m;
                if(menu==null)throw new AssertionError("the menu did not open");
                var image=new java.awt.image.BufferedImage(Math.max(1,menu.getWidth()),Math.max(1,menu.getHeight()),java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();menu.printAll(g);g.dispose();javax.imageio.ImageIO.write(image,"png",SHOTS.resolve("26-menu.png").toFile());
            }catch(Exception e){throw new RuntimeException(e);}});
            SwingUtilities.invokeAndWait(()->javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath());
            SwingUtilities.invokeAndWait(()->{pad.newestKnown="";pad.updateShown();});
            dialog(pad,"20-password",()->DesktopLock.askPassword(pad.frame,"Show recovery words","Type your password to see your recovery words.","Show the words"));
            dialog(pad,"12-add-someone",()->pad.people(new NoteStore.Branch(NoteStore.Branch.Kind.PAGE,id,"","Saturday","",0,0,false)));
            // A click beside a box, on the shade over the window behind it, closes the box and takes the shade away.
            SwingUtilities.invokeLater(()->DesktopUi.tell(pad.frame,"About",DesktopUi.body("Synthetic")));
            JDialog[] about={null};
            await(()->{for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&"About".equals(d.getTitle()))about[0]=d;return about[0]!=null;});
            assertTrue(pad.frame.getGlassPane().isVisible());
            SwingUtilities.invokeAndWait(()->{Component shade=pad.frame.getGlassPane();
                shade.dispatchEvent(new java.awt.event.MouseEvent(shade,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,5,5,1,false));});
            await(()->!about[0].isDisplayable());
            SwingUtilities.invokeAndWait(()->assertFalse(pad.frame.getGlassPane().isVisible()));
            for(String name:new String[]{"01-pad","02-share","03-profile","04-people","05-versions","06-bin","07-scanner","08-about"})
                assertTrue(name,Files.size(SHOTS.resolve(name+".png"))>2000);
        } finally {SwingUtilities.invokeAndWait(()->pad.shutdown(false));await(()->!pad.frame.isDisplayable());}
    }

    /** Opens a window, waits for it to be drawn, keeps a picture of it and closes it. */
    private static void dialog(Desktop pad,String name,Runnable open) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
        java.util.Set<Window> before=new java.util.HashSet<>(java.util.Arrays.asList(Window.getWindows()));
        SwingUtilities.invokeLater(()->{
            long[] seen={0};
            javax.swing.Timer look=new javax.swing.Timer(150,event->{
                for(Window window:Window.getWindows())if(window instanceof JDialog dialog&&dialog.isShowing()&&!before.contains(window)) {
                    // Let whatever it fetches on opening arrive before the picture is taken.
                    if(seen[0]==0){seen[0]=System.nanoTime();return;}
                    if(System.nanoTime()-seen[0]<TimeUnit.MILLISECONDS.toNanos(900))return;
                    ((javax.swing.Timer)event.getSource()).stop();
                    try{shootNow(dialog,name);}catch(Throwable error){failure.set(error);}finally{dialog.dispose();done.countDown();}
                    return;
                }
            });look.start();open.run();
        });
        assertTrue(name+" did not open",done.await(30,TimeUnit.SECONDS));
        if(failure.get()!=null)throw new AssertionError(failure.get());
        pad.disk.flush(10000);SwingUtilities.invokeAndWait(()->{});
    }
    private static JButton find(Container in,String tip) {
        for(Component c:in.getComponents()){if(c instanceof JButton b&&tip.equals(b.getToolTipText()))return b;if(c instanceof Container k){JButton f=find(k,tip);if(f!=null)return f;}}
        return null;
    }
    private static void shoot(Window window,String name) throws Exception {
        SwingUtilities.invokeAndWait(()->{try{shootNow(window,name);}catch(Exception e){throw new RuntimeException(e);}});
    }
    private static void shootNow(Window window,String name) throws Exception {
        // At the screen's own scale: text is measured at it, so a picture drawn at another scale cuts words short.
        var scale=window.getGraphicsConfiguration().getDefaultTransform();
        var image=new java.awt.image.BufferedImage((int)Math.ceil(window.getWidth()*scale.getScaleX()),(int)Math.ceil(window.getHeight()*scale.getScaleY()),java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();g.transform(scale);
        // Text drawn as the screen draws it; without the desktop's hints glyphs come out wider than they were measured.
        Object hints=Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");if(hints instanceof java.util.Map<?,?> map)g.addRenderingHints(map);
        window.paint(g);g.dispose();
        javax.imageio.ImageIO.write(image,"png",SHOTS.resolve(name+".png").toFile());
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){if(condition.call())return;Thread.sleep(40);}fail("Desktop did not finish in time");
    }
}
