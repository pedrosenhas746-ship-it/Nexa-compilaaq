package com.oculus.systemdriver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/** Checks ELF class AND architecture; a 32-bit process must never load an ARM64 fallback. */
public final class NativeLibraryDirectory {
    private static final String[] LIBRARIES = {"libopenxr_loader.so", "libvrapi.so"};
    public static File resolve(File primary, boolean is64Bit) throws IOException {
        if (primary == null) throw new IOException("No extracted native library directory");
        File sibling = new File(primary.getParentFile(), is64Bit ? "arm64" : "arm");
        for (File folder : new File[]{primary, sibling}) {
            boolean valid = true;
            for (String name : LIBRARIES) valid &= matches(new File(folder, name), is64Bit);
            if (valid) return folder.getCanonicalFile();
        }
        throw new IOException("Missing matching ARM " + (is64Bit ? "64" : "32") + "-bit loader/adapter");
    }
    private static boolean matches(File file, boolean is64Bit) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] h = new byte[20]; int offset = 0;
            while (offset < h.length) { int n = in.read(h, offset, h.length-offset); if (n < 0) return false; offset += n; }
            return h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F' &&
                h[4] == (is64Bit ? 2 : 1) && h[5] == 1 && h[6] == 1 &&
                (h[18] & 255) == (is64Bit ? 183 : 40) && h[19] == 0;
        } catch (IOException e) { return false; }
    }
    private NativeLibraryDirectory() {}
}
