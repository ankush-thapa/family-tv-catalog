package com.athapa.familytv.probe;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String DEFAULT_VIDEO_ID = "M7lc1UVf-VE";
    private static final String CATALOG_URL = "https://raw.githubusercontent.com/ankush-thapa/family-tv-catalog/main/family_tv.json";
    private static final int PAGE_HORIZONTAL_PADDING_DP = 42;
    private static final int ROW_FOCUS_PADDING_DP = 12;
    private static final int SEEK_STEP_SECONDS = 5;
    private static final int SEEK_COMMIT_DELAY_MS = 900;
    private static final long CATALOG_REFRESH_INTERVAL_MS = 10 * 60 * 1000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> thumbnailCache = new HashMap<>();
    private final Random random = new Random();
    private final Runnable hidePlayerControlsRunnable = this::hidePlayerControls;
    private final Runnable commitPendingSeekRunnable = this::commitPendingSeek;
    private final Runnable catalogRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (webView == null) {
                fetchCatalog(false);
                mainHandler.postDelayed(this, CATALOG_REFRESH_INTERVAL_MS);
            }
        }
    };

    private FrameLayout root;
    private WebView webView;
    private View lastFocusedCard;
    private List<Playlist> playlists;
    private int currentPlaylistIndex = -1;
    private int currentVideoIndex = -1;

    private FrameLayout playerControls;
    private TextView subtitleLabel;
    private SeekBar seekBar;
    private TextView elapsedLabel;
    private TextView durationLabel;
    private TextView prevButton;
    private TextView playPauseButton;
    private TextView nextButton;
    private TextView shuffleButton;
    private final List<TextView> settingButtons = new ArrayList<>();
    private int focusedSettingIndex = 0;
    private boolean controlsVisible = false;
    private boolean catalogFetchInFlight = false;
    private String lastCatalogJson = "";
    private double currentSeconds = 0;
    private double durationSeconds = 0;
    private double pendingSeekSeconds = -1;
    private int playerState = -1;
    private boolean shuffleEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlists = new ArrayList<>();
        enterImmersiveMode();

        root = new FrameLayout(this);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setBackgroundColor(Color.rgb(9, 12, 17));
        setContentView(root);

        String videoId = getIntent().getStringExtra("video_id");
        if (videoId == null || videoId.trim().isEmpty()) {
            showLoading();
            fetchCatalog(true);
        } else {
            playAdHocVideo(videoId);
        }
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showHome() {
        currentPlaylistIndex = -1;
        currentVideoIndex = -1;
        destroyPlayer();
        root.removeAllViews();
        root.addView(buildHomeView(), matchParent());
        startCatalogRefreshLoop();

        if (lastFocusedCard != null) {
            lastFocusedCard.requestFocus();
        }
    }

    private void showLoading() {
        destroyPlayer();
        root.removeAllViews();
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(Color.rgb(9, 12, 17));

        TextView title = text("Family TV", 34, Color.WHITE, Typeface.BOLD);
        TextView subtitle = text("Loading playlists", 17, Color.rgb(172, 181, 194), Typeface.NORMAL);
        loading.addView(title);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.setMargins(0, dp(8), 0, 0);
        loading.addView(subtitle, subtitleParams);
        root.addView(loading, matchParent());
    }

    private View buildHomeView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setPadding(dp(PAGE_HORIZONTAL_PADDING_DP), dp(28), dp(PAGE_HORIZONTAL_PADDING_DP), dp(42));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("Family TV", 30, Color.WHITE, Typeface.BOLD);
        page.addView(title);

        subtitleLabel = text("Curated playlists only", 15, Color.rgb(172, 181, 194), Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.setMargins(0, dp(4), 0, dp(14));
        page.addView(subtitleLabel, subtitleParams);

        int cardWidth = calculateCardWidth();
        int thumbnailHeight = Math.round(cardWidth * 0.54f);
        int cardHeight = thumbnailHeight + dp(72);
        int rowHeight = cardHeight + dp(ROW_FOCUS_PADDING_DP * 2);

        for (int playlistIndex = 0; playlistIndex < playlists.size(); playlistIndex++) {
            Playlist playlist = playlists.get(playlistIndex);
            TextView playlistTitle = text(playlist.name, 21, Color.WHITE, Typeface.BOLD);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            titleParams.setMargins(0, dp(10), 0, dp(4));
            page.addView(playlistTitle, titleParams);

            HorizontalScrollView rowScroll = new HorizontalScrollView(this);
            rowScroll.setHorizontalScrollBarEnabled(false);
            rowScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            rowScroll.setClipToPadding(false);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipToPadding(false);
            row.setPadding(dp(ROW_FOCUS_PADDING_DP), dp(ROW_FOCUS_PADDING_DP), dp(ROW_FOCUS_PADDING_DP), dp(ROW_FOCUS_PADDING_DP));
            rowScroll.addView(row);

            for (int videoIndex = 0; videoIndex < playlist.videos.size(); videoIndex++) {
                Video video = playlist.videos.get(videoIndex);
                View card = buildVideoCard(video, playlistIndex, videoIndex, thumbnailHeight);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(cardWidth, cardHeight);
                cardParams.setMargins(0, 0, dp(14), 0);
                row.addView(card, cardParams);
            }

            page.addView(rowScroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
            ));
        }

        return scrollView;
    }

    private int calculateCardWidth() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int pagePadding = dp(PAGE_HORIZONTAL_PADDING_DP) * 2;
        int rowFocusPadding = dp(ROW_FOCUS_PADDING_DP) * 2;
        int gaps = dp(14) * 4;
        int available = screenWidth - pagePadding - rowFocusPadding - gaps;
        int widthForFiveCards = available / 5;
        return Math.max(dp(230), Math.min(dp(340), widthForFiveCards));
    }

    private View buildVideoCard(Video video, int playlistIndex, int videoIndex, int thumbnailHeight) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));
        card.setBackground(cardBackground(false));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(Color.rgb(25, 31, 41));
        card.addView(thumbnail, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                thumbnailHeight
        ));
        loadThumbnail(video.thumbnailUrl, thumbnail);

        TextView videoTitle = text(video.title, 14, Color.WHITE, Typeface.BOLD);
        videoTitle.setMaxLines(2);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.setMargins(0, dp(7), 0, 0);
        card.addView(videoTitle, titleParams);

        TextView meta = text(video.durationText, 12, Color.rgb(157, 168, 183), Typeface.NORMAL);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metaParams.setMargins(0, dp(2), 0, 0);
        card.addView(meta, metaParams);

        card.setOnClickListener(v -> playCatalogVideo(playlistIndex, videoIndex));
        card.setOnFocusChangeListener((view, hasFocus) -> {
            view.setBackground(cardBackground(hasFocus));
            view.animate().scaleX(hasFocus ? 1.045f : 1f).scaleY(hasFocus ? 1.045f : 1f).setDuration(120).start();
            if (hasFocus) {
                lastFocusedCard = view;
            }
        });
        return card;
    }

    private void playCatalogVideo(int playlistIndex, int videoIndex) {
        currentPlaylistIndex = playlistIndex;
        currentVideoIndex = videoIndex;
        Video video = playlists.get(playlistIndex).videos.get(videoIndex);
        showPlayer(video.youtubeVideoId);
    }

    private void playAdHocVideo(String videoId) {
        currentPlaylistIndex = -1;
        currentVideoIndex = -1;
        showPlayer(videoId);
    }

    private void fetchCatalog(boolean forceRender) {
        if (catalogFetchInFlight) {
            return;
        }
        catalogFetchInFlight = true;
        catalogExecutor.execute(() -> {
            try {
                String rawCatalog = fetchText(CATALOG_URL);
                if (!forceRender && rawCatalog.equals(lastCatalogJson)) {
                    mainHandler.post(() -> catalogFetchInFlight = false);
                    return;
                }
                List<Playlist> remotePlaylists = CatalogParser.parse(rawCatalog);
                if (remotePlaylists.isEmpty()) {
                    mainHandler.post(() -> catalogFetchInFlight = false);
                    return;
                }
                shuffleVideosInPlaylists(remotePlaylists);
                mainHandler.post(() -> {
                    lastCatalogJson = rawCatalog;
                    playlists = remotePlaylists;
                    lastFocusedCard = null;
                    catalogFetchInFlight = false;
                    if (webView == null) {
                        showHome();
                    }
                });
            } catch (Exception ignored) {
                mainHandler.post(() -> {
                    if (playlists.isEmpty()) {
                        playlists = SampleCatalog.create();
                        shuffleVideosInPlaylists(playlists);
                        lastFocusedCard = null;
                        showHome();
                    }
                    if (subtitleLabel != null) {
                        subtitleLabel.setText("Curated playlists only - using bundled fallback");
                    }
                    catalogFetchInFlight = false;
                });
            }
        });
    }

    private String fetchText(String urlValue) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(withCacheBuster(urlValue));
            connection = (HttpURLConnection) url.openConnection();
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.connect();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                throw new IllegalStateException("Unexpected HTTP " + connection.getResponseCode());
            }
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
            }
            return result.toString();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String withCacheBuster(String urlValue) {
        String separator = urlValue.contains("?") ? "&" : "?";
        return urlValue + separator + "family_tv_t=" + System.currentTimeMillis();
    }

    private void shuffleVideosInPlaylists(List<Playlist> catalog) {
        for (Playlist playlist : catalog) {
            Collections.shuffle(playlist.videos, random);
        }
    }

    private void showPlayer(String videoId) {
        stopCatalogRefreshLoop();
        destroyPlayer();
        root.removeAllViews();
        thumbnailCache.clear();
        currentSeconds = 0;
        durationSeconds = 0;
        playerState = -1;

        webView = buildWebView();
        root.addView(webView, matchParent());
        playerControls = buildPlayerControls();
        root.addView(playerControls, matchParent());
        hidePlayerControls();

        webView.loadDataWithBaseURL(
                "https://www.youtube-nocookie.com/",
                youtubeHtml(videoId),
                "text/html",
                "UTF-8",
                null
        );
        root.requestFocus();
    }

    private FrameLayout buildPlayerControls() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setFocusable(false);
        overlay.setClickable(false);
        overlay.setBackgroundColor(0x00000000);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(34), dp(16), dp(34), dp(20));
        panel.setBackground(playerPanelBackground());

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        elapsedLabel = text("0:00", 14, Color.WHITE, Typeface.BOLD);
        durationLabel = text("--:--", 14, Color.WHITE, Typeface.BOLD);
        TextView spacer = text("", 1, Color.WHITE, Typeface.NORMAL);
        timeRow.addView(elapsedLabel);
        timeRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        timeRow.addView(durationLabel);
        panel.addView(timeRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setFocusable(true);
        seekBar.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
        );
        seekParams.setMargins(0, dp(4), 0, dp(8));
        panel.addView(seekBar, seekParams);

        LinearLayout settingsRow = new LinearLayout(this);
        settingsRow.setOrientation(LinearLayout.HORIZONTAL);
        settingsRow.setGravity(Gravity.CENTER);
        prevButton = playerButton("◀◀");
        playPauseButton = playerButton("Ⅱ");
        nextButton = playerButton("▶▶");
        shuffleButton = playerButton("⇄");

        addSettingButton(settingsRow, prevButton);
        addSettingButton(settingsRow, playPauseButton);
        addSettingButton(settingsRow, nextButton);
        addSettingButton(settingsRow, shuffleButton);
        updateShuffleButtonUi();
        panel.addView(settingsRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        panelParams.setMargins(dp(96), 0, dp(96), dp(30));
        overlay.addView(panel, panelParams);
        return overlay;
    }

    private void addSettingButton(LinearLayout row, TextView button) {
        settingButtons.add(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(40)
        );
        params.setMargins(dp(6), 0, dp(6), 0);
        row.addView(button, params);
    }

    private TextView playerButton(String label) {
        TextView button = text(label, 22, Color.WHITE, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setMinWidth(dp(54));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(playerButtonBackground(false));
        button.setOnFocusChangeListener((view, hasFocus) -> {
            button.setBackground(playerButtonBackground(hasFocus));
            if (hasFocus) {
                button.setTextColor(Color.rgb(9, 12, 17));
            } else if (button == shuffleButton && shuffleEnabled) {
                button.setTextColor(Color.rgb(125, 232, 171));
            } else {
                button.setTextColor(Color.WHITE);
            }
        });
        return button;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView buildWebView() {
        WebView view = new WebView(this);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        view.setBackgroundColor(Color.BLACK);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        view.setWebViewClient(new WebViewClient() {
            @TargetApi(Build.VERSION_CODES.O)
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != null) {
                    root.removeView(view);
                }
                webView = null;
                mainHandler.post(MainActivity.this::showHome);
                return true;
            }
        });
        view.setWebChromeClient(new WebChromeClient());
        view.addJavascriptInterface(new PlayerBridge(), "FamilyTv");

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        return view;
    }

    private String youtubeHtml(String videoId) {
        return "<!doctype html>\n"
                + "<html>\n"
                + "<head>\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "  <style>\n"
                + "    html, body, #player { width:100%; height:100%; margin:0; background:#000; overflow:hidden; }\n"
                + "  </style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div id=\"player\"></div>\n"
                + "  <script src=\"https://www.youtube.com/iframe_api\"></script>\n"
                + "  <script>\n"
                + "    var player;\n"
                + "    function onYouTubeIframeAPIReady() {\n"
                + "      player = new YT.Player('player', {\n"
                + "        width: '100%',\n"
                + "        height: '100%',\n"
                + "        videoId: '" + escapeJs(videoId) + "',\n"
                + "        host: 'https://www.youtube-nocookie.com',\n"
                + "        playerVars: {\n"
                + "          autoplay: 1,\n"
                + "          controls: 0,\n"
                + "          disablekb: 1,\n"
                + "          fs: 0,\n"
                + "          origin: 'https://www.youtube-nocookie.com',\n"
                + "          playsinline: 0,\n"
                + "          rel: 0,\n"
                + "          modestbranding: 1\n"
                + "        },\n"
                + "        events: {\n"
                + "          onReady: function(event) { event.target.playVideo(); postProgress(); },\n"
                + "          onStateChange: function(event) {\n"
                + "            postProgress();\n"
                + "            if (event.data === YT.PlayerState.ENDED) { FamilyTv.onVideoEnded(); }\n"
                + "          }\n"
                + "        }\n"
                + "      });\n"
                + "      setInterval(postProgress, 1000);\n"
                + "    }\n"
                + "    function postProgress() {\n"
                + "      if (!player || !player.getCurrentTime) return;\n"
                + "      FamilyTv.onProgress(player.getCurrentTime() || 0, player.getDuration() || 0, player.getPlayerState ? player.getPlayerState() : -1);\n"
                + "    }\n"
                + "    function seekToSeconds(seconds) {\n"
                + "      if (!player || !player.seekTo) return;\n"
                + "      player.seekTo(seconds, true);\n"
                + "      postProgress();\n"
                + "    }\n"
                + "    function togglePlay() {\n"
                + "      if (!player || !player.getPlayerState) return;\n"
                + "      var state = player.getPlayerState();\n"
                + "      if (state === YT.PlayerState.PLAYING) { player.pauseVideo(); } else { player.playVideo(); }\n"
                + "      setTimeout(postProgress, 150);\n"
                + "    }\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>\n";
    }

    private boolean handlePlayerKey(KeyEvent event) {
        if (webView == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showHome();
            fetchCatalog(false);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            showPlayerControls(false);
            runPlayerJs("togglePlay()");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            showPlayerControls(false);
            playNextOrReturnHome();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            showPlayerControls(false);
            playPreviousOrRestart();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            showPlayerControls(true);
            previewSeekBy(-SEEK_STEP_SECONDS);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            showPlayerControls(true);
            previewSeekBy(SEEK_STEP_SECONDS);
            return true;
        }

        if (!isPlayerControlKey(keyCode)) {
            return false;
        }

        if (!controlsVisible) {
            showPlayerControls(true);
            return true;
        }

        scheduleControlsHide();

        if (seekBar != null && seekBar.hasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                previewSeekBy(-SEEK_STEP_SECONDS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                previewSeekBy(SEEK_STEP_SECONDS);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                commitPendingSeek();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusSetting(0);
                return true;
            }
            return true;
        }

        if (isSettingFocused()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusSetting(Math.max(0, focusedSettingIndex - 1));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                focusSetting(Math.min(settingButtons.size() - 1, focusedSettingIndex + 1));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                seekBar.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                activateFocusedSetting();
                return true;
            }
        }

        seekBar.requestFocus();
        return true;
    }

    private boolean isPlayerControlKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
    }

    private void showPlayerControls(boolean focusSeek) {
        if (playerControls == null) {
            return;
        }
        controlsVisible = true;
        playerControls.setVisibility(View.VISIBLE);
        playerControls.setAlpha(0f);
        playerControls.animate().alpha(1f).setDuration(130).start();
        if (focusSeek && seekBar != null) {
            seekBar.requestFocus();
        }
        scheduleControlsHide();
    }

    private void hidePlayerControls() {
        controlsVisible = false;
        mainHandler.removeCallbacks(hidePlayerControlsRunnable);
        if (playerControls != null) {
            playerControls.setVisibility(View.GONE);
        }
        if (root != null) {
            root.requestFocus();
        }
    }

    private void scheduleControlsHide() {
        mainHandler.removeCallbacks(hidePlayerControlsRunnable);
        mainHandler.postDelayed(hidePlayerControlsRunnable, 7000);
    }

    private void focusSetting(int index) {
        if (settingButtons.isEmpty()) {
            return;
        }
        focusedSettingIndex = Math.max(0, Math.min(index, settingButtons.size() - 1));
        settingButtons.get(focusedSettingIndex).requestFocus();
    }

    private boolean isSettingFocused() {
        for (int i = 0; i < settingButtons.size(); i++) {
            if (settingButtons.get(i).hasFocus()) {
                focusedSettingIndex = i;
                return true;
            }
        }
        return false;
    }

    private void activateFocusedSetting() {
        if (focusedSettingIndex == 0) {
            playPreviousOrRestart();
        } else if (focusedSettingIndex == 1) {
            runPlayerJs("togglePlay()");
        } else if (focusedSettingIndex == 2) {
            playNextOrReturnHome();
        } else if (focusedSettingIndex == 3) {
            toggleShuffle();
        }
    }

    private void previewSeekBy(int deltaSeconds) {
        double base = pendingSeekSeconds >= 0 ? pendingSeekSeconds : currentSeconds;
        double target = base + deltaSeconds;
        if (durationSeconds > 0) {
            target = Math.max(0, Math.min(durationSeconds, target));
        } else {
            target = Math.max(0, target);
        }
        pendingSeekSeconds = target;
        updateProgressUi();
        mainHandler.removeCallbacks(commitPendingSeekRunnable);
        mainHandler.postDelayed(commitPendingSeekRunnable, SEEK_COMMIT_DELAY_MS);
    }

    private void commitPendingSeek() {
        if (pendingSeekSeconds < 0) {
            return;
        }
        double targetSeconds = pendingSeekSeconds;
        pendingSeekSeconds = -1;
        currentSeconds = targetSeconds;
        updateProgressUi();
        runPlayerJs("seekToSeconds(" + Math.round(targetSeconds) + ")");
    }

    private void runPlayerJs(String script) {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript(script, null);
    }

    private void updateProgressUi() {
        double displaySeconds = pendingSeekSeconds >= 0 ? pendingSeekSeconds : currentSeconds;
        if (elapsedLabel != null) {
            elapsedLabel.setText(formatTime(displaySeconds));
        }
        if (durationLabel != null) {
            durationLabel.setText(durationSeconds > 0 ? formatTime(durationSeconds) : "--:--");
        }
        if (seekBar != null && durationSeconds > 0) {
            seekBar.setProgress((int) Math.round((displaySeconds / durationSeconds) * 1000));
        }
        if (playPauseButton != null) {
            playPauseButton.setText(playerState == 1 ? "Ⅱ" : "▶");
        }
        updateShuffleButtonUi();
    }

    private String formatTime(double secondsValue) {
        int seconds = Math.max(0, (int) Math.round(secondsValue));
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remainingSeconds = seconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private void playNextOrReturnHome() {
        pendingSeekSeconds = -1;
        mainHandler.removeCallbacks(commitPendingSeekRunnable);
        if (currentPlaylistIndex < 0 || currentVideoIndex < 0) {
            showHome();
            fetchCatalog(false);
            return;
        }
        if (shuffleEnabled && playRandomCatalogVideo()) {
            return;
        }

        Playlist playlist = playlists.get(currentPlaylistIndex);
        int nextIndex = currentVideoIndex + 1;
        if (nextIndex < playlist.videos.size()) {
            playCatalogVideo(currentPlaylistIndex, nextIndex);
        } else {
            showHome();
            fetchCatalog(false);
        }
    }

    private void playPreviousOrRestart() {
        pendingSeekSeconds = -1;
        mainHandler.removeCallbacks(commitPendingSeekRunnable);
        if (currentPlaylistIndex >= 0 && currentVideoIndex > 0 && currentSeconds < 5) {
            playCatalogVideo(currentPlaylistIndex, currentVideoIndex - 1);
            return;
        }
        currentSeconds = 0;
        updateProgressUi();
        runPlayerJs("seekToSeconds(0)");
    }

    private boolean playRandomCatalogVideo() {
        int totalVideos = countCatalogVideos();
        if (totalVideos <= 0) {
            return false;
        }
        if (totalVideos == 1 && currentPlaylistIndex >= 0 && currentVideoIndex >= 0) {
            return false;
        }

        for (int attempt = 0; attempt < 12; attempt++) {
            int[] indices = indicesForFlatVideoIndex(random.nextInt(totalVideos));
            if (indices[0] < 0) {
                return false;
            }
            if (indices[0] != currentPlaylistIndex || indices[1] != currentVideoIndex) {
                playCatalogVideo(indices[0], indices[1]);
                return true;
            }
        }

        return false;
    }

    private int countCatalogVideos() {
        int total = 0;
        for (Playlist playlist : playlists) {
            total += playlist.videos.size();
        }
        return total;
    }

    private int[] indicesForFlatVideoIndex(int flatIndex) {
        int remaining = flatIndex;
        for (int playlistIndex = 0; playlistIndex < playlists.size(); playlistIndex++) {
            List<Video> videos = playlists.get(playlistIndex).videos;
            if (remaining < videos.size()) {
                return new int[]{playlistIndex, remaining};
            }
            remaining -= videos.size();
        }
        return new int[]{-1, -1};
    }

    private void toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        updateShuffleButtonUi();
        scheduleControlsHide();
    }

    private void updateShuffleButtonUi() {
        if (shuffleButton == null || shuffleButton.hasFocus()) {
            return;
        }
        shuffleButton.setTextColor(shuffleEnabled ? Color.rgb(125, 232, 171) : Color.WHITE);
        shuffleButton.setBackground(playerButtonBackground(false));
    }

    private void destroyPlayer() {
        mainHandler.removeCallbacks(hidePlayerControlsRunnable);
        mainHandler.removeCallbacks(commitPendingSeekRunnable);
        pendingSeekSeconds = -1;
        playerControls = null;
        seekBar = null;
        prevButton = null;
        playPauseButton = null;
        nextButton = null;
        shuffleButton = null;
        settingButtons.clear();
        focusedSettingIndex = 0;
        controlsVisible = false;
        if (webView == null) {
            return;
        }
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.destroy();
        webView = null;
    }

    private void startCatalogRefreshLoop() {
        mainHandler.removeCallbacks(catalogRefreshRunnable);
        mainHandler.postDelayed(catalogRefreshRunnable, CATALOG_REFRESH_INTERVAL_MS);
    }

    private void stopCatalogRefreshLoop() {
        mainHandler.removeCallbacks(catalogRefreshRunnable);
    }

    private void loadThumbnail(String url, ImageView imageView) {
        if (thumbnailCache.size() > 24) {
            thumbnailCache.clear();
        }
        Bitmap cached = thumbnailCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageExecutor.execute(() -> {
            Bitmap bitmap = downloadBitmap(url);
            if (bitmap == null) {
                return;
            }
            thumbnailCache.put(url, bitmap);
            mainHandler.post(() -> imageView.setImageBitmap(bitmap));
        });
    }

    private Bitmap downloadBitmap(String urlValue) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlValue);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.connect();
            try (InputStream input = connection.getInputStream()) {
                return BitmapFactory.decodeStream(input);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private GradientDrawable cardBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(focused ? Color.rgb(28, 36, 48) : Color.rgb(17, 22, 30));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(focused ? 3 : 1), focused ? Color.WHITE : Color.rgb(50, 59, 73));
        return background;
    }

    private GradientDrawable playerPanelBackground() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC101621, 0xEE080B10}
        );
        background.setCornerRadius(dp(8));
        return background;
    }

    private GradientDrawable playerButtonBackground(boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(focused ? Color.WHITE : Color.rgb(31, 39, 51));
        background.setCornerRadius(dp(7));
        background.setStroke(dp(1), focused ? Color.WHITE : Color.rgb(72, 84, 101));
        return background;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setIncludeFontPadding(true);
        return text;
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handlePlayerKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        stopCatalogRefreshLoop();
        destroyPlayer();
        imageExecutor.shutdownNow();
        catalogExecutor.shutdownNow();
        super.onDestroy();
    }

    private class PlayerBridge {
        @JavascriptInterface
        public void onVideoEnded() {
            mainHandler.post(MainActivity.this::playNextOrReturnHome);
        }

        @JavascriptInterface
        public void onProgress(double current, double duration, int state) {
            mainHandler.post(() -> {
                currentSeconds = current;
                durationSeconds = duration;
                playerState = state;
                updateProgressUi();
            });
        }
    }

    private static class SampleCatalog {
        static List<Playlist> create() {
            List<Playlist> catalog = new ArrayList<>();
            catalog.add(new Playlist("bacha-box", "Bacha Box", list(
                    new Video("bacha-01", "Bacha Box 01", "0rFFO7a7a2Y", "Bacha Box"),
                    new Video("bacha-02", "Bacha Box 02", "4kzsCps8qag", "Bacha Box"),
                    new Video("bacha-03", "Bacha Box 03", "67E8xAMoeTI", "Bacha Box"),
                    new Video("bacha-04", "Bacha Box 04", "6GnzAaEbzLQ", "Bacha Box"),
                    new Video("bacha-05", "Bacha Box 05", "7wRid8afxHc", "Bacha Box"),
                    new Video("bacha-06", "Bacha Box 06", "99jigoMRmdM", "Bacha Box"),
                    new Video("bacha-07", "Bacha Box 07", "AWSEnsukOU8", "Bacha Box"),
                    new Video("bacha-08", "Bacha Box 08", "A_BaS3M1wA0", "Bacha Box"),
                    new Video("bacha-09", "Bacha Box 09", "CLO39LyAUwg", "Bacha Box"),
                    new Video("bacha-10", "Bacha Box 10", "DwSBjN3c4Y8", "Bacha Box"),
                    new Video("bacha-11", "Bacha Box 11", "ECof3SeR9tQ", "Bacha Box"),
                    new Video("bacha-12", "Bacha Box 12", "Iv7HtJJEGFs", "Bacha Box"),
                    new Video("bacha-13", "Bacha Box 13", "KPqpEVN6eb4", "Bacha Box"),
                    new Video("bacha-14", "Bacha Box 14", "L46H3wPnYvg", "Bacha Box"),
                    new Video("bacha-15", "Bacha Box 15", "NBHEXcVNVPE", "Bacha Box")
            )));
            catalog.add(new Playlist("ms-rachel", "Ms Rachel", list(
                    new Video("msrachel-talk", "Learn to Talk for Babies and Toddlers", "w264Mn-2MnQ", "Ms Rachel"),
                    new Video("msrachel-animals", "Animal Learning for Toddlers", "8KtnrtHRiCg", "Ms Rachel"),
                    new Video("msrachel-phonics", "Phonics Song and Nursery Rhymes", "1v3Dk41C_10", "Ms Rachel"),
                    new Video("msrachel-friendship", "Friendship and Social Skills", "2dDpryw3z5w", "Ms Rachel"),
                    new Video("msrachel-teeth", "Brush Your Teeth Song", "tYDuAfY77Do", "Ms Rachel"),
                    new Video("msrachel-potty", "Potty Training with Ms Rachel", "qXKsou9UmfY", "Ms Rachel"),
                    new Video("msrachel-hide-seek", "Hide and Seek with Ms Rachel", "drkVagtmIJA", "Ms Rachel"),
                    new Video("msrachel-preschool", "Preschool and Toddler Learning", "K_Aq4H03Nm4", "Ms Rachel"),
                    new Video("msrachel-rainbow", "I Love A Rainbow", "GLigE_f4dl0", "Ms Rachel"),
                    new Video("msrachel-milestones", "Toddler Learning and Milestones", "h67AgK4EHq4", "Ms Rachel"),
                    new Video("msrachel-abc", "ABC Song", "b051ktudQDQ", "Ms Rachel"),
                    new Video("msrachel-school", "Get Ready For School", "nnqsEUxgSBQ", "Ms Rachel"),
                    new Video("msrachel-colors", "Learn Colors Numbers and Feelings", "VvoO9-L1KA4", "Ms Rachel"),
                    new Video("msrachel-bunnies", "Hop Little Bunnies", "gngPQ771Ahk", "Ms Rachel"),
                    new Video("msrachel-read", "Learn to Read with Ms Rachel", "oVtzNpzuvoA", "Ms Rachel")
            )));
            catalog.add(new Playlist("jai-jai-tv", "Jai Jai TV", list(
                    new Video("jaijai-01", "Jai Jai TV 01", "0Q3kgJuJtxs", "Jai Jai TV"),
                    new Video("jaijai-02", "Jai Jai TV 02", "1l29nWIP6l4", "Jai Jai TV"),
                    new Video("jaijai-03", "Jai Jai TV 03", "2J_E6TozpkA", "Jai Jai TV"),
                    new Video("jaijai-04", "Jai Jai TV 04", "2bxz2IQIBhQ", "Jai Jai TV"),
                    new Video("jaijai-05", "Jai Jai TV 05", "CLlEHy16gtk", "Jai Jai TV"),
                    new Video("jaijai-06", "Jai Jai TV 06", "Cjb-pjsKWaY", "Jai Jai TV"),
                    new Video("jaijai-07", "Jai Jai TV 07", "CqcldelgooQ", "Jai Jai TV"),
                    new Video("jaijai-08", "Jai Jai TV 08", "ERFC2DXJ0MQ", "Jai Jai TV"),
                    new Video("jaijai-09", "Jai Jai TV 09", "G2YmJlyAZHs", "Jai Jai TV"),
                    new Video("jaijai-10", "Jai Jai TV 10", "INSjgGBBBek", "Jai Jai TV"),
                    new Video("jaijai-11", "Jai Jai TV 11", "J6R-zj-9pog", "Jai Jai TV"),
                    new Video("jaijai-12", "Bajrang Baan", "KaAa6zG9hPo", "Jai Jai TV"),
                    new Video("jaijai-13", "Jai Jai TV 13", "LjoCf5Fy_jg", "Jai Jai TV"),
                    new Video("jaijai-14", "Jai Jai TV 14", "N6GA4oJZI4Q", "Jai Jai TV"),
                    new Video("jaijai-15", "Jai Jai TV 15", "PbB0qOVye5U", "Jai Jai TV")
            )));
            catalog.add(new Playlist("super-simple-songs", "Super Simple Songs", list(
                    new Video("sss-book-songs", "Top 20 Book Songs", "jZnBNf0GJyU", "Super Simple"),
                    new Video("sss-trip", "I'm Going On A Trip", "wtX9hMdLuew", "Super Simple"),
                    new Video("sss-summer", "Top 20 Summer Songs", "q1DinydBRNE", "Super Simple"),
                    new Video("sss-beach-shapes", "Shapes I See At The Beach", "hDqWTltrGJ8", "Super Simple"),
                    new Video("sss-active", "Top 20 Active Songs", "N8gOM-A3cUw", "Super Simple"),
                    new Video("sss-sports", "Sports Song", "CXb9llX_LoU", "Super Simple"),
                    new Video("sss-food", "Top 20 Food Songs", "L1C9NJpQMRk", "Super Simple"),
                    new Video("sss-fruit", "Fruit Is Yummy", "DDjOLRNby20", "Super Simple"),
                    new Video("sss-routines", "Top 20 Healthy Kids Routines", "_nfCulGTBvM", "Super Simple"),
                    new Video("sss-vegetables", "Healthy Eating Song", "tjT_nFtKTas", "Super Simple"),
                    new Video("sss-family", "Top 20 Family Songs", "8msljf_aMDs", "Super Simple"),
                    new Video("sss-family-tree", "The Family Tree", "ecm9HEFcfdQ", "Super Simple"),
                    new Video("sss-underwater", "Top 20 Underwater Songs", "MC7yXLjtmiU", "Super Simple"),
                    new Video("sss-baby-fish", "I'm a Baby Fish", "j9PpVDuoVAo", "Super Simple"),
                    new Video("sss-extra", "Super Simple Songs 15", "9FcCV286-gA", "Super Simple")
            )));
            catalog.add(new Playlist("pinkfong", "Pinkfong", list(
                    new Video("pinkfong-01", "Pinkfong 01", "01BejTyy0Cw", "Pinkfong"),
                    new Video("pinkfong-02", "Pinkfong 02", "11q4Jt0QAP4", "Pinkfong"),
                    new Video("pinkfong-03", "Pinkfong 03", "4POxq2YKCNY", "Pinkfong"),
                    new Video("pinkfong-04", "Pinkfong 04", "6l14tV-woRs", "Pinkfong"),
                    new Video("pinkfong-05", "Pinkfong 05", "9Ml7Z5lZiSQ", "Pinkfong"),
                    new Video("pinkfong-storytime", "Dinosaur World Storytime", "9sLAGwabeU8", "Pinkfong"),
                    new Video("pinkfong-07", "Pinkfong 07", "F_0uytyoWIo", "Pinkfong"),
                    new Video("pinkfong-spooky", "Spooky Summer Night", "GcAAt1Fws8U", "Pinkfong"),
                    new Video("pinkfong-09", "Pinkfong 09", "IRUJQnsNGSo", "Pinkfong"),
                    new Video("pinkfong-shark-thief", "Hide and Seek with Thief Baby Shark", "JqM0Nuycr1Q", "Pinkfong"),
                    new Video("pinkfong-11", "Pinkfong 11", "JrlVNPZmT4s", "Pinkfong"),
                    new Video("pinkfong-12", "Pinkfong 12", "Lhjd0D-dNI4", "Pinkfong"),
                    new Video("pinkfong-13", "Pinkfong 13", "QrjMcZY7k4E", "Pinkfong"),
                    new Video("pinkfong-14", "Pinkfong 14", "SUIYsHAQjXY", "Pinkfong"),
                    new Video("pinkfong-15", "Pinkfong 15", "SqpCnSptXWE", "Pinkfong")
            )));
            return catalog;
        }

        private static List<Video> list(Video... videos) {
            List<Video> list = new ArrayList<>();
            for (Video video : videos) {
                list.add(video);
            }
            return list;
        }
    }

    private static class CatalogParser {
        static List<Playlist> parse(String rawJson) throws Exception {
            JSONObject root = new JSONObject(rawJson);
            JSONArray playlistArray = root.getJSONArray("playlists");
            List<Playlist> parsedPlaylists = new ArrayList<>();

            for (int i = 0; i < playlistArray.length(); i++) {
                JSONObject playlistJson = playlistArray.getJSONObject(i);
                String playlistId = playlistJson.optString("id", "playlist-" + i);
                String playlistName = playlistJson.optString("name", playlistId);
                JSONArray videoArray = playlistJson.optJSONArray("videos");
                if (videoArray == null) {
                    continue;
                }

                List<Video> parsedVideos = new ArrayList<>();
                for (int j = 0; j < videoArray.length(); j++) {
                    JSONObject videoJson = videoArray.getJSONObject(j);
                    String youtubeVideoId = videoJson.optString("youtubeVideoId", "").trim();
                    if (youtubeVideoId.isEmpty()) {
                        continue;
                    }
                    String id = videoJson.optString("id", playlistId + "-" + j);
                    String title = videoJson.optString("title", "Video " + (j + 1));
                    String durationText = videoJson.optString("durationText", playlistName);
                    String thumbnailUrl = videoJson.optString("thumbnailUrl", "");
                    parsedVideos.add(new Video(id, title, youtubeVideoId, durationText, thumbnailUrl));
                }

                if (!parsedVideos.isEmpty()) {
                    parsedPlaylists.add(new Playlist(playlistId, playlistName, parsedVideos));
                }
            }

            return parsedPlaylists;
        }
    }

    private static class Playlist {
        final String id;
        final String name;
        final List<Video> videos;

        Playlist(String id, String name, List<Video> videos) {
            this.id = id;
            this.name = name;
            this.videos = videos;
        }
    }

    private static class Video {
        final String id;
        final String title;
        final String youtubeVideoId;
        final String thumbnailUrl;
        final String durationText;

        Video(String id, String title, String youtubeVideoId, String durationText) {
            this(id, title, youtubeVideoId, durationText, "");
        }

        Video(String id, String title, String youtubeVideoId, String durationText, String thumbnailUrl) {
            this.id = id;
            this.title = title;
            this.youtubeVideoId = youtubeVideoId;
            this.thumbnailUrl = thumbnailUrl == null || thumbnailUrl.trim().isEmpty()
                    ? "https://i.ytimg.com/vi/" + youtubeVideoId + "/mqdefault.jpg"
                    : thumbnailUrl;
            this.durationText = durationText;
        }
    }
}
