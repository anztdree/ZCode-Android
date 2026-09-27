package com.zcodemobile.app;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.zip.GZIPInputStream;

/**
 * Sandbox proot ala Kai 9000: rootfs Alpine Linux + binary proot statis
 * dibundel di assets, diekstrak ke penyimpanan aplikasi saat "dipasang",
 * lalu semua perintah shell (tool Bash agent + halaman Terminal) berjalan
 * DI DALAM rootfs — tersedia busybox lengkap + `apk` (git, python3, nodejs
 * bisa dipasang dan TERSIMPAN), dan proyek terlihat konsisten di /workspace.
 *
 * Catatan teknis penting: binary di folder data aplikasi hanya bisa
 * dieksekusi bila targetSdk < 29 (Android 10+ melarang exec() dari
 * direktori data untuk aplikasi targetSdk 29+ — pola yang sama dipakai
 * Termux). Karena itu aplikasi memakai targetSdk 28.
 */
public class Sandbox {

    public interface Cb {
        void onProgress(String msg);
        void onDone(boolean ok, String msg);
    }

    private static final long MAX_TAR_BYTES = 80L * 1024 * 1024; // pengaman

    /* ------------------------------- state ------------------------------- */

    private static File sbDir(Context c) { return new File(c.getFilesDir(), "sandbox"); }
    public static File prootBin(Context c) { return new File(sbDir(c), "proot"); }
    public static File rootfsDir(Context c) { return new File(sbDir(c), "rootfs"); }
    private static File stageDir(Context c) { return new File(sbDir(c), "stage"); }
    private static File cwdFile(Context c) { return new File(stageDir(c), ".cwd"); }

    /** Sandbox terpasang & binary siap dieksekusi. */
    public static boolean isReady(Context c) {
        Context ac = c.getApplicationContext();
        return "1".equals(Prefs.get(ac, "sb_ready", "0"))
                && prootBin(ac).canExecute()
                && new File(rootfsDir(ac), "bin/busybox").exists();
    }

    /** Toggle user: gunakan sandbox untuk perintah (default ON bila siap). */
    public static boolean enabled(Context c) {
        return "1".equals(Prefs.get(c.getApplicationContext(), "sb_enabled", "1"));
    }

    public static void setEnabled(Context c, boolean v) {
        Prefs.set(c.getApplicationContext(), "sb_enabled", v ? "1" : "0");
    }

    /** Sandbox AKTIF untuk perintah = siap + toggle ON. */
    public static boolean on(Context c) {
        Context ac = c.getApplicationContext();
        return isReady(ac) && enabled(ac);
    }

    /** Status ringkas untuk kartu UI (Bahasa Indonesia). */
    public static String statusText(Context c) {
        Context ac = c.getApplicationContext();
        if (isReady(ac)) return enabled(ac) ? "Siap · aktif" : "Siap · nonaktif";
        return "Belum terpasang";
    }

    /* --------------------------- pasang / hapus --------------------------- */

    /** Pilih ABI perangkat → nama asset (arm64 | arm | x64). */
    public static String pickAbi() {
        try {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            if (abis != null) {
                for (String a : abis) {
                    if (a == null) continue;
                    if (a.startsWith("arm64")) return "arm64";
                    if (a.startsWith("armeabi")) return "arm";
                    if (a.contains("x86_64")) return "x64";
                }
                for (String a : abis) {
                    if (a == null) continue;
                    if (a.contains("x86")) return "x64";
                }
            }
        } catch (Exception ignore) { }
        return "arm64"; // mayoritas ponsel modern
    }

    public static void install(final Context ctx, final Cb cb) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            final StringBuilder log = new StringBuilder("ZCode Mobile — Log Pasang Sandbox\n");
            try {
                String abi = pickAbi();
                log.append("ABI: ").append(android.os.Build.SUPPORTED_ABIS != null
                        ? java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS) : "?")
                  .append(" → asset: ").append(abi).append('\n');

                Prefs.set(c, "sb_ready", "0");
                File sb = sbDir(c);
                if (!sb.isDirectory() && !sb.mkdirs())
                    throw new Exception("tidak bisa membuat folder sandbox");
                cb.onProgress("Menyalin binary proot (" + abi + ")…");
                log.append("langkah: salin proot_").append(abi).append('\n');
                File proot = prootBin(c);
                copyAsset(c, "sandbox/proot_" + abi, proot);
                if (!proot.setExecutable(true, false))
                    throw new Exception("gagal memberi izin eksekusi pada proot");

