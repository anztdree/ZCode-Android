package com.zcodemobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * ZCode Mobile — antarmuka utama.
 * Struktur mengikuti ZCode Desktop: sidebar tugas (drawer) + header (model chip)
 * + percakapan (bubble user, markdown, tool card, kartu rencana) + composer
 * (mode selector, lampiran, kirim/stop) + halaman Berkas/Todo/Setelan.
 */
public class MainActivity extends Activity implements ChatAdapter.PlanActionListener {

    /* ------------------------------- State ------------------------------- */

    private File workspace;
    private Tools tools;
    private AgentEngine engine;

    private final List<ChatItem> chatItems = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private FilesAdapter filesAdapter;
    private TodoAdapter todoAdapter;
    private SessionAdapter sessionAdapter;
    private MarkdownLite md;

    private String sessionId = "";
    private boolean busy = false;
    private boolean allowAllSession = false;
    private long startedAt = 0;
    private int tokensP = 0, tokensC = 0;
    private String statusBase = "Bekerja…";
    private ChatItem currentAssistant = null;
    private final java.util.Map<String, ChatItem> toolCards = new java.util.HashMap<>();
    private long lastNotify = 0;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable timerRun;

    /* ------------------------------- Views ------------------------------- */

    private View scrim;
    private LinearLayout drawerPanel;
    private TextView txtWsName, txtModel, txtStatus, txtTokens, txtMode, txtTheme, txtGreeting, txtSelModel, txtTest, txtFilesPath, btnProvider, lblBaseUrl, txtSbStatus;
    private View statusStrip, emptyState;
    private ImageButton btnSend;
    private ImageView imgMode, imgTheme;
    private EditText etInput, etApiKey, etBaseUrl, etSessionSearch, etNewTodo;
    private ListView chatList, fileList, todoList, sessionList;
    private ScrollView pageSettings;
    private LinearLayout pageChat, pageFiles, pageTodo, pageTerminal;
    private TextView tvTermOut;
    private EditText etTermInput;
    private ScrollView scrollTerm;
    private ShellSession termShell;
    private boolean termBusy = false;
    private boolean settingsBinding = false;
    private int activePage = 0;

    // Lampiran gambar (vision ala ZCode PC)
    private LinearLayout attachRow;
    private TextView txtAttachName;
    private String pendingImageName = "";
    private String pendingImageDataUrl = null;

    /* =============================== LIFECYCLE =============================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        // Logger crash dipasang SEDINI MUNGKIN agar crash mana pun (termasuk
        // kegagalan inflate layout) tercatat ke files/crash-log.txt.
        installCrashLogger();
        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            showFatal("Gagal memuat tampilan", t);
            return;
        }
        try {
            initApp();
        } catch (Throwable t) {
            // JANGAN pernah mati paksa tanpa UI: tampilkan layar pemulihan.
            showFatal("Gagal menyiapkan aplikasi", t);
            return;
        }
        maybeOfferCrashReport();
    }

    private void initApp() {
        workspace = new File(getFilesDir(), "workspace");
        if (!workspace.exists()) workspace.mkdirs();

        md = new MarkdownLite(col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest),
                col(R.attr.cCodeBg), col(R.attr.cCodeFg), col(R.attr.cAsk),
                code -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code));
                    toast("Kode disalin");
                });

        tools = new Tools(this, workspace);
        tools.mode = Prefs.agentMode(this);
        tools.bridge = bridge();
        tools.modeHook = newMode -> {
            Prefs.set(this, "agent_mode", newMode);
            runOnUiThread(() -> {
                updateModeChip();
                addNote("Mode agent: " + modeLabel(newMode));
            });
        };
        // Subagent (tool Agent) — ala ZCode Desktop: Explore / general-purpose
        tools.subagent = (type, prompt) -> runSubagent(type, prompt);

        bindViews();
        bindListeners();
        refreshSandboxCard(); // status kartu sandbox di halaman Berkas

        // Sesi aktif (atau baru).
        // PERBAIKAN FORCE CLOSE SAAT DIBUKA (v2.2.1): sebelumnya `saved` masih
        // null bila instalasi baru / preferensi kosong (session_current belum
        // terisi), sehingga `saved.length()` langsung NPE — aplikasi mati paksa
        // begitu dibuka, bahkan berulang (crash loop) karena preferensi belum
        // sempat tersimpan sebelum proses mati. Kini selalu diisi array kosong.
        sessionId = SessionStore.currentId(this);
        JSONArray saved = new JSONArray();
        if (!sessionId.isEmpty()) {
            saved = SessionStore.loadItems(this, sessionId);
            if (saved.length() == 0 && !new File(SessionStore.dir(this), sessionId + ".json").exists())
                sessionId = "";
        }
        if (sessionId.isEmpty()) {
            sessionId = SessionStore.create(this, "Tugas baru");
        } else {
            tools.mode = SessionStore.loadMode(this, sessionId);
        }
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            if (o != null) chatItems.add(ChatItem.fromJson(o));
        }

        chatAdapter = new ChatAdapter(chatItems, getLayoutInflater(), md,
                col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest),
                col(R.attr.cCodeBg), col(R.attr.cCodeFg), col(R.attr.cAsk),
                col(R.attr.cSuccess), col(R.attr.cWarning), col(R.attr.cDestructive));
        chatAdapter.planListener = this;
        chatList.setAdapter(chatAdapter);

        // Satu engine seumur activity — riwayat konteks tetap tersambung antar-kirim
        engine = new AgentEngine(new LlmClient(Prefs.activeBaseUrl(this),
                Prefs.activeApiKey(this), Prefs.activeModel(this)), tools, engineCallbacks());
        rebuildHistory();

        filesAdapter = new FilesAdapter(workspace, getLayoutInflater(),
                col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest));
        fileList.setAdapter(filesAdapter);
        filesAdapter.reload("");

        todoAdapter = new TodoAdapter(TodoStore.load(this), getLayoutInflater(),
                col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest),
                col(R.attr.cSuccess), col(R.attr.cWarning), col(R.attr.cDestructive));
        todoList.setAdapter(todoAdapter);

        refreshSessions();
        updateModeChip();
        updateModelChip();
        updateThemeButton();
        bindSettings();
        refreshEmptyState();
        greeting();
        switchPage(0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistSettings();
        saveSession(null);
    }

    private int col(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /* ================================ DRAWER ================================ */

    private void openDrawer() {
        drawerPanel.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(200).start();
        drawerPanel.animate().translationX(0f).setDuration(220).start();
    }

    private void closeDrawer() {
        scrim.animate().alpha(0f).setDuration(180).start();
        drawerPanel.animate().translationX(-dp(320)).setDuration(200)
                .withEndAction(() -> {
                    drawerPanel.setVisibility(View.GONE);
                    scrim.setVisibility(View.GONE);
                }).start();
    }

