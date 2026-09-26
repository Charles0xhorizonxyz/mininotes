package org.mininotes.android;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.zip.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class DesktopUpdateTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void theZipIsFoundFromTheVersionAlone() {
        assertEquals("https://github.com/mininotesorg/mininotes/releases/latest/download/Mininotes-Windows-0.0.020.zip",DesktopUpdate.zip("v0.0.020"));
        assertEquals("",DesktopUpdate.zip("<html>"));
        assertTrue(DesktopUpdate.LATEST.endsWith("/main/dist/latest-windows.txt"));
    }

    @Test public void theVersionInTheBarIsTheOneInLatestWindows() throws Exception {
        // The file every installed PC reads, and this build, say the same when the tree is released.
        Path latest=Path.of("..","dist","latest-windows.txt");
        if(Files.exists(latest))assertEquals(Desktop.VERSION,Update.read(Files.readString(latest)));
        assertTrue(Update.newer("0.0.100",Desktop.VERSION));assertFalse(Update.newer(Desktop.VERSION,Desktop.VERSION));
    }

    @Test public void aZipOpensInsideItsFolderAndOneThatClimbsOutIsRefused() throws Exception {
        Path good=temp.getRoot().toPath().resolve("good.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(good))) {
            out.putNextEntry(new ZipEntry("Mininotes/"));out.putNextEntry(new ZipEntry("Mininotes/Mininotes.exe"));out.write(new byte[]{1,2,3});
            out.putNextEntry(new ZipEntry("Mininotes/app/Mininotes.cfg"));out.write("x".getBytes());
        }
        Path into=temp.newFolder("staged").toPath();DesktopUpdate.unzip(good,into);
        assertEquals(3,Files.size(into.resolve("Mininotes/Mininotes.exe")));
        assertTrue(Files.isRegularFile(into.resolve("Mininotes/app/Mininotes.cfg")));

        Path bad=temp.getRoot().toPath().resolve("bad.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(bad))){out.putNextEntry(new ZipEntry("../outside.txt"));out.write(1);}
        Path other=temp.newFolder("other").toPath();
        assertThrows(java.io.IOException.class,()->DesktopUpdate.unzip(bad,other));
        assertFalse(Files.exists(temp.getRoot().toPath().resolve("outside.txt")));
    }

    /**
     * The swap itself, on throwaway folders: it waits for the program to close, puts the new folder where
     * the old one was, starts what is there and clears the old one away. A copy of hostname.exe stands in
     * for Mininotes.exe, so what gets started prints a name and ends.
     */
    @Test public void theNewFolderTakesTheOldOnesPlaceOnceTheProgramHasClosed() throws Exception {
        if(!System.getProperty("os.name","").startsWith("Windows"))return;
        Path stand=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","hostname.exe");
        Path root=temp.newFolder("Programs ünïcode").toPath();
        Path app=root.resolve("Mininotes-Windows-0.0.001");Files.createDirectories(app);
        Files.copy(stand,app.resolve("Mininotes.exe"));Files.writeString(app.resolve("which.txt"),"old");
        Path opened=root.resolve(".Mininotes-Windows-0.0.001.next").resolve("Mininotes");Files.createDirectories(opened);
        Files.copy(stand,opened.resolve("Mininotes.exe"));Files.writeString(opened.resolve("which.txt"),"new");
        // The program being replaced: something that runs for two seconds.
        Process running=new ProcessBuilder("ping","-n","3","127.0.0.1").redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Process swap=DesktopUpdate.replaceAfterExit(app,opened,running.pid(),true);
        Thread.sleep(500);
        assertEquals("nothing moves while it runs","old",Files.readString(app.resolve("which.txt")));
        assertTrue(swap.waitFor(60,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0,swap.exitValue());
        assertEquals("new",Files.readString(app.resolve("which.txt")));
        try(var left=Files.list(root)){assertEquals("the old and the staged folders are cleared away",java.util.List.of(app),left.toList());}
    }

    @Test public void notThePackagedAppMeansNoFolderToReplace() {
        String was=System.getProperty("jpackage.app-path");
        try{System.clearProperty("jpackage.app-path");assertNull(DesktopUpdate.appFolder());}
        finally{if(was!=null)System.setProperty("jpackage.app-path",was);}
    }
}
