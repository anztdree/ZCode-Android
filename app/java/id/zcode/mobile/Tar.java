package id.zcode.mobile;

import java.io.*;
import java.util.zip.GZIPInputStream;

/**
 * Ekstraktor tar.gz streaming untuk payload ZCode Desktop.
 * Mendukung penuh tar dunia nyata:
 *  - ustar (field prefix), regular '0'/'7'/0, dir '5', symlink '2', hardlink '1'
 *  - GNU longname 'L' / longlink 'K' (nama disimpan utk entri BERIKUTNYA)
 *  - PAX header 'x' (override path=/linkpath=) & 'g' (global, diabaikan)
 *  - konflik file/dir ditangani tanpa crash (EISDIR tidak akan terjadi)
 */
public final class Tar {
    public interface Progress { void on(String msg); }

    private static final int PROGRESS_HINT = 4 << 20; // lapor utk file > 4MB

    public static void extractTarGz(File tarGz, File destDir, Progress cb) throws IOException {
        destDir.mkdirs();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tarGz)), 1 << 16)) {
            byte[] hdr = new byte[512];
            String gnuName = null, gnuLink = null, paxPath = null, paxLink = null;
            while (true) {
                if (readFully(in, hdr, 512) < 512) break;
                if (isZeroBlock(hdr)) continue;

                int type = hdr[156] & 0xFF;
                long size = parseSize(hdr);

                // ---- metadata GNU: longname / longlink (berlaku utk entri berikutnya) ----
                if (type == 'L' || type == 'K') {
                    byte[] buf = new byte[(int) size];
                    readFully(in, buf, (int) size);
                    skipPad(in, size);
                    String s = cstr(buf);
                    if (type == 'L') { gnuName = s; paxPath = null; }
                    else { gnuLink = s; paxLink = null; }
                    continue;
                }
                // ---- metadata PAX: baca data lalu parse override path=/linkpath= ----
                if (type == 'x' || type == 'X' || type == 'g') {
                    byte[] buf = new byte[(int) size];
                    readFully(in, buf, (int) size);
                    skipPad(in, size);
                    if (type != 'g') {
                        String p = paxField(buf, "path");
                        if (p != null) { paxPath = p; gnuName = null; }
                        String l = paxField(buf, "linkpath");
                        if (l != null) { paxLink = l; gnuLink = null; }
                    }
                    continue;
                }
                if (type == 'V') { // volume header — lewati
                    skipData(in, size);
                    continue;
                }

                // ---- entri nyata ----
                String hname = cstr(hdr, 0, 100);
                String prefix = cstr(hdr, 345, 155);
                String hdrName = prefix.isEmpty() ? hname : prefix + "/" + hname;
                String name = gnuName != null ? gnuName : (paxPath != null ? paxPath : hdrName);
                String hdrLink = (type == '1' || type == '2') ? cstr(hdr, 157, 100) : null;
                String linkName = gnuLink != null ? gnuLink : (paxLink != null ? paxLink : hdrLink);
                gnuName = null; gnuLink = null; paxPath = null; paxLink = null; // reset: hanya utk 1 entri
                int mode = parseMode(hdr);

                File out = safeFile(destDir, name);
                if (out == null) { skipData(in, size); continue; }

                if (type == '5') { // direktori
                    if (out.exists() && !out.isDirectory()) out.delete();
                    out.mkdirs();
                    skipData(in, size);
                } else if (type == '2') { // symlink
                    skipData(in, size);
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (!out.isDirectory()) { // dir asli menang atas symlink
                        try { out.delete(); } catch (Exception ignore) { }
                        try { android.system.Os.symlink(linkName, out.getAbsolutePath()); } catch (Throwable ignore) { }
                    }
                } else if (type == '1') { // hardlink — salin isi dari file sumber yg sudah diekstrak
                    skipData(in, size);
                    File src = safeFile(destDir, linkName);
                    if (src != null && (src.isFile() || isSymlink(src))) {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        if (!out.isDirectory()) {
                            try { out.delete(); } catch (Exception ignore) { }
                            try { android.system.Os.link(src.getAbsolutePath(), out.getAbsolutePath()); }
                            catch (Throwable t) { copyFile(src, out); }
                        }
                    }
                } else if (type == '0' || type == '7' || type == 0) { // file reguler
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (out.isDirectory()) { // konflik: jangan pernah buka dir sbg file (EISDIR)
                        skipData(in, size);
                    } else {
                        try { out.delete(); } catch (Exception ignore) { }
                        try (FileOutputStream fo = new FileOutputStream(out)) {
                            long left = size;
                            byte[] buf = new byte[1 << 16];
                            while (left > 0) {
                                int want = (int) Math.min(left, buf.length);
                                int got = readFully(in, buf, want);
                                if (got <= 0) break;
                                fo.write(buf, 0, got);
                                left -= got;
                            }
                        }
                        skipPad(in, size);
                        try { android.system.Os.chmod(out.getAbsolutePath(), mode & 07777); } catch (Throwable ignore) { }
                        if (cb != null && size > PROGRESS_HINT) cb.on(name);
                    }
                } else { // '3' chardev, '4' blockdev, '6' fifo, dll — lewati
                    skipData(in, size);
                }
            }
        }
    }

    // ---------------- helpers ----------------

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    /** String NUL-terminated dari buffer penuh (GNU L/K data). */
    private static String cstr(byte[] buf) {
        int end = 0;
        while (end < buf.length && buf[end] != 0) end++;
        return new String(buf, 0, end);
    }

    /** Field header NUL-terminated — TANPA trim (nama file bisa berakhiran spasi). */
    private static String cstr(byte[] h, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && h[end] != 0) end++;
        return new String(h, off, end - off);
    }

    private static long parseSize(byte[] h) {
        if ((h[124] & 0x80) != 0) { // base-256 (biner)
            long v = h[124] & 0x7F;
            for (int i = 125; i < 136; i++) v = (v << 8) | (h[i] & 0xFF);
            return v;
        }
        String s = cstr(h, 124, 12).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    private static int parseMode(byte[] h) {
        String s = cstr(h, 100, 8).trim();
        try { return s.isEmpty() ? 0644 : (int) Long.parseLong(s, 8); }
        catch (NumberFormatException e) { return 0644; }
    }

    /** Parse record PAX: "<len> <key>=<value>\n" — ambil nilai utk key tertentu. */
    private static String paxField(byte[] buf, String key) {
        int i = 0;
        while (i < buf.length && buf[i] != 0) {
            int sp = i;
            while (sp < buf.length && buf[sp] != ' ') sp++;
            int recLen;
            try { recLen = Integer.parseInt(new String(buf, i, sp - i)); }
            catch (NumberFormatException e) { break; }
            if (recLen <= 0 || i + recLen > buf.length) break;
            int nl = i + recLen - 1; // posisi '\n'
            // record: <len> key=value
            int eq = sp;
            while (eq < nl && buf[eq] != '=') eq++;
            if (eq < nl) {
                String k = new String(buf, sp + 1, eq - sp - 1);
                if (k.equals(key)) {
                    String v = new String(buf, eq + 1, nl - eq - 1);
                    int z = v.indexOf('\0');
                    return z >= 0 ? v.substring(0, z) : v;
                }
            }
            i += recLen;
        }
        return null;
    }

    private static File safeFile(File dest, String name) {
        if (name == null) return null;
        String clean = name.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        String[] segs = clean.split("/");
        StringBuilder rel = new StringBuilder();
        for (String s : segs) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) return null; // path traversal — tolak
            if (rel.length() > 0) rel.append('/');
            rel.append(s);
        }
        if (rel.length() == 0) return null;
        File f = new File(dest, rel.toString());
        try {
            if (!f.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)
                    && !f.getCanonicalPath().equals(dest.getCanonicalPath())) return null;
        } catch (IOException e) { return null; }
        return f;
    }

    private static boolean isSymlink(File f) {
        try {
            return (android.system.Os.lstat(f.getAbsolutePath()).st_mode & 0170000) == 0120000;
        } catch (Throwable t) { return false; }
    }

    private static void copyFile(File src, File dst) {
        try (FileInputStream fi = new FileInputStream(src); FileOutputStream fo = new FileOutputStream(dst)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = fi.read(b)) > 0) fo.write(b, 0, n);
        } catch (Throwable ignore) { }
    }

    /** Konsumsi data entri (size byte) + padding blok 512. */
    private static void skipData(InputStream in, long size) throws IOException {
        long left = size;
        byte[] b = new byte[1 << 16];
        while (left > 0) {
            int got = in.read(b, 0, (int) Math.min(left, b.length));
            if (got <= 0) break;
            left -= got;
        }
        skipPad(in, size);
    }

    private static void skipPad(InputStream in, long size) throws IOException {
        long pad = ((size + 511) / 512) * 512 - size;
        long left = pad;
        byte[] b = new byte[4096];
        while (left > 0) {
            int got = in.read(b, 0, (int) Math.min(left, b.length));
            if (got <= 0) break;
            left -= got;
        }
    }

    private static int readFully(InputStream in, byte[] buf, int want) throws IOException {
        int off = 0;
        while (off < want) {
            int got = in.read(buf, off, want - off);
            if (got <= 0) break;
            off += got;
        }
        return off;
    }
}