    private boolean drawerOpen() {
        return drawerPanel.getVisibility() == View.VISIBLE;
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen()) closeDrawer();
        else super.onBackPressed();
    }

    /* ================================ THEME ================================ */

    private void applyTheme() {
        String t = Prefs.themeMode(this);
        boolean dark;
        if ("dark".equals(t)) dark = true;
        else if ("system".equals(t)) {
            int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            dark = m == Configuration.UI_MODE_NIGHT_YES;
        } else dark = false;
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    private void updateThemeButton() {
        String t = Prefs.themeMode(this);
        if ("dark".equals(t)) {
            imgTheme.setImageResource(R.drawable.ic_moon);
            txtTheme.setText("Tema: Gelap");
        } else if ("system".equals(t)) {
            imgTheme.setImageResource(R.drawable.ic_cpu);
            txtTheme.setText("Tema: Sistem");
        } else {
            imgTheme.setImageResource(R.drawable.ic_sun);
            txtTheme.setText("Tema: Terang");
        }
        String[] order = {"light", "dark", "system"};
        String label;
        int idx = "dark".equals(t) ? 1 : ("system".equals(t) ? 2 : 0);
        label = order[(idx + 1) % 3];
        btnThemeTag = label;
    }

    private String btnThemeTag = "dark";

    /* ================================ BINDING ================================ */

    private void bindViews() {
        scrim = findViewById(R.id.scrim);
        drawerPanel = findViewById(R.id.drawerPanel);
        drawerPanel.setTranslationX(-dp(320));
        txtWsName = findViewById(R.id.txtWsName);
        txtModel = findViewById(R.id.txtModel);
        txtStatus = findViewById(R.id.txtStatus);
        txtTokens = findViewById(R.id.txtTokens);
        txtMode = findViewById(R.id.txtMode);
        txtTheme = findViewById(R.id.txtTheme);
        txtGreeting = findViewById(R.id.txtGreeting);
        txtSelModel = findViewById(R.id.txtSelModel);
        txtTest = findViewById(R.id.txtTest);
        txtFilesPath = findViewById(R.id.txtFilesPath);
        statusStrip = findViewById(R.id.statusStrip);
        emptyState = findViewById(R.id.emptyState);
        btnSend = findViewById(R.id.btnSend);
        imgMode = findViewById(R.id.imgMode);
        imgTheme = findViewById(R.id.imgTheme);
        etInput = findViewById(R.id.etInput);
        etApiKey = findViewById(R.id.etApiKey);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        lblBaseUrl = findViewById(R.id.lblBaseUrl);
        etSessionSearch = findViewById(R.id.etSessionSearch);
        etNewTodo = findViewById(R.id.etNewTodo);
        chatList = findViewById(R.id.chatList);
        fileList = findViewById(R.id.fileList);
        todoList = findViewById(R.id.todoList);
        sessionList = findViewById(R.id.sessionList);
        pageFiles = findViewById(R.id.pageFiles);
        pageTodo = findViewById(R.id.pageTodo);
        pageSettings = findViewById(R.id.pageSettings);
        pageTerminal = findViewById(R.id.pageTerminal);
        pageChat = findViewById(R.id.pageChat);
        btnProvider = findViewById(R.id.btnProvider);
        txtSbStatus = findViewById(R.id.txtSbStatus);
        tvTermOut = findViewById(R.id.tvTermOut);
        etTermInput = findViewById(R.id.etTermInput);
        scrollTerm = findViewById(R.id.scrollTerm);
        // PERBAIKAN FORCE CLOSE v2.1.0: TextView biasa tidak punya buffer
        // Editable — getEditableText() mengembalikan null sehingga append teks
        // berwarna langsung NPE begitu halaman Terminal dibuka. Paksa buffer
        // EDITABLE sejak awal (perilaku ini juga diset ulang setiap setText).
        tvTermOut.setText("", TextView.BufferType.EDITABLE);
        attachRow = findViewById(R.id.attachRow);
        txtAttachName = findViewById(R.id.txtAttachName);
        findViewById(R.id.btnAttachRemove).setOnClickListener(v -> clearAttachment());
        File[] wsList = workspace.listFiles();
        txtWsName.setText(workspace.getName() + " · " + (wsList == null ? 0 : wsList.length) + " item");
    }

    private void bindListeners() {
        findViewById(R.id.btnMenu).setOnClickListener(v -> openDrawer());
        findViewById(R.id.btnModel).setOnClickListener(v -> showModelPicker());
        findViewById(R.id.btnMode).setOnClickListener(v -> showModePicker());
        findViewById(R.id.btnNewTask).setOnClickListener(v -> newTask());
        findViewById(R.id.btnTheme).setOnClickListener(v -> {
            if (busy) { toast("Hentikan agent dulu"); return; }
            Prefs.set(this, "theme_mode", btnThemeTag);
            recreate();
        });
        findViewById(R.id.scrim).setOnClickListener(v -> closeDrawer());
        findViewById(R.id.btnDrawerSettings).setOnClickListener(v -> {
            switchPage(4);
            closeDrawer();
        });
        etSessionSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { refreshSessions(); }
        });

        // Validasi koneksi BYOK otomatis (debounce 800ms ala Kai 9000)
        TextWatcher autoValidate = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { scheduleValidation(); }
        };
        etApiKey.addTextChangedListener(autoValidate);
        etBaseUrl.addTextChangedListener(autoValidate);

        findViewById(R.id.btnProvider).setOnClickListener(v -> showProviderPicker());

        // Sandbox proot (halaman Berkas)
        findViewById(R.id.btnSbInstall).setOnClickListener(v -> sandboxInstallFlow());
        findViewById(R.id.btnSbToggle).setOnClickListener(v -> sandboxToggle());
        findViewById(R.id.btnSbRemove).setOnClickListener(v -> sandboxRemoveFlow());

        btnSend.setOnClickListener(v -> {
            if (busy) {
                engine.cancel();
                toast("Dihentikan");
            } else {
                send();
            }
        });
        findViewById(R.id.btnAttach).setOnClickListener(v -> pickAttachment());
        findViewById(R.id.sug1).setOnClickListener(v -> fillSuggest("Buatkan aplikasi kalkulator sederhana (HTML+JS) di workspace ini."));
        findViewById(R.id.sug2).setOnClickListener(v -> fillSuggest("Buat halaman web portofolio pribadi yang bagus."));
        findViewById(R.id.sug3).setOnClickListener(v -> fillSuggest("Jelaskan isi workspace saya dan berikan ringkasan."));
        findViewById(R.id.sug4).setOnClickListener(v -> fillSuggest("Periksa dan rapikan semua file kode di workspace."));

        findViewById(R.id.navChat).setOnClickListener(v -> switchPage(0));
        findViewById(R.id.navFiles).setOnClickListener(v -> switchPage(1));
        findViewById(R.id.navTerminal).setOnClickListener(v -> switchPage(2));
        findViewById(R.id.navTodo).setOnClickListener(v -> switchPage(3));
        findViewById(R.id.navSettings).setOnClickListener(v -> switchPage(4));

        // Berkas
        findViewById(R.id.btnRefreshFiles).setOnClickListener(v -> { filesAdapter.reload(currentSub); refreshHeader(); });
        txtFilesPath.setOnClickListener(v -> {
            if (!currentSub.isEmpty()) {
                File cur = new File(workspace, currentSub);
                File parent = cur.getParentFile();
                currentSub = parent == null || parent.equals(workspace) ? "" : workspace.getName().equals(parent.getName()) ? "" : workspace.toPath().relativize(parent.toPath()).toString();
                filesAdapter.reload(currentSub);
                refreshHeader();
            }
        });
        fileList.setOnItemClickListener((p, v, pos, id) -> {
            File f = filesAdapter.items.get(pos);
            if (f.isDirectory()) {
                currentSub = currentSub.isEmpty() ? f.getName() : currentSub + "/" + f.getName();
                filesAdapter.reload(currentSub);
                refreshHeader();
            } else {
                editFileDialog(f);
            }
        });
        fileList.setOnItemLongClickListener((p, v, pos, id) -> {
            File f = filesAdapter.items.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("Hapus " + f.getName() + "?")
                    .setPositiveButton("Hapus", (d, w) -> {
                        deleteRec(f);
                        filesAdapter.reload(currentSub);
                        toast("Dihapus");
                    })
                    .setNegativeButton("Batal", null).show();
            return true;
        });

        // Todo
        findViewById(R.id.btnAddTodo).setOnClickListener(v -> {
            String t = etNewTodo.getText().toString().trim();
            if (!t.isEmpty()) {
                TodoStore.add(this, t);
                etNewTodo.setText("");
                todoAdapter = new TodoAdapter(TodoStore.load(this), getLayoutInflater(),
                        col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest),
                        col(R.attr.cSuccess), col(R.attr.cWarning), col(R.attr.cDestructive));
                todoList.setAdapter(todoAdapter);
            }
        });
        findViewById(R.id.btnClearDone).setOnClickListener(v -> {
            TodoStore.clearDone(this);
            todoList.setAdapter(new TodoAdapter(TodoStore.load(this), getLayoutInflater(),
                    col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest),
                    col(R.attr.cSuccess), col(R.attr.cWarning), col(R.attr.cDestructive)));
        });
        todoList.setOnItemClickListener((p, v, pos, id) -> {
            TodoStore.cycle(this, pos);
            todoList.setAdapter(new TodoAdapter(TodoStore.load(this), getLayoutInflater(),
                    col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest),
                    col(R.attr.cSuccess), col(R.attr.cWarning), col(R.attr.cDestructive)));
        });
        todoList.setOnItemLongClickListener((p, v, pos, id) -> {
            TodoStore.remove(this, pos);
            todoList.setAdapter(new TodoAdapter(TodoStore.load(this), getLayoutInflater(),
                    col(R.attr.cFg), col(R.attr.cFgSubtle), col(R.attr.cFgSubtlest),
                    col(R.attr.cSuccess), col(R.attr.cWarning), col(R.attr.cDestructive)));
            return true;
        });

        // Terminal
        findViewById(R.id.btnTermSend).setOnClickListener(v -> runTermCommand());
        findViewById(R.id.btnTermHist).setOnClickListener(v -> termHistNext());
        findViewById(R.id.btnTermHist).setOnLongClickListener(v -> { termHistPrev(); return true; });
        findViewById(R.id.btnTermClear).setOnClickListener(v -> {
            tvTermOut.setText("", TextView.BufferType.EDITABLE); // jaga buffer Editable!
            termBanner(false);
        });
        findViewById(R.id.btnTermRestart).setOnClickListener(v -> {
            if (termShell != null) termShell.kill();
            termShell = new ShellSession(workspace);
            tvTermOut.setText("", TextView.BufferType.EDITABLE); // jaga buffer Editable!
            termBanner(true);
            toast("Sesi shell baru dimulai");
        });
        etTermInput.setOnEditorActionListener((tv, actionId, ev) -> {
            runTermCommand();
            return true;
        });
        bindTermChip(R.id.termChipLs);
        bindTermChip(R.id.termChipPwd);
        bindTermChip(R.id.termChipDf);
        bindTermChip(R.id.termChipPs);
        bindTermChip(R.id.termChipDate);
        bindTermChip(R.id.termChipCpu);

        // Setelan
        findViewById(R.id.btnPickModel).setOnClickListener(v -> showModelPicker());
        findViewById(R.id.btnTest).setOnClickListener(v -> testConnection());
    }

    /* ================================ PAGES ================================ */

    private void switchPage(int idx) {
        activePage = idx;
        pageChat.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageFiles.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageTerminal.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        pageTodo.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(idx == 4 ? View.VISIBLE : View.GONE);

        int on = col(R.attr.cFg), off = col(R.attr.cFgSubtle);
        tint(R.id.navChatIcon, idx == 0 ? on : off);       textCol(R.id.navChatText, idx == 0 ? on : off);
        tint(R.id.navFilesIcon, idx == 1 ? on : off);      textCol(R.id.navFilesText, idx == 1 ? on : off);
        tint(R.id.navTerminalIcon, idx == 2 ? on : off);   textCol(R.id.navTerminalText, idx == 2 ? on : off);
        tint(R.id.navTodoIcon, idx == 3 ? on : off);       textCol(R.id.navTodoText, idx == 3 ? on : off);
        tint(R.id.navSettingsIcon, idx == 4 ? on : off);   textCol(R.id.navSettingsText, idx == 4 ? on : off);
        if (idx == 1) { filesAdapter.reload(currentSub); refreshHeader(); refreshSandboxCard(); }
        if (idx == 2) termEnsureStarted();
    }

    private void tint(int id, int color) {
        View v = findViewById(id);
        if (v instanceof ImageView) ((ImageView) v).setColorFilter(color);
    }

    /* =============================== TERMINAL =============================== */

    // ⚠ Palet terminal TETAP: kotak terminal selalu gelap (#171717) di kedua
    // tema, maka teksnya WAJIB selalu terang. Memakai ?attr (cCodeFg dsb)
    // membuat teks gelap di atas kotak gelap saat tema Terang = output
    // "tidak terlihat" (bug v2.3.1).
    private static final int TERM_FG     = 0xFFD4D4D4; // output utama
    private static final int TERM_GREEN  = 0xFF4ADE80; // banner & prompt $
    private static final int TERM_SUBTLE = 0xFF9CA3AF; // tips/status redup
    private static final int TERM_RED    = 0xFFFF6B6B; // error / exit != 0

    private void termEnsureStarted() {
        if (termShell == null) termShell = new ShellSession(workspace);
        if (!termShell.isAlive() && tvTermOut.length() == 0) {
            try {
                termShell.start();
                termBanner(true);
            } catch (Exception e) {
                termBanner(false);
                termAppend("(gagal menyalakan shell: " + e.getMessage() + ")\n",
                        TERM_RED);
            }
        }
    }

    private void termBanner(boolean withTips) {
        boolean sb = Sandbox.on(this);
        termAppend(sb
                ? "ZCode Terminal — sandbox Alpine Linux (proot) · proyek = /workspace\n"
                : "ZCode Terminal — sh (toybox) Android\n", TERM_GREEN);
        if (withTips)
            termAppend(sb
                    ? "Contoh: apk add git python3 · ls -la · df -h — cwd tersimpan antar perintah.\n\n"
                    : "Direktori kerja: workspace proyek. Contoh: ls -la · cat berkas.txt · df -h\n\n",
                    TERM_SUBTLE);
    }

    private void termAppend(CharSequence s, int color) {
        try {
            android.text.Editable e = tvTermOut.getEditableText();
            if (e == null) {
                // Fallback: paksa buffer editable lalu lanjut (anti-crash).
                tvTermOut.setText(tvTermOut.getText(), TextView.BufferType.EDITABLE);
                e = tvTermOut.getEditableText();
                if (e == null) return;
            }
            int start = e.length();
            e.append(s);
            e.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, e.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Jaga memori: buang awal bila terlalu panjang
            if (e.length() > 250_000) e.delete(0, e.length() - 250_000);
            scrollTerm.post(() -> {
                try { scrollTerm.fullScroll(View.FOCUS_DOWN); } catch (Exception ignore) { }
            });
        } catch (Throwable ignore) { // UI tidak boleh mati karena tampilan terminal
        }
    }

    private void bindTermChip(int id) {
        View v = findViewById(id);
        if (v instanceof TextView)
            v.setOnClickListener(c -> {
                etTermInput.setText(((TextView) v).getText().toString());
                runTermCommand();
            });
    }

    private void runTermCommand() {
        String cmd = etTermInput.getText().toString().trim();
        if (cmd.isEmpty() || termBusy) return;
        etTermInput.setText("");
        hideKeyboardView(etTermInput);

        termEnsureStarted();
        termShell.history.add(cmd);
        termShell.historyCursor = termShell.history.size();

        // Sandbox aktif → perintah dijalankan di dalam rootfs Alpine
        final boolean sb = Sandbox.on(this);
        final String toRun = sb ? Sandbox.wrap(this, cmd, workspace, 120_000) : cmd;

        termAppend("$ " + cmd + "\n", TERM_GREEN);
        int runStart = tvTermOut.length();
        termAppend("menjalankan…\n", TERM_SUBTLE);
        termBusy = true;

        final ShellSession sh = termShell;
        new Thread(() -> {
            ShellSession.Result r;
            try {
                r = sh.run(toRun, sb ? 150_000 : 120_000);
            } catch (Throwable e) { // Throwable agar termBusy selalu pulih
                r = new ShellSession.Result("(galat: " + e + ")", 1);
            }
            final ShellSession.Result rr = r;
            ui.post(() -> {
                try {
                    // Hapus baris "menjalankan…" (aman-batas; teks bisa terpotong
                    // oleh pembersih 250rb karakter di tengah jalan)
                    android.text.Editable e = tvTermOut.getEditableText();
                    if (e != null && runStart >= 0 && runStart <= e.length()) {
                        int nl = e.toString().indexOf('\n', runStart);
                        if (nl >= runStart && nl + 1 <= e.length())
                            e.delete(runStart, nl + 1);
                    }
                    termAppend(rr.output, TERM_FG);
                    if (!rr.ok())
                        termAppend("\n[exit " + rr.exitCode + "]\n", TERM_RED);
                    else
                        termAppend("\n", TERM_FG);
                } catch (Throwable ignore) { // jangan biarkan UI thread mati
                }
                termBusy = false;
            });
        }, "zcode-term").start();
    }

    /** Tombol ↑: perintah berikutnya-mundur dalam riwayat. Tahan: maju. */
    private void termHistPrev() {
        if (termShell == null || termShell.history.isEmpty()) return;
        if (termShell.historyCursor > 0) termShell.historyCursor--;
        if (termShell.historyCursor >= 0 && termShell.historyCursor < termShell.history.size())
            etTermInput.setText(termShell.history.get(termShell.historyCursor));
    }

    private void termHistNext() {
        if (termShell == null || termShell.history.isEmpty()) return;
        if (termShell.historyCursor < termShell.history.size() - 1) {
            termShell.historyCursor++;
            etTermInput.setText(termShell.history.get(termShell.historyCursor));
        } else {
            termShell.historyCursor = termShell.history.size();
            etTermInput.setText("");
        }
    }

    private void textCol(int id, int color) {
        View v = findViewById(id);
        if (v instanceof TextView) ((TextView) v).setTextColor(color);
    }

    /* ============================ SANDBOX PROOT ============================ */

    /** Segarkan kartu Sandbox Linux di halaman Berkas. */
    private void refreshSandboxCard() {
        if (txtSbStatus == null) return;
        try {
            boolean ready = Sandbox.isReady(this);
            txtSbStatus.setText(Sandbox.statusText(this));
            txtSbStatus.setTextColor(ready ? col(R.attr.cSuccess) : col(R.attr.cFgSubtlest));
            TextView install = findViewById(R.id.btnSbInstall);
            install.setText(ready ? "Pasang ulang" : "Pasang sandbox");
            TextView toggle = findViewById(R.id.btnSbToggle);
            toggle.setText(Sandbox.enabled(this) ? "Aktif: Ya" : "Aktif: Tidak");
            toggle.setEnabled(ready);
            toggle.setAlpha(ready ? 1f : 0.45f);
        } catch (Throwable ignore) { // kartu tidak boleh membuat aplikasi mati
        }
    }

    private void sandboxInstallFlow() {
        if (busy) { toast("Hentikan agent dulu"); return; }
        if (Sandbox.isReady(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Pasang ulang sandbox")
                    .setMessage("Rootfs akan ditulis ulang — paket yang terinstal di sandbox (git, python3, dst) hilang. Lanjutkan?")
                    .setPositiveButton("Ya, pasang ulang", (d, w) -> sandboxInstallStart())
                    .setNegativeButton("Batal", null)
                    .show();
        } else {
            sandboxInstallStart();
        }
    }

    private void sandboxInstallStart() {
        try {
            android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
            pd.setTitle("Menyiapkan sandbox");
            pd.setMessage("Memulai…");
            pd.setIndeterminate(true);
            pd.setCanceledOnTouchOutside(false);
            pd.show();
            Sandbox.install(this, new Sandbox.Cb() {
                @Override public void onProgress(String msg) {
                    ui.post(() -> { try { pd.setMessage(msg); } catch (Exception ignore) { } });
                }

                @Override public void onDone(boolean ok, String msg) {
                    ui.post(() -> {
                        try { pd.dismiss(); } catch (Exception ignore) { }
                        toast(msg);
                        refreshSandboxCard();
                        if (ok) termBanner(false); // perbarui judul terminal
                    });
                }
            });
        } catch (Throwable t) {
            toast("Gagal menyiapkan sandbox: " + t.getMessage());
        }
    }

    private void sandboxToggle() {
        if (!Sandbox.isReady(this)) { toast("Pasang sandbox dulu"); return; }
        boolean nv = !Sandbox.enabled(this);
        Sandbox.setEnabled(this, nv);
        refreshSandboxCard();
        toast(nv ? "Sandbox AKTIF — Bash & Terminal berjalan di Alpine Linux"
                 : "Sandbox nonaktif — kembali ke shell toybox Android");
        termBanner(false);
    }

    private void sandboxRemoveFlow() {
        if (!Sandbox.isReady(this)) { toast("Sandbox belum terpasang"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Hapus sandbox")
                .setMessage("Hapus proot & rootfs Alpine (±10 MB)? Paket yang terinstal di sandbox ikut hilang.")
                .setPositiveButton("Hapus", (d, w) -> {
                    Sandbox.remove(MainActivity.this);
                    refreshSandboxCard();
                    termBanner(false);
                    toast("Sandbox dihapus");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /* ============================== CHAT & ENGINE ============================== */

    private void send() {
        final String text = etInput.getText().toString().trim();
        if (text.isEmpty() && pendingImageDataUrl == null) return;

        // Perintah slash ala ZCode PC — dieksekusi lokal, tidak dikirim ke model
        if (text.startsWith("/")) {
            if (handleSlash(text)) { etInput.setText(""); return; }
        }
        if (text.isEmpty()) return;
        etInput.setText("");
        hideKeyboard();

        if (Prefs.activeApiKey(this).isEmpty()) {
            addNote("API Key belum diisi. Buka Setelan → Penyedia Model.");
            switchPage(4);
            return;
        }

        final String img = pendingImageDataUrl;
        final String imgName = pendingImageName;
        clearAttachment();

        ChatItem u = new ChatItem(ChatItem.TYPE_USER);
        u.text = text + (img != null ? "\n\n🖼 [gambar dilampirkan: " + imgName + "]" : "");
        chatItems.add(u);
        refreshEmptyState();
        chatAdapter.notifyDataSetChanged();
        scrollBottom();

        startEngine(text, img);
    }

    /** Kosongkan chip lampiran gambar. */
    private void clearAttachment() {
        pendingImageDataUrl = null;
        pendingImageName = "";
        if (attachRow != null) attachRow.setVisibility(View.GONE);
    }

    private void startEngine(String userText) { startEngine(userText, null); }

    private void startEngine(String userText, String imageDataUrl) {
        busy = true;
        startedAt = System.currentTimeMillis();
        tokensP = 0; tokensC = 0;
        currentAssistant = null;
        toolCards.clear();

        LlmClient client = new LlmClient(Prefs.activeBaseUrl(this),
                Prefs.activeApiKey(this), Prefs.activeModel(this));
        engine.setClient(client);
        setSendState(true);
        statusStrip.setVisibility(View.VISIBLE);
        statusBase = "Berpikir…";
        txtStatus.setText(statusBase);
        startTimer();
        refreshEmptyState();

        // Judul sesi dari pesan pertama
        String title = null;
        if (chatItems.size() <= 1) {
            if (userText == null || userText.trim().isEmpty()) title = "Analisis gambar";
            else {
                title = userText.length() > 42 ? userText.substring(0, 42) + "…" : userText;
                if (title.trim().isEmpty()) title = "Tugas baru";
            }
        }
        final String t = title;
        engine.send(userText, imageDataUrl);
        saveSession(t);
        refreshSessions();
    }

    /* ============================ SLASH COMMANDS ============================ */

    /** Perintah slash ala ZCode PC. Kembalikan true bila teks dikonsumsi. */
    private boolean handleSlash(String raw) {
        String[] parts = raw.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(java.util.Locale.ROOT);
        switch (cmd) {
            case "/help": case "/bantuan":
                showSlashHelp();
                return true;
            case "/baru": case "/new":
                if (busy) { toast("Hentikan agent dulu"); return true; }
                newTask();
                return true;
            case "/bersihkan": case "/clear":
                if (busy) { toast("Hentikan agent dulu"); return true; }
                chatItems.clear();
                engine.replaceHistory(new JSONArray());
                refreshEmptyState();
                addNote("🧹 Konteks agent dikosongkan — mulai percakapan baru.");
                chatAdapter.notifyDataSetChanged();
                scrollBottom();
                saveSession(null);
                return true;
            case "/ringkas": case "/compact":
                if (busy) { toast("Hentikan agent dulu"); return true; }
                engine.compactNow();
                addNote("📦 Konteks dipadatkan — riwayat lama dibuang agar hemat token.");
                chatAdapter.notifyDataSetChanged();
                scrollBottom();
                return true;
            case "/model":
                showModelPicker();
                return true;
            case "/mode":
                showModePicker();
                return true;
            case "/init":
                initAgentsMd();
                return true;
            case "/sandbox":
                showSandboxDialog();
                return true;
            case "/setelan": case "/key": case "/api":
                switchPage(4);
                return true;
            case "/terminal":
                switchPage(2);
                return true;
            case "/berkas": case "/files":
                switchPage(1);
                return true;
            default:
                addNote("Perintah tidak dikenali: " + cmd + " — ketik /help untuk daftar.");
                chatAdapter.notifyDataSetChanged();
                scrollBottom();
                return true;
        }
    }

    private void showSlashHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Perintah Slash ZCode")
                .setMessage(
                        "/help        — daftar perintah\n"
                      + "/baru        — mulai tugas baru\n"
                      + "/bersihkan   — kosongkan konteks agent\n"
                      + "/ringkas     — padatkan konteks (hemat token)\n"
                      + "/model       — ganti model\n"
                      + "/mode        — ganti mode agent\n"
                      + "/init        — buat AGENTS.md (memori proyek)\n"
                      + "/sandbox     — status Sandbox Linux (proot)\n"
                      + "/setelan     — buka Setelan / API Key\n"
                      + "/terminal    — buka Terminal\n"
                      + "/berkas      — buka halaman Berkas\n\n"
                      + "Tips: tombol 📎 juga bisa melampirkan GAMBAR\n"
                      + "(gunakan model vision, mis. glm-4v-flash).")
                .setPositiveButton("Mengerti", null)
                .show();
    }

    /** /init — buat AGENTS.md (memori proyek ala ZCode Desktop). */
    private void initAgentsMd() {
        try {
            File ag = new File(workspace, "AGENTS.md");
            if (ag.exists()) {
                addNote("AGENTS.md sudah ada — edit lewat halaman Berkas.");
                chatAdapter.notifyDataSetChanged();
                scrollBottom();
                return;
            }
            String tpl = "# Memori Proyek (dibuat oleh /init)\n\n"
                    + "- Tujuan proyek: \n- Bahasa/framework: \n- Perintah penting: \n- Gaya kode: \n\n"
                    + "Tuliskan preferensi tetap di sini — ZCode membacanya otomatis tiap ronde.\n";
            FileOutputStream fo = new FileOutputStream(ag);
            fo.write(tpl.getBytes(StandardCharsets.UTF_8));
            fo.close();
            addNote("📄 AGENTS.md dibuat di workspace — isi preferensi proyek; ZCode membacanya otomatis.");
            filesAdapter.reload(currentSub);
            chatAdapter.notifyDataSetChanged();
            scrollBottom();
        } catch (Exception e) {
            toast("Gagal membuat AGENTS.md: " + e.getMessage());
        }
    }

    /** /sandbox — dialog status + pintasan ke kartu sandbox. */
    private void showSandboxDialog() {
        boolean ready = Sandbox.isReady(this);
        String msg = ready
                ? ("Status: " + Sandbox.statusText(this)
                  + "\n\nBash agent & Terminal berjalan di Alpine Linux dengan akses ke /workspace.")
                : "Sandbox belum terpasang. Pasang dari kartu Sandbox Linux di halaman Berkas.";
        new AlertDialog.Builder(this)
                .setTitle("Sandbox Linux (proot)")
                .setMessage(msg)
                .setPositiveButton(ready ? "Buka halaman Berkas" : "Pasang sekarang", (d, w) -> {
                    switchPage(1);
                    if (!ready) sandboxInstallStart();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private AgentEngine.Callbacks engineCallbacks() {
        return new AgentEngine.Callbacks() {
            @Override public void onDelta(String piece) {
                if (currentAssistant == null) {
                    currentAssistant = new ChatItem(ChatItem.TYPE_ASSISTANT);
                    chatItems.add(currentAssistant);
                }
                currentAssistant.text += piece;
                throttledNotify();
            }

            @Override public void onToolStart(String callId, String name, String detail) {
                flushAssistant();
                ChatItem t = new ChatItem(ChatItem.TYPE_TOOL);
                t.toolName = name;
                t.toolDetail = detail;
                t.toolStatus = ChatItem.ST_RUNNING;
                toolCards.put(callId, t);
                chatItems.add(t);
                statusBase = detail.isEmpty() ? "Menjalankan " + name : detail;
                chatAdapter.notifyDataSetChanged();
                scrollBottom();
            }

            @Override public void onToolEnd(String callId, int status, String output) {
                ChatItem t = toolCards.get(callId);
                if (t != null) {
                    t.toolStatus = status;
                    t.text = output;
                }
                chatAdapter.notifyDataSetChanged();
                saveSession(null);
            }

            @Override public void onUsage(int pt, int ct) {
                tokensP = pt; tokensC = ct;
                txtTokens.setText("· " + fmtTokens(tokensP + tokensC) + " token");
            }

            @Override public void onStatus(String s) {
                statusBase = s;
                txtStatus.setText(s);
            }

            @Override public void onError(String message) {
                flushAssistant();
                addNote("⚠ " + message);
                chatAdapter.notifyDataSetChanged();
                scrollBottom();
            }

            @Override public void onDone(String reason) {
                busy = false;
                stopTimer();
                statusStrip.setVisibility(View.GONE);
                setSendState(false);
                flushAssistant();
                chatAdapter.notifyDataSetChanged();
                saveSession(null);
                refreshSessions();
                refreshEmptyState();
                filesAdapter.reload(currentSub);
            }
        };
    }

    private void flushAssistant() {
        if (currentAssistant != null && currentAssistant.text.isEmpty()) {
            chatItems.remove(currentAssistant);
        }
        currentAssistant = null;
    }

    private void throttledNotify() {
        long now = System.currentTimeMillis();
        if (now - lastNotify > 140) {
            lastNotify = now;
            chatAdapter.notifyDataSetChanged();
            scrollBottom();
        } else {
            ui.removeCallbacks(notifyRun);
            ui.postDelayed(notifyRun, 160);
        }
    }

    private final Runnable notifyRun = new Runnable() {
        @Override public void run() {
            chatAdapter.notifyDataSetChanged();
            scrollBottom();
        }
    };

    private void scrollBottom() {
        chatList.post(() -> chatList.setSelection(chatItems.size() - 1));
    }

    private void addNote(String s) {
        ChatItem n = new ChatItem(ChatItem.TYPE_NOTE);
        n.text = s;
        chatItems.add(n);
    }

    private void refreshEmptyState() {
        boolean empty = chatItems.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) greeting();
    }

    private void greeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String g;
        if (h >= 4 && h < 10) g = "Pagi, ada yang bisa saya bantu?";
        else if (h >= 10 && h < 15) g = "Siang! Serahkan sisanya pada saya.";
        else if (h >= 15 && h < 18) g = "Sore, apa yang bisa saya kerjakan?";
        else if (h >= 18 && h < 22) g = "Malam, kerja bagus hari ini";
        else g = "Sudah malam—jangan lupa istirahat.";
        txtGreeting.setText(g);
    }

    private void fillSuggest(String s) {
        etInput.setText(s);
        etInput.requestFocus();
        etInput.setSelection(s.length());
    }

    private void setSendState(boolean running) {
        if (running) {
            btnSend.setImageResource(R.drawable.ic_stop);
            btnSend.setColorFilter(col(R.attr.cDestructive));
            btnSend.setBackgroundResource(R.drawable.bg_chip);
        } else {
            btnSend.setImageResource(R.drawable.ic_arrow_up);
            btnSend.setColorFilter(col(R.attr.cBrandFg));
            btnSend.setBackgroundResource(R.drawable.bg_send);
        }
    }

    private void startTimer() {
        timerRun = new Runnable() {
            @Override public void run() {
                if (!busy) return;
                long d = (System.currentTimeMillis() - startedAt) / 1000;
                txtStatus.setText(statusBase + " · " + d + " dtk");
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(timerRun, 1000);
    }

    private void stopTimer() {
        if (timerRun != null) ui.removeCallbacks(timerRun);
    }

    private static String fmtTokens(int t) {
        if (t >= 1000) {
            double k = t / 1000.0;
            return String.format(java.util.Locale.US, "%.1frb", k).replace(".", ",");
        }
        return String.valueOf(t);
    }

    private void hideKeyboard() {
        hideKeyboardView(etInput);
    }

    private void hideKeyboardView(View v) {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignore) { }
    }

    /** Catat crash tak tertangkap ke files/crash-log.txt (maks ±40KB terakhir). */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            appendCrashFile("uncaught", e);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Tambah satu laporan error ke files/crash-log.txt (paling baru di atas). */
    private void appendCrashFile(String what, Throwable e) {
        try {
            File f = new File(getFilesDir(), "crash-log.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(new java.util.Date())
                    .append(" — ").append(what)
                    .append(" thread=").append(Thread.currentThread().getName()).append('\n');
            sb.append(android.util.Log.getStackTraceString(e)).append("\n\n");
            String old = "";
            try {
                FileInputStream fi = new FileInputStream(f);
                byte[] b = new byte[40_000];
                int n = fi.read(b); fi.close();
                if (n > 0) old = new String(b, 0, n, StandardCharsets.UTF_8);
            } catch (Exception ignore) { }
            String all = sb.toString() + old;
            if (all.length() > 40_000) all = all.substring(0, 40_000);
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(all.getBytes(StandardCharsets.UTF_8));
            fo.close();
        } catch (Exception ignore) { }
    }

    private static String readHead(File f, int max) {
        try {
            FileInputStream fi = new FileInputStream(f);
            byte[] b = new byte[max];
            int n = fi.read(b); fi.close();
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void copyText(String label, String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
        } catch (Throwable ignore) { }
    }

    /** Bila ada laporan crash dari sesi sebelumnya, tawarkan lihat/salin. */
    private void maybeOfferCrashReport() {
        try {
            final File f = new File(getFilesDir(), "crash-log.txt");
            if (!f.exists() || f.length() == 0) return;
            final String body = readHead(f, 40_000);
            String snippet = body.length() > 500 ? body.substring(0, 500) + "…" : body;
            new AlertDialog.Builder(this)
                    .setTitle("Terjadi error sebelumnya")
                    .setMessage("ZCode Mobile menemukan laporan error dari sesi sebelumnya:\n\n" + snippet)
                    .setPositiveButton("Salin laporan", (d, w) -> {
                        copyText("Laporan ZCode Mobile", body);
                        f.delete();
                        toast("Laporan disalin — tempel (paste) ke pengembang");
                    })
                    .setNeutralButton("Lihat lengkap", (d, w) -> showFullCrashLog(f))
                    .setNegativeButton("Tutup", null)
                    .show();
        } catch (Throwable ignore) { }
    }

    private void showFullCrashLog(final File f) {
        String body = readHead(f, 40_000);
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int px = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(px, px / 2, px, px / 2);
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Laporan error")
                .setView(sc)
                .setPositiveButton("Salin semua", (d, w) -> {
                    copyText("Laporan ZCode Mobile", body);
                    f.delete();
                    toast("Laporan disalin");
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    /** Layar pemulihan bila startup gagal — aplikasi tidak pernah mati tanpa UI. */
    private void showFatal(String what, Throwable t) {
        try {
            appendCrashFile(what, t);
            int px = (int) (20 * getResources().getDisplayMetrics().density);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(px, px * 2, px, px);
            box.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView ic = new TextView(this);
            ic.setText("⚠ ZCode Mobile");
            ic.setTextSize(20);
            ic.setTypeface(null, Typeface.BOLD);
            ic.setTextColor(colOr(R.attr.cDestructive, 0xFFD64545));
            box.addView(ic);

            TextView sub = new TextView(this);
            sub.setText(what + "\n\nAplikasi tidak berhasil dimulai. Coba \"Mulai ulang\"; "
                    + "bila tetap gagal, gunakan \"Perbaiki data\", lalu kirim laporan "
                    + "error (disalin otomatis) ke pengembang.");
            sub.setTextSize(14);
            sub.setTextColor(colOr(R.attr.cFg, 0xFF333333));
            sub.setPadding(0, px / 2, 0, px);
            box.addView(sub);

            TextView err = new TextView(this);
            err.setText(android.util.Log.getStackTraceString(t));
            err.setTextSize(11);
            err.setTypeface(Typeface.MONOSPACE);
            err.setTextColor(colOr(R.attr.cFgSubtle, 0xFF777777));
            ScrollView esc = new ScrollView(this);
            esc.addView(err);
            box.addView(esc, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, px, 0, 0);
            row.addView(fatalBtn("Mulai ulang", v -> recreate()));
            row.addView(fatalBtn("Perbaiki data", v -> repairAndRestart()));
            row.addView(fatalBtn("Salin laporan", v -> {
                copyText("Laporan ZCode Mobile", android.util.Log.getStackTraceString(t));
                toast("Laporan disalin");
            }));
            box.addView(row);
            setContentView(box);
        } catch (Throwable ignore) {
            try { finish(); } catch (Throwable ignored) { }
        }
    }

    private android.widget.Button fatalBtn(String label, View.OnClickListener l) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** Resolusi warna tema dengan nilai cadangan (layar pemulihan). */
    private int colOr(int attr, int def) {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        } catch (Throwable ignore) { }
        return def;
    }

    /** Bersihkan data yang berpotensi korup lalu mulai ulang aplikasi. */
    private void repairAndRestart() {
        try {
            getSharedPreferences("zcode_prefs", MODE_PRIVATE).edit().clear().commit();
            File sd = new File(getFilesDir(), "sessions");
            File[] fs = sd.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            new File(getFilesDir(), "todo.json").delete();
            toast("Data diperbaiki — memulai ulang…");
            ui.postDelayed(this::recreate, 400);
        } catch (Throwable t) {
            toast("Gagal memperbaiki: " + t);
        }
    }

    /* ================================ SUBAGENT ================================ */

    /**
     * Jalankan subagent (tool Agent) — engine kedua dengan konteks sendiri.
     * Explore dipaksa read-only (mode plan pada loop gate); general-purpose
     * mengikuti mode & izin utama. Maks 6 menit; hasil dipotong 15rb karakter.
     */
    private String runSubagent(String type, String prompt) {
        try {
            final boolean explore = !"general-purpose".equals(type);
            Tools subTools = new Tools(this, workspace);
            subTools.mode = explore ? Prefs.MODE_PLAN : tools.mode;
            subTools.bridge = tools.bridge;
            final StringBuilder out = new StringBuilder();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String fullPrompt = (explore
                    ? "Kamu adalah subagent Explore — STRIKT read-only: hanya gunakan Read, Glob, Grep, WebFetch, WebSearch, dan TodoRead. JANGAN menulis/mengedit/menghapus berkas atau menjalankan Bash. Kerjakan tugas berikut secara mandiri, lalu akhiri dengan LAPORAN ringkas dan padat.\n\n"
                    : "Kamu adalah subagent general-purpose. Kerjakan tugas berikut secara mandiri, lalu akhiri dengan LAPORAN ringkas dan padat.\n\n")
                    + prompt;
            AgentEngine sub = new AgentEngine(
                    new LlmClient(Prefs.activeBaseUrl(this), Prefs.activeApiKey(this), Prefs.activeModel(this)),
                    subTools,
                    new AgentEngine.Callbacks() {
                        @Override public void onDelta(String piece) { out.append(piece); }
                        @Override public void onToolStart(String c, String n, String d) { }
                        @Override public void onToolEnd(String c, int s, String o) { }
                        @Override public void onUsage(int p, int ct) { }
                        @Override public void onStatus(String s) { }
                        @Override public void onError(String m) { out.append("\n[galat subagent] ").append(m); }
                        @Override public void onDone(String r) { latch.countDown(); }
                    });
            sub.send(fullPrompt);
            boolean finished = false;
            try {
                finished = latch.await(6, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException ie) { }
            if (!finished) {
                sub.cancel();
                if (out.length() == 0) return "(subagent dihentikan — batas waktu 6 menit)";
            }
            String res = out.toString().trim();
            if (res.isEmpty()) return "(subagent tidak menghasilkan laporan)";
            return res;
        } catch (Throwable t) {
            return "Error: subagent gagal: " + t;
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /* ============================== MODE & MODEL ============================== */

    private void updateModeChip() {
        String m = Prefs.agentMode(this);
        txtMode.setText(modeLabel(m));
        imgMode.setImageResource(modeIcon(m));
        imgMode.setColorFilter(col(R.attr.cFgSubtle));
    }

    public static String modeLabel(String m) {
        switch (m) {
            case Prefs.MODE_EDIT: return "Ubah otomatis";
            case Prefs.MODE_PLAN: return "Mode rencana";
            case Prefs.MODE_YOLO: return "Akses penuh";
            default: return "Tanya dulu";
        }
    }

    private static int modeIcon(String m) {
        switch (m) {
            case Prefs.MODE_EDIT: return R.drawable.ic_pen;
            case Prefs.MODE_PLAN: return R.drawable.ic_target;
            case Prefs.MODE_YOLO: return R.drawable.ic_terminal;
            default: return R.drawable.ic_help;
        }
    }

    private static String modeDesc(String m) {
        switch (m) {
            case Prefs.MODE_EDIT: return "Edit berkas otomatis tanpa bertanya.";
            case Prefs.MODE_PLAN: return "Teliti kode dan sajikan rencana sebelum mengedit.";
            case Prefs.MODE_YOLO: return "Edit dan jalankan dengan lebih sedikit konfirmasi.";
            default: return "Tanyakan sebelum setiap perubahan berkas.";
        }
    }

    private void showModePicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String[] modes = {Prefs.MODE_BUILD, Prefs.MODE_EDIT, Prefs.MODE_PLAN, Prefs.MODE_YOLO};
        String cur = Prefs.agentMode(this);
        final AlertDialog[] holder = new AlertDialog[1];
        for (final String m : modes) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            int px = (int) (14 * getResources().getDisplayMetrics().density);
            row.setPadding(px, px, px, px);
            row.setBackgroundResource(R.drawable.bg_selectable);
            TextView t = new TextView(this);
            t.setText((m.equals(cur) ? "●  " : "○  ") + modeLabel(m));
            t.setTextSize(15);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(col(R.attr.cFg));
            TextView d = new TextView(this);
            d.setText(modeDesc(m));
            d.setTextSize(12);
            d.setTextColor(col(R.attr.cFgSubtle));
            row.addView(t);
            row.addView(d);
            row.setOnClickListener(v -> {
                Prefs.set(this, "agent_mode", m);
                tools.mode = m;
                updateModeChip();
                if (holder[0] != null) holder[0].dismiss();
                addNote("Mode agent: " + modeLabel(m));
                chatAdapter.notifyDataSetChanged();
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Ganti mode");
        b.setView(sc);
        holder[0] = buildShownDialog(b, sc);
    }

    /** Tampilkan dialog dengan latar kartu tema. */
    private AlertDialog buildShownDialog(AlertDialog.Builder b, View content) {
        AlertDialog dlg = b.show();
        dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        return dlg;
    }

    private void updateModelChip() {
        txtModel.setText(Prefs.prettyModel(Prefs.activeModel(this)));
    }

    private void showModelPicker() {
        String pt = Prefs.providerType(this);
        if ("zai".equals(pt)) {
            String[] free = Prefs.zaiFreeModels();
            String[] paid = Prefs.zaiPaidModels();
            List<String> all = new ArrayList<>();
            java.util.Set<String> freeSet = new java.util.HashSet<>();
            for (String s : free) { all.add(s); freeSet.add(s); }
            for (String s : paid) all.add(s);
            pickModelDialog("Pilih model · Z.ai", all, freeSet);
            return;
        }
        // Penjaga sebelum fetch — hindari error membingungkan di bagian API key
        Providers.P p = Providers.byId(pt);
        if (p.editableUrl && Prefs.baseUrlOf(this, pt).isEmpty()) {
            toast("Isi Base URL " + p.name + " dulu di Setelan");
            manualModelDialog();
            return;
        }
        if (Prefs.apiKeyOf(this, pt).isEmpty()) {
            toast("Tempel API key " + p.name + " dulu — atau ketik nama model manual");
            manualModelDialog();
            return;
        }
        // BYOK: fetch /models penyedia (ala Kai 9000)
        txtTest.setText("Memuat model dari " + p.name + "…");
        txtTest.setTextColor(col(R.attr.cFgSubtlest));
        LlmClient.fetchModels(Prefs.activeBaseUrl(this), Prefs.activeApiKey(this),
                new LlmClient.ModelsCallback() {
                    @Override public void onModels(List<String> ids) {
                        runOnUiThread(() -> {
                            if (ids.isEmpty()) {
                                toast("Daftar model kosong — ketik nama model manual");
                                manualModelDialog();
                            } else {
                                pickModelDialog("Pilih model · " + Providers.byId(Prefs.providerType(MainActivity.this)).name,
                                        ids, null);
                            }
                        });
                    }
                    @Override public void onError(String message) {
                        runOnUiThread(() -> {
                            txtTest.setText("✗ " + message);
                            txtTest.setTextColor(col(R.attr.cDestructive));
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Gagal memuat model")
                                    .setMessage(message + "\n\nAnda tetap bisa mengetik nama model secara manual.")
                                    .setPositiveButton("Ketik manual", (d, w) -> manualModelDialog())
                                    .setNegativeButton("Tutup", null)
                                    .show();
                        });
                    }
                });
    }

    /** Dialog pilih model dgn pencarian + input manual (ala model card Kai 9000). */
    private void pickModelDialog(String title, final List<String> all, final java.util.Set<String> freeSet) {
        int px = (int) (14 * getResources().getDisplayMetrics().density);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(px, px, px, 0);

        final EditText etSearch = new EditText(this);
        etSearch.setHint("Cari model…");
        etSearch.setTextSize(13);
        etSearch.setBackgroundResource(R.drawable.bg_search);
        etSearch.setPadding(px / 2, px / 3, px / 2, px / 3);
        etSearch.setTextColor(col(R.attr.cFg));
        etSearch.setHintTextColor(col(R.attr.cFgSubtlest));
        etSearch.setMaxLines(1);
        outer.addView(etSearch);

        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = new ScrollView(this);
        sc.addView(list);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380));
        outer.addView(sc, lp);

        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            String q = etSearch.getText().toString().trim().toLowerCase();
            String cur = Prefs.activeModel(this);
            int shown = 0;
            for (final String id : all) {
                if (!q.isEmpty() && !id.toLowerCase().contains(q)) continue;
                if (shown >= 120) {
                    list.addView(sectionLabel("… ketik kata kunci untuk melihat lainnya"));
                    break;
                }
                boolean free = freeSet != null && freeSet.contains(id);
                if (free && shown == 0) list.addView(sectionLabel("GRATIS"));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px, px, px, px);
                row.setBackgroundResource(R.drawable.bg_selectable);
                TextView t = new TextView(this);
                t.setText(Prefs.prettyModel(id) + (id.equals(cur) ? "  ✓" : ""));
                t.setTextSize(14);
                t.setTextColor(col(R.attr.cFg));
                t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(t);
                if (free) {
                    TextView badge = new TextView(this);
                    badge.setText("GRATIS");
                    badge.setTextSize(10);
                    badge.setTextColor(0xFF1E8A3E);
                    badge.setBackgroundResource(R.drawable.bg_badge_free);
                    int p2 = (int) (6 * getResources().getDisplayMetrics().density);
                    badge.setPadding(p2, 2, p2, 2);
                    row.addView(badge);
                }
                row.setOnClickListener(v -> {
                    Prefs.set(this, "model_" + Prefs.providerType(this), id);
                    updateModelChip();
                    txtSelModel.setText(Prefs.prettyModel(id));
                    if (holder[0] != null) holder[0].dismiss();
                    toast("Model: " + Prefs.prettyModel(id));
                });
                list.addView(row);
                shown++;
            }
            if (shown == 0) list.addView(sectionLabel("Tidak ada model yang cocok"));
        };
        etSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { rebuild[0].run(); }
        });
        rebuild[0].run();

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(title);
        b.setView(outer);
        b.setNeutralButton("Ketik manual", (d, w) -> manualModelDialog());
        holder[0] = buildShownDialog(b, outer);
    }

    /** Input model manual (custom model free-text ala Kai 9000). */
    private void manualModelDialog() {
        int px = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px, px / 2, px, 0);
        final EditText ed = new EditText(this);
        ed.setHint("mis: llama-3.3-70b-instruct");
        ed.setTextSize(13);
        ed.setBackgroundResource(R.drawable.bg_search);
        ed.setPadding(px / 2, px / 3, px / 2, px / 3);
        ed.setTextColor(col(R.attr.cFg));
        box.addView(ed);
        new AlertDialog.Builder(this)
                .setTitle("Model manual")
                .setMessage("Ketik ID model persis seperti di penyedia.")
                .setView(box)
                .setPositiveButton("Simpan", (d, w) -> {
                    String id = ed.getText().toString().trim();
                    if (!id.isEmpty()) {
                        Prefs.set(this, "model_" + Prefs.providerType(this), id);
                        updateModelChip();
                        txtSelModel.setText(Prefs.prettyModel(id));
                        toast("Model: " + id);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Pemilih penyedia BYOK — 13 preset (baseUrl + tautan API key + catatan). */
    private void showProviderPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String cur = Prefs.providerType(this);
        for (final Providers.P p : Providers.all()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            int px = (int) (14 * getResources().getDisplayMetrics().density);
            row.setPadding(px, px, px, px);
            row.setBackgroundResource(R.drawable.bg_selectable);
            TextView t = new TextView(this);
            t.setText((p.id.equals(cur) ? "●  " : "○  ") + p.name);
            t.setTextSize(15);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(col(R.attr.cFg));
            TextView d = new TextView(this);
            d.setText((p.baseUrl.isEmpty() ? "(Base URL kustom)" : p.baseUrl) + "\n" + p.note);
            d.setTextSize(11);
            d.setTextColor(col(R.attr.cFgSubtle));
            row.addView(t);
            row.addView(d);
            row.setOnClickListener(v -> {
                Prefs.set(this, "provider_type", p.id);
                refreshSettingsPanel();
                updateModelChip();
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                toast("Penyedia: " + p.name);
                if (!p.apiKeyUrl.isEmpty()) showKeyHint(p);
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("Pilih penyedia (BYOK)");
        b.setView(sc);
        dialogHolder[0] = buildShownDialog(b, sc);
    }

    /** Tawarkan membuka halaman pembuatan API key (deep-link ala Kai 9000). */
    private void showKeyHint(final Providers.P p) {
        new AlertDialog.Builder(this)
                .setTitle(p.name)
                .setMessage(p.note + "\n\nBuat API key di:\n" + p.apiKeyUrl)
                .setPositiveButton("Buka browser", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.apiKeyUrl)));
                    } catch (Exception e) {
                        copyText("Tautan API key " + p.name, p.apiKeyUrl);
                        toast("Tidak bisa membuka browser — tautan disalin");
                    }
                })
                .setNeutralButton("Salin tautan", (d, w) -> {
                    copyText("Tautan API key " + p.name, p.apiKeyUrl);
                    toast("Tautan disalin");
                })
                .setNegativeButton("Nanti", null)
                .show();
    }

    /** Segarkan panel setelan sesuai penyedia aktif (label, key, base URL, model). */
    private void refreshSettingsPanel() {
        Providers.P p = Providers.byId(Prefs.providerType(this));
        btnProvider.setText("Penyedia: " + p.name);
        etApiKey.setHint("tempel API key " + p.name);
        etApiKey.setText(Prefs.apiKeyOf(this, p.id));
        lblBaseUrl.setVisibility(p.editableUrl ? View.VISIBLE : View.GONE);
        etBaseUrl.setVisibility(p.editableUrl ? View.VISIBLE : View.GONE);
        if (p.editableUrl) etBaseUrl.setText(Prefs.baseUrlOf(this, p.id));
        txtSelModel.setText(Prefs.prettyModel(Prefs.activeModel(this)));
        txtTest.setText("");
        txtTest.setTextColor(col(R.attr.cFgSubtle));
    }

    /** Validasi koneksi otomatis (debounce 800ms) — status granular. */
    private void scheduleValidation() {
        if (settingsBinding || activePage != 4) return;
        ui.removeCallbacks(validateRun);
        ui.postDelayed(validateRun, 800);
    }

    private int validationGen = 0; // pembasmi respons basi antar ketikan

    private final Runnable validateRun = new Runnable() {
        @Override public void run() {
            persistSettings();
            String base = Prefs.activeBaseUrl(MainActivity.this);
            String key = Prefs.activeApiKey(MainActivity.this);
            final int myGen = ++validationGen;
            // PERBAIKAN BUG API KEY (v2.3.1): jangan menembak ke penyedia saat
            // data belum layak — dulu field kosong memicu fetch dengan key ""
            // sehingga muncul error mentah 401/404 ("Full error") di panel.
            if (base.isEmpty()) {
                txtTest.setText("Isi Base URL penyedia dulu — koneksi dicek otomatis");
                txtTest.setTextColor(col(R.attr.cWarning));
                return;
            }
            if (key.isEmpty()) {
                txtTest.setText("Tempel API key " + Providers.byId(Prefs.providerType(MainActivity.this)).name
                        + " — koneksi dicek otomatis saat key terisi");
                txtTest.setTextColor(col(R.attr.cFgSubtlest));
                return;
            }
            txtTest.setText("Memeriksa koneksi…");
            txtTest.setTextColor(col(R.attr.cFgSubtlest));
            LlmClient.fetchModels(base, key,
                    new LlmClient.ModelsCallback() {
                        @Override public void onModels(final List<String> ids) {
                            runOnUiThread(() -> {
                                if (myGen != validationGen) return; // respons basi
                                if (ids.isEmpty()) {
                                    txtTest.setText("✓ Terhubung — daftar model kosong (ketik nama model manual)");
                                    txtTest.setTextColor(col(R.attr.cWarning));
                                } else {
                                    txtTest.setText("✓ Terhubung — " + ids.size() + " model tersedia");
                                    txtTest.setTextColor(col(R.attr.cSuccess));
                                }
                            });
                        }

                        @Override public void onError(final String message) {
                            runOnUiThread(() -> {
                                if (myGen != validationGen) return; // respons basi
                                txtTest.setText("✗ " + message);
                                txtTest.setTextColor(col(R.attr.cDestructive));
                            });
                        }
                    });
        }
    };

    // pickFromList lama digantikan pickModelDialog (pencarian + input manual)

    private final AlertDialog[] dialogHolder = new AlertDialog[1];

    private TextView sectionLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(col(R.attr.cFgSubtlest));
        int px = (int) (14 * getResources().getDisplayMetrics().density);
        tv.setPadding(px, px / 2, px, px / 4);
        return tv;
    }

    /* ============================== SESI / DRAWER ============================== */

    private void refreshSessions() {
        List<SessionStore.Meta> all = SessionStore.list(this);
        String q = etSessionSearch.getText().toString().trim().toLowerCase();
        List<SessionStore.Meta> filtered = new ArrayList<>();
        for (SessionStore.Meta m : all) {
            if (q.isEmpty() || m.title.toLowerCase().contains(q)) filtered.add(m);
        }
        sessionAdapter = new SessionAdapter(filtered, getLayoutInflater(),
                col(R.attr.cFg), col(R.attr.cFgSubtlest), sessionId);
        sessionAdapter.listener = new SessionAdapter.SessionListener() {
            @Override public void onOpen(SessionStore.Meta m) { openSession(m.id); }
            @Override public void onDelete(SessionStore.Meta m) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Hapus tugas?")
                        .setMessage(m.title)
                        .setPositiveButton("Hapus", (d, w) -> {
                            SessionStore.delete(MainActivity.this, m.id);
                            if (m.id.equals(sessionId)) loadCurrentOrNew();
                            refreshSessions();
                        })
                        .setNegativeButton("Batal", null).show();
            }
        };
        sessionList.setAdapter(sessionAdapter);
        // Tekan-lama: ganti nama tugas (fitur sesi ala desktop)
        sessionList.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= sessionAdapter.items.size()) return true;
            final SessionStore.Meta m = sessionAdapter.items.get(pos);
            int px = (int) (16 * getResources().getDisplayMetrics().density);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(px, px / 2, px, 0);
            final EditText ed = new EditText(this);
            ed.setText(m.title);
            ed.setTextSize(14);
            ed.setTextColor(col(R.attr.cFg));
            ed.setBackgroundResource(R.drawable.bg_search);
            ed.setPadding(px / 2, px / 3, px / 2, px / 3);
            box.addView(ed);
            new AlertDialog.Builder(this)
                    .setTitle("Ganti nama tugas")
                    .setView(box)
                    .setPositiveButton("Simpan", (d, w) -> {
                        SessionStore.rename(MainActivity.this, m.id, ed.getText().toString());
                        refreshSessions();
                    })
                    .setNegativeButton("Batal", null).show();
            return true;
        });
    }

    private void loadCurrentOrNew() {
        sessionId = SessionStore.currentId(this);
        if (sessionId.isEmpty()) sessionId = SessionStore.create(this, "Tugas baru");
        loadSession();
    }

    private void openSession(String id) {
        if (busy) { toast("Hentikan agent dulu"); return; }
        saveSession(null);
        sessionId = id;
        SessionStore.setCurrent(this, id);
        tools.mode = SessionStore.loadMode(this, id);
        Prefs.set(this, "agent_mode", tools.mode);
        loadSession();
        updateModeChip();
        closeDrawer();
    }

    private void loadSession() {
        chatItems.clear();
        JSONArray saved = SessionStore.loadItems(this, sessionId);
        for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i);
            if (o != null) chatItems.add(ChatItem.fromJson(o));
        }
        chatAdapter.notifyDataSetChanged();
        scrollBottom();
        refreshEmptyState();
        refreshSessions();
        rebuildHistory();
    }

    /** Bangun ulang riwayat konteks model dari item chat (pesan user & assistant). */
    private void rebuildHistory() {
        JSONArray h = new JSONArray();
        for (ChatItem it : chatItems) {
            try {
                if (it.type == ChatItem.TYPE_USER && !it.text.isEmpty()) {
                    h.put(new JSONObject().put("role", "user").put("content", it.text));
                } else if (it.type == ChatItem.TYPE_ASSISTANT && !it.text.isEmpty()) {
                    h.put(new JSONObject().put("role", "assistant").put("content", it.text));
                }
            } catch (Exception ignore) { }
        }
        engine.replaceHistory(h);
    }

    private void newTask() {
        if (busy) { toast("Hentikan agent dulu"); return; }
        saveSession(null);
        sessionId = SessionStore.create(this, "Tugas baru");
        chatItems.clear();
        chatAdapter.notifyDataSetChanged();
        refreshEmptyState();
        refreshSessions();
        rebuildHistory();
        closeDrawer();
        switchPage(0);
    }

    private void saveSession(String newTitle) {
        if (sessionId.isEmpty() || chatItems.isEmpty()) return;
        JSONArray arr = new JSONArray();
        for (ChatItem it : chatItems) arr.put(it.toJson());
        SessionStore.save(this, sessionId, arr, Prefs.agentMode(this), newTitle);
    }

    /* ============================== BRIDGE UI TOOLS ============================== */

    private Tools.UiBridge bridge() {
        return new Tools.UiBridge() {
            @Override public String askUser(final JSONArray questions) {
                final StringBuilder answers = new StringBuilder();
                final CountDownLatch latch = new CountDownLatch(1);
                runOnUiThread(() -> askSequential(questions, 0, answers, latch));
                try { latch.await(); } catch (InterruptedException ignore) { return null; }
                return answers.length() == 0 ? null : answers.toString();
            }

            @Override public String submitPlan(final String plan) {
                final CountDownLatch latch = new CountDownLatch(1);
                final java.util.concurrent.atomic.AtomicReference<String> verdict =
                        new java.util.concurrent.atomic.AtomicReference<>("rejected");
                runOnUiThread(() -> {
                    flushAssistant();
                    ChatItem p = new ChatItem(ChatItem.TYPE_PLAN);
                    p.text = plan;
                    p.planResolved = false;
                    chatItems.add(p);
                    chatAdapter.notifyDataSetChanged();
                    scrollBottom();
                    planItem = p;
                    planVerdict = verdict;
                    pendingPlanLatch = latch;
                });
                try { latch.await(); } catch (InterruptedException ignore) { }
                return verdict.get();
            }

            @Override public boolean permission(final String toolName, final String detail) {
                if (allowAllSession) return true;
                final CountDownLatch latch = new CountDownLatch(1);
                final java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean(false);
                runOnUiThread(() -> {
                    AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this);
                    b.setTitle("Izin diperlukan");
                    b.setMessage("ZCode ingin " + detail + ".\n\nIzinkan operasi ini?");
                    b.setPositiveButton("Izinkan", (d, w) -> { ok.set(true); latch.countDown(); });
                    b.setNegativeButton("Tolak", (d, w) -> latch.countDown());
                    b.setNeutralButton("Selalu izinkan (sesi ini)", (d, w) -> { allowAllSession = true; ok.set(true); latch.countDown(); });
                    AlertDialog dlg = b.show();
                    dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
                });
                try { latch.await(); } catch (InterruptedException ignore) { }
                return ok.get();
            }
        };
    }

    private ChatItem planItem;
    private java.util.concurrent.atomic.AtomicReference<String> planVerdict;
    private CountDownLatch pendingPlanLatch;

    @Override
    public void onApprove(ChatItem item) {
        item.planResolved = true;
        chatAdapter.notifyDataSetChanged();
        if (pendingPlanLatch != null && planItem == item) {
            planVerdict.set("approved");
            addNote("Rencana disetujui — agent melanjutkan ke implementasi…");
            chatAdapter.notifyDataSetChanged();
            pendingPlanLatch.countDown();
            pendingPlanLatch = null;
        }
    }

    @Override
    public void onReject(ChatItem item) {
        item.planResolved = true;
        chatAdapter.notifyDataSetChanged();
        if (pendingPlanLatch != null && planItem == item) {
            planVerdict.set("rejected");
            addNote("Rencana ditolak. Agent akan menanyakan perubahan yang diinginkan.");
            chatAdapter.notifyDataSetChanged();
            pendingPlanLatch.countDown();
            pendingPlanLatch = null;
        }
    }

    /** Tanyakan daftar pertanyaan satu per satu (AskUserQuestion). */
    private void askSequential(final JSONArray questions, final int idx,
                               final StringBuilder answers, final CountDownLatch done) {
        if (idx >= questions.length()) { done.countDown(); return; }
        JSONObject q = questions.optJSONObject(idx);
        if (q == null) { done.countDown(); return; }
        String question = q.optString("question", "");
        String header = q.optString("header", "Pertanyaan");
        JSONArray opts = q.optJSONArray("options");
        if (opts == null || opts.length() == 0) {
            answers.append(header).append(": ").append(question).append(" — (user melewati)\n");
            askSequential(questions, idx + 1, answers, done);
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < opts.length() && i < 4; i++) {
            JSONObject op = opts.optJSONObject(i);
            if (op == null) continue;
            final String label = op.optString("label", "Opsi " + (i + 1));
            String desc = op.optString("description", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            int px = (int) (14 * getResources().getDisplayMetrics().density);
            row.setPadding(px, px, px, px);
            row.setBackgroundResource(R.drawable.bg_selectable);
            TextView t = new TextView(this);
            t.setText(label);
            t.setTextSize(14);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(col(R.attr.cAsk));
            row.addView(t);
            if (!desc.isEmpty()) {
                TextView d = new TextView(this);
                d.setText(desc);
                d.setTextSize(12);
                d.setTextColor(col(R.attr.cFgSubtle));
                row.addView(d);
            }
            row.setOnClickListener(v -> {
                answers.append(header).append(": ").append(question).append("\nJawaban: ").append(label);
                if (desc.isEmpty()) answers.append("\n"); else answers.append(" — ").append(desc).append("\n");
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                askSequential(questions, idx + 1, answers, done);
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(header.isEmpty() ? "Pertanyaan" : header);
        b.setMessage(question);
        b.setNegativeButton("Lewati", (d, w) -> {
            answers.append(header).append(": ").append(question).append(" — (user melewati)\n");
            askSequential(questions, idx + 1, answers, done);
        });
        // Sisipkan daftar opsi di atas tombol
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(this);
        int px2 = (int) (20 * getResources().getDisplayMetrics().density);
        msg.setPadding(px2, px2 / 2, px2, px2 / 4);
        msg.setText(question);
        msg.setTextSize(14);
        msg.setTextColor(col(R.attr.cFg));
        outer.addView(msg);
        outer.addView(box);
        b.setMessage(null);
        b.setView(outer);
        dialogHolder[0] = buildShownDialog(b, outer);
    }

    /* ============================== ATTACHMENT ============================== */

    private static final int REQ_ATTACH = 4711;

    private void pickAttachment() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Lampirkan berkas"), REQ_ATTACH);
        } catch (Exception e) {
            toast("Tidak bisa membuka pemilih berkas");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ATTACH && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            try {
                String mime = getContentResolver().getType(uri);
                boolean isImage = (mime != null && mime.startsWith("image/"))
                        || (mime == null && (uri.getPath() == null || uri.getPath().matches("(?i).*(png|jpe?g|webp|gif|bmp)$")));
                if (isImage) { handleImageAttach(uri); return; }
            } catch (Exception ignore) { }
            try {
                String name = queryName(uri);
                java.io.InputStream is = getContentResolver().openInputStream(uri);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 120_000) break;
                }
                is.close();
                String content = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                String cur = etInput.getText().toString();
                String block = (cur.isEmpty() ? "" : cur + "\n\n") + "Konteks file \"" + name + "\":\n```\n" + content + "\n```";
                etInput.setText(block);
                etInput.requestFocus();
                etInput.setSelection(block.length());
            } catch (Exception e) {
                toast("Gagal membaca lampiran");
            }
        }
    }

    /**
     * Lampiran gambar (vision ala ZCode PC): turunkan skala ke sisi maks 768px,
     * JPEG q82, base64 → data URL — dikirim sebagai content part image_url.
     */
    private void handleImageAttach(final android.net.Uri uri) {
        toast("Menyiapkan gambar…");
        new Thread(() -> {
            try {
                String name = queryName(uri);
                // 1) ukuran asli
                android.graphics.BitmapFactory.Options ob = new android.graphics.BitmapFactory.Options();
                ob.inJustDecodeBounds = true;
                java.io.InputStream is0 = getContentResolver().openInputStream(uri);
                android.graphics.BitmapFactory.decodeStream(is0, null, ob);
                try { is0.close(); } catch (Exception ignore) { }
                if (ob.outWidth <= 0 || ob.outHeight <= 0) {
                    ui.post(() -> toast("Berkas gambar tidak valid"));
                    return;
                }
                // 2) sampling awal
                int sample = 1;
                while (Math.max(ob.outWidth, ob.outHeight) / (sample * 2) >= 768) sample *= 2;
                android.graphics.BitmapFactory.Options od = new android.graphics.BitmapFactory.Options();
                od.inSampleSize = sample;
                java.io.InputStream is1 = getContentResolver().openInputStream(uri);
                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is1, null, od);
                try { is1.close(); } catch (Exception ignore) { }
                if (bmp == null) { ui.post(() -> toast("Gagal membaca gambar")); return; }
                // 3) skala sisa → maks 768px sisi terpanjang
                int mw = bmp.getWidth(), mh = bmp.getHeight();
                int longSide = Math.max(mw, mh);
                if (longSide > 768) {
                    float sc = 768f / longSide;
                    bmp = android.graphics.Bitmap.createScaledBitmap(bmp,
                            Math.round(mw * sc), Math.round(mh * sc), true);
                }
                java.io.ByteArrayOutputStream jb = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, jb);
                bmp.recycle();
                final String dataUrl = "data:image/jpeg;base64,"
                        + android.util.Base64.encodeToString(jb.toByteArray(), android.util.Base64.NO_WRAP);
                final int kb = jb.size() / 1024;
                ui.post(() -> {
                    try {
                        pendingImageName = (name == null || name.isEmpty()) ? "gambar.jpg" : name;
                        pendingImageDataUrl = dataUrl;
                        txtAttachName.setText("🖼 " + pendingImageName + " (" + kb + " KB)");
                        attachRow.setVisibility(View.VISIBLE);
                        toast("Gambar siap — tulis pertanyaan lalu kirim");
                    } catch (Throwable ignore) { }
                });
            } catch (Exception e) {
                ui.post(() -> toast("Gagal menyiapkan gambar: " + e.getMessage()));
            }
        }, "img-attach").start();
    }

    private String queryName(android.net.Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) {
                    String s = c.getString(idx);
                    c.close();
                    return s;
                }
                c.close();
            }
        } catch (Exception ignore) { }
        return "lampiran.txt";
    }

    /* ============================== BERKAS ============================== */

    private String currentSub = "";

    private void refreshHeader() {
        txtFilesPath.setText("workspace" + (currentSub.isEmpty() ? "" : "/" + currentSub));
        txtWsName.setText(workspace.getName() + " · " + countFiles(workspace) + " berkas");
    }

    private int countFiles(File d) {
        int n = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) n += f.isDirectory() ? countFiles(f) : 1;
        return n;
    }

    private void editFileDialog(final File f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int px = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(px, px / 2, px, 0);
        TextView lbl = new TextView(this);
        lbl.setText(f.getName());
        lbl.setTextSize(13);
        lbl.setTextColor(col(R.attr.cFgSubtle));
        box.addView(lbl);
        final EditText ed = new EditText(this);
        ed.setBackgroundResource(R.drawable.bg_search);
        ed.setPadding(px / 2, px / 3, px / 2, px / 3);
        ed.setTextSize(13);
        ed.setTextColor(col(R.attr.cFg));
        ed.setTypeface(Typeface.MONOSPACE);
        ed.setMinLines(6);
        try {
            FileInputStream fis = new FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = fis.read(b)) > 0) bos.write(b, 0, n);
            fis.close();
            String all = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            ed.setText(all.length() > 60000 ? all.substring(0, 60000) : all);
        } catch (Exception ignore) { }
        box.addView(ed);
        new AlertDialog.Builder(this)
                .setTitle("Edit berkas")
                .setView(box)
                .setPositiveButton("Simpan", (d, w) -> {
                    try {
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(ed.getText().toString().getBytes(StandardCharsets.UTF_8));
                        fos.close();
                        toast("Tersimpan");
                        filesAdapter.reload(currentSub);
                    } catch (Exception ignore) { }
                })
                .setNegativeButton("Batal", null).show();
    }

    private void deleteRec(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }

    /* ============================== SETELAN ============================== */

    private void bindSettings() {
        String t = Prefs.themeMode(this);
        ((RadioButton) findViewById(R.id.rbLight)).setChecked("light".equals(t));
        ((RadioButton) findViewById(R.id.rbDark)).setChecked("dark".equals(t));
        ((RadioButton) findViewById(R.id.rbSystem)).setChecked("system".equals(t));

        settingsBinding = true;

        ((RadioGroup) findViewById(R.id.rgTheme)).setOnCheckedChangeListener((g, id) -> {
            String nt = id == R.id.rbDark ? "dark" : (id == R.id.rbSystem ? "system" : "light");
            if (nt.equals(Prefs.themeMode(this))) return;
            if (busy) {
                toast("Hentikan agent dulu");
                String cur = Prefs.themeMode(this);
                ((RadioButton) findViewById(R.id.rbLight)).setChecked("light".equals(cur));
                ((RadioButton) findViewById(R.id.rbDark)).setChecked("dark".equals(cur));
                ((RadioButton) findViewById(R.id.rbSystem)).setChecked("system".equals(cur));
                return;
            }
            Prefs.set(this, "theme_mode", nt);
            recreate();
        });

        refreshSettingsPanel();
        settingsBinding = false;
    }

    private void persistSettings() {
        String id = Prefs.providerType(this);
        Prefs.setApiKey(this, id, etApiKey.getText().toString().trim());
        if (Providers.byId(id).editableUrl)
            Prefs.setBaseUrl(this, id, etBaseUrl.getText().toString().trim());
    }

    private void testConnection() {
        persistSettings();
        String model = Prefs.activeModel(this);
        // PERBAIKAN BUG API KEY (v2.3.1): dulu model di-hardcode glm-4.5-flash,
        // jadi uji koneksi PENYEDIA LAIN selalu gagal 400/404 dengan error mentah.
        txtTest.setText(model.isEmpty()
                ? "Menguji koneksi…"
                : "Menguji koneksi & model " + Prefs.prettyModel(model) + "…");
        txtTest.setTextColor(col(R.attr.cFgSubtlest));
        LlmClient.testConnection(Prefs.activeBaseUrl(this), Prefs.activeApiKey(this), model,
                (ok, msg) -> runOnUiThread(() -> {
                    txtTest.setText(ok ? "✓ " + msg : "✗ " + msg);
                    txtTest.setTextColor(ok ? col(R.attr.cSuccess) : col(R.attr.cDestructive));
                }));
    }

    /* ============================== UTIL ============================== */
}
