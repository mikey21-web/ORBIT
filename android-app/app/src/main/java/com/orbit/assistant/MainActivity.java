package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.net.Uri;
import android.os.PowerManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chat surface for the agent that runs inside this app.
 *
 * `AgentService` owns the virtual display and serves tasks on loopback, so the
 * phone needs neither a host process nor `adb reverse`: this activity posts to
 * 127.0.0.1 and polls for steps and the latest virtual-display frame.
 */
public class MainActivity extends Activity {

    private static final String BASE = "http://127.0.0.1:" + BuildConfig.AGENT_PORT;
    //: Set from filesDir/agent-token in onCreate. The agent rejects requests
    //: without it: on Android any app can reach loopback, so an open port here
    //: would let anything on the phone drive the agent.
    private static String TOKEN = "";

    private static HttpURLConnection connect(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        if (!TOKEN.isEmpty()) conn.setRequestProperty("X-Agent-Token", TOKEN);
        return conn;
    }

    private android.view.ViewGroup messages;
    private android.view.View welcome;
    private android.widget.LinearLayout lastAgentBubble;
    private EditText input;
    private ScrollView scroll;
    private ImageView frameView;
    private android.view.View frameCard;
    //: The in-app mirror of the agent's screen (see the layout's comment).
    private ImageView capsuleFrame;
    private android.view.View capsule;
    private android.widget.TextView capsuleDot;
    private boolean capsuleCollapsed;
    //: Only a capsule with a picture is worth showing; an empty one looks broken.
    private boolean hasFrame;
    private TextView frameLabel;
    private android.app.Dialog frameDialog;
    private ZoomableImage bigFrame;
    private View statusRow;
    private TextView statusText;
    private android.view.View sendButton;
    // which task the cancel button should stop, and how much of its step list
    // has already been shown
    private String currentTaskId = "";
    //: Is a poll chain alive? MIUI's power keeper froze this process mid-task
    //: and the chain died with it, leaving "Working…" on screen forever.
    private boolean polling = false;
    //: What the interpreter reported at startup; shown in Settings → Diagnostics.
    private String bootInfo = "";
    //: When healAgent last ran; the check must not become a reconnect loop.
    private long lastHealAt = 0;
    //: Retries spent waiting for a just-started agent to become ready.
    private int healAttempts;
    //: The app a running crawl is building a map of, for its messages.
    private String crawlName = "";
    //: One worker for the polling loops. A task polls every 0.7 s and the frame
    //: ticker every 2.5 s; a thread each time was dozens of threads a minute for
    //: work that is strictly sequential.
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "pa-io");
                thread.setDaemon(true);
                return thread;
            });
    //: The one view that accumulates step lines, and how many bubbles to keep.
    private TextView stepBlock;
    //: The step lines as text, so the highlighted ones survive a rebuild.
    private final java.util.ArrayList<String> stepLines = new java.util.ArrayList<>();
    private static final int MAX_BUBBLES = 120;
    //: When the poll chain last heard from the agent. A chain can die without
    //: saying so (an Error kills the thread past `catch (Exception)`), and the
    //: screen then says "Working…" forever.
    private volatile long lastPollAt = 0;
    //: The task whose answer is already on screen. The watchdog can restart a
    //: chain for a task that has just finished, and without this the same
    //: completion was printed again every three seconds.
    private String answeredTaskId = "";
    //: Frames are full screenshots; decoding every poll is what filled the heap.
    private long lastFrameAt = 0;

    /**
     * Keeps the screen honest even when the poll chain dies.
     *
     * Three times now a task finished on the phone while the chat still said
     * "Working…": the polling thread was gone and nothing was left to notice.
     * A Handler on the main looper survives that, so it re-arms the chain.
     */
    private final Runnable pollWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!currentTaskId.isEmpty() && !polling
                    && System.currentTimeMillis() - lastPollAt > 5000) {
                poll(currentTaskId, 0);
            }
            handler.postDelayed(this, 3000);
        }
    };
    // set while the agent is waiting for an answer: the next Send goes to it
    private String awaitingTaskId = "";
    private int shownSteps = 0;
    private String lastFrame = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Tr.attach(this);
        setContentView(R.layout.activity_main);
        Tr.apply(findViewById(android.R.id.content));
        TOKEN = AgentToken.INSTANCE.ensure(this);

        messages = findViewById(R.id.messages);
        welcome = findViewById(R.id.welcome);
        input = findViewById(R.id.input);
        scroll = findViewById(R.id.scroll);
        capsule = findViewById(R.id.capsule);
        capsuleFrame = findViewById(R.id.capsuleFrame);
        capsuleDot = findViewById(R.id.capsuleDot);
        if (capsule != null) {
            capsuleCollapsed = getSharedPreferences("agent", MODE_PRIVATE)
                    .getBoolean("capsule_collapsed", false);
            capsule.post(() -> {
                applyCapsuleSize(capsuleCollapsed);
                restoreCapsulePosition();
                // Not until there is a picture: an empty black rectangle on the
                // welcome screen reads as a rendering bug. The first frame turns
                // it on (see loadLastFrame).
                showCapsule(false);
            });
            capsule.setOnTouchListener(new CapsuleTouch());
            capsule.getViewTreeObserver().addOnGlobalLayoutListener(this::onRootLayout);
        }
        frameView = findViewById(R.id.frame);
        frameCard = findViewById(R.id.frameCard);
        frameLabel = findViewById(R.id.frameLabel);
        frameCard.setOnClickListener(v -> showFrameViewer());
        statusRow = findViewById(R.id.statusRow);
        statusText = findViewById(R.id.status);
        android.view.View send = findViewById(R.id.send);
        sendButton = send;
        send.setAlpha(1f);
        findViewById(R.id.cancel).setOnClickListener(v -> cancelTask());
        // The capsule is a preference, so it should come back by itself after a
        // restart instead of waiting for someone to open the settings dialog.
        // An older version floated a system overlay; stop any leftover one so
        // there is exactly one mirror, and it belongs to this window.
        stopService(new Intent(this, com.orbit.assistant.CapsuleService.class));
        getSharedPreferences("agent", MODE_PRIVATE).edit()
                .putBoolean("capsule", false).apply();

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                send.performClick();
                return true;
            }
            return false;
        });

        handler.postDelayed(pollWatchdog, 4000);
        loadLastFrame();
        handler.postDelayed(frameTicker, 2500);
        ensureSession();
        // the subtitle is the session switcher: it is where the conversation's
        // identity belongs, and it costs no header space
        android.widget.TextView subtitle = findViewById(R.id.subtitle);
        if (subtitle != null) {
            subtitle.setOnClickListener(v -> showSessions());
            refreshSubtitle();
        }
        restoreChat();
        // Diagnostics, not conversation: five lines about Python versions in
        // the middle of the transcript told the user nothing they could act on.
        // They live in Settings → Diagnostics now.
        bootInfo = pythonStatus();
        checkAccessibility();
        checkWirelessDebugging();
        checkBattery();
        checkApiKey();
        resumeRunningTask();
        if (hasIdentity()) {
            startAgentService();
        } else {
            note("Wireless debugging is not paired yet. Pair it once first.");
            startPairingWatch();
        }

        for (int id : new int[]{R.id.chip1, R.id.chip2, R.id.chip3}) {
            TextView chip = findViewById(id);
            chip.setOnClickListener(v -> {
                input.setText(chip.getText());
                input.setSelection(input.getText().length());
            });
        }

        // One button, one panel. A long press used to open a plain list of the
        // same maps; the library shows more (screens, operations, landing rate)
        // and can act on them, so the second door only caused choices.
        findViewById(R.id.maps).setOnClickListener(v -> showMapsLibrary());
        findViewById(R.id.usage).setOnClickListener(v -> showUsage());

        // "CLI+GUI / GUI" was a developer's name for two behaviours, and it left
        // every other reader guessing. The chip now says what it does and opens a
        // choice with one line of plain language each, instead of flipping
        // blindly and explaining itself in the chat.
        modeButton = findViewById(R.id.mode);
        modeButton.setText(Tr.t(mapsMode() ? getString(R.string.mode_auto) : getString(R.string.mode_vision)));
        modeButton.setOnClickListener(v -> showModeChooser());

        TextView settings = findViewById(R.id.settings);
        settings.setOnClickListener(v -> showSettings());
        settings.setOnLongClickListener(v -> {
            showSystemSettings();
            return true;
        });

        // self-pairing test hook: am start ... --es pair "code@host:port"
        String pairArg = getIntent() == null ? null : getIntent().getStringExtra("pair");
        if (pairArg != null && pairArg.contains(":")) {
            // format: code@host:port
            String hostArg = "127.0.0.1";
            String rest = pairArg;
            if (pairArg.contains("@")) {
                rest = pairArg.substring(pairArg.indexOf('@') + 1);
                hostArg = rest.substring(0, rest.lastIndexOf(':'));
                rest = pairArg.substring(0, pairArg.indexOf('@')) + rest.substring(rest.lastIndexOf(':'));
            }
            final String pairCode = rest.substring(0, rest.lastIndexOf(':'));
            final int pairPort = Integer.parseInt(rest.substring(rest.lastIndexOf(':') + 1));
            final String pairHost = hostArg;
            append("You", "Pairing " + pairCode + " @ " + pairHost + ":" + pairPort);
            new Thread(() -> {
                note("Pairing with the phone (" + pairHost + ")…");
                com.orbit.assistant.pairing.Pairing.Result r =
                        com.orbit.assistant.pairing.Pairing.INSTANCE.pair(pairHost, pairPort, pairCode, null, "orbit", 20000, null);
                android.util.Log.i("Pairing", "result ok=" + r.getOk() + " detail=" + r.getDetail());
                append("ORBIT", (r.getOk() ? "✅ " : "❌ ") + r.getDetail());
            }).start();
        }

        // handy for testing without typing: am start ... --es task "open bluetooth"
        String preset = getIntent() == null ? null : getIntent().getStringExtra("task");
        if (preset != null && !preset.trim().isEmpty()) {
            append("You", preset);
            startTask(preset.trim());
        }
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            // The keyboard stayed up after sending and covered half of the
            // mirror and the answer: measured on a 1280x2772 screen, the task
            // ran with 40% of the window hidden behind the IME.
            hideKeyboard();
            if (!awaitingTaskId.isEmpty()) {
                // the agent is waiting on a question, so this is the answer
                String id = awaitingTaskId;
                awaitingTaskId = "";
                append("You", text);
                answerTask(id, text);
                return;
            }
            append("You", text);
            startTask(text);
        });
    }

    /** Runs the agent core that ships inside the APK. */
    private String pythonStatus() {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            Python py = Python.getInstance();
            String version = py.getModule("sys").get("version").toString().split("\n")[0];
            PyObject agent = py.getModule("orbit");
            PyObject maps = py.getModule("orbit.core.app_map");
            PyObject spake2 = py.getModule("spake2");
            PyObject crypto = py.getModule("cryptography");
            String cryptoVersion = py.getModule("cryptography.__about__")
                    .get("__version__").toString();
            return "Python is ready: " + version
                    + "\norbit " + agent.get("__version__")
                    + "\napp_map module: " + (maps != null ? "loaded" : "missing")
                    + "\nspake2:" + (spake2 != null ? "ok" : "missing")
                    + "  cryptography:" + cryptoVersion;
        } catch (Throwable t) {
            return "Python failed to start: " + t;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Re-opening the app should retry a start that failed (or was killed),
        // not silently keep a dead agent -- but the agent itself brings this
        // window forward when a task ends, and restarting there printed
        // "Starting local agent…" underneath the answer it had just written.
        if (!AgentService.isReady()) {
            startAgentService();
        }
    }

    private String lastPairHost = "127.0.0.1";

    /** Model settings: the key has to come from the user, not from adb push. */
    /** Package the map builder is currently crawling, if any. */
    private String crawlId = "";
    private String lastCrawlLine = "";

    /**
     * Build a macro map for one app, on the display the agent already owns.
     *
     * The crawl runs inside this process: the phone stays usable, nothing is
     * driven through adb from a computer, and the map is recorded on the very
     * display it will be replayed on -- a map measured anywhere else scores
     * about half as well.
     */
    /**
     * What the agent will actually load, with the two things you can do about
     * it: pin the map to use for a package, or delete a crawl you do not want.
     *
     * One map per package is in use at a time (more screens wins, newest breaks
     * the tie); a pin overrides that until it is removed.
     */
    /**
     * The header line: which conversation you are in, and how to change it.
     *
     * Without the arrow nobody knew the line was tappable -- the session list
     * was found only by accident.
     */
    private void refreshSubtitle() {
        android.widget.TextView subtitle = findViewById(R.id.subtitle);
        if (subtitle == null) {
            return;
        }
        String title = "";
        org.json.JSONArray sessions = readSessions();
        for (int i = 0; i < sessions.length(); i++) {
            org.json.JSONObject entry = sessions.optJSONObject(i);
            if (entry != null && sessionId.equals(entry.optString("id"))) {
                title = entry.optString("title");
                break;
            }
        }
        if (title.isEmpty() || "New chat".equals(title)) {
            title = "Chats";
        }
        subtitle.setText(getString(R.string.subtitle_with_arrow, title));
    }

    /**
     * Several conversations with the same phone.
     *
     * Same device, separate transcripts: "what did I ask about ShopApp" does not
     * have to scroll past yesterday's experiments.
     */
    /** How old something is, in the terms a person reads it. */
    private static String when(long seconds) {
        if (seconds <= 0) {
            return "time unknown";
        }
        long age = System.currentTimeMillis() / 1000 - seconds;
        if (age < 3600) {
            return (age / 60) + " min ago";
        }
        if (age < 86400) {
            return (age / 3600) + " h ago";
        }
        return (age / 86400) + " d ago";
    }

    private void showSessions() {
        org.json.JSONArray sessions = readSessions();
        String[] labels = new String[sessions.length() + 1];
        labels[0] = "+ New chat";
        for (int i = 0; i < sessions.length(); i++) {
            org.json.JSONObject entry = sessions.optJSONObject(i);
            String id = entry == null ? "" : entry.optString("id");
            String title = entry == null ? "" : entry.optString("title");
            labels[i + 1] = (id.equals(sessionId) ? getString(R.string.session_current) + " " : "　 ") + title
                    + " · " + when(entry == null ? 0 : entry.optLong("updated") / 1000);
        }
        new AlertDialog.Builder(this)
                .setTitle("Chats")
                .setItems(labels, (d, which) -> {
                    if (which == 0) {
                        saveNow();
                        newSession();
                        conversation.clear();
                        clearChat();
                        stepBlock = null;
                        if (welcome != null) {
                            welcome.setVisibility(View.VISIBLE);
                        }
                        note("New chat · what you had stays under Chats");
                        return;
                    }
                    if (!currentTaskId.isEmpty()) {
                        // The task's steps stream into whatever session is open,
                        // so switching mid-task wrote the answer into the wrong
                        // one and left the instruction behind in the old one.
                        android.widget.Toast.makeText(this,
                                "A task is still running. Switch chats when it finishes.",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    org.json.JSONObject entry = sessions.optJSONObject(which - 1);
                    if (entry != null) {
                        switchSession(entry.optString("id"));
                    }
                })
                .setNeutralButton("Delete this chat", (d, w) -> confirmDeleteSession())
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmDeleteSession() {
        new AlertDialog.Builder(this)
                .setTitle("Delete this chat?")
                .setMessage("Only the chat log goes; maps and task results on the phone are untouched.")
                .setPositiveButton("Delete", (d, w) -> {
                    java.io.File file = sessionFile();
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    org.json.JSONArray sessions = readSessions();
                    org.json.JSONArray next = new org.json.JSONArray();
                    for (int i = 0; i < sessions.length(); i++) {
                        org.json.JSONObject entry = sessions.optJSONObject(i);
                        if (entry != null && !sessionId.equals(entry.optString("id"))) {
                            next.put(entry);
                        }
                    }
                    writeSessions(next);
                    conversation.clear();
                    clearChat();
                    stepBlock = null;
                    sessionId = "";
                    ensureSession();
                    if (welcome != null) {
                        welcome.setVisibility(View.VISIBLE);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startCrawlFor(String pkg, String label, String file) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView about = new TextView(this);
        about.setText(getString(R.string.crawl_about, label, pkg));
        about.setTextSize(12f);
        about.setTextColor(getColor(R.color.ink_soft));
        box.addView(about);

        box.addView(label("Max screens to crawl"));
        EditText screens = new EditText(this);
        screens.setText(R.string.crawl_screens);
        screens.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(screens);

        box.addView(label("Time budget (minutes)"));
        EditText minutes = new EditText(this);
        minutes.setText(R.string.crawl_minutes);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(minutes);

        box.addView(label("Max depth · taps from the home screen"));
        EditText depth = new EditText(this);
        depth.setText("3");
        depth.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(depth);

        new AlertDialog.Builder(this)
                .setTitle("Re-crawl " + label)
                .setView(box)
                .setPositiveButton("Start", (d, w) -> startCrawl(pkg, label,
                        parseCount(screens.getText().toString(), 12),
                        parseCount(minutes.getText().toString(), 20),
                        parseCount(depth.getText().toString(), 3)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(JSONObject row) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this map?")
                .setMessage(row.optString("file") + "\n\nOnly the copy on this phone goes, and there is no undo.")
                .setPositiveButton("Delete", (d, w) -> postMapAction("delete", row, null))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void postMapAction(String action, JSONObject row, String file) {
        final String name = file == null ? "" : file;
        new Thread(() -> {
            String problem = "";
            try {
                JSONObject body = new JSONObject();
                body.put("package", row.optString("package"));
                if (!name.isEmpty()) {
                    body.put("file", name);
                } else {
                    body.put("file", row.optString("file"));
                }
                HttpURLConnection conn = connect("/maps/" + action);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(6000);
                conn.setDoOutput(true);
                conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                int code = conn.getResponseCode();
                if (code != 200) {
                    problem = "HTTP " + code;
                }
            } catch (Throwable t) {
                problem = t.toString();
            }
            final String failure = problem;
            ui(() -> {
                note(failure.isEmpty() ? "Map updated" : "Map action failed: " + failure);
                showMapsLibrary();         // the cards are the one place maps live
            });
        }).start();
    }

    /**
     * The maps this phone has, as cards.
     *
     * This used to be reachable only through Maps → Build map, which is the crawl
     * builder: the maps themselves were invisible, so "which of these actually
     * works" could not be answered from the app at all. The landing rate is the
     * number that decides it, and it is now on the card.
     */
    private void showMapsLibrary() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect("/maps");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(8000);
                JSONObject reply = new JSONObject(read(conn));
                JSONArray maps = reply.optJSONArray("maps");
                ui(() -> renderMapsLibrary(maps == null ? new JSONArray() : maps));
            } catch (Exception e) {
                ui(() -> append("Maps", "Could not read the map list: " + e));
            }
        }).start();
    }

    private void renderMapsLibrary(JSONArray maps) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(4));

        TextView intro = new TextView(this);
        intro.setText(maps.length() == 0
                ? "No maps yet. Once one is built, the same app's steps are recorded and replayed next time."
                : "Landings grade the map against itself: a few paths are replayed at random to see if they end where they were recorded.");
        intro.setTextSize(12.5f);
        intro.setTextColor(getColor(R.color.ink_soft));
        intro.setPadding(0, 0, 0, dp(10));
        box.addView(intro);

        for (int i = 0; i < maps.length(); i++) {
            JSONObject map = maps.optJSONObject(i);
            if (map == null) {
                continue;
            }
            box.addView(mapCard(map));
        }

        TextView build = new TextView(this);
        build.setText("+ Build a new map");
        build.setTextSize(14f);
        build.setTextColor(getColor(R.color.accent));
        build.setGravity(android.view.Gravity.CENTER);
        build.setPadding(0, dp(16), 0, dp(12));
        build.setOnClickListener(v -> showMapBuilder());
        box.addView(build);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Maps")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    /** One map: what it is, how well it scored, and what can be done to it. */
    private android.view.View mapCard(JSONObject map) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_card));
        card.setPadding(dp(14), dp(12), dp(14), dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(map.optString("app", map.optString("file", "?")));
        title.setTextSize(16f);
        title.setTextColor(getColor(R.color.ink));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (map.optBoolean("pinned", false)) {
            header.addView(pill("In use", R.color.accent, R.color.accent_soft));
        }
        card.addView(header);

        TextView pkg = new TextView(this);
        pkg.setText(map.optString("package", ""));
        pkg.setTextSize(11.5f);
        pkg.setTextColor(getColor(R.color.ink_soft));
        card.addView(pkg);

        TextView facts = new TextView(this);
        facts.setTextSize(13f);
        facts.setTextColor(getColor(R.color.ink));
        facts.setPadding(0, dp(8), 0, 0);
        facts.setText(summaryLine(map));
        card.addView(facts);

        TextView score = new TextView(this);
        score.setText(benchLine(map));
        score.setTextSize(12f);
        score.setTextColor(getColor(benchColour(map)));
        score.setPadding(0, dp(2), 0, 0);
        card.addView(score);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.END);

        TextView measure = action("Re-measure landings", R.color.accent);
        measure.setOnClickListener(v -> startBench(map));
        actions.addView(measure);

        if (map.optBoolean("deletable", false)) {
            TextView remove = action("Delete", R.color.err);
            remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Delete map")
                    .setMessage("Delete \"" + map.optString("app") + "\"'s map?\n"
                            + "This app goes back to having no map.")
                    .setPositiveButton("Delete", (d, w) -> postMapChange("/maps/delete",
                            map.optString("file", ""), false))
                    .setNegativeButton("Cancel", null)
                    .show());
            actions.addView(remove);
        }
        if (map.optBoolean("pinned", false)) {
            TextView unpin = action("Unpin", R.color.ink_soft);
            unpin.setOnClickListener(v -> postMapChange("/maps/unpin",
                    map.optString("package", ""), true));
            actions.addView(unpin);
        } else {
            TextView pin = action("Pin", R.color.accent);
            pin.setOnClickListener(v -> postMapChange("/maps/pin",
                    map.optString("package", ""), true, map.optString("file", "")));
            actions.addView(pin);
        }
        card.addView(actions);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private String summaryLine(JSONObject map) {
        StringBuilder line = new StringBuilder();
        line.append(map.optInt("screens", 0)).append(" screens · ")
                .append(map.optInt("operations", 0)).append(" actions");
        JSONObject coverage = map.optJSONObject("coverage");
        if (coverage != null && coverage.optInt("queued", 0) > 0) {
            line.append(" · and ").append(coverage.optInt("queued")).append(" not reached");
        }
        String crawled = map.optString("crawled_at", "");
        if (!crawled.isEmpty()) {
            line.append(" · ").append(crawled.replace("T", " "));
        }
        int alternatives = map.optInt("alternatives", 0);
        if (alternatives > 0) {
            // choose() shows one map per package; without this the others are
            // invisible even though they exist and take space.
            line.append(" · and ").append(alternatives).append(" maps for this package");
        }
        return line.toString();
    }

    /** "landings 4/4" -- the one number that says whether this map is worth using. */
    private String benchLine(JSONObject map) {
        JSONObject bench = map.optJSONObject("bench");
        if (bench == null || bench.length() == 0) {
            return "landings not measured";
        }
        int ops = bench.optInt("ops", 0);
        if (ops <= 0) {
            // Measured, but the map has no navigation to replay -- every
            // operation is an action on the screen itself. "0/0" would read as
            // a failure of the map.
            return "no navigation to measure";
        }
        return "Landings " + bench.optInt("exact", 0) + "/" + ops
                + (bench.optBoolean("stale", false) ? " (older data)" : "");
    }

    private int benchColour(JSONObject map) {
        JSONObject bench = map.optJSONObject("bench");
        int ops = bench == null ? 0 : bench.optInt("ops", 0);
        int exact = bench == null ? 0 : bench.optInt("exact", 0);
        if (ops == 0) {
            return R.color.ink_soft;
        }
        if (exact == 0) {
            return R.color.err;
        }
        return exact * 10 >= ops * 8 ? R.color.ok
                : exact * 2 >= ops ? R.color.warn : R.color.err;
    }

    private TextView pill(String text, int ink, int background) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11f);
        view.setTextColor(getColor(ink));
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setColor(getColor(background));
        shape.setCornerRadius(dp(9));
        view.setBackground(shape);
        view.setPadding(dp(8), dp(3), dp(8), dp(3));
        return view;
    }

    private TextView action(String text, int colour) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13f);
        view.setTextColor(getColor(colour));
        view.setPadding(dp(14), dp(4), dp(2), dp(4));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private void postMapChange(String path, String value, boolean refresh) {
        postMapChange(path, value, refresh, "");
    }

    /**
     * Replay a few known paths and score the map again.
     *
     * Runs in the background on the agent: each sample relaunches the app and
     * taps its way to a recorded screen, which takes a few seconds apiece.
     */
    private void startBench(JSONObject map) {
        String path = map.optString("dir", "") + "/" + map.optString("file", "");
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect("/bench");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject body = new JSONObject();
                body.put("map", path);
                body.put("limit", 6);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                JSONObject reply = new JSONObject(read(conn));
                String id = reply.optString("id", "");
                ui(() -> append("Maps", "Re-measuring \"" + map.optString("app") + "\" landings…"));
                pollBench(map, id, 0);
            } catch (Exception e) {
                ui(() -> append("Maps", "Could not start measuring: " + e));
            }
        }).start();
    }

    private void pollBench(JSONObject map, String id, int attempt) {
        if (id.isEmpty() || attempt > 200) {          // ~10 minutes at 3 s
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                HttpURLConnection conn = connect("/bench/" + id);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(8000);
                JSONObject state = new JSONObject(read(conn));
                String status = state.optString("state", "");
                if ("done".equals(status) || "failed".equals(status)) {
                    JSONObject summary = state.optJSONObject("summary");
                    String line = "failed".equals(status)
                            ? "Measurement failed: " + state.optString("error", "")
                            : "「" + map.optString("app") + "\" landings "
                              + (summary == null ? 0 : summary.optInt("exact", 0)) + "/"
                              + (summary == null ? 0 : summary.optInt("ops", 0));
                    ui(() -> append("Maps", line));
                    showMapsLibrary();
                    return;
                }
                pollBench(map, id, attempt + 1);
            } catch (Exception e) {
                pollBench(map, id, attempt + 1);
            }
        }).start();
    }

    /** Pin, unpin or delete, then reload the list so the cards tell the truth. */
    private void postMapChange(String path, String value, boolean refresh, String file) {
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect(path);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject body = new JSONObject();
                if (path.endsWith("delete")) {
                    body.put("file", value);
                } else {
                    body.put("package", value);
                    if (!file.isEmpty()) {
                        body.put("file", file);
                    }
                }
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                read(conn);
                if (refresh) {
                    ui(() -> { });
                    showMapsLibrary();
                }
            } catch (Exception e) {
                ui(() -> append("Maps", "Action failed: " + e));
            }
        }).start();
    }

    private void showMapBuilder() {
        append("Build map", "Reading the apps that can be crawled…");
        new Thread(() -> {
            try {
                HttpURLConnection conn =
                        connect("/crawl/apps");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(8000);
                JSONObject reply = new JSONObject(read(conn));
                JSONArray apps = reply.optJSONArray("apps");
                if (apps == null || apps.length() == 0) {
                    ui(() -> append("Build map", "The local agent has not listed any apps yet; try again shortly."));
                    return;
                }
                List<String> packages = new ArrayList<>();
                List<String> names = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                List<String> states = new ArrayList<>();
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.getJSONObject(i);
                    String pkg = app.optString("package");
                    String name = app.optString("label", pkg);
                    String state = app.optString("state", "crawlable");
                    String mark = "mapped".equals(state) ? "📌 "
                            : "refused".equals(state) ? "⛔ "
                            : "risky".equals(state) ? "⚠️ " : "✅ ";
                    packages.add(pkg);
                    names.add(name);
                    states.add(state);
                    labels.add(mark + name + "  (" + pkg + ")");
                }
                ui(() -> showMapDialog(labels, packages, names, states));
            } catch (Exception e) {
                ui(() -> append("Build map", "Could not read the app list: " + e));
            }
        }).start();
    }

    private void showMapDialog(List<String> labels, List<String> packages,
                               List<String> names, List<String> states) {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView hint = new TextView(this);
        hint.setText(R.string.crawl_hint);
        box.addView(hint);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // start on the app that was crawled last, or the first one that works
        String last = getSharedPreferences("maps", MODE_PRIVATE).getString("last", "");
        int start = packages.indexOf(last);
        if (start < 0) {
            for (int i = 0; i < states.size(); i++) {
                if (!"refused".equals(states.get(i))) {
                    start = i;
                    break;
                }
            }
        }
        if (start >= 0) spinner.setSelection(start);
        box.addView(spinner);

        // Labels, not hints: the fields are pre-filled, and a hint disappears
        // the moment there is text -- which left three bare numbers.
        box.addView(label("Max screens · the most this map may record"));
        EditText screens = new EditText(this);
        screens.setText(R.string.crawl_screens);
        screens.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(screens);

        box.addView(label("Time budget (minutes) · stops when it runs out"));
        EditText minutes = new EditText(this);
        minutes.setText(R.string.crawl_minutes);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(minutes);

        // BFS depth: how many taps away from the home screen the crawl may go.
        // 1 means "open the pages the home screen lists, never tap inside them".
        box.addView(label("Max depth · taps from the home screen (1 = only what the home screen lists)"));
        EditText depth = new EditText(this);
        depth.setText("3");
        depth.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(depth);

        new AlertDialog.Builder(this)
                .setTitle("Build map")
                .setView(box)
                .setPositiveButton("Start", (d, w) -> {
                    int index = spinner.getSelectedItemPosition();
                    if (index < 0 || index >= packages.size()) return;
                    if ("risky".equals(states.get(index))) {
                        new AlertDialog.Builder(this)
                                .setTitle("Try once first")
                                .setMessage(names.get(index) + "'s home screen is declared singleTask, "
                                        + "It may not launch. One crawl tells you: it stops at once and remembers, "
                                        + "and marks it ⛔ next time.")
                                .setPositiveButton("Try", (d2, w2) -> {
                                    int maxScreens = parseCount(screens.getText().toString(), 12);
                                    int maxMinutes = parseCount(minutes.getText().toString(), 20);
                                    int maxDepth = parseCount(depth.getText().toString(), 3);
                                    startCrawl(packages.get(index), names.get(index),
                                            maxScreens, maxMinutes, maxDepth);
                                })
                                .setNegativeButton("Never mind", null)
                                .show();
                        return;
                    }
                    if ("refused".equals(states.get(index))) {
                        new AlertDialog.Builder(this)
                                .setTitle("This app cannot be crawled")
                                .setMessage(names.get(index) + "'s home screen is declared singleTask, "
                                        + "The system only puts it back on its own display; it will not start on the virtual one.\n\n"
                                        + "Pick an app marked ✅ instead.")
                                .setPositiveButton("Got it", null)
                                .show();
                        return;
                    }
                    getSharedPreferences("maps", MODE_PRIVATE).edit()
                            .putString("last", packages.get(index)).apply();
                    int maxScreens = parseCount(screens.getText().toString(), 12);
                    int maxMinutes = parseCount(minutes.getText().toString(), 20);
                    startCrawl(packages.get(index), names.get(index), maxScreens, maxMinutes,
                            parseCount(depth.getText().toString(), 3));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12f);
        view.setTextColor(getColor(R.color.ink_soft));
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }

    private static int parseCount(String text, int fallback) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void startCrawl(String pkg, String name, int maxScreens, int maxMinutes) {
        startCrawl(pkg, name, maxScreens, maxMinutes, 3);
    }

    private void startCrawl(String pkg, String name, int maxScreens, int maxMinutes,
                            int maxDepth) {
        crawlName = name;
        append("Build map", "Starting a map for \"" + name + "\": up to " + maxScreens
                + " screens, " + maxMinutes + " minutes.");
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect("/crawl");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject body = new JSONObject();
                body.put("package", pkg);
                body.put("app", name);
                body.put("max_screens", maxScreens);
                body.put("max_minutes", maxMinutes);
                body.put("max_depth", maxDepth);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                JSONObject reply = new JSONObject(read(conn));
                crawlId = reply.getString("id");
                lastCrawlLine = "";
                ui(() -> appendLines("Progress shows up here line by line; when it is done the map appears under Maps."
                        + "\nCrawling (tap to cancel)"));
                pollCrawl(0);
            } catch (Exception e) {
                ui(() -> append("Build map", "Could not start: " + e));
            }
        }).start();
    }

    /** Stop the running crawl; it keeps the screens it already has. */
    private void cancelCrawl() {
        final String id = crawlId;
        if (id.isEmpty()) {
            return;
        }
        note("Stopping the crawl… (what it reached is kept)");
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect("/crawl/" + id + "/cancel");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(8000);
                conn.getOutputStream().write("{}".getBytes("UTF-8"));
                read(conn);
            } catch (Throwable t) {
                note("Could not cancel: " + t);
            }
        }).start();
    }

    private void pollCrawl(int attempt) {
        if (attempt > 1200 || crawlId.isEmpty()) {   // ~50 minutes at 2.5 s
            return;
        }
        final String id = crawlId;
        new Thread(() -> {
            try {
                Thread.sleep(2500);
                HttpURLConnection conn = connect("/crawl/" + id);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(15000);
                JSONObject state = new JSONObject(read(conn));
                String status = state.optString("state", "?");
                String error = state.optString("error", "");
                JSONArray lines = state.optJSONArray("lines");
                String last = "";
                if (lines != null && lines.length() > 0) {
                    last = lines.getString(lines.length() - 1);
                }
                if ("done".equals(status) || "failed".equals(status)) {
                    final String tail = last;
                    final String took = spentSeconds(tail);
                    int screens = state.optInt("screens", 0);
                    if (screens <= 0) {
                        JSONObject coverage = state.optJSONObject("coverage");
                        if (coverage != null) screens = coverage.optInt("screens", 0);
                    }
                    final int found = screens;
                    final String appName = crawlName.isEmpty() ? "This app" : crawlName;
                    ui(() -> append("Build map", "done".equals(status)
                            ? "「" + appName + "\" finished: "
                              + (found > 0 ? found + " screens" : "Map saved")
                              + (took.isEmpty() ? "" : ", took " + took)
                              + ".\nSaved  -  see it under Maps, or pin it for use."
                            : "「" + appName + "\" did not finish: " + error));
                    crawlId = "";
                    return;
                }
                // Only milestones reach the chat. A crawl prints a line every
                // couple of seconds -- "[Enrich] screen_15: 653 chars -> 6
                // items" is a debug trace, not a conversation, and 750 of them
                // made the transcript unreadable (186 of 200 stored entries
                // were this). The full log stays on /crawl/<id>.
                if (!last.isEmpty() && !last.equals(lastCrawlLine) && isMilestone(last)) {
                    lastCrawlLine = last;
                    final String line = friendlyCrawlLine(last);
                    if (!line.isEmpty()) ui(() -> appendLines("  " + line));
                } else if (!last.isEmpty()) {
                    lastCrawlLine = last;
                }
                pollCrawl(attempt + 1);
            } catch (Exception e) {
                // the server may be busy; try again rather than losing the crawl
                pollCrawl(attempt + 1);
            }
        }).start();
    }

    /**
     * The agent's display, full screen and zoomable.
     *
     * The card in the chat is 280dp tall -- enough to see that something is
     * happening, not enough to read a row of text. This view fetches
     * /frame directly so it keeps updating even between task polls, and it
     * keeps the last good frame while a new one is on the way.
     */
    private void showFrameViewer() {
        if (frameDialog != null && frameDialog.isShowing()) {
            return;
        }
        ZoomableImage big = new ZoomableImage(this);
        bigFrame = big;
        big.setBackgroundColor(0xFF111111);
        big.setImageDrawable(frameView.getDrawable());

        TextView hint = new TextView(this);
        hint.setText(R.string.viewer_hint);
        hint.setTextSize(12);
        hint.setTextColor(0xFFBBBBBB);
        hint.setPadding(24, 12, 24, 24);
        hint.setGravity(android.view.Gravity.CENTER);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF111111);
        box.addView(big, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(hint);

        frameDialog = new android.app.Dialog(this,
                android.R.style.Theme_Material_Light_NoActionBar_Fullscreen);
        frameDialog.setContentView(box);
        frameDialog.setOnDismissListener(d -> frameDialog = null);

        // own refresh: the task poll stops as soon as a task ends, and the
        // point of watching is to see the end happen
        final android.os.Handler handler = new android.os.Handler(getMainLooper());
        final Runnable[] tick = new Runnable[1];
        final boolean[] alive = {true};
        tick[0] = new Runnable() {
            @Override
            public void run() {
                if (!alive[0] || frameDialog == null) {
                    return;
                }
                io.execute(() -> {
                    HttpURLConnection conn = null;
                    try {
                        conn = connect("/frame");
                        conn.setConnectTimeout(2000);
                        conn.setReadTimeout(4000);
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream in = conn.getInputStream()) {
                            byte[] buffer = new byte[8192];
                            int n;
                            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                        }
                        byte[] png = out.toByteArray();
                        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                        if (bitmap != null) {
                            ui(() -> {
                                if (frameDialog == null) {
                                    bitmap.recycle();
                                    return;
                                }
                                // One 1280x2772 bitmap per second otherwise: the
                                // viewer is the only place that keeps replacing
                                // the same ImageView while it is open.
                                android.graphics.drawable.Drawable old = big.getDrawable();
                                big.setImageBitmap(bitmap);
                                if (old instanceof android.graphics.drawable.BitmapDrawable) {
                                    android.graphics.Bitmap previous =
                                            ((android.graphics.drawable.BitmapDrawable) old).getBitmap();
                                    if (previous != null && previous != bitmap
                                            && !previous.isRecycled()) {
                                        previous.recycle();
                                    }
                                }
                            });
                        }
                    } catch (Throwable ignored) {
                        // the agent may be between displays; keep the old frame
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                });
                handler.postDelayed(this, 900);
            }
        };
        frameDialog.setOnDismissListener(d -> {
            alive[0] = false;
            handler.removeCallbacks(tick[0]);
            frameDialog = null;
        });
        handler.post(tick[0]);
        frameDialog.show();
    }

    /**
     * A notification for the moments the agent cannot continue by itself.
     *
     * A task can sit waiting for an answer -- a login, a payment, an app that
     * walked onto the user's screen -- while the user is in another app and
     * cannot see the chat. Without this the task simply appears stuck.
     */
    private void notifyNeedsYou(String question) {
        try {
            android.app.NotificationManager manager =
                    getSystemService(android.app.NotificationManager.class);
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    "attention", "Needs you", android.app.NotificationManager.IMPORTANCE_HIGH));
            android.content.Intent open = new android.content.Intent(this, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pending = android.app.PendingIntent.getActivity(
                    this, 9, open,
                    android.app.PendingIntent.FLAG_IMMUTABLE
                            | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(9, new android.app.Notification.Builder(this, "attention")
                    .setContentTitle(Tr.t("ORBIT needs you"))
                    .setContentText(question.length() > 60 ? question.substring(0, 60) + "…" : question)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(question))
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build());
        } catch (Throwable t) {
            android.util.Log.w("Pairing", "could not post the attention notification: " + t);
        }
    }

    /**
     * Pick up a task that is still running after the app was closed.
     *
     * The task lives in the agent service, not in this activity: reopening the
     * app used to show nothing at all, which reads as "the task was lost" when
     * the phone was in fact still working on it.
     */
    private void resumeRunningTask() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect("/tasks");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(4000);
                JSONObject data = new JSONObject(read(conn));
                JSONObject tasks = data.optJSONObject("tasks");
                if (tasks == null) {
                    return;
                }
                for (java.util.Iterator<String> it = tasks.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONObject task = tasks.optJSONObject(id);
                    if (task == null) {
                        continue;
                    }
                    String state = task.optString("state");
                    if ("running".equals(state) || "queued".equals(state)
                            || "waiting".equals(state)) {
                        final String found = id;
                        ui(() -> {
                            currentTaskId = found;
                            shownSteps = 0;
                            statusRow.setVisibility(View.VISIBLE);
                            statusText.setText("Resuming the last task…");
                            poll(found, 0);
                        });
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // no agent yet; the start path will report that
            }
        }).start();
    }

    /**
     * What the last week of conversations cost.
     *
     * The provider reports token counts with every response; the server keeps
     * them in one file next to the memories and adds them up by day, model and
     * provider.
     */
    /**
     * Token usage, with the shape of the week rather than a block of text.
     *
     * The numbers were already there but as a paragraph: 389 calls and 1.4 M
     * tokens says nothing about whether today is normal. A bar per day does.
     */
    private void showUsage() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect("/usage?days=7");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(8000);
                JSONObject data = new JSONObject(read(conn));
                ui(() -> renderUsage(data));
            } catch (Throwable t) {
                ui(() -> append("Usage", "Could not read usage: " + t));
            }
        }).start();
    }

    private void renderUsage(JSONObject data) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(4));

        JSONObject today = data.optJSONObject("today");
        JSONObject total = data.optJSONObject("total");
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.addView(usageFigure("Today", today), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        summary.addView(usageFigure("Last " + data.optInt("days", 7) + " days", total),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(summary);

        JSONArray byDay = data.optJSONArray("by_day");
        long[] values = new long[7];
        String[] labels = new String[7];
        long max = 1;
        if (byDay != null) {
            for (int i = 0; i < byDay.length() && i < 7; i++) {
                JSONObject day = byDay.optJSONObject(i);
                if (day == null) {
                    continue;
                }
                int slot = 6 - i;                       // newest first -> left to right
                if (slot < 0) {
                    continue;
                }
                values[slot] = day.optLong("total", 0);
                labels[slot] = shortDay(day.optString("day", ""));
                max = Math.max(max, values[slot]);
            }
        }

        TextView chartTitle = new TextView(this);
        chartTitle.setText("By day");
        chartTitle.setTextSize(13f);
        chartTitle.setTextColor(getColor(R.color.ink_soft));
        chartTitle.setPadding(0, dp(14), 0, dp(6));
        box.addView(chartTitle);

        LinearLayout chart = new LinearLayout(this);
        chart.setOrientation(LinearLayout.HORIZONTAL);
        chart.setGravity(android.view.Gravity.BOTTOM);
        for (int i = 0; i < 7; i++) {
            chart.addView(usageBar(labels[i], values[i], max),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        box.addView(chart);

        JSONArray tasks = data.optJSONArray("by_task");
        java.util.List<View> taskRows = new java.util.ArrayList<>();
        if (tasks != null) {
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String label = task.optString("label", "");
                if (label.isEmpty()) {
                    label = task.optString("task", "?");
                }
                // One line per task: a long instruction wrapped to two lines and
                // five of them filled the page on their own.
                if (label.length() > 24) {
                    label = label.substring(0, 24) + "…";
                }
                TextView row = new TextView(this);
                row.setSingleLine(true);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setText(getString(R.string.usage_task_row, label,
                        task.optInt("calls", 0), tokens(task.optLong("total", 0))));
                row.setTextSize(12.5f);
                row.setTextColor(getColor(R.color.ink));
                row.setPadding(0, dp(3), 0, dp(3));
                taskRows.add(row);
            }
        }
        usageSection(box, "By task", taskRows, 5);

        JSONArray models = data.optJSONArray("by_model");
        java.util.List<View> modelRows = new java.util.ArrayList<>();
        if (models != null) {
            for (int i = 0; i < models.length(); i++) {
                JSONObject model = models.optJSONObject(i);
                if (model == null) {
                    continue;
                }
                TextView row = new TextView(this);
                row.setText(getString(R.string.usage_model_row, model.optString("model", "?"),
                        model.optInt("calls", 0), tokens(model.optLong("total", 0))));
                row.setTextSize(12.5f);
                row.setTextColor(getColor(R.color.ink));
                row.setPadding(0, dp(3), 0, dp(3));
                modelRows.add(row);
            }
        }
        usageSection(box, "By model", modelRows, 5);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Token usage")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    /**
     * A usage list that does not grow without end.
     *
     * Both the task and model lists used to print every row they were given, so
     * the page got longer with every task. The first few are always visible and
     * the rest sit behind one toggle.
     */
    private void usageSection(LinearLayout box, String title, java.util.List<View> rows,
                              int preview) {
        if (rows.isEmpty()) {
            return;
        }
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(13f);
        heading.setTextColor(getColor(R.color.ink_soft));
        heading.setPadding(0, dp(16), 0, dp(4));
        box.addView(heading);

        int shown = Math.min(preview, rows.size());
        for (int i = 0; i < shown; i++) {
            box.addView(rows.get(i));
        }
        if (rows.size() <= preview) {
            return;
        }
        TextView more = new TextView(this);
        more.setText(getString(R.string.usage_more, rows.size()));
        more.setTextSize(12.5f);
        more.setTextColor(getColor(R.color.accent));
        more.setPadding(0, dp(6), 0, dp(2));
        box.addView(more);
        more.setOnClickListener(v -> {
            int at = box.indexOfChild(more);
            for (int i = shown; i < rows.size(); i++) {
                box.addView(rows.get(i), at + (i - shown));
            }
            box.removeView(more);
            TextView less = new TextView(this);
            less.setText("Collapse");
            less.setTextSize(12.5f);
            less.setTextColor(getColor(R.color.accent));
            less.setPadding(0, dp(6), 0, dp(2));
            box.addView(less, at + (rows.size() - shown));
            less.setOnClickListener(v2 -> {
                for (int i = shown; i < rows.size(); i++) {
                    box.removeView(rows.get(i));
                }
                box.removeView(less);
                box.addView(more, at);
            });
        });
    }

    private android.view.View usageFigure(String caption, JSONObject numbers) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(4), dp(6), dp(4));

        TextView label = new TextView(this);
        label.setText(caption);
        label.setTextSize(12f);
        label.setTextColor(getColor(R.color.ink_soft));
        column.addView(label);

        long tokens = numbers == null ? 0 : numbers.optLong("total", 0);
        int calls = numbers == null ? 0 : numbers.optInt("calls", 0);

        TextView big = new TextView(this);
        big.setText(tokens(tokens));
        big.setTextSize(22f);
        big.setTextColor(getColor(R.color.ink));
        big.setTypeface(null, android.graphics.Typeface.BOLD);
        column.addView(big);

        TextView small = new TextView(this);
        small.setText(getString(R.string.usage_calls, calls));
        small.setTextSize(12f);
        small.setTextColor(getColor(R.color.ink_soft));
        column.addView(small);
        return column;
    }

    /** One bar: value on top, height proportional, day underneath. */
    private android.view.View usageBar(String label, long value, long max) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(3), 0, dp(3), 0);

        TextView amount = new TextView(this);
        amount.setText(value <= 0 ? "" : compact(value));
        amount.setTextSize(9.5f);
        amount.setTextColor(getColor(R.color.ink_soft));
        amount.setGravity(android.view.Gravity.CENTER);
        column.addView(amount);

        View bar = new View(this);
        int height = value <= 0 ? dp(3) : (int) (dp(96) * value / Math.max(1, max));
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setColor(getColor(value <= 0 ? R.color.line : R.color.accent));
        shape.setCornerRadius(dp(5));
        bar.setBackground(shape);
        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(Math.min(dp(28), dp(200)), Math.max(dp(3), height));
        // the bars sit on a common baseline
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(android.view.Gravity.BOTTOM);
        wrapper.setMinimumHeight(dp(100));
        wrapper.addView(bar, barParams);
        column.addView(wrapper);

        TextView day = new TextView(this);
        day.setText(label);
        day.setTextSize(9.5f);
        day.setTextColor(getColor(R.color.ink_soft));
        day.setGravity(android.view.Gravity.CENTER);
        column.addView(day);
        return column;
    }

    /** "23:41" -- the clock time of a message. */
    private static String hhmm(long millis) {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(millis));
    }

    private static String shortDay(String iso) {
        if (iso == null || iso.length() < 10) {
            return "";
        }
        return iso.substring(5);                 // "11-12"
    }

    private static String tokens(long value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format(java.util.Locale.US, "%.0fK", value / 1_000.0);
        }
        return String.valueOf(value);
    }

    private static String compact(long value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return Math.round(value / 1_000.0) + "K";
        }
        return String.valueOf(value);
    }

    /** Bring the chat back to the front after a task driven on the user's screen. */
    private void returnToChat() {
        try {
            android.content.Intent back = new android.content.Intent(this, MainActivity.class);
            // REORDER_TO_FRONT moves the existing task to the front; NEW_TASK
            // was also there, and together they produced a *second* instance of
            // this activity whenever the task was not already in front --
            // measured: two live MainActivity tasks, the visible one watching a
            // finished task forever while the other one held the answer.
            back.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(back);
        } catch (Throwable ignored) {
            // if it does not come forward the answer is still in the chat
        }
    }

    private void showSettings() {
        AgentConfig.Config cfg = AgentConfig.load(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText key = new EditText(this);
        key.setHint("API Key(sk-or-v1-…)");
        key.setText(cfg.getApiKey());
        key.setSingleLine(true);
        key.setHint("API Key(sk-or-v1-… / sk-…)");

        EditText model = new EditText(this);
        model.setHint("model");
        model.setText(cfg.getModel());
        model.setSingleLine(true);

        EditText base = new EditText(this);
        base.setHint("api base (e.g. https://api.deepseek.com/v1)");
        base.setText(cfg.getApiBase());
        base.setSingleLine(true);

        EditText vision = new EditText(this);
        vision.setHint("Vision model (empty = same as above)");
        vision.setText(cfg.getVisionModel());
        vision.setSingleLine(true);

        EditText rounds = new EditText(this);
        rounds.setHint("Max steps per task (default 35)");
        rounds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        rounds.setText(cfg.getMaxRounds() > 0 ? String.valueOf(cfg.getMaxRounds()) : "35");
        rounds.setSingleLine(true);

        // One row instead of a wall of fields: the provider decides the base
        // URL and the model id, and everything else hides under Advanced settings. The
        // chip row this replaces only offered seven providers and still asked
        // the reader to understand what a base URL was.
        final String[][] PROVIDERS = {
                {"DeepSeek Flash", "https://api.deepseek.com", "deepseek-flash"},
                {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"},
                {"Groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-120b"},
                {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"},
                {"Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-5"},
                {"Custom", "", ""},
        };

        TextView provider = new TextView(this);
        provider.setTextSize(15f);
        provider.setPadding(0, dp(6), 0, dp(6));
        String currentBase = cfg.getApiBase();
        String providerName = "";
        for (String[] row : PROVIDERS) {
            if (!row[1].isEmpty() && row[1].equals(currentBase)) {
                providerName = row[0];
                break;
            }
        }
        provider.setText("" + ":  " + providerName + "  \u203a");
        provider.setOnClickListener(v -> {
            String[] labels = new String[PROVIDERS.length];
            for (int i = 0; i < PROVIDERS.length; i++) {
                labels[i] = PROVIDERS[i][0];
            }
            new AlertDialog.Builder(this)
                    .setTitle("")
                    .setItems(labels, (d, which) -> {
                        provider.setText("" + ":  " + PROVIDERS[which][0] + "  \u203a");
                        if (!PROVIDERS[which][1].isEmpty()) {
                            base.setText(PROVIDERS[which][1]);
                            model.setText(PROVIDERS[which][2]);
                        }
                    })
                    .show();
        });

        // A pasted key often names its provider already. Fill the endpoint and
        // model as soon as the prefix is recognised; anything else stays Custom.
        key.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                String value = text.toString().trim();
                String picked = "";
                if (value.startsWith("gsk_")) {
                    picked = "Groq";
                } else if (value.startsWith("sk-ant-")) {
                    picked = "Anthropic";
                } else if (value.startsWith("sk-proj-")) {
                    picked = "OpenAI";
                } else if (value.startsWith("sk-")) {
                    picked = "DeepSeek Flash";
                }
                if (picked.isEmpty()) {
                    return;
                }
                for (String[] row : PROVIDERS) {
                    if (row[0].equals(picked)) {
                        provider.setText("" + ":  " + row[0] + "  \u203a");
                        base.setText(row[1]);
                        model.setText(row[2]);
                        return;
                    }
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        // Advanced settings: the four fields a user should rarely touch.
        LinearLayout advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        advanced.addView(model);
        advanced.addView(base);
        advanced.addView(vision);
        advanced.addView(rounds);

        TextView advancedToggle = new TextView(this);
        advancedToggle.setText("" + "  \u25b8");
        advancedToggle.setTextSize(13f);
        advancedToggle.setTextColor(getColor(R.color.ink_soft));
        advancedToggle.setPadding(0, dp(10), 0, dp(2));
        advancedToggle.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedToggle.setText("" + (show ? "  \u25be" : "  \u25b8"));
        });

        TextView test = new TextView(this);
        test.setText("Test connection");
        test.setTextSize(13f);
        test.setTextColor(getColor(R.color.accent));
        test.setPadding(0, dp(10), 0, 0);
        test.setOnClickListener(v -> {
            test.setText("Testing…");
            new Thread(() -> {
                String result;
                try {
                    org.json.JSONObject body = new org.json.JSONObject()
                            .put("api_key", key.getText().toString().trim())
                            .put("api_base", base.getText().toString().trim())
                            .put("model", model.getText().toString().trim())
                            .put("vision_model", vision.getText().toString().trim());
                    HttpURLConnection conn = connect("/test-llm");
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                    org.json.JSONObject out = new org.json.JSONObject(read(conn));
                    result = out.optBoolean("ok")
                            ? "✅ Works: " + out.optString("detail")
                            : "❌ " + out.optString("detail");
                } catch (Throwable t) {
                    if (t instanceof java.net.ConnectException) {
                        restartAgentService("test connection: " + t);
                        result = "❌ " + Tr.t("Local agent did not respond; restart triggered. "
                                + "Please tap Test connection again in a few seconds.");
                    } else {
                        result = "❌ " + t;
                    }
                }
                final String text = result;
                ui(() -> test.setText(text));
            }).start();
        });

        // The system-overlay capsule is gone: the mirror is a view inside this
        // window now (see the layout), so nothing needs a permission and nothing
        // floats over other apps. The old service only drew that overlay.

        // The old label said Autonomous mode: no asking before spending, which reads as "and it stops
        // asking about other things too". It does not: this switch covers money
        // and destructive shell commands, nothing else. Name what it does, and
        // ask once before switching it on -- paying is not undoable.
        CheckBox autonomous = new CheckBox(this);
        autonomous.setText("Never ask me about money (auto-pay / auto-order)");
        autonomous.setChecked(autonomousMode());
        autonomous.setOnClickListener(v -> {
            if (!autonomous.isChecked()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Never ask me about money (auto-pay / auto-order)")
                    .setMessage("With this on, paying, ordering and submitting run without asking again.")
                    .setPositiveButton("", null)
                    .setNegativeButton("Cancel", (d, w) -> autonomous.setChecked(false))
                    .show();
        });

        TextView diag = new TextView(this);
        diag.setText("Diagnostics: inspect the runtime");
        diag.setTextSize(13f);
        diag.setTextColor(getColor(R.color.ink_soft));
        diag.setPadding(0, dp(14), 0, dp(4));
        diag.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Runtime")
                .setMessage((bootInfo.isEmpty() ? "not started" : bootInfo)
                        + "\n\nheartbeat: " + beatLine())
                .setPositiveButton("Got it", null)
                .setNeutralButton("Reconnect now", (d2, w2) -> {
                    alert("Checking the connection…");
                    healAgent(true);
                })
                .show());

        TextView keyLabel = new TextView(this);
        keyLabel.setText("API Key");
        keyLabel.setTextSize(13f);
        keyLabel.setTextColor(getColor(R.color.ink_soft));
        keyLabel.setPadding(0, dp(4), 0, 0);
        box.addView(keyLabel);
        box.addView(key);
        box.addView(provider);
        box.addView(test);
        box.addView(advancedToggle);
        box.addView(advanced);
        box.addView(autonomous);
        box.addView(diag);

        new AlertDialog.Builder(this)
                .setTitle("Model settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    getSharedPreferences("agent", MODE_PRIVATE).edit()
                            .putBoolean("autonomous", autonomous.isChecked()).apply();
                    int steps;
                    try {
                        steps = Integer.parseInt(rounds.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        steps = 0;
                    }
                    // Clamped rather than rejected: a typo should not silently
                    // become a one-step task.
                    steps = Math.max(5, Math.min(80, steps));
                    saveAgentConfig(
                            key.getText().toString().trim(),
                            base.getText().toString().trim(),
                            model.getText().toString().trim(),
                            vision.getText().toString().trim(),
                            steps);
                    // Save should tell you whether the key works; asking the
                    // user to press Test connection straight after is a second thought.
                    if (key.getText().toString().trim().isEmpty()) {
                        alert("Key cleared; the agent can only run in screenshot mode.");
                    } else {
                        test.performClick();
                    }
                })
                .setNeutralButton("Pair wireless debugging", (d, w) -> startPairingWatch())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static final int REQUEST_BACKUP_IMPORT = 1001;
    private TextView modeButton;

    /**
     * Two ways to drive a phone, described without the jargon.
     *
     * A list dialog clipped the second hint: AlertController gives its list a
     * fixed maximum height and the item text is taller than one line. Two rows
     * in a plain LinearLayout size themselves.
     */
    private void showModeChooser() {
        boolean auto = mapsMode();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        android.widget.RadioButton autoRow = modeOption(
                Tr.t(getString(R.string.mode_auto)), Tr.t(getString(R.string.mode_auto_hint)), auto);
        android.widget.RadioButton visionRow = modeOption(
                Tr.t(getString(R.string.mode_vision)), Tr.t(getString(R.string.mode_vision_hint)), !auto);
        box.addView(autoRow);
        box.addView(visionRow);

        // Hands-off mode belongs here, not buried in Settings: it changes what the user will
        // see happen on their own screen, which is the most surprising thing this
        // app can do.
        android.widget.CheckBox handsOff = new android.widget.CheckBox(this);
        handsOff.setText(handsOffLabel());
        handsOff.setTextSize(14f);
        handsOff.setChecked(handsOffMode());
        handsOff.setPadding(0, dp(12), 0, dp(2));
        handsOff.setOnCheckedChangeListener((b, checked) -> {
            getSharedPreferences("agent", MODE_PRIVATE).edit()
                    .putBoolean("hands_off", checked).apply();
            android.widget.Toast.makeText(this,
                    checked ? R.string.hands_off_on_toast : R.string.hands_off_off_toast,
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        box.addView(handsOff);

        TextView handsHint = new TextView(this);
        handsHint.setText(R.string.hands_off_hint);
        handsHint.setTextSize(11.5f);
        handsHint.setTextColor(getColor(R.color.ink_soft));
        handsHint.setPadding(dp(36), 0, 0, dp(6));
        box.addView(handsHint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.mode_title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();

        autoRow.setOnClickListener(v -> {
            applyMode(true);
            dialog.dismiss();
        });
        visionRow.setOnClickListener(v -> {
            applyMode(false);
            dialog.dismiss();
        });
        dialog.show();
    }

    private String beatLine() {
        long seconds = AgentHealth.beatSeconds(this);
        if (seconds < 0) {
            return "no heartbeat yet";
        }
        return seconds <= 90 ? seconds + " s ago (ok)" : seconds + " s ago (may be stuck)";
    }

    private String handsOffLabel() {
        return getString(R.string.hands_off_label);
    }

    /** One choice: the name, and a line saying what it does. */
    private android.widget.RadioButton modeOption(String title, String hint, boolean checked) {
        android.widget.RadioButton row = new android.widget.RadioButton(this);
        row.setText(getString(R.string.mode_option, title, hint));
        row.setTextSize(14f);
        row.setChecked(checked);
        row.setButtonTintList(null);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setOnClickListener(v -> { });
        return row;
    }

    private void applyMode(boolean auto) {
        setMapsMode(auto);
        if (modeButton != null) {
            modeButton.setText(Tr.t(auto ? getString(R.string.mode_auto) : getString(R.string.mode_vision)));
        }
        android.widget.Toast.makeText(this,
                Tr.t(auto ? getString(R.string.mode_auto_toast) : getString(R.string.mode_vision_toast)),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private boolean isCapsuleOn() {
        return getSharedPreferences("agent", MODE_PRIVATE).getBoolean("capsule", false);
    }

    private void startCapsule() {
        getSharedPreferences("agent", MODE_PRIVATE).edit().putBoolean("capsule", true).apply();
        // Without POST_NOTIFICATIONS the capsule has no way to reach the user
        // when a background activity start is refused (measured: granted=false
        // is the default on Android 13+, and the tapping notification never
        // appeared).
        // minSdk is 34, so POST_NOTIFICATIONS always exists; no version guard.
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
        startForegroundService(new Intent(this, CapsuleService.class));
    }

    private void stopCapsule() {
        getSharedPreferences("agent", MODE_PRIVATE).edit().putBoolean("capsule", false).apply();
        stopService(new Intent(this, CapsuleService.class));
    }

    /** Ask mode by default: a step that spends money waits for the user. */
    /**
     * CLI+GUI by default: replay the compiled map first, fall back to vision.
     * GUI-only drops the maps entirely and drives the phone from screenshots.
     */
    private boolean mapsMode() {
        return getSharedPreferences("agent", MODE_PRIVATE).getBoolean("maps", true);
    }

    private void setMapsMode(boolean on) {
        getSharedPreferences("agent", MODE_PRIVATE).edit().putBoolean("maps", on).apply();
    }

    /** Drive the phone's own screen instead of a virtual display. */
    private boolean handsOffMode() {
        return getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("hands_off", false);
    }

    private boolean autonomousMode() {
        // Opt-in, and only through the switch: a build that stored the old
        // Autonomous mode flag is reset once, because the label made it sound harmless.
        android.content.SharedPreferences prefs = getSharedPreferences("agent", MODE_PRIVATE);
        if (!prefs.getBoolean("autonomous_default_migrated", false)) {
            prefs.edit().putBoolean("autonomous", false)
                    .putBoolean("autonomous_default_migrated", true).apply();
        }
        return prefs.getBoolean("autonomous", false);
    }

    private void saveAgentConfig(String apiKey, String apiBase, String model,
                                 String visionModel, int maxRounds) {
        try {
            AgentConfig.save(this, apiKey, apiBase, model, visionModel, maxRounds, "");
            note(apiKey.isEmpty()
                    ? "Key cleared; the agent can only run in screenshot mode."
                    : "Saved, restarting the local agent…");
            AgentService.restart(this);
        } catch (Exception e) {
            append("ORBIT", Tr.t("Save failed: ") + e);
        }
    }

    /**
     * Without a key the task server declines to start, and that failure is
     * invisible from here -- the user just sees nothing happen.
     */
    private void checkApiKey() {
        if (!AgentConfig.hasKey(this)) {
            note("No model key yet. Open Settings and paste an API key, or tasks cannot run.");
        }
    }

    private boolean accessibilityWarned;

    /**
     * Worth saying out loud: without the service the agent can only look at
     * pixels. But do not say it too early -- the Python side turns the service
     * back on itself as soon as it has its adb shell (MIUI forgets it after a
     * reboot), so a check at launch reports a state that is about to change.
     * Wait for the repair, and if it worked, say that instead.
     */
    private void checkAccessibility() {
        // Adb comes up around 20 s in on this phone; warning before the repair
        // has had a chance is how the app ended up telling people the service
        // was off while it was already on.
        checkAccessibility(22000);
    }

    private int accessibilityChecks;

    private void checkAccessibility(long delayMs) {
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            boolean enabled = accessibilityEnabled();
            if (enabled) {
                if (accessibilityWarned) {
                    accessibilityWarned = false;
                    note("✅ Accessibility service is ready (the app restores it at launch).");
                }
                return;
            }
            if (!accessibilityWarned) {
                accessibilityWarned = true;
note(Tr.t("Hint: accessibility service is not enabled. Without it, ORBIT cannot read the pairing code,")
        + Tr.t("so the agent can only use screenshots (cannot click by text label).")
                        + Tr.t("\nLong-press \"Settings\" to open the accessibility settings page directly."));
            }
            // The repair happens once adb is up, which can be 20 s in; keep
            // looking for a while so the warning clears itself instead of
            // staying on screen after the problem is gone.
            if (accessibilityChecks++ < 6) {
                checkAccessibility(12000);
            }
        }, delayMs);
    }

    private static String readText(java.io.File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Brings up the in-app agent: virtual display plus the HTTP server the
     * chat below talks to. Everything happens on this phone -- the server is
     * in this process, not on a host reached through `adb reverse`.
     */
    /** When the agent last restarted itself, so a broken state cannot loop. */
    private long lastAgentRestart = 0;

    /**
     * Bring the local agent back without asking the user to swipe the app away.
     *
     * The failure this answers: the Python server stops answering (heartbeat
     * older than two minutes, or "Failed to connect to /127.0.0.1:8765" on the
     * first task) while the process is still alive. Telling the user to kill it
     * from Recents works, but it is the app's job first.
     */
    private void restartAgentService(String why) {
        long now = System.currentTimeMillis();
        if (now - lastAgentRestart < 120000) {
            return;      // tried a moment ago; do not loop
        }
        lastAgentRestart = now;
        android.util.Log.i("Pairing", "restarting the agent: " + why);
        try {
            stopService(new Intent(this, AgentService.class));
        } catch (Throwable ignored) {
            // not running, or already gone
        }
        // The beat file is the stall detector's input: left behind, it makes a
        // freshly started service look stalled for the first half minute.
        java.io.File beat = new java.io.File(getCacheDir(), "chaquopy/tmp/orbit-beat.txt");
        //noinspection ResultOfMethodCallIgnored
        beat.delete();
        append("ORBIT", Tr.t("Local agent not responding, restarting automatically…"));
        startAgentService();
    }

    private void startAgentService() {
        if (!hasIdentity()) {
            note("No pairing identity yet. Pair wireless debugging first.");
            return;
        }
        if (!wirelessDebuggingEnabled()) {
            note("Wireless debugging is off, so ORBIT cannot reach its own adbd."
                    + "\nOpen Settings → Developer options → Wireless debugging, turn it on, then come back.");
            return;
        }
        try {
            startForegroundService(new Intent(this, AgentService.class));
            note("Starting the local agent (finding adbd, creating the display)…");
        } catch (Throwable t) {
            append("ORBIT", Tr.t("Failed to start local agent:\n") + t);
        }
    }

    private boolean hasIdentity() {
        java.io.File dir = new java.io.File(getFilesDir(), "identity");
        return new java.io.File(dir, "pair-cert.pem").exists()
                && new java.io.File(dir, "pair-key.pem").exists();
    }

    /**
     * HyperOS kills a process that only holds a foreground service, so the
     * agent has to be exempt from battery optimisation to survive in the
     * background. There is no API to grant it; the best we can do is take the
     * user to the right page.
     */
    private void showSystemSettings() {
        new AlertDialog.Builder(this)
                .setTitle("System settings")
                .setItems(new String[]{
                        "Accessibility service", "Battery optimisation (background)",
                        "Export backup (includes the API key)", "Import backup",
                        ""}, (d, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } else if (which == 1) {
                        requestBatteryExemption();
                    } else if (which == 2) {
                        exportBackup();
                    } else if (which == 3) {
                        pickBackup();
                    } else {
                        chooseLanguage();
                    }
                })
                .show();
    }

    /**
     * The interface language. "System" is the default so a Chinese phone shows
     * Chinese without anyone choosing; English is here because the repository is
     * read in English and the phone it runs on may not be.
     */
    private void chooseLanguage() {
        String[] labels = {"Follow system", "Chinese", "English"};
        String[] modes = {"system", "zh", "en"};
        String current = getSharedPreferences("agent", MODE_PRIVATE).getString("lang", "system");
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("Language")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    getSharedPreferences("agent", MODE_PRIVATE).edit()
                            .putString("lang", modes[which]).apply();
                    d.dismiss();
                    recreate();
                })
                .show();
    }

    /**
     * A reinstall used to mean re-pairing, re-entering the key and losing every
     * crawled map: they live in the app's private data, and the key is sealed
     * with a Keystore key that dies with the install. One zip brings all of it
     * back; the dialog says the key is in the clear before the file is written.
     */
    private void exportBackup() {
        new AlertDialog.Builder(this)
                .setTitle("Export backup")
                .setMessage("Packs the pairing identity, the maps, the chats and the model settings into one zip, "
                        + "saved to Downloads.\n\n"
                        + "The API key inside is plain text (on this phone it is encrypted and cannot be decrypted elsewhere), "
                        + "so do not hand the file to anyone.")
                .setPositiveButton("Export", (d, w) -> {
                    note("Exporting…");
                    new Thread(() -> {
                        try {
                            String name = Backup.exportToDownloads(this);
                            runOnUiThread(() -> note("Backup saved to Downloads: " + name));
                        } catch (Exception e) {
                            runOnUiThread(() -> note("Export failed: " + e.getMessage()));
                        }
                    }, "backup-export").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip",
                "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_BACKUP_IMPORT);
        } catch (Exception e) {
            note("Could not open the file picker: " + e.getMessage());
        }
    }

    private void importBackup(Uri uri) {
        note("Importing…");
        new Thread(() -> {
            try {
                int files = Backup.restore(this, uri);
                runOnUiThread(() -> {
                    note("Restored " + files + " files. Restarting the agent…");
                    healAgent(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> note("Import failed: " + e.getMessage()));
            }
        }, "backup-import").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BACKUP_IMPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importBackup(data.getData());
        }
    }

    /**
     * Play policy dislikes this dialog; MIUI needs it. Without an exemption the
     * system freezes the agent's process mid-task (measured on this phone), so
     * the trade is deliberate.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && power.isIgnoringBatteryOptimizations(getPackageName())) {
            note("Battery optimisation is already off for ORBIT; it can stay in the background.");
            return;
        }
        try {
            startActivity(new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            // MIUI replaces the stock page with its own
            try {
                startActivity(new Intent().setComponent(new ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")));
            } catch (Exception inner) {
                append("ORBIT", Tr.t("Cannot open battery settings, go manually to \"Settings → Apps → ORBIT → Battery optimization → Unrestricted\"."));
            }
        }
    }

    /** Without this adbd has no TLS listener at all, so nothing can connect. */
    private boolean wirelessDebuggingEnabled() {
        return android.provider.Settings.Global.getInt(
                getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    private void checkWirelessDebugging() {
        if (!wirelessDebuggingEnabled()) {
            note("Note: wireless debugging is off and adbd is not listening."
                    + "\nOpen Settings → Developer options → Wireless debugging and turn it on.");
        }
    }

    private void checkBattery() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isIgnoringBatteryOptimizations(getPackageName())) {
            note("Note: battery optimisation may kill ORBIT in the background."
                    + "\nLong-press Settings → Battery optimisation to set it unrestricted.");
        }
    }

    private boolean accessibilityEnabled() {
        String enabled = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName());
    }

    /**
     * Pairs without the user retyping anything: the service watches the
     * screen, and the system's pairing dialog is the only place the code
     * exists. It also dies as soon as that dialog is dismissed, so this has to
     * run in the background while the user stays on the Settings screen --
     * which is exactly why typing the code in by hand cannot work either.
     */
    private void startPairingWatch() {
        if (!accessibilityEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn on the accessibility service first")
                    .setMessage("The pairing code only exists in the system dialog, and closing it stops the pairing service, "
                            + Tr.t("so only ORBIT itself can read it.\n\n")
                            + Tr.t("Go to \"Settings → Accessibility\" to enable ORBIT, then return to pair."))
                    .setPositiveButton("Open accessibility settings", (d, w) ->
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        try {
            Intent intent = new Intent(this, PairingService.class);
            intent.putExtra("watch", true);
            startForegroundService(intent);
            note("Turn on Settings → Developer options → Wireless debugging → Pair device with pairing code."
                    + "\nORBIT reads the code itself; you do not type it. Finish within two minutes of the dialog appearing.");
        } catch (Throwable t) {
            append("ORBIT", Tr.t("Failed to start pairing:\n") + t);
        }
    }

    private void startTask(String text) {
        new Thread(() -> {
            Exception last = null;
            int failures = 0;
            boolean restarted = false;
            // the display takes a few seconds to come up; retry rather than
            // failing the user's first message
            for (int attempt = 0; attempt < 100; attempt++) {
                // Restart on refusals alone, not on `isReady()`: that flag is
                // only ever set, so a server that hung after answering looks
                // ready while refusing every connection -- measured on a phone
                // where the heartbeat was fresh and 127.0.0.1:8765 was dead. Ten
                // seconds of refusals is longer than a cold start needs.
                if (failures == 10 && !restarted) {
                    restarted = true;
                    restartAgentService("no server after 10 attempts: " + last);
                }
                try {
                    HttpURLConnection conn = connect("/task");
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    JSONObject body = new JSONObject();
                    body.put("text", text);
                    body.put("autonomous", autonomousMode());
                    body.put("maps", mapsMode());
                    if (handsOffMode()) {
                        // the user handed the phone over: drive their own screen
                        body.put("display", 0);
                    }
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    final String id = new JSONObject(read(conn)).getString("id");
                    ui(() -> {
                        currentTaskId = id;
                        shownSteps = 0;
                        lastFrame = "";
                        setBusy(true);
                        // The status row already says Running… and offers Cancel;
                        // a task id in the transcript was pure noise.
                        showThinking();
                    });
                    poll(id, 0);
                    return;
                } catch (Exception e) {
                    last = e;
                    failures++;
                    if (failures == 1) {
                        ui(() -> append("ORBIT", Tr.t("Waiting for local agent to be ready…")));
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
            final Exception e = last;
            final boolean tried = restarted;
            boolean noKey = !AgentConfig.hasKey(this);
            ui(() -> append("ORBIT", noKey
                    ? Tr.t("Task could not be submitted: no model API key configured, go to \"Settings\" to enter it.\n") + e
                    : Tr.t("Local agent did not start:\n") + e
                    + (tried ? Tr.t("\n(Already tried auto-restart once; if it keeps happening, swipe ORBIT away from Recents and open again.)") : "")));
        }).start();
    }

    @Override
    protected void onDestroy() {
        // A Dialog holds a window on this activity; leaving one showing while
        // the activity goes away is a leaked window (logcat: "WindowLeaked").
        if (frameDialog != null) {
            frameDialog.dismiss();
            frameDialog = null;
        }
        // Every repeating runnable on this handler (the mirror ticker, the
        // thinking dots, the capsule fold timer) captures `this`. Left running
        // after the activity is gone they keep it, and its whole view tree,
        // reachable from a static handler queue.
        handler.removeCallbacksAndMessages(null);
        polling = false;
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveNow();          // a session switch or a kill must not lose the last line
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The app can come back from a freeze with its poll chain gone; ask
        // about the task again instead of showing a stale "Working…". The age of
        // the last answer is the only trustworthy signal -- `polling` stays
        // true when the chain died silently.
        if (!currentTaskId.isEmpty() && !polling
                && System.currentTimeMillis() - lastPollAt > 15000) {
            poll(currentTaskId, 0);
        }
        // Coming back to the app is the moment the user expects it to work
        // again: wireless debugging may have been off while it was away, and
        // waiting for the next task to find that out wastes the first task.
        handler.postDelayed(this::healAgent, 1200);
    }

    /**
     * Rebuild the display if the adb connection died while the app was away.
     *
     * Runs off the UI thread (the check is a shell command on the phone's own
     * adbd) and at most once every half minute, so switching apps does not turn
     * into a reconnect loop.
     */
    private void healAgent() {
        healAgent(false);
    }

    /** manual: the user pressed Reconnect now, so skip the throttle and say what happened. */
    /** Last warm-up of the mapped apps, so a resume does not repeat it. */
    private long lastWarmup = 0;

    private void healAgent(boolean manual) {
        long now = System.currentTimeMillis();
        // Only once the agent is up: "started" is set before the display exists,
        // and a check in that window would rebuild the display the service is
        // still building, leaving the server down.
        if (!currentTaskId.isEmpty() || !crawlId.isEmpty()) {
            // A task or a crawl owns the adb transport: it is one channel and
            // everything
            // travels in order. Asking healthy() from here interleaved a shell
            // command with the agent's own reads and corrupted the stream --
            // "frame magic mismatch" on three tasks in a row, and then again on
            // every crawl, because a crawl only skips the heal when a *task*
            // is running.
            return;
        }
        boolean ready = AgentService.isReady();
        // Off unless asked for. The warm-up launches the first mapped apps
        // (Settings, ReviewApp, NoteApp) on the agent's display, and to anyone
        // watching the phone it looks exactly like the agent wandering into the
        // wrong app in the middle of a task -- measured: a NoteApp→TravelApp task that
        // opened ReviewApp while it was being set up. The saving (nine steps
        // instead of four on the first task) is not worth that.
        boolean warmup = getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("warmup", false);
        if (ready && warmup && System.currentTimeMillis() - lastWarmup > 10 * 60 * 1000L) {
            lastWarmup = System.currentTimeMillis();
            new Thread(() -> {
                try {
                    HttpURLConnection conn = connect("/warmup");
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(8000);
                    conn.getOutputStream().write("{}".getBytes("UTF-8"));
                    read(conn);
                } catch (Throwable ignored) {
                    // a warm-up that fails costs nothing
                }
            }).start();
        }
        long beat = AgentHealth.beatSeconds(this);
        android.util.Log.i("Pairing", "heal: ready=" + ready + " beat=" + beat
                + "s since=" + (now - lastHealAt) / 1000 + "s");
        if (!ready) {
            // Coming back to a freshly started app: the display takes twenty
            // seconds, so a single check at 1.2 s always finds it unready and
            // the heal would never run when it is most needed.
            if (manual) {
                alert("The agent is still starting; give it ten seconds and try again.");
                return;
            }
            if (healAttempts < 10) {
                healAttempts++;
                handler.postDelayed(this::healAgent, 3000);
            }
            return;
        }
        healAttempts = 0;
        if (!manual && now - lastHealAt < 30000) {
            return;
        }
        lastHealAt = now;
        // A frozen interpreter cannot answer healthy(); asking it would hang this
        // check forever. The heartbeat file is the one signal that survives.
        if (beat > 120) {
            if (System.currentTimeMillis() - lastAgentRestart > 120000) {
                restartAgentService("heartbeat " + beat + "s old");
                healAttempts = 0;
                handler.postDelayed(this::healAgent, 8000);
                return;
            }
            alert(Tr.t("ORBIT's background process is stuck (") + beat + Tr.t(" seconds without heartbeat.")
                    + Tr.t("\nI already tried restarting once; it hasn't recovered, please swipe ORBIT away from Recents and open again."));
            return;
        }
        new Thread(() -> {
            String verdict = "";
            try {
                PyObject ondevice = Python.getInstance().getModule("orbit.ondevice");
                boolean healthy = Boolean.parseBoolean(
                        ondevice.callAttr("healthy").toString());
                android.util.Log.i("Pairing", "heal: healthy=" + healthy);
                if (healthy) {
                    if (manual) {
                        ui(() -> alert("The connection is fine; nothing to reconnect."));
                    }
                    return;
                }
                // recover_json answers {"ok": ..., "message": ...}. The plain
                // recover() returns a sentence whose success form is
                // "display=367 size=1280x2772 dpi=520 app=..." -- that string
                // reached the chat once and looked like a bug report.
                org.json.JSONObject recovery =
                        new org.json.JSONObject(ondevice.callAttr("recover_json").toString());
                boolean rebuilt = recovery.optBoolean("ok", false);
                android.util.Log.i("Pairing", "heal: rebuilt=" + rebuilt
                        + " via " + recovery.optString("message", ""));
                if (rebuilt) {
                    verdict = manual ? "Reconnected  -  carry on." : "Reconnected automatically  -  carry on.";
                } else {
                    verdict = recovery.optString("message", "");
                }
            } catch (Throwable t) {
                verdict = "Reconnect failed: " + t;
                android.util.Log.w("Pairing", "heal failed", t);
            }
            final String result = verdict;
            ui(() -> {
                if (result.isEmpty() || result.startsWith("nothing")) {
                    return;
                }
                alert(result);
            });
        }).start();
    }

    /** Consecutive poll requests that did not come back; reset by a good one. */
    private int pollFailures = 0;

    private void poll(String id, int attempt) {
        polling = true;
        // ~20 minutes. The old cap was ~6, and a cross-app task that reads a
        // note and then prices on another app legitimately runs that long: the
        // app gave up while the agent was still working, told the user "timeout not
        // returned", and the answer never appeared even though the run finished.
        if (attempt > 1600) {
            polling = false;
            ui(() -> {
                append("ORBIT", "This task has been running over 20 minutes, so I stopped waiting."
                        + "It may still be running; the result shows up under Maps / Usage later.");
                setBusy(false);
            });
            return;
        }
        io.execute(() -> {
            try {
                Thread.sleep(700);
                HttpURLConnection conn = connect("/task/" + id);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(15000);
                JSONObject state = new JSONObject(read(conn));
                lastPollAt = System.currentTimeMillis();
                pollFailures = 0;
                AgentService.renewWakeLock(this);
                String status = state.optString("state", "?");
                String answer = state.optString("answer", "");
                String question = state.optString("question", "");
                String frame = state.optString("frame", "");
                JSONArray steps = state.optJSONArray("steps");

                long nowMs = System.currentTimeMillis();
                if (!frame.isEmpty() && !frame.equals(lastFrame) && nowMs - lastFrameAt > 1200) {
                    lastFrame = frame;
                    lastFrameAt = nowMs;
                    byte[] png = Base64.decode(frame, Base64.DEFAULT);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                    ui(() -> {
                        // drop the previous picture before keeping a new one:
                        // a 1280x2772 bitmap every second is 14 MB
                        android.graphics.drawable.Drawable old = frameView.getDrawable();
                        frameView.setImageBitmap(bitmap);
                        if (capsuleFrame != null && capsule != null) {
                            capsuleFrame.setImageBitmap(bitmap);
                            capsule.setVisibility(View.VISIBLE);
                        }
                        if (old instanceof android.graphics.drawable.BitmapDrawable) {
                            android.graphics.Bitmap previous =
                                    ((android.graphics.drawable.BitmapDrawable) old).getBitmap();
                            if (previous != null && previous != bitmap && !previous.isRecycled()) {
                                previous.recycle();
                            }
                        }
                        frameCard.setVisibility(View.VISIBLE);
                        // The capsule already shows the picture, so this only
                        // needs to be a way in. Keeping 280dp of the same
                        // frames in the chat is duplication, not information.
                        // The mirror is always in the capsule now (a view in
                        // this window), so a second copy above the input is
                        // pure duplication -- and it came back the moment the
                        // old system-overlay preference was cleared.
                        frameView.setVisibility(View.GONE);
                        frameCard.setVisibility(View.GONE);
                        if (frameDialog != null && bigFrame != null) {
                            bigFrame.setImageBitmap(bitmap);
                        }
                        frameLabel.setText(getString(R.string.frame_step, shownSteps));
                    });
                }

                // new steps only: the server mirrors the agent's list, so the
                // first N entries have already been shown
                int total = steps == null ? 0 : steps.length();
                if (total > shownSteps) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = shownSteps; i < total; i++) {
                        JSONObject step = steps.optJSONObject(i);
                        // the answer arrives as its own bubble; printing it a
                        // second time as a step reads like a duplicate
                        if ("finish".equals(step.optString("kind"))) {
                            continue;
                        }
                        String kind = step.optString("kind");
                        String line = describeStep(
                                kind,
                                step.optString("action"),
                                step.optString("detail"));
                        if (!line.isEmpty()) {
                            // ◆ marks a replay from the map: the only step in
                            // the transcript that costs no model round. It is
                            // drawn in the accent colour, so "what did the map
                            // actually save" is visible without reading.
                            sb.append("macro".equals(kind) ? "◆ " : "· ")
                                    .append(line).append("\n");
                        }
                    }
                    final boolean firstBatch = shownSteps == 0;
                    shownSteps = total;
                    final String chunk = sb.toString().trim();
                    // What arrives can be finish-only: the answer is its own
                    // bubble, so those steps are skipped above and nothing is
                    // left to print. That case used to `return` from this task,
                    // which skipped the terminal-state handling below -- no
                    // answer, no setBusy(false) -- and the watchdog restarted
                    // the poll, which returned again. Measured: the row stayed
                    // on "Running… completed 2  steps" with the answer already on the
                    // server, forever.
                    if (!chunk.isEmpty()) {
                        // one header per task, not one per batch: the steps read
                        // as a single block while they arrive
                        ui(() -> {
                            appendLines(chunk);
                        });
                    }
                }

                if (!question.isEmpty() && !id.equals(awaitingTaskId)) {
                    ui(() -> {
                        awaitingTaskId = id;
                        notifyNeedsYou(question);
                        append("ORBIT", Tr.t("I need your answer: ") + question);
                        statusText.setText("Waiting for your answer…");
                        // the send button is the reply channel, so it has to be
                        // usable even though a task is running
                        sendButton.setEnabled(true);
                        sendButton.setAlpha(1f);
                        input.setHint(Tr.t("Reply to ORBIT"));
                    });
                }

                if ("done".equals(status) || "failed".equals(status)
                        || "partial".equals(status) || "cancelled".equals(status)) {
                    final boolean ok = "done".equals(status);
                    final boolean stopped = "cancelled".equals(status);
                    final boolean part = "partial".equals(status);
                    int replays = 0;
                    JSONArray allSteps = state.optJSONArray("steps");
                    if (allSteps != null) {
                        for (int i = 0; i < allSteps.length(); i++) {
                            JSONObject one = allSteps.optJSONObject(i);
                            if (one != null && "macro".equals(one.optString("kind"))) {
                                replays++;
                            }
                        }
                    }
                    final String spend = spentLine(state.optJSONObject("usage"), replays);
                    polling = false;
                    lastPollAt = System.currentTimeMillis();
                    boolean alreadyShown = id.equals(answeredTaskId);
                    answeredTaskId = id;
                    currentTaskId = "";
                    if (alreadyShown) {
                        // The answer is on screen; do not repeat it -- but this
                        // chain still owns the busy state. Measured: two chains
                        // (a resume plus the one already running) both reached
                        // the terminal state, both took this branch, and the row
                        // stayed on "Running… completed N  steps" with a Cancel button
                        // after the task had finished.
                        ui(() -> setBusy(false));
                        return;
                    }
                    // A task that ran on the user's own screen leaves its app
                    // in front there -- they asked a question and got NoteApp
                    // back. Unless the instruction asked to be left on that
                    // screen, the chat comes forward again.
                    if (state.optBoolean("on_user_display") && !state.optBoolean("leave_app")) {
                        ui(this::returnToChat);
                    }
                    ui(() -> {
                        append("ORBIT", stopped ? Tr.t("Cancelled")
                                : (ok ? "Done: " : (part ? "Partly done: " : "Failed: ")) + answer);
                        // what this instruction cost, right under its own answer
                        if (!spend.isEmpty()) {
                            note(spend);
                        }
                        setBusy(false);
                        // Flush the transcript now: the agent's hand-back can
                        // bring the window forward at this moment, and a copy
                        // that reads the file before this save shows an empty
                        // conversation.
                        saveNow();
                    });
                    return;
                }

                final int stepCount = shownSteps;
                ui(() -> {
                    // a pending question owns the status line; do not overwrite it
                    if (statusText != null && statusRow.getVisibility() == View.VISIBLE
                            && awaitingTaskId.isEmpty()) {
                        statusText.setText(stepCount > 0
                                ? "Working… " + stepCount + " steps"
                                : "Working…");
                    }
                });
                ui(() -> poll(id, attempt + 1));
            } catch (Throwable e) {
                // Throwable, not Exception: an OutOfMemoryError used to kill
                // this thread silently, leaving the task "running" on screen
                // long after it had finished.
                //
                // A read timeout is not a finished task: the server can take
                // longer than 15 s to answer while a model call holds the
                // interpreter, and giving up there told the user the task had
                // failed while it was still running. Retry a few times first.
                boolean transientFailure = e instanceof java.net.SocketTimeoutException
                        || e instanceof java.net.ConnectException
                        || e instanceof java.io.EOFException;
                pollFailures++;
                if (transientFailure && pollFailures <= 5) {
                    android.util.Log.i("Pairing", "poll " + id + " hiccup "
                            + pollFailures + ": " + e);
                    ui(() -> poll(id, attempt + 1));
                    return;
                }
                polling = false;
                final int failures = pollFailures;
                pollFailures = 0;
                ui(() -> {
                    append("ORBIT", Tr.t("Poll failed: ") + e
                            + (failures > 1 ? "" + failures
                            + "" : ""));
                    setBusy(false);
                });
            }
        });
    }

    /**
     * One task's token spend, in the terms a person reads.
     *
     * Shown under the answer it belongs to rather than in the usage dialog,
     * because "what did this one instruction cost" is the question people
     * actually ask.
     */
    private static String spentLine(JSONObject usage, int replays) {
        if (usage == null) {
            return "";
        }
        int calls = usage.optInt("calls");
        int prompt = usage.optInt("prompt");
        int completion = usage.optInt("completion");
        if (calls <= 0) {
            return "";
        }
        String line = "This run: " + calls + " calls · in " + compact(prompt)
                + " · out " + compact(completion) + " token";
        if (replays > 0) {
            // the point of the map, spelled out: these steps cost no call
            line += " · map replays " + replays + "";
        }
        return line;
    }

    /** 19268 -> 19.3k, so the line stays one line. */
    private static String compact(int value) {
        if (value < 1000) {
            return String.valueOf(value);
        }
        return String.format(java.util.Locale.US, "%.1fk", value / 1000.0);
    }

    private void cancelTask() {
        if (currentTaskId.isEmpty()) {
            return;
        }
        final String id = currentTaskId;
        statusText.setText("Cancelling…");
        new Thread(() -> {
            try {
                HttpURLConnection conn =
                        connect("/task/" + id + "/cancel");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.getOutputStream().close();
                read(conn);
            } catch (Exception e) {
                ui(() -> append("ORBIT", Tr.t("Cancel failed: ") + e));
            }
        }).start();
    }

    /** Send the user's reply to a task that is waiting on ask_user. */
    private void answerTask(String id, String text) {
        statusText.setText("Working…");
        new Thread(() -> {
            try {
                HttpURLConnection conn = connect("/task/" + id + "/answer");
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject body = new JSONObject().put("text", text);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                read(conn);
                ui(() -> {
                    sendButton.setEnabled(false);
                    sendButton.setAlpha(0.45f);
                    input.setHint(getString(R.string.hint));
                });
            } catch (Exception e) {
                ui(() -> append("ORBIT", Tr.t("Failed to submit answer: ") + e));
            }
        }).start();
    }

    /** While a task runs the send button is off and the cancel button is on. */
    /**
     * Show the last picture the agent published, even if this app did not
     * start the task (a task can come from the API, or from before a restart).
     */
    private void loadLastFrame() {
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = connect("/frame");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() != 200) {
                    return;                       // the finally closes it
                }
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                // /frame serves the PNG itself, not base64 of it -- decoding it
                // as base64 produced garbage and an empty capsule
                byte[] png = out.toByteArray();
                conn.disconnect();
                if (png.length < 100) {         // 503 body, not an image
                    return;
                }
                ui(() -> {
                    hasFrame = true;
                    showCapsule(true);
                });
                android.graphics.Bitmap bitmap =
                        BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bitmap == null) {
                    return;
                }
                ui(() -> {
                    if (capsuleFrame != null && capsule != null) {
                        capsuleFrame.setImageBitmap(bitmap);
                        capsule.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Throwable ignored) {
                // no frame yet: the capsule simply stays hidden
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * Keeps the capsule showing the newest picture.
     *
     * A task started from the API (or one running before a restart) never goes
     * through this app's poll, so the mirror would sit on an old screenshot.
     */
    private final Runnable frameTicker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            loadLastFrame();
            handler.postDelayed(this, 2500);
        }
    };

    /** Expanded shows the picture; collapsed is one dot you can shove aside. */
    private void applyCapsuleSize(boolean collapsed) {
        if (capsule == null) {
            return;
        }
        capsuleCollapsed = collapsed;
        android.view.ViewGroup.LayoutParams params = capsule.getLayoutParams();
        // Smaller than the first version: 96x200dp mirrors a 1:2.08 screen
        // without covering a chat bubble's worth of text.
        params.width = dp(collapsed ? 38 : 96);
        params.height = dp(collapsed ? 38 : 200);
        capsule.setLayoutParams(params);
        if (capsuleFrame != null) {
            capsuleFrame.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (capsuleDot != null) {
            capsuleDot.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }
        android.widget.TextView label = findViewById(R.id.capsuleLabel);
        if (label != null) {
            label.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        getSharedPreferences("agent", MODE_PRIVATE).edit()
                .putBoolean("capsule_collapsed", collapsed).apply();
        capsule.post(() -> {
            if (capsule.getParent() != null) {
                android.view.View parent = (android.view.View) capsule.getParent();
                capsule.setX(clamp(capsule.getX(), 0,
                        Math.max(0, parent.getWidth() - capsule.getWidth())));
                capsule.setY(clamp(capsule.getY(), 0,
                        Math.max(0, parent.getHeight() - capsule.getHeight())));
                snapCapsuleToEdge(false);
                saveCapsulePosition();
            }
        });
    }

    /** Where the user last left it, as a fraction of the window. */
    private void saveCapsulePosition() {
        if (capsule == null || capsule.getParent() == null) {
            return;
        }
        android.view.View parent = (android.view.View) capsule.getParent();
        if (parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }
        getSharedPreferences("agent", MODE_PRIVATE).edit()
                .putFloat("capsule_x", capsule.getX() / parent.getWidth())
                .putFloat("capsule_y", capsule.getY() / parent.getHeight())
                .apply();
    }

    private void restoreCapsulePosition() {
        if (capsule == null || capsule.getParent() == null) {
            return;
        }
        android.content.SharedPreferences prefs =
                getSharedPreferences("agent", MODE_PRIVATE);
        if (!prefs.contains("capsule_x")) {
            return;                       // never dragged: the layout's corner is fine
        }
        android.view.View parent = (android.view.View) capsule.getParent();
        float x = prefs.getFloat("capsule_x", 0f) * parent.getWidth();
        float y = prefs.getFloat("capsule_y", 0f) * parent.getHeight();
        // A position saved before this rule existed can be on top of the input
        // row; clamping on the way in fixes it without a migration.
        capsule.setX(clamp(x, 0, parent.getWidth() - capsule.getWidth()));
        capsule.setY(clamp(y, capsuleTop(), capsuleBottom()));
    }

    /**
     * Snap to the nearer side, like a phone's assistive touch.
     *
     * Free placement means it always ends up over something; hugging an edge
     * costs the least room and is where the hand expects to find it again.
     */
    private void snapCapsuleToEdge(boolean animate) {
        if (capsule == null || capsule.getParent() == null) {
            return;
        }
        android.view.View parent = (android.view.View) capsule.getParent();
        if (parent.getWidth() == 0) {
            return;
        }
        float margin = dp(2);
        float center = capsule.getX() + capsule.getWidth() / 2f;
        float targetX = center < parent.getWidth() / 2f
                ? margin
                : parent.getWidth() - capsule.getWidth() - margin;
        float targetY = clamp(capsule.getY(), 0,
                parent.getHeight() - capsule.getHeight());
        if (animate) {
            capsule.animate().x(targetX).y(targetY).setDuration(160).start();
        } else {
            capsule.setX(targetX);
            capsule.setY(targetY);
        }
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }

    /**
     * Drag to move, tap to fold or unfold.
     *
     * A touch listener rather than a click listener because both gestures start
     * the same way: the movement decides which one it was.
     */
    private final class CapsuleTouch implements android.view.View.OnTouchListener {
        private float startX;
        private float startY;
        private float startRawX;
        private float startRawY;
        private boolean moved;
        private boolean folded;
        private final Runnable fold = () -> {
            folded = true;
            android.view.View view = capsule;
            if (view != null) {
                view.setAlpha(1f);
                toggleCapsule();
            }
        };

        @Override
        public boolean onTouch(android.view.View view, android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    startX = view.getX();
                    startY = view.getY();
                    startRawX = event.getRawX();
                    startRawY = event.getRawY();
                    moved = false;
                    folded = false;
                    view.setAlpha(0.85f);
                    // the view consumes the gesture, so a long press has to be
                    // timed here -- a click listener never sees it
                    handler.postDelayed(fold, 500);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - startRawX;
                    float dy = event.getRawY() - startRawY;
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        if (!moved) {
                            handler.removeCallbacks(fold);
                        }
                        moved = true;
                    }
                    if (moved && view.getParent() != null) {
                        android.view.View parent = (android.view.View) view.getParent();
                        view.setX(clamp(startX + dx, 0, parent.getWidth() - view.getWidth()));
                        view.setY(clamp(startY + dy, capsuleTop(), capsuleBottom()));
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.setAlpha(1f);
                    handler.removeCallbacks(fold);
                    if (folded) {
                        folded = false;              // the long press already acted
                    } else if (moved) {
                        snapCapsuleToEdge(true);
                        // the animation moves it after the fact, so give the
                        // saving a moment to see the settled position
                        handler.postDelayed(MainActivity.this::saveCapsulePosition, 220);
                    } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        // Tell accessibility services a click happened: the view
                        // eats the gesture, so nothing else would report it.
                        view.performClick();
                        if (capsuleCollapsed) {
                            applyCapsuleSize(false);
                            restoreCapsulePosition();
                        } else {
                            showFrameViewer();
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    /**
     * The mirror steps aside while the keyboard is up.
     *
     * With the keyboard open the window is less than three quarters of the
     * screen; the capsule then has nowhere to go that is not on top of the
     * conversation, so it hides until the keyboard closes.
     */
    private void onRootLayout() {
        if (capsule == null || capsule.getParent() == null) {
            return;
        }
        android.view.View root = (android.view.View) capsule.getParent();
        int height = root.getHeight();
        if (height <= 0) {
            return;
        }
        int screen = getResources().getDisplayMetrics().heightPixels;
        boolean keyboard = height < screen * 3 / 4;
        capsule.setVisibility(keyboard || !hasFrame ? View.GONE : View.VISIBLE);
        if (!keyboard) {
            clampCapsule();
        }
    }

    /** Below the title row: the capsule must not sit on the header. */
    private int capsuleTop() {
        return dp(76);
    }

    /** Above the input row: covering the send button makes the app unusable. */
    private int capsuleBottom() {
        android.view.View parent = capsule == null ? null : (android.view.View) capsule.getParent();
        int height = parent == null ? 0 : parent.getHeight();
        if (height <= 0) {
            return Math.max(capsuleTop(), getResources().getDisplayMetrics().heightPixels - dp(260));
        }
        return Math.max(capsuleTop(), height - dp(120) - (capsule == null ? 0 : capsule.getHeight()));
    }

    /** Keep the capsule inside the part of the window that has no controls. */
    private void clampCapsule() {
        if (capsule == null || capsule.getParent() == null) {
            return;
        }
        android.view.View parent = (android.view.View) capsule.getParent();
        int maxX = Math.max(0, parent.getWidth() - capsule.getWidth());
        capsule.setX(clamp(capsule.getX(), 0, maxX));
        capsule.setY(clamp(capsule.getY(), capsuleTop(), capsuleBottom()));
    }

    /** Long-press on the capsule folds it; the dot folds back out. */
    private void toggleCapsule() {
        applyCapsuleSize(!capsuleCollapsed);
        restoreCapsulePosition();
        clampCapsule();
    }

    /** The in-app capsule: visible whenever there is a picture to show. */
    private void showCapsule(boolean visible) {
        if (capsule != null) {
            capsule.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        // The mirror floats over the transcript. Without room on the right the
        // answer bubble slides under it and a sentence is cut in half -- the
        // capsule is 96dp wide plus its margin, so the chat keeps that much.
        if (messages != null) {
            int pad = (visible && !capsuleCollapsed) ? dp(104) : 0;
            messages.setPadding(messages.getPaddingLeft(), messages.getPaddingTop(),
                    pad, messages.getPaddingBottom());
        }
    }

    private void hideKeyboard() {
        android.view.View focus = getCurrentFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (focus != null && imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    //: The bubble that sits in the chat while a task runs. It is replaced by the
    //: first real message, so the transcript never claims something it cannot.
    private android.view.View thinkingRow;
    private android.widget.TextView thinkingText;
    private int thinkingDots;
    private final Runnable thinkingTick = new Runnable() {
        @Override
        public void run() {
            if (thinkingText == null) {
                return;
            }
            thinkingDots = (thinkingDots + 1) % 4;
            StringBuilder dots = new StringBuilder("Driving the phone");
            for (int i = 0; i < thinkingDots; i++) {
                dots.append("·");
            }
            thinkingText.setText(dots.toString());
            handler.postDelayed(this, 450);
        }
    };

    private void showThinking() {
        if (thinkingRow != null || messages == null) {
            return;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.START);

        TextView avatar = new TextView(this);
        avatar.setText("✦");
        avatar.setTextSize(13f);
        avatar.setTextColor(getColor(R.color.white));
        avatar.setGravity(android.view.Gravity.CENTER);
        avatar.setBackground(getDrawable(R.drawable.bg_avatar));
        LinearLayout.LayoutParams avatarParams =
                new LinearLayout.LayoutParams(dp(30), dp(30));
        avatarParams.rightMargin = dp(8);
        avatarParams.topMargin = dp(2);
        row.addView(avatar, avatarParams);

        thinkingText = new TextView(this);
        thinkingText.setText("Driving the phone");
        thinkingText.setTextSize(14f);
        thinkingText.setTextColor(getColor(R.color.ink_soft));
        thinkingText.setBackground(getDrawable(R.drawable.bg_agent));
        thinkingText.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.addView(thinkingText);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        messages.addView(row, params);
        thinkingRow = row;
        thinkingDots = 0;
        closeStepBlock();
        scrollDown();
        handler.post(thinkingTick);
    }

    private void hideThinking() {
        handler.removeCallbacks(thinkingTick);
        if (thinkingRow != null && messages != null) {
            messages.removeView(thinkingRow);
        }
        thinkingRow = null;
        thinkingText = null;
    }

    private void setBusy(boolean busy) {
        if (!busy) {
            hideThinking();
        }
        if (statusRow == null) {
            return;
        }
        statusRow.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusText.setText(getString(R.string.running));
        sendButton.setEnabled(!busy);
    }


    private static String read(HttpURLConnection conn) throws Exception {
        try {
            InputStream in = conn.getResponseCode() >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } finally {
            // Every poll opens a new connection; leaving them to the pool is how
            // a phone ends up with a socket leak after an hour of tasks.
            conn.disconnect();
        }
    }

    private void ui(Runnable action) {
        handler.post(action);
    }

    /** System chatter: centred, small, no bubble -- it is not the agent talking. */
    /**
     * A message the user has to see, welcome screen or not.
     *
     * note() deliberately stays out of the welcome composition; a failed
     * reconnect is not decoration, so it gets a bubble of its own.
     */
    private void alert(String text) {
        if (welcome != null && welcome.getVisibility() == View.VISIBLE) {
            append("ORBIT", text);
        } else {
            note(text);
        }
    }

    private void note(String text) {
        if (welcome != null && welcome.getVisibility() == View.VISIBLE) {
            // The welcome screen is a deliberate composition; a status line
            // dropping into the middle of it looks like part of the artwork.
            return;
        }
        TextView line = new TextView(this);
        line.setText(text.trim());
        line.setTextSize(12f);
        line.setTextColor(getColor(R.color.ink_soft));
        line.setGravity(android.view.Gravity.CENTER);
        line.setPadding(dp(24), dp(6), dp(24), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        closeStepBlock();
        messages.addView(line, params);
        trimMessages();
        // deliberately not remembered: startup chatter repeated on every launch
        // is noise, and the conversation is what should survive a restart
        scrollDown();
    }

    /**
     * Step lines, accumulated into one view instead of one per line.
     *
     * A 25-minute crawl prints a line every couple of seconds. Giving each its
     * own TextView built a transcript of a thousand views: the main thread then
     * spent its whole time on layout, taps went unanswered and polling stopped
     * -- the app looked frozen while the agent was still working. One growing
     * block, capped, keeps that from happening.
     */
    private void appendLines(String text) {
        hideThinking();
        if (stepBlock == null || stepBlock.getParent() == null) {
            stepLines.clear();               // a new block starts a new list
            stepBlock = new TextView(this);
            stepBlock.setTextSize(12.5f);
            stepBlock.setTextColor(getColor(R.color.ink_soft));
            stepBlock.setLineSpacing(6f, 1f);
            stepBlock.setPadding(dp(14), dp(6), dp(14), dp(6));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(44);      // line up under the bubble, past the avatar
            params.bottomMargin = dp(4);
            messages.addView(stepBlock, params);
        }
        text = friendlyStep(text);
        if (text.isEmpty()) {
            return;
        }
        if (!crawlId.isEmpty()) {
            // There is no per-line button in the transcript, so the progress
            // block itself is the control while a crawl runs. Killing the app
            // used to be the only way to stop one.
            stepBlock.setOnClickListener(v -> cancelCrawl());
            stepBlock.setTextColor(getColor(R.color.accent));
        } else {
            stepBlock.setOnClickListener(null);
            stepBlock.setTextColor(getColor(R.color.ink_soft));
        }
        // Kept as lines so a replayed step can be styled: a plain String cannot
        // hold a colour, and rebuilding the block is cheap next to one layout.
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) {
                stepLines.add(line.trim());
            }
        }
        while (stepLines.size() > 80) {              // keep the tail, drop the head
            stepLines.remove(0);
        }
        android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder();
        for (String line : stepLines) {
            int start = styled.length();
            styled.append(line).append("\n");
            if (line.startsWith("◆")) {
                styled.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.accent)),
                        start, start + line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, start + line.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        stepBlock.setText(styled);
        trimMessages();
        scrollDown();
    }

    /**
     * Is this crawl line worth putting in the conversation?
     *
     * Screens found, screens named, warnings, the end -- those move the story.
     * Enrichment counts, launch retries and dumps do not.
     */
    /** "… (176.1s)" -> "176 s"; empty when the line carries no timing. */
    /**
     * A step as a sentence instead of a log fragment.
     *
     * "Bluetooth macro ok (3/3 steps)" is what the executor reports; what happened is
     * that it walked a known path to the Bluetooth page. The plan line is an
     * English instruction to the model, so it is dropped rather than shown.
     */
    /** Screen ids ("wlan_wlan") are not names; a person needs none of them. */
    private static boolean looksLikeId(String text) {
        return text != null && text.matches("^[a-z0-9_]+$");
    }

    /**
     * One step, in words a person can follow.
     *
     * The server sends {kind: vlm, action: launch, detail: ok}; printing that
     * verbatim gives "launch ok" over and over, which says nothing about what
     * the agent is doing.
     */
    private static String describeStep(String kind, String action, String detail) {
        String what = action == null ? "" : action.trim();
        String how = detail == null ? "" : detail.trim();
        if ("route".equals(kind)) {
            return "Routing: this step uses \"" + how + "\"";
        }
        if ("escaped".equals(kind)) {
            String app = what.isEmpty() ? "App" : what;
            return "「" + app + "\" and landed on your own screen";
        }
        if ("follow".equals(kind)) {
            String app = what.isEmpty() ? "App" : what;
            return "Following \"" + app + "\" onto your own screen";
        }
        if ("type".equals(kind)) {
            // the search-query step the macro layer types by itself
            return what.isEmpty() ? "Type something" : "Typing \"" + what + "\"";
        }
        if ("pull".equals(kind)) {
            // the detail is "moved back to the agent's display": what happened,
            // in words a person reading the chat would use
            String app = what.isEmpty() ? "Target app" : what;
            return "「" + app + "\" jumped to your screen; moved back to the virtual display";
        }
        if ("verify".equals(kind)) {
            if ("mismatch".equals(what)) {
                return "The screen differs from the map; continuing from what is on it";
            }
            if ("refused".equals(what)) {
                return "The model did not see the screen; taking another look";
            }
            return "Checking the screen";
        }
        if ("shade".equals(kind)) {
            return "The notification shade was covering the screen; dismissed";
        }
        if ("memory".equals(kind)) {
            // the detail is the fact itself; "remembered" is the verb
            return how.isEmpty() ? "Noted one item from the screen" : "Noted: " + how;
        }
        if ("macro".equals(kind)) {
            String name = what.isEmpty() ? "Known path" : what;
            if (how.contains("steps")) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\(([0-9]+)/([0-9]+) steps\\)").matcher(how);
                if (m.find()) {
                    return "Map replay: go to \"" + cleanLabel(name) + "\": " + m.group(1) + " actions";
                }
            }
            return "Map replay: go to \"" + cleanLabel(name) + "\"" + (how.contains("ok") ? " (done)" : "");
        }
        if ("vlm".equals(kind) || "tap".equals(kind) || "act".equals(kind)) {
            if ("launch".equals(what)) {
                return "Open app";
            }
            if ("macro".equals(what)) {
                return "Follow the known path";
            }
            if ("tap_index".equals(what)) {
                return "Tap a number on the screenshot";
            }
            if ("tap".equals(what)) {
                return "Tap the screen";
            }
            if ("scroll".equals(what) || "swipe".equals(what)) {
                return "Scroll";
            }
            if ("wait".equals(what)) {
                return "Wait for the screen";
            }
            if ("back".equals(what)) {
                return "Go back";
            }
            if ("type".equals(what)) {
                return "Type something";
            }
            if ("tap_text".equals(what)) {
                return how.isEmpty() || "ok".equals(how)
                        ? "Tap the text" : "Tap \"" + how + "\"";
            }
            if ("shell".equals(what)) {
                // the detail is the command itself now, and a person reading
                // the chat deserves to see what was run on their phone
                return how.isEmpty() ? "Run a command" : "Run command: " + how;
            }
            if ("finish".equals(what)) {
                return "";
            }
            return what.isEmpty() ? "" : what;
        }
        return what.isEmpty() ? "" : what + (how.isEmpty() || "ok".equals(how) ? "" : " " + how);
    }

    /** A label without the bullet the step renderer already adds. */
    private static String cleanLabel(String text) {
        String value = text == null ? "" : text.trim();
        while (value.startsWith("·") || value.startsWith("-") || value.startsWith("•")) {
            value = value.substring(1).trim();
        }
        return value;
    }

    private static String friendlyStep(String text) {
        String line = text == null ? "" : text.trim();
        if (line.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher macro =
                java.util.regex.Pattern.compile("^(.*?)\\s+macro ok\\s*\\(([0-9]+)/([0-9]+) steps\\)")
                        .matcher(line);
        if (macro.find()) {
            String target = cleanLabel(macro.group(1));
            return looksLikeId(target)
                    ? "· follow the known path (" + macro.group(2) + " steps)"
                    : "· follow the known path into \"" + target + "\" (" + macro.group(2) + " steps)";
        }
        if (line.endsWith("Full map")) {
            String target = cleanLabel(line.replace("Full map", ""));
            return looksLikeId(target) ? "· use the known map" : "· use the map for \"" + target + "\"";
        }
        // A plan sentence is the model talking to itself in English; the visible
        // step is the one after it.
        if (line.matches("^[A-Za-z ,;:'\\-]+$")) {
            return "";
        }
        return line.startsWith("· ") || line.startsWith("  ") ? line : "· " + line;
    }

    private static String spentSeconds(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(([0-9]+(?:\\.[0-9]+)?)s\\)").matcher(line == null ? "" : line);
        if (!m.find()) {
            return "";
        }
        return Math.round(Double.parseDouble(m.group(1))) + " s";
    }

    /**
     * A crawl line without its tracer packaging.
     *
     * "[Verified] depth=1 tap 'Recommended' (from screen_0)" is the log format; what a
     * person needs is "Tap Recommended". Paths are dropped entirely -- where the map is
     * written is a detail for the Maps page.
     */
    private static String friendlyCrawlLine(String line) {
        String text = line == null ? "" : line.trim();
        text = text.replaceAll("^\\[[A-Za-z]+\\]\\s*", "");
        text = text.replaceAll("/data/\\S+", "").trim();
        text = text.replaceAll("\\s{2,}", " ");
        if (text.startsWith("depth=")) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("tap '([^']+)'").matcher(text);
            return m.find() ? "Tap " + m.group(1) : "";
        }
        // Everything that is not a tap is a tracer line: a package name, a
        // display id, a count of elements. The user is watching screens being
        // opened, not reading a log.
        return "";
    }

    private static boolean isMilestone(String line) {
        String text = line.trim();
        if (text.isEmpty()) {
            return false;
        }
        if (text.startsWith("[Enrich]") || text.startsWith("[launch]")
                || text.startsWith("[Verified]") || text.startsWith("[crawl]")) {
            return false;
        }
        return text.contains("new screen") || text.contains("known screen")
                || text.contains("screen_") && text.contains("->")
                || text.contains("found") || text.contains("complete") || text.contains("failed")
                || text.contains("note") || text.contains("stop") || text.contains("abort")
                || text.contains("coverage") || text.contains("map ");
    }

    /** A new bubble ends the step block, so later steps start fresh under it. */
    private void closeStepBlock() {
        stepBlock = null;
    }

    /**
     * Empty the transcript without losing the welcome block.
     *
     * removeAllViews() detaches every child, welcome included, and the block
     * was never put back -- a new session showed a blank screen.
     */
    private void clearChat() {
        messages.removeAllViews();
        if (welcome != null) {
            messages.addView(welcome, 0);
        }
    }

    /** Keep the transcript bounded: old views cost layout time forever. */
    private void trimMessages() {
        if (messages == null) {
            return;
        }
        for (int i = 0; i < MAX_BUBBLES; i++) {
            if (messages.getChildCount() <= MAX_BUBBLES) {
                return;
            }
            // The welcome block lives at index 0 until the first message, and
            // removing it leaves an empty screen with no explanation.
            android.view.View first = messages.getChildAt(0);
            if (first == welcome) {
                return;
            }
            messages.removeViewAt(0);
        }
    }

    /**
     * One message, as a bubble.
     *
     * The user speaks on the right in the accent colour; the agent replies on
     * the left with a small avatar, the way a chat with a person looks. Before
     * this, everything was one TextView with "OrbitAgent" printed above it.
     */
    private void append(String who, String message) {
        boolean mine = "You".equals(who);
        if (!mine) {
            hideThinking();
        }
        if (mine && welcome != null && welcome.getVisibility() == View.VISIBLE) {
            welcome.setVisibility(View.GONE);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(mine ? android.view.Gravity.END : android.view.Gravity.START);

        if (!mine) {
            TextView avatar = new TextView(this);
            avatar.setText("✦");
            avatar.setTextSize(13f);
            avatar.setTextColor(getColor(R.color.white));
            avatar.setGravity(android.view.Gravity.CENTER);
            avatar.setBackground(getDrawable(R.drawable.bg_avatar));
            LinearLayout.LayoutParams avatarParams =
                    new LinearLayout.LayoutParams(dp(30), dp(30));
            avatarParams.rightMargin = dp(8);
            avatarParams.topMargin = dp(2);
            row.addView(avatar, avatarParams);
        }

        TextView bubble = new TextView(this);
        bubble.setText(message);
        bubble.setTextSize(15f);
        bubble.setLineSpacing(6f, 1f);
        bubble.setTextIsSelectable(true);
        bubble.setTextColor(getColor(mine ? R.color.bubble_user_ink : R.color.ink));
        bubble.setBackground(getDrawable(mine ? R.drawable.bg_user : R.drawable.bg_agent));
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.76f));
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(bubble, bubbleParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(mine ? android.view.Gravity.END : android.view.Gravity.START);
        column.addView(row);

        TextView stamp = new TextView(this);
        stamp.setText(hhmm(System.currentTimeMillis()));
        stamp.setTextSize(10f);
        stamp.setTextColor(getColor(R.color.ink_soft));
        stamp.setPadding(dp(4), dp(2), dp(4), 0);
        column.addView(stamp);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        closeStepBlock();
        messages.addView(column, rowParams);
        trimMessages();
        remember(mine ? "you" : "agent", message);
        scrollDown();
    }

    // -- the conversation outlives the activity ---------------------------

    /** One message as it will be restored: kind plus text. */
    private final java.util.List<org.json.JSONObject> conversation = new java.util.ArrayList<>();
    private boolean restoring;

    // -- sessions ----------------------------------------------------------
    //
    // Several conversations with the same phone. Each owns its transcript;
    // they share the device, so a task belongs to the session that started it
    // and the others simply do not see it.

    /** The conversation being shown. Empty until the store is read. */
    private String sessionId = "";

    /**
     * Write a file so a kill cannot leave half of it behind.
     *
     * The transcript and the session index are the only copies of what the user
     * said; a truncating FileWriter loses all of it when the process dies
     * between the truncate and the write (installs, OEM battery managers, OOM).
     * The Python side already does this (orbit/atomic.py) -- this is the
     * same rule on the Java side: write a sibling temp file, fsync, then replace,
     * keeping the previous copy as .bak for readers to fall back on.
     */
    private static void writeAtomically(java.io.File target, String text) throws java.io.IOException {
        java.io.File tmp = new java.io.File(target.getParentFile(), target.getName() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        java.io.File bak = new java.io.File(target.getParentFile(), target.getName() + ".bak");
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            bak.delete();
            //noinspection ResultOfMethodCallIgnored
            target.renameTo(bak);
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new java.io.IOException("could not replace " + target);
        }
    }

    /** The freshest readable copy of a state file, or null. */
    private static java.io.File readable(java.io.File file) {
        if (file.exists() && file.length() > 0) {
            return file;
        }
        java.io.File bak = new java.io.File(file.getParentFile(), file.getName() + ".bak");
        return bak.exists() && bak.length() > 0 ? bak : null;
    }

    private java.io.File sessionFile() {
        return new java.io.File(getFilesDir(), "session-" + sessionId + ".json");
    }

    private java.io.File sessionIndex() {
        return new java.io.File(getFilesDir(), "sessions.json");
    }

    /** [{id, title, updated}] newest first. */
    private org.json.JSONArray readSessions() {
        java.io.File file = readable(sessionIndex());
        if (file == null) {
            return new org.json.JSONArray();
        }
        try {
            return new org.json.JSONArray(readText(file));
        } catch (Throwable t) {
            android.util.Log.w("Pairing", "could not read the session list: " + t);
            return new org.json.JSONArray();
        }
    }

    private void writeSessions(org.json.JSONArray sessions) {
        try {
            writeAtomically(sessionIndex(), sessions.toString());
        } catch (Throwable t) {
            android.util.Log.w("Pairing", "could not save the session list: " + t);
        }
    }

    /** Create (or pick up) the session the app should show at startup. */
    private void ensureSession() {
        org.json.JSONArray sessions = readSessions();
        if (sessions.length() > 0) {
            org.json.JSONObject first = sessions.optJSONObject(0);
            sessionId = first == null ? "" : first.optString("id");
        }
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = newSession();
        }
    }

    /** A fresh conversation, made the current one. */
    private String newSession() {
        String id = "s" + System.currentTimeMillis();
        org.json.JSONArray sessions = readSessions();
        try {
            org.json.JSONObject entry = new org.json.JSONObject();
            entry.put("id", id);
            entry.put("title", "New chat");
            entry.put("updated", System.currentTimeMillis());
            org.json.JSONArray next = new org.json.JSONArray();
            next.put(entry);
            for (int i = 0; i < sessions.length(); i++) {
                next.put(sessions.get(i));
            }
            writeSessions(next);
        } catch (org.json.JSONException ignored) {
            // a session that cannot be indexed still works in memory
        }
        sessionId = id;
        return id;
    }

    /** Keep the list's title and order in step with what was said. */
    private void touchSession(String firstLine) {
        org.json.JSONArray sessions = readSessions();
        org.json.JSONArray next = new org.json.JSONArray();
        boolean found = false;
        try {
            for (int i = 0; i < sessions.length(); i++) {
                org.json.JSONObject entry = sessions.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                if (sessionId.equals(entry.optString("id"))) {
                    entry.put("updated", System.currentTimeMillis());
                    if (!firstLine.isEmpty() && "New chat".equals(entry.optString("title"))) {
                        entry.put("title", firstLine.length() > 18
                                ? firstLine.substring(0, 18) + "…" : firstLine);
                    }
                    found = true;
                }
                // Every entry is kept: the old "if (!found)" guard dropped the
                // sessions listed after the current one, so talking in an older
                // conversation made the newer ones vanish from the list (their
                // files stayed on disk, unreachable).
                next.put(entry);
            }
            if (!found) {
                org.json.JSONObject entry = new org.json.JSONObject();
                entry.put("id", sessionId);
                entry.put("title", firstLine.isEmpty() ? "New chat" : firstLine);
                entry.put("updated", System.currentTimeMillis());
                next.put(entry);
            }
            // the touched session goes first, everything else keeps its order
            org.json.JSONArray ordered = new org.json.JSONArray();
            for (int i = 0; i < next.length(); i++) {
                org.json.JSONObject entry = next.optJSONObject(i);
                if (entry != null && sessionId.equals(entry.optString("id"))) {
                    ordered.put(entry);
                }
            }
            for (int i = 0; i < next.length(); i++) {
                org.json.JSONObject entry = next.optJSONObject(i);
                if (entry != null && !sessionId.equals(entry.optString("id"))) {
                    ordered.put(entry);
                }
            }
            writeSessions(ordered);
        } catch (org.json.JSONException ignored) {
            // titles are cosmetic
        }
    }

    /** Switch to another conversation and rebuild the panel. */
    private void switchSession(String id) {
        if (id == null || id.isEmpty() || id.equals(sessionId)) {
            return;
        }
        saveNow();
        sessionId = id;
        conversation.clear();
        clearChat();
        stepBlock = null;
        if (welcome != null) {
            welcome.setVisibility(View.GONE);
        }
        restoreChat();
        touchSession("");
        refreshSubtitle();
    }

    /** Build the chat panel from the current session's file. */
    private void reloadSession() {
        conversation.clear();
        clearChat();
        stepBlock = null;
        restoreChat();
    }

    private void saveNow() {
        handler.removeCallbacks(saveChat);
        saveChat.run();
    }

    private void remember(String kind, String text) {
        if (restoring) {
            return;                        // replaying the file, not adding to it
        }
        try {
            conversation.add(new org.json.JSONObject().put("k", kind).put("t", text));
            if ("you".equals(kind)) {
                // the first thing said names the conversation
                touchSession(text);
                org.json.JSONArray list = readSessions();
                if (list.length() > 0) {
                    refreshSubtitle();
                }
            }
            while (conversation.size() > 200) {
                conversation.remove(0);
            }
            handler.removeCallbacks(saveChat);
            handler.postDelayed(saveChat, 600);
        } catch (org.json.JSONException ignored) {
            // a message that cannot be stored is not worth failing over
        }
    }

    private final Runnable saveChat = () -> {
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            for (org.json.JSONObject item : conversation) {
                array.put(item);
            }
            writeAtomically(sessionFile(), array.toString());
        } catch (Throwable t) {
            android.util.Log.w("Pairing", "could not save the chat: " + t);
        }
    };

    /**
     * Put the previous conversation back.
     *
     * The transcript lived only in the view tree, so closing the app -- or the
     * system reclaiming it -- threw away everything that had been said.
     */
    private void restoreChat() {
        java.io.File file = readable(sessionFile());
        if (file == null) {
            return;
        }
        restoring = true;
        try {
            org.json.JSONArray array = new org.json.JSONArray(readText(file));
            boolean any = false;
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String kind = item.optString("k");
                String text = item.optString("t");
                // a version of this app printed a finished task's answer every
                // few seconds; those repeats are in the stored transcript and
                // should not come back on every start
                if (text.matches("^display=\\d+ size=.*")) {
                    // A reconnect used to print its raw result
                    // ("display=367 size=1280x2772 dpi=520 app=..."). It is in
                    // stored transcripts; drop it instead of showing it again
                    // on every launch.
                    continue;
                }
                if (!"steps".equals(kind) && !conversation.isEmpty()) {
                    org.json.JSONObject before = conversation.get(conversation.size() - 1);
                    if (text.equals(before.optString("t")) && kind.equals(before.optString("k"))) {
                        continue;
                    }
                }
                conversation.add(item);
                if ("you".equals(kind)) {
                    append("You", text);
                    any = true;
                } else if ("note".equals(kind)) {
                    note(text);
                } else if ("steps".equals(kind)) {
                    appendLines(text);
                } else {
append("ORBIT", text);
                    any = true;
                }
            }
            if (any && welcome != null) {
                welcome.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            android.util.Log.w("Pairing", "could not restore the chat: " + t);
        } finally {
            restoring = false;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void scrollDown() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
