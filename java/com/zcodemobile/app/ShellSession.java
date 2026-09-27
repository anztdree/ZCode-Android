package com.zcodemobile.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shell persisten ala node-pty ZCode Desktop — satu proses `sh` yang hidup
 * terus sehingga `cd`, variabel lingkungan, dan fungsi tetap tersimpan antar
 * perintah. Dipakai oleh: halaman Terminal (interaktif) dan tool Bash (agent).
 *
 * Teknik: tiap perintah diikuti `echo __ZCMD_END_<n>_<exit>__`; SATU thread
 * pembaca tunggal memindai output dan menandai selesai saat marker terlihat.
 * Output ANSI dari program sistem dibersihkan agar tampil rapi di TextView.
 */
public class ShellSession {

    private static final AtomicLong SEQ = new AtomicLong(1);
    private static final int MAX_OUTPUT = 120_000; // karakter

    /** Handler baris aktif — diatur oleh run(), dibaca thread pembaca. */
    private interface LineSink { void onLine(String line); void onEof(); }

    private Process proc;
    private OutputStream stdin;
    private BufferedReader stdout;
    private Thread reader;
    private volatile LineSink sink;   // null = idle (output dibuang)
    private volatile int gen = 0;     // generasi proses — anti "stale EOF" antar restart
    private final File cwd;
    private volatile boolean dead = false;

    /** Riwayat perintah (dipakai halaman Terminal untuk tombol ↑). */
    public final List<String> history = new ArrayList<>();
    public int historyCursor = -1;

    public ShellSession(File workspace) {
        this.cwd = workspace;
    }

    public boolean isAlive() {
        return !dead && proc != null && proc.isAlive();
    }

    /** Nyalakan (atau nyalakan ulang) proses shell di direktori kerja. */
    public synchronized void start() throws Exception {
        if (isAlive()) return;
        if (proc != null) { try { proc.destroy(); } catch (Exception ignore) { } }
        dead = false;
        sink = null;
        ProcessBuilder pb = new ProcessBuilder("sh");
        pb.directory(cwd != null && cwd.isDirectory() ? cwd : null);
        pb.redirectErrorStream(true);
        // Beberapa varian Android bisa menolak modifikasi lingkungan —
        // jika gagap, lanjutkan dengan lingkungan bawaan (tetap jalan).
        try {
            java.util.Map<String, String> env = pb.environment();
            env.put("HOME", cwd != null ? cwd.getAbsolutePath() : "/data/local/tmp");
            env.put("TERM", "dumb");
            env.put("PATH",
                    "/system/bin:/system/xbin:/vendor/bin:/odm/bin:/system_ext/bin");
        } catch (Exception ignore) { }
        proc = pb.start();
        stdin = proc.getOutputStream();
        stdout = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        final int myGen = ++gen;
        reader = new Thread(() -> drainLoop(myGen), "zcode-shell");
        reader.setDaemon(true);
        reader.start();
    }

    /** SATU pembaca tunggal: teruskan baris ke sink aktif, atau buang saat idle. */
    private void drainLoop(int myGen) {
        try {
            String line;
            while (proc != null && gen == myGen && (line = stdout.readLine()) != null) {
                LineSink s = sink;
                if (s != null) s.onLine(line);
            }
        } catch (Exception ignore) {
        }
        // EOF hanya berlaku untuk generasi proses sendiri — EOF "basi" dari
        // shell lama tidak boleh menyelesaikan perintah sesi baru (race ala
        // yang dijaga Kai 9000 lewat mekanisme serupa).
        if (gen == myGen) {
            LineSink s = sink;
            if (s != null) s.onEof();
            try { if (proc == null || !proc.isAlive()) dead = true; } catch (Exception ignore) { }
        }
    }

