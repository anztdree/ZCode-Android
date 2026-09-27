package com.zcodemobile.app;

import java.io.BufferedReader;
import java.io.File;
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
        pb.directory(cwd);
        pb.redirectErrorStream(true);
        pb.environment().put("HOME", cwd.getAbsolutePath());
        pb.environment().put("TERM", "dumb");
        pb.environment().put("PATH",
                "/system/bin:/system/xbin:/vendor/bin:/odm/bin:/system_ext/bin");
        proc = pb.start();
        stdin = proc.getOutputStream();
        stdout = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
        reader = new Thread(this::drainLoop, "zcode-shell");
        reader.setDaemon(true);
        reader.start();
    }

    /** SATU pembaca tunggal: teruskan baris ke sink aktif, atau buang saat idle. */
    private void drainLoop() {
        try {
            String line;
            while (proc != null && (line = stdout.readLine()) != null) {
                LineSink s = sink;
                if (s != null) s.onLine(line);
            }
            LineSink s = sink;
            if (s != null) s.onEof();
        } catch (Exception ignore) {
            LineSink s = sink;
            if (s != null) s.onEof();
        }
    }

    /**
     * Jalankan satu perintah; kembalikan output lengkap + kode akhir.
     * Shell tetap hidup setelahnya (cd & variabel tersimpan).
     */
    public synchronized Result run(String command, long timeoutMs) throws Exception {
        if (command == null || command.trim().isEmpty())
            return new Result("(perintah kosong)", 0);
        if (!isAlive()) start();

        final long id = SEQ.getAndIncrement();
        final String marker = "__ZCMD_END_" + id + "_";
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

        try {
            stdin.write((command + "\necho " + marker + "$?__\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception e) {
            sink = null;
            dead = true;
            return new Result("(shell mati: " + e.getMessage() + " — coba lagi untuk sesi baru)", 127);
        }

        boolean finished = done.await(timeoutMs > 0 ? timeoutMs : 120_000, TimeUnit.MILLISECONDS);
        if (!finished) {
            sink = null;
            kill();
            String so = stripAnsi(out.toString());
            return new Result(so + "\n(Timeout — perintah dihentikan setelah "
                    + (timeoutMs / 1000) + " dtk. Sesi shell akan dinyalakan ulang.)", 124);
        }
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
