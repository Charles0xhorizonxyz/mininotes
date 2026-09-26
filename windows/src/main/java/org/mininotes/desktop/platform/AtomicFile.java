package org.mininotes.desktop.platform;

import java.io.*;
import java.nio.file.*;

/** Replace only after the complete new file has reached disk. */
public final class AtomicFile {
    private AtomicFile() {}
    public static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path pending=Files.createTempFile(target.toAbsolutePath().getParent(), ".mininotes-", ".tmp");
        try {
            try(FileOutputStream out=new FileOutputStream(pending.toFile())) {
                out.write(bytes); out.getFD().sync();
            }
            Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }
}