    /**
     * Jalankan satu perintah; kembalikan output lengkap + kode akhir.
     * Shell tetap hidup setelahnya (cd & variabel tersimpan).
     *
     * Teknik staging ala Kai 9000: perintah ditulis ke berkas sementara lalu
     * di-`source` dari shell aktif — aman untuk perintah multiline dan kutip
     * yang tidak tertutup (parsing berkas utuh, marker tak pernah tertelan).
     * Marker memakai nonce acak agar tak mungkin tertukar antar sesi.
     */
    public synchronized Result run(String command, long timeoutMs) throws Exception {
        if (command == null || command.trim().isEmpty())
            return new Result("(perintah kosong)", 0);
        if (!isAlive()) start();

        final long id = SEQ.getAndIncrement();
        final String nonce = Long.toHexString(System.nanoTime())
                + Long.toHexString((long) (Math.random() * 1.0e9));
        final String marker = "__ZCMD_END_" + id + "_" + nonce + "_";
        final StringBuilder out = new StringBuilder();
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = new CountDownLatch(1);

        sink = new LineSink() {
            @Override public void onLine(String line) {
                int idx = line.indexOf(marker);
                if (idx >= 0) {
                    String tail = line.substring(idx + marker.length());
                    int us = tail.indexOf("__");
                    try { exit.set(Integer.parseInt(tail.substring(0, us))); } catch (Exception ignore) { }
                    sink = null;          // kembali idle
                    done.countDown();
                } else if (out.length() < MAX_OUTPUT) {
                    out.append(line).append('\n');
                }
            }
            @Override public void onEof() {
                done.countDown();
            }
        };

        // 1) Tahapkan perintah ke berkas sementara di direktori kerja.
        File cmdFile = new File(cwd != null ? cwd : new File("."), ".zcmd_" + nonce);
        try {
            FileOutputStream cf = new FileOutputStream(cmdFile);
            cf.write(command.getBytes(StandardCharsets.UTF_8));
            cf.write((int) '\n');
            cf.close();
        } catch (Exception e) {
            sink = null;
            return new Result("(gagal menulis berkas perintah: " + e.getMessage() + ")", 1);
        }

        // 2) Sumberkan berkas di shell aktif (cd/env tetap tersimpan), catat
        //    kode akhir, hapus berkas, lalu terbitkan marker lewat stderr
        //    (teknik Kai 9000: tahan bila stdout dialihkan user; pada
        //    redirectErrorStream(true) stderr tetap sampai ke pembaca kita).
        //    PENTING: ${__zst} WAJIB ber-kurung-kurawal — tanpa itu shell
        //    membaca variabel "__zst__" (underscore menyatu) → kosong.
        final String abs = cmdFile.getAbsolutePath();
        try {
            stdin.write((". '" + abs + "'; __zst=$?; rm -f '" + abs
                    + "' >/dev/null 2>&1; echo " + marker + "${__zst}__ >&2\n")
                    .getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception e) {
            sink = null;
            dead = true;
            try { cmdFile.delete(); } catch (Exception ignore) { }
            return new Result("(shell mati: " + e.getMessage() + " — coba lagi untuk sesi baru)", 127);
        }

        boolean finished = done.await(timeoutMs > 0 ? timeoutMs : 120_000, TimeUnit.MILLISECONDS);
        if (!finished) {
            sink = null;
            kill();
            try { cmdFile.delete(); } catch (Exception ignore) { }
            String so = stripAnsi(out.toString());
            return new Result(so + "\n(Timeout — perintah dihentikan setelah "
                    + (timeoutMs / 1000) + " dtk. Sesi shell akan dinyalakan ulang.)", 124);
        }
        try { cmdFile.delete(); } catch (Exception ignore) { }
        String so = stripAnsi(out.toString());
        if (so.length() > MAX_OUTPUT) so = so.substring(0, MAX_OUTPUT) + "… (dipotong)";
        return new Result(so.isEmpty() ? "(tidak ada output)" : so, exit.get());
    }

    /** Eksekusi sekali-pakai tanpa sesi (fallback). */
    public static Result execOnce(File cwd, String command, long timeoutMs) throws Exception {
        ShellSession s = new ShellSession(cwd);
        s.start();
        try { return s.run(command, timeoutMs); }
        finally { s.kill(); }
    }

    public synchronized void kill() {
        dead = true;
        sink = null;
        if (proc != null) { proc.destroy(); proc = null; }
    }

    /* ------------------------------ ANSI strip ------------------------------ */

    private static final java.util.regex.Pattern ANSI =
            java.util.regex.Pattern.compile("\\u001B(?:\\[[0-9;?]*[a-zA-Z]|\\][^\u0007]*(?:\u0007|\\u001B\\\\)|[=>])");

    public static String stripAnsi(String s) {
        if (s == null) return "";
        return ANSI.matcher(s).replaceAll("").replace("\r\n", "\n").replace("\r", "");
    }

    /* -------------------------------- Hasil -------------------------------- */

    public static class Result {
        public final String output;
        public final int exitCode;
        public Result(String o, int e) { output = o; exitCode = e; }
        public boolean ok() { return exitCode == 0; }
    }
}
