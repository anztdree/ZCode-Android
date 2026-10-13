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
 * ZCode Mobile v1.0 — shell tipis: pasang payload Desktop asli → jalankan engine
 * (node glibc LANGSUNG, TANPA proot/chroot) → tampilkan renderer asli di WebView.
 *
 * Arsitektur boot (tangga gaya, dipilih otomatis):
 *  D1: exec node langsung — PT_INTERP node sudah menunjuk loader glibc milik app
 *      (dipatch saat build). process.execPath benar → spawn CLI agent beres.
 *  D2: exec loader glibc eksplisit (--library-path) — bridge meng-override
 *      process.execPath dari env ZCODE_NODE.
 * Semua jalur env-driven (ZCODE_APPROOT dll) — tanpa terjemahan path apa pun.
 * DNS: dns-shim di bridge aktif otomatis (Android tak punya /etc/resolv.conf).
 * v1.0.1: glibc bundel di-patch saat build (clone3 → return -ENOSYS) — Android 12
 * membunuh clone3 dgn SIGSYS (exit=159 di log v1.0.0); dengan ENOSYS, glibc jatuh
 * sendiri ke clone legacy yang di-whitelist Android.
 */
public class MainActivity extends Activity {
    private static final String VERSION = "1.0.1";
    private int port = 8080; // dipilih dinamis — 8080 bisa terpakai app lain
    private FrameLayout root;
    private WebView web;
    private LinearLayout splash;
    private TextView splashText;
    private Button copyBtn;
    private Process engine;
    private volatile boolean engineReady = false;
    private String bootStyle = "D1";

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
        s.setUserAgentString(s.getUserAgentString() + " ZCodeMobile/1.0");
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
                blog("boot v" + VERSION + " abi=" + pickAbi() + " android=" + android.os.Build.VERSION.RELEASE
                        + " (" + android.os.Build.VERSION.SDK_INT + ")");
                installIfNeeded();
                postSplash("Memeriksa runtime…");
                String style = probeBootStyle();
                bootStyle = style;
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

    /** Pilih port bebas 8080–8089 (hindari bentrok dgn app lain). */
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
        synchronized (bootLog) {
            bootLog.append(line).append('\n');
            if (bootLog.length() > 200_000) bootLog.delete(0, 100_000);
        }
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
        sb.append("=== ZCode Mobile v").append(VERSION).append(" boot log ===\n");
        sb.append("ABI: ").append(android.os.Build.SUPPORTED_ABIS[0]).append('\n');
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("Model: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n');
        sb.append("Port: ").append(port).append(" | Gaya boot: ").append(bootStyle).append("\n\n");
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

    // ---------------- layout payload (tanpa proot) ----------------
    private File sbDir() { return new File(getFilesDir(), "sandbox"); }
    private File zhostDir() { return new File(sbDir(), "zhost"); }
    private File glibcDir() { return new File(sbDir(), "glibc"); }
    private File nodeBin() { return new File(glibcDir(), "bin/node"); }
    private File loaderBin() {
        String abi = pickAbi();
        return new File(glibcDir(), "lib/" + ("arm".equals(abi) ? "ld-linux-armhf.so.3" : "ld-linux-aarch64.so.1"));
    }

    private String pickAbi() {
        for (String a : android.os.Build.SUPPORTED_ABIS) {
            if (a.startsWith("arm64")) return "arm64";
        }
        return "none";
    }

    private String marker() { return new File(getFilesDir(), "payload-1.0.ok").getAbsolutePath(); }

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

    /** Env dasar untuk node (D1/D2) — jalur ABSOLUT milik app, bukan guest proot. */
    private Map<String, String> baseEnv() {
        Map<String, String> env = new HashMap<>();
        String sb = sbDir().getAbsolutePath();
        env.put("LD_LIBRARY_PATH", sb + "/glibc/lib");
        env.put("HOME", sb + "/data");
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("PATH", sb + "/glibc/bin:/system/bin:/system/xbin");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "xterm");
        // Asuransi: matikan registrasi rseq glibc (aman diabaikan bila tunable tak dikenal)
        env.put("GLIBC_TUNABLES", "glibc.pthread.rseq=0");
        return env;
    }

    // ---------------- tangga gaya boot: D1 → D2 ----------------
    /**
     * D1: node langsung (PT_INTERP = loader milik app, dipatch saat build).
     * D2: loader-prefix eksplisit (--library-path) — cadangan bila interp
     *     tidak cocok (mis. profil kerja /data/user/10). Bridge meng-override
     *     process.execPath dari env ZCODE_NODE agar spawn CLI agent tetap benar.
     */
    private String probeBootStyle() throws Exception {
        checkFile("node", nodeBin());
        checkFile("loader glibc", loaderBin());
        checkFile("engine utama", new File(zhostDir(), "out/main/index.js"));
        checkFile("core bridge", new File(zhostDir(), "core/src/main.mjs"));

        if (runProbe("D1 node-langsung", new String[]{ nodeBin().getAbsolutePath(), "-e", "console.log('NODE_OK')" }, "NODE_OK", null)) {
            return "D1";
        }
        Map<String, String> withNode = new HashMap<>();
        withNode.put("ZCODE_NODE", nodeBin().getAbsolutePath());
        if (runProbe("D2 loader-prefix", new String[]{ loaderBin().getAbsolutePath(),
                "--library-path", glibcDir().getAbsolutePath() + "/lib",
                nodeBin().getAbsolutePath(), "-e", "console.log('NODE_OK')" }, "NODE_OK", withNode)) {
            return "D2";
        }
        throw new IllegalStateException("node glibc tidak bisa jalan (D1 & D2 gagal; exit≥128 = dibunuh sinyal/seccomp) — lihat baris probe di atas");
    }

