package org.mininotes.android;

import static org.junit.Assert.*;

import java.nio.file.*;
import org.junit.Test;

public class DesktopHelloTest {
    @Test public void aDamagedCopyIsSaidSoWithoutAskingHello() throws Exception {
        Path folder=Files.createTempDirectory("hello");
        assertFalse(DesktopHello.has(folder));
        Files.write(folder.resolve(DesktopHello.FILE),new byte[]{'M','N','H','1',1,2,3});
        assertTrue(DesktopHello.has(folder));
        try{DesktopHello.open(folder);fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("damaged"));}
        DesktopHello.forget(folder);
        assertFalse(DesktopHello.has(folder));
    }

    /**
     * The step that left Hello half set up: a buffer Windows made, read back as bytes. The helper's own self
     * test makes one from the bytes it is given and hands them back, asking nothing of anybody.
     */
    @Test public void theHelperReadsAWindowsBufferBackAsBytes() throws Exception {
        if(!System.getProperty("os.name","").startsWith("Windows"))return;
        String bytes=java.util.Base64.getEncoder().encodeToString(new byte[]{1,2,3,(byte)250,0,7});
        assertEquals("SIGNED "+bytes,DesktopHello.run("selftest",bytes).trim());
    }

    /** The PowerShell side runs and answers on Windows; whether Hello is set up depends on the PC. */
    @Test public void askingWhetherHelloIsHereAnswers() {
        if(!System.getProperty("os.name","").startsWith("Windows"))return;
        System.out.println("Windows Hello here: "+DesktopHello.supported());
    }
}
