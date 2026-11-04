package net.ranold.polychrome.far;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// gzipped: magic, version, count, then per section key + CELLS packed cells.
// written to a temp file then moved so a crash mid save keeps the old file
public final class FarFile {

    private static final int MAGIC = 0x434C4652;  // "CLFR", from the old name. dont change, old saves use it
    private static final int VERSION = 1;

    public interface Sink {
        void section(long key, int[] cells);
    }

    private FarFile() {
    }

    public static void save(Path file, long[] keys, int[] cells) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(keys.length);
            for (int i = 0; i < keys.length; i++) {
                out.writeLong(keys[i]);
                for (int c = 0; c < FarTable.CELLS; c++) {
                    out.writeInt(cells[i * FarTable.CELLS + c]);
                }
            }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // returns how many, 0 if no file
    public static int load(Path file, Sink sink) throws IOException {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("not a Polychrome distant color file, or a newer version: " + file);
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int[] cells = new int[FarTable.CELLS];
                for (int c = 0; c < FarTable.CELLS; c++) {
                    cells[c] = in.readInt();
                }
                sink.section(key, cells);
            }
            return count;
        }
    }
}
