package org.mininotes.android;

import static org.junit.Assert.*;
import java.awt.Window;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.mininotes.desktop.platform.content.Context;

/** A locked notebook, locked again: the key let go, the notebook closed, the password asked for. Synthetic data. */
public class DesktopRelockTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void lockingAgainClosesTheNotebookAndAsksForThePassword() throws Exception {
        Path folder=temp.newFolder("relock").toPath();
        Vault.Made made=Vault.make("password1".toCharArray(),20_000);
        try(NoteStore store=new NoteStore(new Context(folder.toFile()))) {
            NoteStore.Note note=new NoteStore.Note();note.book=store.someBook();note.body="Synthetic line";store.save(note);
            store.getWritableDatabase().rekey(made.key);
        }
        Files.write(folder.resolve(DesktopLock.KEPT),made.kept);
        Desktop[] app=new Desktop[1];
        SwingUtilities.invokeAndWait(()->{try{app[0]=new Desktop(folder,true,made.key);app[0].leave=()->{};app[0].show();}catch(Exception e){throw new RuntimeException(e);}});
        Desktop pad=app[0];
        await(()->pad.store.latest()!=null&&pad.page.getText().contains("Synthetic"));
        SwingUtilities.invokeLater(pad::relock);
        JDialog[] asked={null};
        await(()->{for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&"Mininotes is locked".equals(d.getTitle()))asked[0]=d;return asked[0]!=null;});
        assertFalse("the old window is gone",pad.frame.isDisplayable());
        assertNull("the key has left memory",pad.context.databaseKey());
        SwingUtilities.invokeAndWait(()->asked[0].dispose());
    }
    private static void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){if(condition.call())return;Thread.sleep(40);}fail("did not happen in time");
    }
}
