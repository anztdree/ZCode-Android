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
    private TextView txtWsName, txtModel, txtStatus, txtTokens, txtMode, txtTheme, txtGreeting, txtSelModel, txtTest, txtFilesPath;
    private View statusStrip, emptyState;
    private ImageButton btnSend;
    private ImageView imgMode, imgTheme;
    private EditText etInput, etApiKey, etBaseUrl, etSessionSearch, etNewTodo;
    private ListView chatList, fileList, todoList, sessionList;
    private ScrollView pageSettings;
    private LinearLayout pageChat, pageFiles, pageTodo;
    private int activePage = 0;

    /* =============================== LIFECYCLE =============================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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

        bindViews();
        bindListeners();

        // Sesi aktif (atau baru)
        sessionId = SessionStore.currentId(this);
        JSONArray saved = null;
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
        etSessionSearch = findViewById(R.id.etSessionSearch);
        etNewTodo = findViewById(R.id.etNewTodo);
        chatList = findViewById(R.id.chatList);
        fileList = findViewById(R.id.fileList);
        todoList = findViewById(R.id.todoList);
        sessionList = findViewById(R.id.sessionList);
        pageFiles = findViewById(R.id.pageFiles);
        pageTodo = findViewById(R.id.pageTodo);
        pageSettings = findViewById(R.id.pageSettings);
        pageChat = findViewById(R.id.pageChat);
        txtWsName.setText(workspace.getName() + " · " + workspace.listFiles().length + " item");
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
            switchPage(3);
            closeDrawer();
        });
        etSessionSearch.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { refreshSessions(); }
        });

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
        findViewById(R.id.navTodo).setOnClickListener(v -> switchPage(2));
        findViewById(R.id.navSettings).setOnClickListener(v -> switchPage(3));

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

        // Setelan
        findViewById(R.id.btnPickModel).setOnClickListener(v -> showModelPicker());
        findViewById(R.id.btnTest).setOnClickListener(v -> testConnection());
    }

    /* ================================ PAGES ================================ */

    private void switchPage(int idx) {
        activePage = idx;
        pageChat.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageFiles.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageTodo.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);

        int on = col(R.attr.cFg), off = col(R.attr.cFgSubtle);
        tint(R.id.navChatIcon, idx == 0 ? on : off);   textCol(R.id.navChatText, idx == 0 ? on : off);
        tint(R.id.navFilesIcon, idx == 1 ? on : off);  textCol(R.id.navFilesText, idx == 1 ? on : off);
        tint(R.id.navTodoIcon, idx == 2 ? on : off);   textCol(R.id.navTodoText, idx == 2 ? on : off);
        tint(R.id.navSettingsIcon, idx == 3 ? on : off); textCol(R.id.navSettingsText, idx == 3 ? on : off);
        if (idx == 1) { filesAdapter.reload(currentSub); refreshHeader(); }
    }

    private void tint(int id, int color) {
        View v = findViewById(id);
        if (v instanceof ImageView) ((ImageView) v).setColorFilter(color);
    }

    private void textCol(int id, int color) {
        View v = findViewById(id);
        if (v instanceof TextView) ((TextView) v).setTextColor(color);
    }

    /* ============================== CHAT & ENGINE ============================== */

    private void send() {
        final String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        hideKeyboard();

        if (Prefs.activeApiKey(this).isEmpty()) {
            addNote("API Key belum diisi. Buka Setelan → Penyedia Model.");
            switchPage(3);
            return;
        }

        ChatItem u = new ChatItem(ChatItem.TYPE_USER);
        u.text = text;
        chatItems.add(u);
        refreshEmptyState();
        chatAdapter.notifyDataSetChanged();
        scrollBottom();

        startEngine(text);
    }

    private void startEngine(String userText) {
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
            title = userText.length() > 42 ? userText.substring(0, 42) + "…" : userText;
            if (title.trim().isEmpty()) title = "Tugas baru";
        }
        final String t = title;
        engine.send(userText);
        saveSession(t);
        refreshSessions();
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
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
        } catch (Exception ignore) { }
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
        if ("custom".equals(Prefs.get(this, "provider_type", "zai"))) {
            txtTest.setText("Memuat model dari penyedia kustom…");
            LlmClient.fetchModels(Prefs.activeBaseUrl(this), Prefs.activeApiKey(this),
                    new LlmClient.ModelsCallback() {
                        @Override public void onModels(List<String> ids) {
                            runOnUiThread(() -> pickFromList("Pilih model", ids.toArray(new String[0]), null));
                        }
                        @Override public void onError(String message) {
                            runOnUiThread(() -> toast("Gagal memuat model: " + message));
                        }
                    });
        } else {
            String[] free = Prefs.zaiFreeModels();
            String[] paid = Prefs.zaiPaidModels();
            List<String> all = new ArrayList<>();
            for (String s : free) all.add(s);
            for (String s : paid) all.add(s);
            pickFromList("Pilih model", all.toArray(new String[0]), free);
        }
    }

    private void pickFromList(String title, String[] ids, String[] freeIds) {
        List<String> freeList = new ArrayList<>();
        if (freeIds != null) for (String f : freeIds) freeList.add(f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        String cur = Prefs.activeModel(this);
        boolean headerDone = false;
        for (final String id : ids) {
            boolean free = freeList.contains(id);
            if (freeIds != null && free && !headerDone) {
                box.addView(sectionLabel("GRATIS"));
                headerDone = true;
            }
            if (freeIds != null && headerDone && !free) {
                box.addView(sectionLabel("BERBAYAR"));
                headerDone = false;
                // tandai agar label tak diulang
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int px = (int) (14 * getResources().getDisplayMetrics().density);
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
                Prefs.set(this, "model_" + Prefs.get(this, "provider_type", "zai"), id);
                updateModelChip();
                txtSelModel.setText(Prefs.prettyModel(id));
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                toast("Model: " + Prefs.prettyModel(id));
            });
            box.addView(row);
        }
        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(title);
        b.setView(sc);
        dialogHolder[0] = buildShownDialog(b, sc);
    }

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
            try {
                String name = queryName(data.getData());
                java.io.InputStream is = getContentResolver().openInputStream(data.getData());
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

        String pt = Prefs.get(this, "provider_type", "zai");
        ((RadioButton) findViewById(R.id.rbZai)).setChecked("zai".equals(pt));
        ((RadioButton) findViewById(R.id.rbCustom)).setChecked("custom".equals(pt));
        boolean custom = "custom".equals(pt);
        findViewById(R.id.lblBaseUrl).setVisibility(custom ? View.VISIBLE : View.GONE);
        etBaseUrl.setVisibility(custom ? View.VISIBLE : View.GONE);

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
        ((RadioGroup) findViewById(R.id.rgProvider)).setOnCheckedChangeListener((g, id) -> {
            String np = id == R.id.rbCustom ? "custom" : "zai";
            Prefs.set(this, "provider_type", np);
            boolean cu = "custom".equals(np);
            findViewById(R.id.lblBaseUrl).setVisibility(cu ? View.VISIBLE : View.GONE);
            etBaseUrl.setVisibility(cu ? View.VISIBLE : View.GONE);
            updateModelChip();
        });

        etApiKey.setText(Prefs.activeApiKey(this));
        etBaseUrl.setText(Prefs.get(this, "custom_base_url", ""));
        txtSelModel.setText(Prefs.prettyModel(Prefs.activeModel(this)));
    }

    private void persistSettings() {
        Prefs.set(this, "zai_api_key", etApiKey.getText().toString().trim());
        Prefs.set(this, "custom_api_key", etApiKey.getText().toString().trim());
        Prefs.set(this, "custom_base_url", etBaseUrl.getText().toString().trim());
    }

    private void testConnection() {
        persistSettings();
        txtTest.setText("Menguji…");
        LlmClient.testConnection(Prefs.activeBaseUrl(this), Prefs.activeApiKey(this),
                (ok, msg) -> runOnUiThread(() -> {
                    txtTest.setText(msg);
                    txtTest.setTextColor(ok ? col(R.attr.cSuccess) : col(R.attr.cDestructive));
                }));
    }

    /* ============================== UTIL ============================== */
}
