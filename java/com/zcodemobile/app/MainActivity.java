package com.zcodemobile.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private boolean dark;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* Navigasi */
    private View chatPage, filesPage, todoPage, settingsPage;
    private Button navChat, navFiles, navTodo, navSettings;
    private TextView titleText, modelChip;

    /* Chat */
    private ListView chatList;
    private EditText inputText;
    private Button sendBtn;
    private ChatAdapter chatAdapter;
    private JSONArray history = new JSONArray();
    private boolean agentBusy = false;
    private AgentEngine engine;
    private final List<Integer> pendingToolCards = new ArrayList<>();

    /* Sesi */
    private long sessionId = SessionStore.newSession();

    /* Berkas */
    private ListView fileList;
    private TextView pathText;
    private FilesAdapter filesAdapter;
    private File currentDir;

    /* Tugas */
    private ListView todoList;
    private EditText todoInput;
    private Button todoAddBtn;
    private TodoAdapter todoAdapter;

    /* Setelan */
    private RadioGroup providerGroup, themeGroup;
    private RadioButton providerZai, providerCustom, themeLight, themeDark;
    private EditText apiKeyInput, baseUrlInput;
    private Spinner modelSpinner;
    private Button refreshModelsBtn, testBtn;
    private TextView testResult;
    private ArrayAdapter<String> modelAdapter;
    private List<String> modelIds = new ArrayList<>();

    private Tools tools;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = Prefs.isDarkTheme(this);
        setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File ws = new File(getFilesDir(), "workspace");
        if (!ws.exists()) ws.mkdirs();
        ensureWelcomeFile(ws);
        tools = new Tools(getApplicationContext(), ws);

        bindViews();
        setupNav();
        setupChat(ws);
        setupFiles(ws);
        setupTodo();
        setupSettings();
        setupHeaderActions();
        fillModels(null);
        refreshHeader();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHeader();
        filesAdapter.load(currentDir);
        reloadTodo();
    }

    /* ================= BINDING ================= */

    private void bindViews() {
        chatPage = findViewById(R.id.chatPage);
        filesPage = findViewById(R.id.filesPage);
        todoPage = findViewById(R.id.todoPage);
        settingsPage = findViewById(R.id.settingsPage);

        navChat = findViewById(R.id.navChat);
        navFiles = findViewById(R.id.navFiles);
        navTodo = findViewById(R.id.navTodo);
        navSettings = findViewById(R.id.navSettings);

        titleText = findViewById(R.id.titleText);
        modelChip = findViewById(R.id.modelChip);

        chatList = findViewById(R.id.chatList);
        inputText = findViewById(R.id.inputText);
        sendBtn = findViewById(R.id.sendBtn);

        fileList = findViewById(R.id.fileList);
        pathText = findViewById(R.id.pathText);

        todoList = findViewById(R.id.todoList);
        todoInput = findViewById(R.id.todoInput);
        todoAddBtn = findViewById(R.id.todoAddBtn);

        providerGroup = findViewById(R.id.providerGroup);
        themeGroup = findViewById(R.id.themeGroup);
        providerZai = findViewById(R.id.providerZai);
        providerCustom = findViewById(R.id.providerCustom);
        themeLight = findViewById(R.id.themeLight);
        themeDark = findViewById(R.id.themeDark);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        baseUrlInput = findViewById(R.id.baseUrlInput);
        modelSpinner = findViewById(R.id.modelSpinner);
        refreshModelsBtn = findViewById(R.id.refreshModelsBtn);
        testBtn = findViewById(R.id.testBtn);
        testResult = findViewById(R.id.testResult);

        paintHeader();
    }

    private void paintHeader() {
        if (dark) {
            titleText.setTextColor(Color.parseColor("#FAFAFA"));
            modelChip.setTextColor(Color.parseColor("#A3A3A3"));
        } else {
            titleText.setTextColor(Color.parseColor("#0A0A0A"));
            modelChip.setTextColor(Color.parseColor("#737373"));
        }
    }

    private void refreshHeader() {
        String type = Prefs.get(this, "provider_type", "zai");
        String prov = "custom".equals(type) ? "Custom" : "Z.ai";
        String model = Prefs.activeModel(this);
        modelChip.setText(prov + " • " + (model.isEmpty() ? "pilih model" : model));
    }

    /* ================= HEADER AKSI (sesi + model picker) ================= */

    private void setupHeaderActions() {
        modelChip.setOnClickListener(v -> showModelPicker());

        findViewById(R.id.newChatBtn).setOnClickListener(v -> newChat());

        findViewById(R.id.historyBtn).setOnClickListener(v -> showHistory());
    }

    private void showModelPicker() {
        String type = Prefs.get(this, "provider_type", "zai");
        final List<String> ids = new ArrayList<>();
        if ("custom".equals(type)) {
            String saved = Prefs.get(this, "custom_models", "");
            try {
                JSONArray arr = new JSONArray(saved);
                for (int i = 0; i < arr.length(); i++) ids.add(arr.getString(i));
            } catch (Exception ignore) { }
            if (ids.isEmpty()) {
                toast("Buka Setelan → Muat ulang daftar model dulu");
                return;
            }
        } else {
            ids.addAll(java.util.Arrays.asList(Prefs.zaiModels()));
        }
        String current = Prefs.activeModel(this);
        int sel = ids.indexOf(current);

        new AlertDialog.Builder(this)
                .setTitle(R.string.pick_model)
                .setSingleChoiceItems(ids.toArray(new String[0]), sel, (d, w) -> {
                    Prefs.set(this, "model_" + type, ids.get(w));
                    d.dismiss();
                    refreshHeader();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void newChat() {
        saveCurrentSession();
        history = new JSONArray();
        sessionId = SessionStore.newSession();
        chatAdapter.clear();
        chatAdapter.addInfo("Chat baru dimulai ✨\nApa yang ingin kita bangun hari ini?");
    }

    private void showHistory() {
        List<SessionStore.Meta> metas = SessionStore.list(this);
        if (metas.isEmpty()) { toast(getString(R.string.no_sessions)); return; }
        final List<String> titles = new ArrayList<>();
        for (SessionStore.Meta m : metas) titles.add(m.title);
        CharSequence[] arr = titles.toArray(new String[0]);
        CharSequence[] actions = {"📂 Buka", "🗑️ Hapus", "🗑️ Hapus semua"};

        new AlertDialog.Builder(this)
                .setTitle(R.string.history)
                .setItems(arr, (d, w) -> openSession(metas.get(w)))
                .setNeutralButton("Hapus semua", (d, w) -> {
                    SessionStore.deleteAll(this);
                    toast("Semua riwayat dihapus");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openSession(SessionStore.Meta m) {
        saveCurrentSession();
        history = SessionStore.loadMessages(this, m.id);
        sessionId = m.id;
        chatAdapter.clear();
        try {
            for (int i = 0; i < history.length(); i++) {
                JSONObject o = history.getJSONObject(i);
                String role = o.optString("role");
                if ("user".equals(role)) chatAdapter.addUser(o.optString("content"));
                else if ("assistant".equals(role)) {
                    String c = o.optString("content");
                    chatAdapter.addBot(c == null || c.isEmpty() ? "(tool)" : c);
                }
            }
        } catch (Exception ignore) { }
        showPage(0);
    }

    private void saveCurrentSession() {
        if (history.length() > 0) {
            String title = firstUserText();
            SessionStore.save(this, sessionId, title, history);
        }
    }

    private String firstUserText() {
        try {
            for (int i = 0; i < history.length(); i++) {
                JSONObject o = history.getJSONObject(i);
                if ("user".equals(o.optString("role"))) {
                    String t = o.optString("content", "");
                    return t.length() > 42 ? t.substring(0, 42) + "…" : t;
                }
            }
        } catch (Exception ignore) { }
        return "Chat " + sessionId;
    }

    /* ================= NAVIGASI ================= */

    private void setupNav() {
        navChat.setOnClickListener(v -> showPage(0));
        navFiles.setOnClickListener(v -> showPage(1));
        navTodo.setOnClickListener(v -> showPage(2));
        navSettings.setOnClickListener(v -> showPage(3));
        showPage(0);
    }

    private void showPage(int idx) {
        chatPage.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        filesPage.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        todoPage.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        String[] titles = {"ZCode", "Berkas", "Tugas", "Setelan"};
        titleText.setText(titles[idx]);
        String active = dark ? "#FAFAFA" : "#0A0A0A";
        String idle = dark ? "#A3A3A3" : "#737373";
        navChat.setTextColor(Color.parseColor(idx == 0 ? active : idle));
        navFiles.setTextColor(Color.parseColor(idx == 1 ? active : idle));
        navTodo.setTextColor(Color.parseColor(idx == 2 ? active : idle));
        navSettings.setTextColor(Color.parseColor(idx == 3 ? active : idle));
        if (idx == 1) filesAdapter.load(currentDir);
        if (idx == 2) reloadTodo();
    }

    /* ================= CHAT ================= */

    private void setupChat(final File ws) {
        chatAdapter = new ChatAdapter(this, dark);
        chatList.setAdapter(chatAdapter);
        chatAdapter.addInfo("Selamat datang di ZCode Mobile ⚡\n"
                + "Agent coding native di HP-mu, terinspirasi ZCode Desktop.\n\n"
                + "Coba: \"buatkan halaman web profil\" atau \"buat landing page toko\".\n"
                + "Tekan lama pesan untuk menyalin • 🕘 untuk riwayat • ＋ untuk chat baru.");

        chatList.setOnItemLongClickListener((p, v, pos, id) -> {
            String t = chatAdapter.textAt(pos);
            if (t != null && !t.isEmpty() && !"…".equals(t)) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("zcode", t));
                toast(getString(R.string.copied));
                return true;
            }
            return false;
        });

        sendBtn.setOnClickListener(v -> {
            if (agentBusy) {
                if (engine != null) engine.cancel();
                setBusy(false);
                chatAdapter.addInfo("⏹ Dihentikan.");
                return;
            }
            String text = inputText.getText().toString().trim();
            if (text.isEmpty()) return;
            String key = Prefs.activeApiKey(this);
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.error_no_key, Toast.LENGTH_LONG).show();
                showPage(3);
                return;
            }
            inputText.setText("");
            startAgent(text);
        });
    }

    private void setBusy(boolean busy) {
        agentBusy = busy;
        sendBtn.setText(busy ? R.string.stop : R.string.send);
    }

    private void startAgent(String userText) {
        try {
            history.put(new JSONObject().put("role", "user").put("content", userText));
        } catch (Exception ignore) { }
        chatAdapter.addUser(userText);
        chatAdapter.addTyping();
        scrollChat();
        setBusy(true);
        pendingToolCards.clear();

        String baseUrl = Prefs.activeBaseUrl(this);
        String key = Prefs.activeApiKey(this);
        String model = Prefs.activeModel(this);
        String sys = AgentEngine.buildSystemPrompt(tools.getWorkspace().getPath());
        engine = new AgentEngine(baseUrl, key, model, tools, sys);

        final StringBuilder lastBotBuf = new StringBuilder();

        engine.run(history, new AgentEngine.Listener() {
            @Override public void onStreamDelta(String piece) {
                ui.post(() -> {
                    lastBotBuf.append(piece);
                    chatAdapter.updateLastBot(lastBotBuf.toString());
                    scrollChat();
                });
            }
            @Override public void onToolStart(String name, String argsPreview) {
                ui.post(() -> {
                    pendingToolCards.add(chatAdapter.addTool(name, argsPreview));
                    scrollChat();
                });
            }
            @Override public void onToolResult(String name, String resultPreview, boolean ok) {
                ui.post(() -> {
                    int pos = pendingToolCards.isEmpty() ? -1 : pendingToolCards.remove(0);
                    chatAdapter.updateTool(pos, name + " → " + resultPreview, ok);
                    if ("todo_write".equals(name)) reloadTodo();
                    if ("write_file".equals(name) || "delete_path".equals(name)) filesAdapter.load(currentDir);
                    scrollChat();
                });
            }
            @Override public void onAssistantDone(String fullText) {
                ui.post(() -> {
                    String clean = fullText == null || fullText.trim().isEmpty() ? "(selesai)" : fullText.trim();
                    try { history.put(new JSONObject().put("role", "assistant").put("content", clean)); }
                    catch (Exception ignore) { }
                    chatAdapter.updateLastBot(clean);
                    setBusy(false);
                    saveCurrentSession();
                    scrollChat();
                });
            }
            @Override public void onError(String message) {
                ui.post(() -> {
                    chatAdapter.addInfo("❌ " + message);
                    setBusy(false);
                });
            }
            @Override public void onRound(int round) { }
        });
    }

    private void scrollChat() {
        chatList.post(() -> chatList.setSelection(chatAdapter.getCount() - 1));
    }

    /* ================= BERKAS ================= */

    private void setupFiles(final File ws) {
        currentDir = ws;
        filesAdapter = new FilesAdapter(getLayoutInflater());
        fileList.setAdapter(filesAdapter);
        pathText.setText(shownPath(ws));
        filesAdapter.load(ws);

        filesAdapter.setListener(new FilesAdapter.Listener() {
            @Override public void onOpenFile(File f) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".html") || n.endsWith(".htm")) fileOptions(f);
                else openEditor(f);
            }
            @Override public void onOpenFolder(File f) {
                currentDir = f;
                pathText.setText(shownPath(f));
                filesAdapter.load(f);
            }
            @Override public void onMore(File f) { fileOptions(f); }
        });

        findViewById(R.id.upBtn).setOnClickListener(v -> {
            File parent = currentDir.getParentFile();
            if (parent != null && parent.getPath().startsWith(ws.getPath())) {
                currentDir = parent;
                pathText.setText(shownPath(parent));
                filesAdapter.load(parent);
            }
        });

        findViewById(R.id.newFileBtn).setOnClickListener(v ->
                askName("Berkas baru", "contoh.html", name -> {
                    File f = new File(currentDir, name);
                    try {
                        if (!f.exists()) Files.write(f.toPath(), new byte[0]);
                        filesAdapter.load(currentDir);
                        openEditor(f);
                    } catch (Exception e) { toast("Gagal: " + e.getMessage()); }
                }));

        findViewById(R.id.newFolderBtn).setOnClickListener(v ->
                askName("Folder baru", "proyek", name -> {
                    File f = new File(currentDir, name);
                    if (f.mkdirs()) filesAdapter.load(currentDir);
                    else toast("Gagal membuat folder");
                }));
    }

    private String shownPath(File f) {
        File ws = tools.getWorkspace();
        String p = f.getPath();
        return p.equals(ws.getPath()) ? "workspace/" : p.replace(ws.getPath() + "/", "workspace/");
    }

    private interface NameCb { void onName(String name); }

    private void askName(String title, String hint, final NameCb cb) {
        final EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) cb.onName(n);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void fileOptions(final File f) {
        boolean html = f.getName().toLowerCase().endsWith(".html") || f.getName().toLowerCase().endsWith(".htm");
        List<String> opts = new ArrayList<>();
        if (html) opts.add(getString(R.string.preview));
        opts.add(getString(R.string.open_editor));
        opts.add(getString(R.string.rename));
        opts.add(getString(R.string.delete));
        CharSequence[] arr = opts.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(arr, (d, w) -> {
                    String chosen = opts.get(w);
                    if (chosen.equals(getString(R.string.preview))) showPreview(f);
                    else if (chosen.equals(getString(R.string.open_editor))) openEditor(f);
                    else if (chosen.equals(getString(R.string.rename))) {
                        askName("Ganti nama", f.getName(), n -> {
                            File nf = new File(f.getParentFile(), n);
                            if (f.renameTo(nf)) filesAdapter.load(currentDir);
                            else toast("Gagal mengganti nama");
                        });
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Hapus " + f.getName() + "?")
                                .setPositiveButton(R.string.delete, (d2, w2) -> {
                                    deleteRecursive(f);
                                    filesAdapter.load(currentDir);
                                })
                                .setNegativeButton(R.string.cancel, null)
                                .show();
                    }
                })
                .show();
    }

    /** Pratinjau HTML di WebView (ala artifact preview ZCode Desktop). */
    private void showPreview(File f) {
        WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.loadUrl("file://" + f.getAbsolutePath());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        new AlertDialog.Builder(this)
                .setTitle("👁 " + f.getName())
                .setView(box)
                .setPositiveButton(R.string.cancel, null)
                .show();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private void openEditor(final File f) {
        final EditText et = new EditText(this);
        et.setTypeface(Typeface.MONOSPACE);
        et.setTextSize(13f);
        et.setMinLines(8);
        et.setGravity(android.view.Gravity.TOP);
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            et.setText(new String(b, StandardCharsets.UTF_8));
        } catch (Exception ignore) { }
        ScrollView sv = new ScrollView(this);
        sv.addView(et, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    try {
                        Files.write(f.toPath(), et.getText().toString().getBytes(StandardCharsets.UTF_8));
                        toast("Tersimpan ✓");
                        filesAdapter.load(currentDir);
                    } catch (Exception e) { toast("Gagal simpan: " + e.getMessage()); }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    /* ================= TUGAS ================= */

    private void setupTodo() {
        todoAdapter = new TodoAdapter(getLayoutInflater());
        todoList.setAdapter(todoAdapter);
        todoAdapter.setListener(new TodoAdapter.Listener() {
            @Override public void onToggle(int pos) { TodoStore.toggle(MainActivity.this, pos); reloadTodo(); }
            @Override public void onDelete(int pos) { TodoStore.remove(MainActivity.this, pos); reloadTodo(); }
        });
        todoAddBtn.setOnClickListener(v -> {
            String t = todoInput.getText().toString().trim();
            if (t.isEmpty()) return;
            TodoStore.add(MainActivity.this, t);
            todoInput.setText("");
            reloadTodo();
        });
        reloadTodo();
    }

    private void reloadTodo() {
        todoAdapter.setData(TodoStore.load(this));
    }

    /* ================= SETELAN ================= */

    private void setupSettings() {
        String type = Prefs.get(this, "provider_type", "zai");
        (type.equals("custom") ? providerCustom : providerZai).setChecked(true);
        apiKeyInput.setText(Prefs.activeApiKey(this));
        baseUrlInput.setText("custom".equals(type) ? Prefs.get(this, "custom_base_url", "") : "");
        baseUrlInput.setEnabled(type.equals("custom"));

        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modelIds);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);

        providerGroup.setOnCheckedChangeListener((g, id) -> {
            boolean custom = id == R.id.providerCustom;
            Prefs.set(this, "provider_type", custom ? "custom" : "zai");
            baseUrlInput.setEnabled(custom);
            if (!custom) baseUrlInput.setText("");
            apiKeyInput.setText(Prefs.activeApiKey(this));
            fillModels(null);
            refreshHeader();
        });

        refreshModelsBtn.setOnClickListener(v -> fillModels(() -> toast("Daftar model diperbarui")));
        fillModels(null);

        themeGroup.setOnCheckedChangeListener((g, id) -> {
            String t = id == R.id.themeDark ? "dark" : "light";
            if (!t.equals(Prefs.get(this, "theme", "light"))) {
                Prefs.set(this, "theme", t);
                recreate();
            }
        });
        (dark ? themeDark : themeLight).setChecked(true);

        testBtn.setOnClickListener(v -> {
            saveSettings();
            testResult.setText("⏳ Menguji koneksi…");
            final String bu = Prefs.activeBaseUrl(this);
            final String key = Prefs.activeApiKey(this);
            if (key.isEmpty()) { testResult.setText("❌ API key masih kosong"); return; }
            LlmClient.testConnection(bu, key, (ok, msg) -> ui.post(() ->
                    testResult.setText(ok ? "✅ " + msg : "❌ " + msg)));
        });
    }

    private void saveSettings() {
        String type = Prefs.get(this, "provider_type", "zai");
        if ("custom".equals(type)) {
            Prefs.set(this, "custom_api_key", apiKeyInput.getText().toString().trim());
            Prefs.set(this, "custom_base_url", baseUrlInput.getText().toString().trim());
        } else {
            Prefs.set(this, "zai_api_key", apiKeyInput.getText().toString().trim());
        }
        Object sel = modelSpinner.getSelectedItem();
        if (sel != null) {
            String m = sel.toString();
            if (!m.isEmpty() && !m.startsWith("(") && !m.startsWith("⚠"))
                Prefs.set(this, "model_" + type, m);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettings();
        saveCurrentSession();
    }

    /** Isi spinner model. Untuk custom → fetch /models. cb null = senyap. */
    private void fillModels(final Runnable done) {
        String type = Prefs.get(this, "provider_type", "zai");
        modelIds.clear();
        if ("custom".equals(type)) {
            String bu = baseUrlInput.getText().toString().trim();
            String key = apiKeyInput.getText().toString().trim();
            if (bu.isEmpty()) {
                modelIds.add("(isi Base URL dulu)");
                modelAdapter.notifyDataSetChanged();
                return;
            }
            if (bu.endsWith("/")) bu = bu.substring(0, bu.length() - 1);
            final String fbu = bu;
            LlmClient.fetchModels(fbu, key, new LlmClient.ModelsCallback() {
                @Override public void onModels(List<String> ids) {
                    ui.post(() -> {
                        modelIds.clear();
                        modelIds.addAll(ids);
                        if (modelIds.isEmpty()) modelIds.add("(tidak ada model)");
                        // simpan utk model picker header
                        JSONArray arr = new JSONArray();
                        for (String id : ids) arr.put(id);
                        Prefs.set(MainActivity.this, "custom_models", arr.toString());
                        modelAdapter.notifyDataSetChanged();
                        selectSavedModel();
                        if (done != null) done.run();
                    });
                }
                @Override public void onError(String message) {
                    ui.post(() -> {
                        modelIds.clear();
                        modelIds.add("⚠ gagal: " + message);
                        modelAdapter.notifyDataSetChanged();
                        if (done != null) done.run();
                    });
                }
            });
        } else {
            modelIds.addAll(java.util.Arrays.asList(Prefs.zaiModels()));
            modelAdapter.notifyDataSetChanged();
            selectSavedModel();
            if (done != null) done.run();
        }
    }

    private void selectSavedModel() {
        String saved = Prefs.activeModel(this);
        for (int i = 0; i < modelIds.size(); i++) {
            if (modelIds.get(i).equals(saved)) { modelSpinner.setSelection(i); return; }
        }
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    /* ================= WELCOME ================= */

    private void ensureWelcomeFile(File ws) {
        File hello = new File(ws, "hello.html");
        if (hello.exists()) return;
        try {
            String html = "<!DOCTYPE html>\n<html lang=\"id\">\n<head>\n<meta charset=\"utf-8\">\n"
                    + "<title>Halo dari ZCode Mobile</title>\n"
                    + "<style>body{font-family:sans-serif;background:#0a0a0a;color:#fafafa;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}\n"
                    + "h1{font-size:2em;font-style:italic}p{opacity:.6}</style>\n</head>\n<body>\n<div style=\"text-align:center\">\n"
                    + "<h1>Z</h1>\n<p>ZCode Mobile — berkas pertamamu.<br>Minta agent mengubahnya!</p>\n</div>\n"
                    + "</body>\n</html>\n";
            FileOutputStream fos = new FileOutputStream(hello);
            fos.write(html.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignore) { }
    }
}
