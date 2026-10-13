package id.zcode.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ZCode Mobile v4 — shell tipis: pasang payload Desktop asli → jalankan engine
 * (proot + node + core/main.mjs) → tampilkan renderer asli di WebView.
 *
 * v4.0.4: proot arm64 diganti build Termux (loader eksternal + patch Android:
 * f2fs/seccomp/link2symlink) — build vanilla 5.3.0 gagal senyap (exit 255) saat
 * exec guest. Matriks probe diagnostik (file + exec bertahap), start engine
 * adaptif (node langsung / via loader glibc / via wrapper).
 */
public class MainActivity extends Activity {
    private int port = 8080; // dipilih dinamis — 8080 bisa terpakai app lain
    private FrameLayout root;
    private WebView web;
    private LinearLayout splash;
    private TextView splashText;
    private Button copyBtn;
    private Process engine;
    private volatile boolean engineReady = false;

    private final StringBuilder bootLog = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        setContentView(root);
        buildSplash();
        port = pickPort();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " ZCodeMobile/4.0");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest rq) {
                return handleUrl(rq.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(Uri.parse(url));
            }
        });
        web.setBackgroundColor(0xFF141516);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                blog("boot v4.0.4 abi=" + pickAbi() + " android=" + android.os.Build.VERSION.RELEASE);
                installIfNeeded();
                // payload lama (v4.0.1/4.0.2) belum membawa loader /lib64 — pasang di tempat
                ensureGlibcCompat();
                postSplash("Memeriksa runtime…");
                String style = probeMatrix();
                startEngine(style);
                waitHealthy(240_000);
                runOnUiThread(() -> {
                    engineReady = true;
                    splash.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    web.loadUrl("http://127.0.0.1:" + port + "/");
                });
            } catch (Throwable t) {
                showFatal(t);
            }
        }, "zcode-boot").start();
    }

    /** Pilih port bebas 8080–8089 ( hindari bentrok dgn app lain). */
    private int pickPort() {
        for (int p = 8080; p < 8090; p++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket(p)) { return p; }
            catch (Throwable ignore) { }
        }
        return 8080;
    }

    private boolean handleUrl(Uri u) {
        if (u == null) return false;
        String sc = u.getScheme();
        if ("zcode".equals(sc)) {
            forwardDeeplink(u.toString());
            return true;
        }
        if ("zcode-open".equals(sc)) {
            try {
                String flat = u.toString();
                int qi = flat.indexOf("url=");
                if (qi >= 0) {
                    String target = java.net.URLDecoder.decode(flat.substring(qi + 4), "UTF-8");
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                }
            } catch (Throwable ignore) { }
            return true;
        }
        if ("http".equals(sc) || "https".equals(sc)) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Throwable ignore) { }
            return true; // semua navigasi eksternal keluar dari WebView
        }
        return false;
    }

    private void forwardDeeplink(String url) {
        new Thread(() -> {
            try {
                URL u = new URL("http://127.0.0.1:" + port + "/deeplink?url=" + java.net.URLEncoder.encode(url, "UTF-8"));
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(4000);
                c.getInputStream().close();
            } catch (Throwable ignore) { }
        }).start();
    }

    // ---------------- splash ----------------
    private void buildSplash() {
        splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        splash.setBackgroundColor(0xFF141516);
        TextView logo = new TextView(this);
        logo.setText("Z");
        logo.setTextColor(0xFFFFFFFF);
        logo.setTextSize(64);
        logo.setGravity(Gravity.CENTER);
        splashText = new TextView(this);
        splashText.setText(getString(R.string.preparing));
        splashText.setTextColor(0xFFB9BDC0);
        splashText.setTextSize(14);
        splashText.setGravity(Gravity.CENTER);
        ProgressBar pb = new ProgressBar(this);
        copyBtn = new Button(this);
        copyBtn.setText("SALIN LOG");
        copyBtn.setTextSize(13);
        copyBtn.setVisibility(View.GONE);
        copyBtn.setOnClickListener(v -> copyLogToClipboard());
        splash.addView(logo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 40, 0, 24);
        splash.addView(splashText, lp);
        splash.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, 32, 0, 0);
        splash.addView(copyBtn, bp);
        root.addView(splash, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void postSplash(String msg) {
        runOnUiThread(() -> splashText.setText(msg));
    }

    private synchronized void blog(String line) {
        bootLog.append(line).append('\n');
        if (bootLog.length() > 200_000) bootLog.delete(0, 100_000);
    }

    private void showFatal(Throwable t) {
        blog("FATAL: " + t);
        String msg = "Gagal: " + t;
        postSplash(msg);
        runOnUiThread(() -> {
            copyBtn.setVisibility(View.VISIBLE);
            splashText.setText(msg + "\n\nTekan SALIN LOG lalu tempel teksnya ke berkas di GitHub.");
        });
    }

    private void copyLogToClipboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ZCode Mobile v4.0.4 boot log ===\n");
        sb.append("ABI: ").append(android.os.Build.SUPPORTED_ABIS[0]).append('\n');
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("Model: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
        sb.append("Port: ").append(port).append("\n\n");
        synchronized (bootLog) { sb.append(bootLog); }
        try {
            File lf = new File(getFilesDir(), "engine.log");
            if (lf.isFile()) {
                sb.append("\n=== engine.log (120 baris terakhir) ===\n");
                java.util.ArrayDeque<String> dq = new java.util.ArrayDeque<>();
                try (BufferedReader r = new BufferedReader(new FileReader(lf))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        dq.addLast(l);
                        if (dq.size() > 120) dq.removeFirst();
                    }
                }
                for (String x : dq) sb.append(x).append('\n');
            } else {
                sb.append("\n(engine.log belum ada)\n");
            }
        } catch (Throwable ignore) { }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("zcode-log", sb.toString()));
            Toast.makeText(this, "Log disalin — tempel ke berkas teks di GitHub", Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) { }
    }

    // ---------------- instalasi payload ----------------
    private File sbDir() { return new File(getFilesDir(), "sandbox"); }
    private File rootfsDir() { return new File(sbDir(), "rootfs"); }
    private File prootBin() { return new File(sbDir(), "proot"); }

    private String pickAbi() {
        for (String a : android.os.Build.SUPPORTED_ABIS) {
            if (a.startsWith("arm64")) return "arm64";
            if (a.startsWith("armeabi")) return "arm";
        }
        return "arm64";
    }

    /** Nama dynamic loader glibc sesuai ABI. */
    private String glibcLoader() {
        String abi = pickAbi();
        if ("arm".equals(abi)) return "ld-linux-armhf.so.3";
        if ("arm64".equals(abi)) return "ld-linux-aarch64.so.1";
        return "ld-linux-x86-64.so.2";
    }

    /** Nama dynamic loader musl (rootfs Alpine) sesuai ABI. */
    private String muslLoader() {
        String abi = pickAbi();
        if ("arm".equals(abi)) return "ld-musl-armhf.so.3";
        if ("arm64".equals(abi)) return "ld-musl-aarch64.so.1";
        return "ld-musl-x86_64.so.1";
    }

    private String marker() { return new File(getFilesDir(), "payload-3.15.1.ok").getAbsolutePath(); }

    private final java.util.ArrayDeque<String> engineTail = new java.util.ArrayDeque<>();
    private long lastSplashLog = 0;

    private String engineTailText() {
        synchronized (engineTail) {
            if (engineTail.isEmpty()) return "(engine tanpa output)";
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = engineTail.descendingIterator();
            int n = 0;
            while (it.hasNext() && n < 3) {
                String s = it.next();
                if (s.length() > 100) s = s.substring(0, 100);
                if (n > 0) sb.append(" || ");
                sb.append(s);
                n++;
            }
            return sb.toString();
        }
    }

    // ---------------- env proot ----------------
    /** Env dasar utk SEMUA pemanggilan proot (probe & engine). */
    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("PROOT_NO_SECCOMP", "1");
        env.put("PROOT_TMP_DIR", getCacheDir().getAbsolutePath());
        // proot Termux (bionic) butuh libtalloc.so.2 + libandroid-shmem.so dari sbDir/lib;
        // loader termux berupa file terpisah — diarahkan via PROOT_LOADER
        File loader = new File(sbDir(), "loader");
        if (loader.isFile()) {
            env.put("PROOT_LOADER", loader.getAbsolutePath());
            File l32 = new File(sbDir(), "loader32");
            if (l32.isFile()) env.put("PROOT_LOADER_32", l32.getAbsolutePath());
        }
        File libDir = new File(sbDir(), "lib");
        StringBuilder ld = new StringBuilder();
        if (libDir.isDirectory()) ld.append(libDir.getAbsolutePath()).append(':');
        ld.append("/opt/zhost/glibc/lib");
        env.put("LD_LIBRARY_PATH", ld.toString());
        env.put("HOME", "/opt/zhost/data");
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/zhost/glibc/bin");
        return env;
    }

    // ---------------- glibc compat (fix device: loader /lib64) ----------------
    /**
     * Node resmi glibc butuh dynamic loader di /lib64/<ld> — rootfs Alpine (musl)
     * tidak memilikinya, sehingga execve node gagal. Loader sudah ada di payload
     * (opt/zhost/glibc/lib) — cukup disalin ke lib64. Idempoten & murah.
     */
    private void ensureGlibcCompat() {
        String ld = glibcLoader();
        File src = new File(rootfsDir(), "opt/zhost/glibc/lib/" + ld);
        File dstDir = new File(rootfsDir(), "lib64");
        File dst = new File(dstDir, ld);
        if (!src.isFile() || dst.isFile()) return;
        try {
            dstDir.mkdirs();
            try (FileInputStream in = new FileInputStream(src); FileOutputStream fo = new FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 14];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            chmodExec(dst);
            blog("glibc-compat: " + ld + " dipasang di lib64 (" + src.length() + " B)");
        } catch (Throwable t) {
            blog("glibc-compat GAGAL: " + t);
        }
    }

    // ---------------- matriks probe diagnostik ----------------
    /**
     * Lapisan diperiksa berurutan dan SEMUA hasil dicatat (tidak fail-fast):
     *  [berkas]  integritas payload dibaca dari sisi host
     *  [hostsh]  proot melacak proses host Android (/system/bin/sh) → mesin proot sehat?
     *  [busybox] ELF musl guest dieksekusi langsung
     *  [echo]    symlink /bin/echo (repro baseline v4.0.3)
     *  [node]    3 gaya start: langsung / via loader glibc / via wrapper
     * gaya node pertama yang sukses dipakai untuk memulai engine.
     */
    private String probeMatrix() throws Exception {
        // 0) info host
        runSimple("uname", "/system/bin/uname", "-r");
        runSimple("patch", "/system/bin/getprop", "ro.build.version.security_patch");

        // 1) integritas berkas payload (sisi host, tanpa eksekusi)
        checkFile("rootfs/bin/busybox", new File(rootfsDir(), "bin/busybox"));
        checkFile("rootfs/bin/sh", new File(rootfsDir(), "bin/sh"));
        checkFile("rootfs/bin/echo", new File(rootfsDir(), "bin/echo"));
        checkFile("rootfs/lib/" + muslLoader(), new File(rootfsDir(), "lib/" + muslLoader()));
        checkFile("rootfs/lib64/" + glibcLoader(), new File(rootfsDir(), "lib64/" + glibcLoader()));
        checkFile("guest node", new File(rootfsDir(), "opt/zhost/glibc/bin/node"));
        checkFile("guest wrapper", new File(rootfsDir(), "usr/bin/node"));
        checkFile("proot", prootBin());

        String rfs = rootfsDir().getAbsolutePath();
        String proot = prootBin().getAbsolutePath();
        String musl = "/lib/" + muslLoader();
        String gld = "/lib64/" + glibcLoader();

        // 2) diagnostik eksekusi (hasil dicatat, tidak menghentikan)
        runProbe("hostsh", new String[]{ proot, "-R", rfs, "-b", "/system/bin", "/system/bin/sh", "-c", "echo HOSTSH_OK" }, "HOSTSH_OK", null);
        runProbe("busybox", new String[]{ proot, "-R", rfs, "/bin/busybox", "echo", "BB_OK" }, "BB_OK", null);
        runProbe("echo", new String[]{ proot, "-R", rfs, "/bin/echo", "E_OK" }, "E_OK", null);
        runProbe("echo-min", new String[]{ proot, "-r", rfs, "/bin/echo", "E_OK" }, "E_OK", null);
        runProbe("musl-ld", new String[]{ proot, "-R", rfs, musl, "/bin/echo", "MUSL_OK" }, "MUSL_OK", null);

        Map<String, String> verbose = new HashMap<>();
        verbose.put("PROOT_VERBOSE", "1");
        runProbe("echo-verbose", new String[]{ proot, "-R", rfs, "/bin/echo", "E_OK" }, null, verbose);

        // 3) gaya start node — keputusan nyata
        if (runProbe("node-direct", new String[]{ proot, "-R", rfs, "-w", "/opt/zhost",
                "/opt/zhost/glibc/bin/node", "--version" }, "v", null)) {
            return "direct";
        }
        if (runProbe("node-loader", new String[]{ proot, "-R", rfs, "-w", "/opt/zhost", gld,
                "--library-path", "/opt/zhost/glibc/lib", "/opt/zhost/glibc/bin/node", "--version" }, "v", null)) {
            return "loader";
        }
        if (runProbe("node-wrapper", new String[]{ proot, "-R", rfs, "-w", "/opt/zhost",
                "/usr/bin/node", "--version" }, "v", null)) {
            return "wrapper";
        }
        throw new IllegalStateException("semua gaya start node gagal — lihat hasil matriks di log");
    }

    private void checkFile(String label, File f) {
        if (f.isFile()) blog("file " + label + ": OK (" + f.length() + " B)");
        else blog("file " + label + ": HILANG!");
    }

    /** Info host (tidak fatal bila gagal). */
    private void runSimple(String label, String cmd, String arg) {
        try {
            Process p = new ProcessBuilder(cmd, arg).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String out = r.readLine();
            r.close();
            blog("host " + label + ": " + out);
        } catch (Throwable t) {
            blog("host " + label + ": (tidak tersedia)");
        }
    }

    /**
     * Jalankan satu probe; kembalikan true bila exit 0 & mustContain ada.
     * Output selalu dicatat ke bootLog; kegagalan TIDAK dilempar (matriks lanjut).
     */
    private boolean runProbe(String label, String[] cmd, String mustContain, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = baseEnv();
        if (extraEnv != null) env.putAll(extraEnv);
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final StringBuilder so = new StringBuilder();
        Thread rd = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) {
                    synchronized (so) {
                        if (so.length() < 8000) so.append(l.trim()).append('\n');
                    }
                }
            } catch (Throwable ignore) { }
        });
        rd.setDaemon(true);
        rd.start();
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            blog("probe " + label + ": MACET 60 dtk (dihentikan) → " + so.toString().trim());
            return false;
        }
        rd.join(2000);
        String out = so.toString().trim();
        blog("probe " + label + ": exit=" + p.exitValue() + (out.isEmpty() ? " (tanpa output)" : " → " + out));
        return p.exitValue() == 0 && (mustContain == null || out.contains(mustContain));
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteRecursive(x);
        f.delete();
    }

    private boolean assetExists(String path) {
        try { getAssets().open(path).close(); return true; } catch (Throwable t) { return false; }
    }

    private String baseName(String p) {
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private void requirePath(File f, String what) throws Exception {
        if (!f.exists()) throw new IllegalStateException("Instalasi tidak lengkap: " + what + " hilang");
    }

    private void installIfNeeded() throws Exception {
        String abi = pickAbi();
        File mark = new File(marker());
        File legacy = new File(getFilesDir(), "installed-v4.0.1"); // payload v4.0.1 sama — jangan ekstrak ulang
        File rootfsBin = new File(rootfsDir(), "bin/busybox");
        boolean have = mark.exists() || legacy.exists();
        if (have && rootfsBin.exists() && prootBin().canExecute()) {
            if (!mark.exists()) { try { mark.createNewFile(); } catch (Exception ignore) { } }
            // proot + loader + lib SELALU disegarkan dari APK — binary proot vanilla
            // lama (v4.0.1) gagal senyap saat exec guest; ganti tanpa ekstrak ulang payload
            try {
                copyAsset("sandbox/proot_" + abi, prootBin());
                chmodExec(prootBin());
            } catch (Throwable t) {
                blog("refresh proot gagal (pakai yang terpasang): " + t);
            }
            installProotHelpers();
            return;
        }
        postSplash("Memasang payload Desktop (" + abi + ")…");
        // payload lama (mis. hasil extractor rusak v4.0.0) dibersihkan total — mulai bersih
        deleteRecursive(sbDir());
        sbDir().mkdirs();
        if (!assetExists("zcode/zrt-" + abi + ".tar.gz"))
            throw new IllegalStateException("Perangkat " + abi + " belum didukung — butuh arm64-v8a");
        // 1) proot
        File proot = prootBin();
        copyAsset("sandbox/proot_" + abi, proot);
        chmodExec(proot);
        installProotHelpers();
        // 2) rootfs
        File rootfsTgz = new File(getCacheDir(), "rootfs.tar.gz");
        copyAsset("sandbox/rootfs_" + abi + ".tar.gz", rootfsTgz);
        postSplash("Membongkar rootfs…");
        Tar.extractTarGz(rootfsTgz, rootfsDir(), msg -> postSplash("rootfs: " + baseName(msg)));
        rootfsTgz.delete();
        // 3) runtime node glibc (usr/bin/node + /opt/zhost/glibc + lib64 loader)
        postSplash("Memasang runtime Node…");
        File zrtTgz = new File(getCacheDir(), "zrt.tar.gz");
        copyAsset("zcode/zrt-" + abi + ".tar.gz", zrtTgz);
        Tar.extractTarGz(zrtTgz, rootfsDir(), null);
        zrtTgz.delete();
        File node = new File(rootfsDir(), "opt/zhost/glibc/bin/node");
        chmodExec(node);
        // 4) host engine (res/glm + apps/zcode-cli)
        postSplash("Memasang host ZCode…");
        File zhostTgz = new File(getCacheDir(), "zhost.tar.gz");
        copyAsset("zcode/zhost.tar.gz", zhostTgz);
        Tar.extractTarGz(zhostTgz, new File(rootfsDir(), "opt"), msg -> postSplash("host: " + baseName(msg)));
        zhostTgz.delete();
        // 5) main + preload + node_modules + core
        postSplash("Memasang mesin utama…");
        File zmainTgz = new File(getCacheDir(), "zmain.tar.gz");
        copyAsset("zcode/zmain.tar.gz", zmainTgz);
        Tar.extractTarGz(zmainTgz, new File(rootfsDir(), "opt"), msg -> postSplash("mesin: " + baseName(msg)));
        zmainTgz.delete();
        // 6) UI renderer asli
        postSplash("Memasang antarmuka Desktop…");
        File zuiTgz = new File(getCacheDir(), "zui.tar.gz");
        copyAsset("zcode/zui.tar.gz", zuiTgz);
        Tar.extractTarGz(zuiTgz, new File(rootfsDir(), "opt"), msg -> postSplash("UI: " + baseName(msg)));
        zuiTgz.delete();
        // 7) verifikasi hasil instalasi — gagal cepat & jelas bila tak lengkap
        File dataDir = new File(rootfsDir(), "opt/zhost/data");
        dataDir.mkdirs();
        requirePath(new File(rootfsDir(), "bin/busybox"), "rootfs/bin/busybox");
        requirePath(node, "runtime node");
        requirePath(new File(rootfsDir(), "opt/zhost/core/src/main.mjs"), "core/main.mjs");
        requirePath(new File(rootfsDir(), "opt/zhost/out/main/index.js"), "engine utama");
        requirePath(new File(rootfsDir(), "opt/zhost/out/renderer/index.html"), "antarmuka Desktop");
        requirePath(new File(rootfsDir(), "opt/zhost/res/glm"), "resource glm");
        if (!dataDir.isDirectory()) throw new IllegalStateException("dir data tidak bisa dibuat");
        mark.createNewFile();
        ensureGlibcCompat();
    }

    /** Loader + lib pendamping proot Termux (tidak ada di payload lama → opsional). */
    private void installProotHelpers() throws Exception {
        String abi = pickAbi();
        if (assetExists("sandbox/prootloader_" + abi)) {
            File loader = new File(sbDir(), "loader");
            copyAsset("sandbox/prootloader_" + abi, loader);
            chmodExec(loader);
        }
        if (assetExists("sandbox/prootloader32_" + abi)) {
            File loader32 = new File(sbDir(), "loader32");
            copyAsset("sandbox/prootloader32_" + abi, loader32);
            chmodExec(loader32);
        }
        if (assetExists("sandbox/libtalloc_" + abi + ".so")) {
            File libDir = new File(sbDir(), "lib");
            libDir.mkdirs();
            File talloc = new File(libDir, "libtalloc.so.2");
            copyAsset("sandbox/libtalloc_" + abi + ".so", talloc);
            chmodExec(talloc);
        }
        if (assetExists("sandbox/libshmem_" + abi + ".so")) {
            File libDir = new File(sbDir(), "lib");
            libDir.mkdirs();
            File shmem = new File(libDir, "libandroid-shmem.so");
            copyAsset("sandbox/libshmem_" + abi + ".so", shmem);
            chmodExec(shmem);
        }
    }

    private void copyAsset(String asset, File out) throws Exception {
        try (InputStream in = getAssets().open(asset); FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); total += n; }
            if (total < 1024 * 1024) postSplash("menyalin " + asset + "…");
            else postSplash(asset + " (" + (total >> 20) + "MB)…");
        }
    }

    private void chmodExec(File f) {
        try { f.setExecutable(true, false); } catch (Throwable ignore) { }
        try { android.system.Os.chmod(f.getAbsolutePath(), 0755); } catch (Throwable ignore) { }
    }

    // ---------------- engine ----------------
    private void startEngine(String style) throws Exception {
        String nodePath = "/opt/zhost/glibc/bin/node";
        String rfs = rootfsDir().getAbsolutePath();
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(prootBin().getAbsolutePath());
        argv.add("-R");
        argv.add(rfs);
        argv.add("-w");
        argv.add("/opt/zhost");
        if ("loader".equals(style)) {
            argv.add("/lib64/" + glibcLoader());
            argv.add("--library-path");
            argv.add("/opt/zhost/glibc/lib");
        } else if ("wrapper".equals(style)) {
            argv.add("/usr/bin/node");
        } else {
            argv.add(nodePath);
        }
        argv.add("/opt/zhost/core/src/main.mjs");
        blog("engine start gaya=" + style);

        File logFile = new File(getFilesDir(), "engine.log");
        ProcessBuilder pb = new ProcessBuilder(argv);
        Map<String, String> env = baseEnv();
        // PENTING: HOME TIDAK BOLEH /root — proot -R mem-bind $HOME host ke guest;
        // di Android /root host tak bisa ditulis app → EACCES. Pakai dir data di dalam sandbox.
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "xterm");
        env.put("ZCODE_APPROOT", "/opt/zhost");
        env.put("ZCODE_RES", "/opt/zhost/res");
        env.put("ZCODE_USERDATA", "/opt/zhost/data/.zcode-data");
        env.put("ZCODE_PORT", String.valueOf(port));
        env.put("ZCODE_VERSION", "3.15.1");
        env.put("ZCODE_LOCALE", "id-ID");
        env.put("ZCODE_SCREEN_W", String.valueOf(screenPx(true)));
        env.put("ZCODE_SCREEN_H", String.valueOf(screenPx(false)));
        env.put("ZCODE_DPR", String.valueOf(getResources().getDisplayMetrics().density));
        env.put("ELECTRON_RUN_AS_NODE", "1");
        pb.environment().putAll(env);
        // KRITIS: stdout+stderr DIGABUNG & DIBACA terus-menerus. Tanpa ini, pipe stdout
        // (64KB) meluap saat engine rajin menulis log → proses BEKU selamanya
        // (penyebab spinner tak berhenti di v4.0.1).
        pb.redirectErrorStream(true);
        engine = pb.start();

        final BufferedReader out = new BufferedReader(new InputStreamReader(engine.getInputStream()), 1 << 14);
        Thread logT = new Thread(() -> {
            try (java.io.FileWriter fw = new java.io.FileWriter(logFile, false)) {
                String line;
                while ((line = out.readLine()) != null) {
                    fw.write(line);
                    fw.write("\n");
                    fw.flush();
                    blog("engine: " + line);
                    synchronized (engineTail) {
                        engineTail.addLast(line);
                        while (engineTail.size() > 8) engineTail.removeFirst();
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastSplashLog > 1200) {
                        lastSplashLog = now;
                        String s = line.length() > 90 ? line.substring(0, 90) : line;
                        postSplash("engine: " + s);
                    }
                }
            } catch (Throwable ignore) { }
        }, "zcode-log");
        logT.setDaemon(true);
        logT.start();
    }

    private int screenPx(boolean w) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.round((w ? dm.widthPixels : dm.heightPixels) / dm.density);
    }

    private boolean healthy() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/health").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Throwable t) { return false; }
    }

    private void waitHealthy(long timeoutMs) throws Exception {
        long dl = System.currentTimeMillis() + timeoutMs;
        boolean announced = false;
        while (System.currentTimeMillis() < dl) {
            if (healthy()) return;
            if (!announced && System.currentTimeMillis() - (dl - timeoutMs) > 4000) {
                announced = true;
                postSplash("Memulai mesin Desktop…");
            }
            Thread.sleep(600);
        }
        throw new IllegalStateException("engine tak merespons " + (timeoutMs / 1000)
                + " dtk — log terakhir: " + engineTailText());
    }

    // ---------------- lifecycle ----------------
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = intent.getData();
        if (u != null && "zcode".equals(u.getScheme())) forwardDeeplink(u.toString());
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (engine != null) engine.destroy();
        super.onDestroy();
    }
}