                cb.onProgress("Mengekstrak rootfs Alpine (sekali saja)…");
                log.append("langkah: ekstrak rootfs_").append(abi).append('\n');
                File rootfs = rootfsDir(c);
                if (rootfs.exists()) deleteRecur(rootfs);
                if (!rootfs.mkdirs()) throw new Exception("tidak bisa membuat folder rootfs");
                extractTarGz(c.getAssets().open("sandbox/rootfs_" + abi + ".tar.gz"), rootfs, cb);

                cb.onProgress("Menyiapkan konfigurasi jaringan…");
                writeFile(new File(rootfs, "etc/resolv.conf"),
                        "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 8.8.4.4\n");
                writeFile(new File(rootfs, "root/.profile"), "export PS1='zcode:\\w# '\n");
                File stage = stageDir(c);
                if (!stage.isDirectory() && !stage.mkdirs())
                    throw new Exception("tidak bisa membuat folder stage");

                cb.onProgress("Menguji sandbox…");
                log.append("langkah: uji eksekusi proot\n");
                String test = wrap(c, "echo zcode-sandbox-ok", c.getFilesDir(), 30_000);
                log.append("perintah: ").append(test).append('\n');
                ShellSession.Result r = ShellSession.execOnce(c.getFilesDir(), test, 30_000);
                log.append("exit=").append(r.exitCode).append(" output=\"").append(r.output).append("\"\n");
                if (!r.ok() || !r.output.contains("zcode-sandbox-ok"))
                    throw new Exception("uji eksekusi gagal (exit " + r.exitCode + "): "
                            + firstLines(r.output, 4));