    private void checkFile(String label, File f) {
        if (f.isFile()) blog("file " + label + ": OK (" + f.length() + " B)");
        else blog("file " + label + ": HILANG!");
    }

    /**
     * Jalankan satu probe; kembalikan true bila exit 0 & mustContain ada.
     * Output selalu dicatat ke bootLog; kegagalan TIDAK dilempar (tangga lanjut).
     */
    private boolean runProbe(String label, String[] cmd, String mustContain, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(zhostDir());
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
        int ev = p.exitValue();
        String why = "";
        if (ev >= 128 && ev - 128 <= 64) {
            int sig = ev - 128;
            why = " = dibunuh sinyal " + sig + (sig == 31 ? " (SIGSYS: syscall diblokir seccomp Android)" : "");
        }
        blog("probe " + label + ": exit=" + ev + why + (out.isEmpty() ? " (tanpa output)" : " → " + out));
        return ev == 0 && (mustContain == null || out.contains(mustContain));
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
        if (!"arm64".equals(abi)) {
            throw new IllegalStateException("Perangkat " + (android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "?")
                    + " belum didukung — v1.0 memakai payload arm64-v8a");
        }
        File mark = new File(marker());
        if (mark.exists() && nodeBin().isFile() && new File(zhostDir(), "out/main/index.js").isFile()) {
            return; // payload v1.0 sudah terpasang
        }
        postSplash("Memasang payload Desktop (arm64)…");
        // payload lama (v4.x dgn proot/rootfs) dibersihkan total — arsitektur berubah
        deleteRecursive(sbDir());
        sbDir().mkdirs();
        extractBundle("zcode/zrt-arm64.tar.gz", "runtime node+glibc");
        chmodExec(nodeBin());
        chmodExec(loaderBin());
        extractBundle("zcode/zcode-app.tar.gz", "mesin utama");
        extractBundle("zcode/zcode-ui.tar.gz", "antarmuka Desktop");
        extractBundle("zcode/zcode-res.tar.gz", "resource glm/tools");
        // verifikasi hasil instalasi — gagal cepat & jelas bila tak lengkap
        File dataDir = new File(zhostDir(), "data");
        dataDir.mkdirs();
        requirePath(nodeBin(), "runtime node");
        requirePath(new File(zhostDir(), "core/src/main.mjs"), "core/main.mjs");
        requirePath(new File(zhostDir(), "out/main/index.js"), "engine utama");
        requirePath(new File(zhostDir(), "out/renderer/index.html"), "antarmuka Desktop");
        requirePath(new File(zhostDir(), "res/glm/zcode.cjs"), "resource glm");
        requirePath(loaderBin(), "loader glibc");
        if (!dataDir.isDirectory()) throw new IllegalStateException("dir data tidak bisa dibuat");
        mark.createNewFile();
        blog("instal payload v1.0.1 (glibc anti-clone3) selesai: " + sbDir().getAbsolutePath());
    }

    /** Ekstrak satu bundle tar.gz dari assets ke sbDir (path top-level: glibc/ zhost/). */
    private void extractBundle(String asset, String label) throws Exception {
        postSplash("Membongkar " + label + "…");
        File tgz = new File(getCacheDir(), "bundle.tar.gz");
        copyAsset(asset, tgz);
        Tar.extractTarGz(tgz, sbDir(), msg -> postSplash(label + ": " + baseName(msg)));
        tgz.delete();
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
        String sb = sbDir().getAbsolutePath();
        java.util.List<String> argv = new java.util.ArrayList<>();
        if ("D2".equals(style)) {
            argv.add(loaderBin().getAbsolutePath());
            argv.add("--library-path");
            argv.add(sb + "/glibc/lib");
        }
        argv.add(nodeBin().getAbsolutePath());
        argv.add(sb + "/zhost/core/src/main.mjs");
        blog("engine start gaya=" + style);

        File logFile = new File(getFilesDir(), "engine.log");
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(zhostDir()); // cwd = ROOT — findUpward CLI agent & jalur relatif beres
        Map<String, String> env = baseEnv();
        env.put("ZCODE_NODE", nodeBin().getAbsolutePath()); // override execPath utk D2 (diabaikan D1)
        env.put("ZCODE_APPROOT", sb + "/zhost");
        env.put("ZCODE_RES", sb + "/zhost/res");
        env.put("ZCODE_USERDATA", sb + "/zhost/data/.zcode-data");
        env.put("ZCODE_PORT", String.valueOf(port));
        env.put("ZCODE_VERSION", "3.15.1");
        env.put("ZCODE_LOCALE", "id-ID");
        env.put("ZCODE_SCREEN_W", String.valueOf(screenPx(true)));
        env.put("ZCODE_SCREEN_H", String.valueOf(screenPx(false)));
        env.put("ZCODE_DPR", String.valueOf(getResources().getDisplayMetrics().density));
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