                Prefs.set(c, "sb_ready", "1");
                saveLog(c, log.append("HASIL: SUKSES\n").toString());
                cb.onDone(true, "Sandbox siap — perintah kini berjalan di Alpine Linux");
            } catch (Exception e) {
                Prefs.set(c, "sb_ready", "0");
                saveLog(c, log.append("HASIL: GAGAL — ").append(e).append('\n').toString());
                cb.onDone(false, "Gagal memasang sandbox: " + e.getMessage());
            }
        }, "sb-install").start();
    }

    /** Simpan log pasang sandbox → files/sandbox-log.txt (bisa dikirim user). */
    private static void saveLog(Context c, String content) {
        try {
            writeFile(new File(c.getFilesDir(), "sandbox-log.txt"), content);
        } catch (Exception ignore) { }
    }

    public static void remove(Context c) {
        Context ac = c.getApplicationContext();
        Prefs.set(ac, "sb_ready", "0");
        File sb = sbDir(ac);
        if (sb.exists()) deleteRecur(sb);
    }

    private static boolean hasHostTimeout() {
        for (String p : new String[]{"/system/bin/timeout", "/system/xbin/timeout", "/vendor/bin/timeout"})
            if (new File(p).exists()) return true;
        return false;
    }

    /** N baris pertama output (untuk pesan error yang bisa ditindaklanjuti). */
    private static String firstLines(String s, int max) {
        if (s == null) return "(kosong)";
        s = s.trim();
        if (s.isEmpty()) return "(kosong)";
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, lines.length); i++)
            sb.append(i > 0 ? " | " : "").append(lines[i].trim());
        if (sb.length() > 320) sb.setLength(320);
        return sb.toString();
    }

    /* ------------------------- bungkus perintah ------------------------- */

    /**
     * Bungkus perintah user agar berjalan DI DALAM rootfs. Baris hasil
     * dipanggil dari shell HOST persisten (ShellSession). Skrip tamu ditulis
     * ke folder stage (ter-bind ke /zstage); cwd terakhir disimpan agar `cd`
     * tetap terasa persisten antar perintah — seperti shell biasa.
     */
    public static String wrap(Context c, String userCmd, File workspace, long timeoutMs) {
        try {
            Context ac = c.getApplicationContext();
            File stage = stageDir(ac);
            if (!stage.isDirectory() && !stage.mkdirs())
                throw new Exception("folder stage tidak tersedia");
            sweepStage(stage);

            // cwd tersimpan dari perintah sebelumnya (persistensi cd)
            String lastCwd = "";
            File cf = cwdFile(ac);
            if (cf.isFile()) {
                String s = new String(Files.readAllBytes(cf.toPath()), StandardCharsets.UTF_8).trim();
                if (s.startsWith("/") && !s.contains("..") && s.length() < 512) lastCwd = s;
            }

            String name = "cmd_" + Long.toHexString(System.nanoTime()) + ".sh";
            StringBuilder gs = new StringBuilder();
            gs.append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n");
            gs.append("export HOME=/root LANG=C.UTF-8 TERM=xterm\n");
            if (!lastCwd.isEmpty())
                gs.append("cd ").append(shellQ(lastCwd)).append(" 2>/dev/null || cd /workspace\n");
            else
                gs.append("cd /workspace 2>/dev/null || cd /root\n");
            gs.append(userCmd == null ? "" : userCmd.replace("\r", ""));
            if (gs.length() == 0 || gs.charAt(gs.length() - 1) != '\n') gs.append('\n');
            // simpan cwd untuk perintah berikutnya + teruskan kode akhir asli
            gs.append("__zc=$?; pwd > /zstage/.cwd 2>/dev/null; exit $__zc\n");
            FileOutputStream f = new FileOutputStream(new File(stage, name));
            f.write(gs.toString().getBytes(StandardCharsets.UTF_8));
            f.close();

            long base = timeoutMs <= 0 ? 120_000 : timeoutMs;
            long secs = Math.max(15, base / 1000 + 5);
            // `timeout` di host (toybox) menjaga proot tidak jadi proses yatim
            // saat perintah melewati batas waktu.
            // ⚠ PENTING: penugasan env (PROOT_NO_SECCOMP=1) HARUS di depan —
            // sebelum `timeout`. Kalau diselipkan setelah `timeout`, toybox
            // menganggapnya NAMA PROGRAM → "No such file or directory" →
            // semua perintah sandbox gagal (bug v2.3.1!). Env di depan
            // diwarisi timeout dan diturunkan ke proot — POSIX benar.
            boolean useTimeout = hasHostTimeout();
            StringBuilder cmd = new StringBuilder("PROOT_NO_SECCOMP=1 ");
            if (useTimeout) cmd.append("timeout ").append(secs).append(' ');
            cmd.append(shellQ(prootBin(ac).getAbsolutePath()))
               .append(" -0 --kill-on-exit")
               .append(" -r ").append(shellQ(rootfsDir(ac).getAbsolutePath()))
               .append(" -w /workspace")
               .append(" -b ").append(shellQ(workspace.getAbsolutePath() + ":/workspace"))
               .append(" -b ").append(shellQ(stage.getAbsolutePath() + ":/zstage"))
               .append(" -b /dev -b /proc -b /sys")
               .append(" /bin/sh ").append(shellQ("/zstage/" + name));
            return "PROOT_NO_SECCOMP=1 " + cmd;
        } catch (Exception e) {
            return "echo '(sandbox gagal menyiapkan perintah: " + e.getMessage() + ")'; exit 1";
        }
    }

    private static String shellQ(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Buang skrip stage basi (>15 mnt) & sisakan maks 8 berkas terbaru. */
    private static void sweepStage(File stage) {
        try {
            File[] fs = stage.listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                String n = f.getName();
                if (!n.startsWith("cmd_")) continue;
                if (now - f.lastModified() > 15 * 60_000L) f.delete();
            }
            File[] rest = stage.listFiles();
            if (rest == null || rest.length <= 8) return;
            java.util.Arrays.sort(rest, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 8; i < rest.length; i++) rest[i].delete();
        } catch (Exception ignore) { }
    }

    /* ------------------------------ util IO ------------------------------ */

    private static void copyAsset(Context c, String asset, File out) throws Exception {
        InputStream in = c.getAssets().open(asset);
        FileOutputStream fo = new FileOutputStream(out);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.close();
        in.close();
    }

    private static void writeFile(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(content.getBytes(StandardCharsets.UTF_8));
        fo.close();
    }

    private static void deleteRecur(File f) {
        try {
            if (f == null || !f.exists()) return;
            if (Files.isSymbolicLink(f.toPath())) { f.delete(); return; }
            if (f.isDirectory()) {
                File[] ch = f.listFiles();
                if (ch != null) for (File c : ch) deleteRecur(c);
            }
            f.delete();
        } catch (Exception ignore) { }
    }

    /* ---------------- ekstraktor tar.gz mandiri (tanpa lib) ---------------- */

    private static void extractTarGz(InputStream src, File dest, Cb cb) throws Exception {
        GZIPInputStream gz = new GZIPInputStream(src, 65536);
        DataInputStream in = new DataInputStream(new BufferedInputStream(gz, 65536));
        byte[] hdr = new byte[512];
        int files = 0;
        long total = 0;
        String gnuLongName = null, gnuLongLink = null, paxPath = null;
        try {
            while (true) {
                try { in.readFully(hdr); } catch (EOFException eof) { break; }
                if (isZero(hdr)) continue; // blok nol (penutup arsip)

                String name = str(hdr, 0, 100);
                String linkname = str(hdr, 157, 100);
                long size = oct(hdr, 124, 12);
                char type = (char) (hdr[156] & 0xFF);
                int mode = (int) (oct(hdr, 100, 8) & 07777);
                String magic = str(hdr, 257, 6);
                if (magic.startsWith("ustar")) {
                    String prefix = str(hdr, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }

                String path = name;
                if (paxPath != null) { path = paxPath; paxPath = null; }
                if (gnuLongName != null) { path = gnuLongName; gnuLongName = null; }
                String link = linkname;
                if (gnuLongLink != null) { link = gnuLongLink; gnuLongLink = null; }

                // Header panjang (GNU/PAX) — konten data = nama sebenarnya
                if (type == 'L') { gnuLongName = readString(in, size); continue; }
                if (type == 'K') { gnuLongLink = readString(in, size); continue; }
                if (type == 'x' || type == 'X') {
                    // rekam PAX: "<len> key=value\n" — hanya "path" yang kita butuh
                    String pax = readString(in, size);
                    for (String rec : pax.split("\n")) {
                        int sp = 0;
                        while (sp < rec.length() && Character.isDigit(rec.charAt(sp))) sp++;
                        if (sp == 0) continue;
                        int eq = rec.indexOf('=', sp);
                        if (eq < 0) continue;
                        if (rec.substring(sp, eq).equals("path")) {
                            paxPath = rec.substring(eq + 1);
                            break;
                        }
                    }
                    continue;
                }
                if (type == 'g') { skipFully(in, size + pad(size)); continue; }

                total += size;
                if (total > MAX_TAR_BYTES) throw new Exception("rootfs melebihi batas ukuran");

                String clean = normalize(path);
                if (clean.isEmpty()) { skipFully(in, size + pad(size)); continue; }
                File out = new File(dest, clean);

                switch (type) {
                    case '5': // direktori
                        out.mkdirs();
                        break;
                    case '2': { // symlink
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        try { if (out.exists()) out.delete();
                              Files.createSymbolicLink(out.toPath(), Paths.get(link)); }
                        catch (Exception se) {
                            // fallback: salin target bila symlink ditolak FS
                            File t = new File(dest, normalize(link));
                            if (t.isFile()) copyFile(t, out);
                        }
                        break;
                    }
                    case '1': { // hardlink
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        File target = new File(dest, normalize(link));
                        try { if (out.exists()) out.delete();
                              Files.createLink(out.toPath(), target.toPath()); }
                        catch (Exception he) { if (target.isFile()) copyFile(target, out); }
                        break;
                    }
                    case '0': case '\0': case '7': { // berkas biasa
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        if (out.exists()) out.delete();
                        FileOutputStream fo = new FileOutputStream(out);
                        byte[] buf = new byte[65536];
                        long left = size;
                        while (left > 0) {
                            int want = (int) Math.min(buf.length, left);
                            in.readFully(buf, 0, want);
                            fo.write(buf, 0, want);
                            left -= want;
                        }
                        fo.close();
                        skipFully(in, pad(size));
                        if ((mode & 0111) != 0) out.setExecutable(true, false);
                        break;
                    }
                    default: // karakter/blok/fifo dsb — lewati
                        skipFully(in, size + pad(size));
                        break;
                }

                if (++files % 150 == 0)
                    cb.onProgress("Mengekstrak rootfs… (" + files + " berkas)");
            }
        } finally {
            try { in.close(); } catch (Exception ignore) { }
        }
    }

    private static long pad(long size) { return (512 - (size % 512)) % 512; }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    /** Oktal tar (atau base-256 GNU utk nilai besar). */
    private static long oct(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xFF);
            return v;
        }
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') { if (any) break; else continue; }
            if (c < '0' || c > '7') break;
            v = (v << 3) | (c - '0');
            any = true;
        }
        return v;
    }

    private static String readString(DataInputStream in, long size) throws Exception {
        byte[] buf = new byte[(int) size];
        in.readFully(buf);
        skipFully(in, pad(size));
        int end = buf.length;
        for (int i = 0; i < buf.length; i++) if (buf[i] == 0) { end = i; break; }
        return new String(buf, 0, end, StandardCharsets.UTF_8);
    }

    private static void skipFully(InputStream in, long n) throws Exception {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            long got = in.skip(left);
            if (got > 0) { left -= got; continue; }
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) return;
            left -= r;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream fo = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        fo.close();
        in.close();
    }

    /** "./bin/sh" -> "bin/sh", buang "../" (pengaman path). */
    private static String normalize(String p) {
        if (p == null) return "";
        p = p.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.equals(".") || p.equals("..")) return "";
        if (p.contains("..")) return "";
        return p;
    }
}
