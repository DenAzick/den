package local.parkbrowser;

import android.accounts.Account;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONException;

import local.parkbrowser.YouTubeAccountApi.Section;

public final class YouTubeAccountActivity extends Activity {
    static final String CONNECT_NOW = "local.parkbrowser.CONNECT_NOW";
    static final String SHOW_FEED = "local.parkbrowser.SHOW_FEED";
    static final String SHOW_BROWSER_MENU = "local.parkbrowser.SHOW_BROWSER_MENU";
    private static final int CONSENT_REQUEST = 1;
    private static final String SAVED_SUBJECT = "subject";
    private static final String SAVED_EMAIL = "email";
    private static final String SAVED_CHANNEL = "channel";
    private static final String CONSENT_PENDING = "consent_pending";
    private static final String SELECTED_TAB = "selected_tab";
    private static final String SELECTED_PLAYLIST = "selected_playlist";
    private static final String SELECTED_PLAYLIST_TITLE = "selected_playlist_title";
    private static final int BACKGROUND = Color.rgb(14, 21, 24);
    private static final int PANEL = Color.rgb(26, 41, 46);
    private static final int INK = Color.rgb(241, 250, 247);
    private static final int MUTED = Color.rgb(177, 197, 201);
    private static final int TEAL = Color.rgb(125, 228, 203);
    private static final int THUMBNAIL_LIMIT = 1_048_576;
    private static final String YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly";
    private static final List<Scope> SCOPES = List.of(new Scope("openid"),
            new Scope("https://www.googleapis.com/auth/userinfo.email"), new Scope(YOUTUBE_READONLY));

    private SharedPreferences preferences;
    private TextView status;
    private ScrollView scroll;
    private LinearLayout content;
    private Screen screen;
    private String playlistId;
    private String playlistTitle;
    private int libraryRequest;
    private int accountMessage = R.string.account_intro;
    private boolean accountWorking;
    private boolean connected;
    private boolean waitingForConsent;
    private final Map<Screen, Button> tabs = new EnumMap<>(Screen.class);
    private final ExecutorService thumbnailWorkers = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> thumbnails = new LruCache<>(7 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap image) {
            return image.getAllocationByteCount();
        }
    };
    private final Map<Screen, YouTubeAccountApi.Page> firstPages = new EnumMap<>(Screen.class);

    private enum Screen {
        FEED(R.string.feed_tab, Section.RECENT), SUBSCRIPTIONS(R.string.account_subscriptions, Section.SUBSCRIPTIONS),
        PLAYLISTS(R.string.feed_playlists, Section.PLAYLISTS), PROFILE(R.string.feed_profile, null);

        final int label;
        final Section section;

        Screen(int label, Section section) {
            this.label = label;
            this.section = section;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Device-local state. Backup rules include only the browser's toolbar preferences.
        preferences = getSharedPreferences("den_youtube_account", MODE_PRIVATE);
        waitingForConsent = state != null && state.getBoolean(CONSENT_PENDING);
        screen = state == null ? (getIntent().getBooleanExtra(SHOW_FEED, false) ? Screen.FEED : Screen.PROFILE)
                : Screen.values()[state.getInt(SELECTED_TAB)];
        if (state != null) {
            playlistId = state.getString(SELECTED_PLAYLIST);
            playlistTitle = state.getString(SELECTED_PLAYLIST_TITLE);
        }
        createUi();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
            if (playlistId != null) {
                select(Screen.PLAYLISTS);
            } else {
                finish();
            }
        });
        if (waitingForConsent) {
            render(R.string.account_checking, true, false);
        } else if (savedEmail() != null) {
            authorize(false);
        } else if (state == null && getIntent().getBooleanExtra(CONNECT_NOW, false)) {
            authorize(true);
        } else {
            render(R.string.account_intro, false, false);
        }
    }

    private void createUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            var insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsets.CONSUMED;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(7), dp(11), dp(6));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo, new LinearLayout.LayoutParams(dp(33), dp(33)));
        TextView wordmark = text(getString(R.string.app_name), 25, INK, true);
        wordmark.setLetterSpacing(0.06f);
        wordmark.setPadding(dp(8), 0, 0, 0);
        header.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = control("⋮", INK, BACKGROUND, 25);
        menu.setContentDescription(getString(R.string.browser_menu));
        menu.setOnClickListener(view -> {
            setResult(RESULT_OK, new Intent().putExtra(SHOW_BROWSER_MENU, true));
            finish();
        });
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        LinearLayout search = new LinearLayout(this);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(rounded(PANEL, 18));
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint(R.string.feed_search);
        query.setTextColor(INK);
        query.setHintTextColor(MUTED);
        query.setTextSize(15);
        query.setPadding(dp(16), 0, 0, 0);
        query.setBackgroundColor(Color.TRANSPARENT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            openSearch(query);
            return true;
        });
        search.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageButton searchButton = new ImageButton(this);
        searchButton.setImageResource(android.R.drawable.ic_menu_search);
        searchButton.setImageTintList(ColorStateList.valueOf(TEAL));
        searchButton.setContentDescription(getString(R.string.feed_search));
        searchButton.setBackground(pressable(PANEL, 18));
        searchButton.setOnClickListener(view -> openSearch(query));
        search.addView(searchButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.setMargins(dp(18), 0, dp(18), dp(6));
        root.addView(search, searchParams);

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(16), dp(18), dp(18));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setPadding(dp(8), dp(5), dp(8), dp(6));
        navigation.setBackgroundColor(PANEL);
        for (Screen tab : Screen.values()) {
            Button button = control(getString(tab.label), MUTED, PANEL, 12);
            button.setOnClickListener(view -> select(tab));
            tabs.put(tab, button);
            navigation.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        root.addView(navigation);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        root.requestFocus();
    }

    private String savedEmail() {
        return preferences.getString(SAVED_EMAIL, null);
    }

    private void render(int message, boolean working, boolean connected) {
        this.accountMessage = message;
        this.accountWorking = working;
        this.connected = connected;
        if (!connected) {
            firstPages.clear();
        }
        redraw();
    }

    private void select(Screen tab) {
        if (screen == tab && playlistId == null) {
            return;
        }
        screen = tab;
        playlistId = null;
        playlistTitle = null;
        redraw();
    }

    private void redraw() {
        for (var entry : tabs.entrySet()) {
            boolean selected = entry.getKey() == screen;
            Button button = entry.getValue();
            button.setSelected(selected);
            button.setTextColor(selected ? BACKGROUND : MUTED);
            button.setBackground(pressable(selected ? TEAL : PANEL, 15));
        }
        if (!connected || screen == Screen.PROFILE) {
            libraryRequest++;
            showAccount();
        } else if (playlistId != null) {
            loadLibrary(Section.VIDEOS, playlistId, "");
        } else {
            YouTubeAccountApi.Page cached = firstPages.get(screen);
            if (cached == null) {
                loadLibrary(screen.section, null, "");
            } else {
                libraryRequest++;
                showLibrary(screen.section, null, cached);
            }
        }
    }

    private void showAccount() {
        startContent(playlistId != null ? playlistTitle : getString(screen == Screen.PROFILE
                ? R.string.youtube_account : screen == Screen.FEED ? R.string.feed_recent : screen.label));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(13), dp(16), dp(16));
        panel.setBackground(rounded(PANEL, 19));
        String email = savedEmail();
        if (email != null && screen == Screen.PROFILE) {
            String channel = preferences.getString(SAVED_CHANNEL, "");
            panel.addView(text(channel.isEmpty() ? email : channel, 20, INK, true));
            if (!channel.isEmpty()) {
                panel.addView(text(email, 14, MUTED, false));
            }
        }
        int displayMessage = screen == Screen.FEED && accountMessage == R.string.account_intro
                ? R.string.feed_subscribe_hint : accountMessage;
        status = text(getString(displayMessage), 15, connected ? TEAL : MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(7), 0, dp(7));
        panel.addView(status);
        if (!accountWorking && !connected) {
            Button connect = control(getString(email == null ? R.string.account_connect : R.string.retry),
                    BACKGROUND, TEAL, 14);
            connect.setOnClickListener(view -> authorize(true));
            panel.addView(connect, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        if (!accountWorking && email != null && screen == Screen.PROFILE) {
            Button forget = control(getString(R.string.account_forget), MUTED, PANEL, 14);
            forget.setOnClickListener(view -> {
                preferences.edit().clear().apply();
                thumbnails.evictAll();
                render(R.string.account_intro, false, false);
            });
            panel.addView(forget, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        content.addView(panel);
        if (screen == Screen.FEED && !connected && !accountWorking) {
            addWebButton();
        }
    }

    private void startContent(String title) {
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        TextView heading = text(title, 26, INK, true);
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, 0, 0, dp(12));
        content.addView(heading);
    }

    private void addWebButton() {
        Button web = control(getString(R.string.feed_web), TEAL, BACKGROUND, 14);
        web.setOnClickListener(view -> openInBrowser(BrowserAddress.YOUTUBE_HOME));
        content.addView(web, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void openSearch(EditText query) {
        String destination = BrowserAddress.resolve(query.getText().toString());
        if (destination == null) {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show();
            return;
        }
        openInBrowser(destination);
    }

    private TextView text(String label, int size, int color, boolean emphasis) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        if (emphasis) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private Button control(String label, int ink, int surface, int size) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(size);
        button.setTextColor(ink);
        button.setStateListAnimator(null);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setBackground(pressable(surface, 15));
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable pressable(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x327DE4CB), rounded(color, radius), null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private AuthorizationRequest authorizationRequest() {
        AuthorizationRequest.Builder request = AuthorizationRequest.builder().setRequestedScopes(SCOPES);
        String email = savedEmail();
        if (email != null) {
            request.setAccount(new Account(email, "com.google"));
        }
        return request.build();
    }

    private void authorize(boolean interactive) {
        render(R.string.account_checking, true, false);
        Identity.getAuthorizationClient(this).authorize(authorizationRequest()).addOnCompleteListener(task -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (task.isSuccessful()) {
                handleAuthorization(task.getResult(), interactive);
            } else {
                showFailure(task.getException());
            }
        });
    }

    private void handleAuthorization(AuthorizationResult result, boolean interactive) {
        if (result.hasResolution()) {
            if (!interactive) {
                render(R.string.account_confirm_again, false, false);
                return;
            }
            try {
                waitingForConsent = true;
                startIntentSenderForResult(result.getPendingIntent().getIntentSender(), CONSENT_REQUEST,
                        null, 0, 0, 0);
            } catch (IntentSender.SendIntentException exception) {
                waitingForConsent = false;
                showFailure(exception);
            }
            return;
        }
        String token = accessToken(result);
        if (token == null) {
            render(R.string.account_confirm_again, false, false);
            return;
        }
        new Thread(() -> {
            try {
                var identity = YouTubeAccountApi.loadIdentity(token);
                try {
                    String channel = YouTubeAccountApi.loadChannel(token);
                    runOnUiThread(() -> remember(identity, channel, R.string.account_connected));
                } catch (YouTubeAccountApi.ServiceDisabledException exception) {
                    runOnUiThread(() -> remember(identity, null, R.string.account_api_disabled));
                } catch (IOException | JSONException exception) {
                    runOnUiThread(() -> remember(identity, null, R.string.account_library_failed));
                }
            } catch (IOException | JSONException exception) {
                runOnUiThread(() -> showFailure(exception));
            }
        }, "den-youtube-account").start();
    }

    private String accessToken(AuthorizationResult result) {
        return result.getGrantedScopes().contains(YOUTUBE_READONLY) ? result.getAccessToken() : null;
    }

    private void loadLibrary(Section section, String playlistId, String pageToken) {
        int request = ++libraryRequest;
        startContent(sectionTitle(section));
        TextView loading = text(getString(section == Section.RECENT ? R.string.feed_loading : R.string.feed_load_list),
                15, MUTED, false);
        loading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(loading);
        Identity.getAuthorizationClient(this).authorize(authorizationRequest()).addOnCompleteListener(task -> {
            if (!isActive(request)) {
                return;
            }
            if (!task.isSuccessful()) {
                showFailure(task.getException());
                return;
            }
            AuthorizationResult result = task.getResult();
            String token = result.hasResolution() ? null : accessToken(result);
            if (token == null) {
                render(R.string.account_confirm_again, false, false);
                return;
            }
            new Thread(() -> {
                try {
                    YouTubeAccountApi.Page page = YouTubeAccountApi.readPage(token, section, playlistId, pageToken);
                    runOnUiThread(() -> {
                        if (isActive(request)) {
                            if (section == screen.section && pageToken.isEmpty()) {
                                firstPages.put(screen, page);
                            }
                            showLibrary(section, playlistId, page);
                        }
                    });
                } catch (IOException | JSONException exception) {
                    runOnUiThread(() -> {
                        if (isActive(request)) {
                            startContent(sectionTitle(section));
                            content.addView(text(getString(R.string.account_library_failed), 15, MUTED, false));
                            Button retry = control(getString(R.string.retry), TEAL, PANEL, 14);
                            retry.setOnClickListener(view -> loadLibrary(section, playlistId, pageToken));
                            content.addView(retry, new LinearLayout.LayoutParams(-1, dp(48)));
                        }
                    });
                }
            }, "den-youtube-library").start();
        });
    }

    private boolean isActive(int request) {
        return request == libraryRequest && !isFinishing() && !isDestroyed();
    }

    private String sectionTitle(Section section) {
        return switch (section) {
            case RECENT -> getString(R.string.feed_recent);
            case SUBSCRIPTIONS -> getString(R.string.account_subscriptions);
            case PLAYLISTS -> getString(R.string.account_playlists);
            case VIDEOS -> playlistTitle == null ? getString(R.string.account_playlist_videos) : playlistTitle;
        };
    }

    private void showLibrary(Section section, String playlistId, YouTubeAccountApi.Page page) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        startContent(sectionTitle(section));
        if (section == Section.RECENT) {
            TextView source = text(getString(R.string.feed_recent_hint), 14, MUTED, false);
            source.setPadding(0, 0, 0, dp(12));
            content.addView(source);
        }
        if (page.items().isEmpty()) {
            content.addView(text(getString(section == Section.RECENT ? R.string.feed_empty : R.string.account_empty_list),
                    15, MUTED, false));
        }
        for (YouTubeAccountApi.Item item : page.items()) {
            addCard(section, item);
        }
        if (!page.next().isEmpty()) {
            Button more = control(getString(R.string.account_next_page), TEAL, PANEL, 14);
            more.setOnClickListener(view -> loadLibrary(section, playlistId, page.next()));
            content.addView(more, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        if (section == Section.RECENT) {
            addWebButton();
        }
    }

    private void addCard(Section section, YouTubeAccountApi.Item item) {
        boolean video = section == Section.RECENT || section == Section.VIDEOS;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(video ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(video ? 8 : 10), dp(8), dp(video ? 8 : 10), dp(8));
        card.setBackground(pressable(PANEL, 20));
        card.setFocusable(true);
        card.setContentDescription(item.title());
        card.setOnClickListener(view -> openLibraryItem(section, item));

        ImageView preview;
        if (video) {
            preview = new ImageView(this) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = View.MeasureSpec.getSize(widthSpec);
                    setMeasuredDimension(width, Math.min(dp(320), width * 9 / 16));
                }
            };
            card.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        } else {
            preview = new ImageView(this);
            card.addView(preview, new LinearLayout.LayoutParams(dp(section == Section.SUBSCRIPTIONS ? 54 : 82), dp(54)));
        }
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview.setBackground(rounded(BACKGROUND, section == Section.SUBSCRIPTIONS ? 27 : 13));
        preview.setClipToOutline(true);

        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(dp(video ? 6 : 12), dp(video ? 9 : 3), dp(6), dp(video ? 5 : 3));
        TextView title = text(item.title(), 16, INK, true);
        title.setMaxLines(video ? 3 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        caption.addView(title);
        if (!item.channel().isBlank()) {
            TextView channel = text(item.channel(), 13, MUTED, false);
            channel.setMaxLines(1);
            channel.setEllipsize(TextUtils.TruncateAt.END);
            channel.setPadding(0, dp(2), 0, 0);
            caption.addView(channel);
        }
        card.addView(caption, video ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams dimensions = new LinearLayout.LayoutParams(-1, -2);
        dimensions.bottomMargin = dp(12);
        content.addView(card, dimensions);
        showThumbnail(preview, item.thumbnail());
    }

    private void showThumbnail(ImageView target, String url) {
        if (!BrowserAddress.isHttps(url)) {
            return;
        }
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ((uri.getPort() != -1 && uri.getPort() != 443)
                || !(host.endsWith(".ytimg.com") || host.equals("yt3.ggpht.com")
                || host.equals("yt3.googleusercontent.com"))) {
            return;
        }
        Bitmap saved = thumbnails.get(url);
        if (saved != null) {
            target.setImageBitmap(saved);
            return;
        }
        thumbnailWorkers.execute(() -> {
            if (!target.isAttachedToWindow()) {
                return;
            }
            try {
                Bitmap image = readThumbnail(uri);
                if (image != null) {
                    runOnUiThread(() -> {
                        if (!isDestroyed() && target.isAttachedToWindow()) {
                            thumbnails.put(url, image);
                            target.setImageBitmap(image);
                        }
                    });
                }
            } catch (IOException ignored) {
                // The title remains available when its image cannot be loaded.
            }
        });
    }

    private Bitmap readThumbnail(URI uri) throws IOException {
        HttpsURLConnection request = (HttpsURLConnection) uri.toURL().openConnection();
        request.setInstanceFollowRedirects(false);
        request.setConnectTimeout(4_000);
        request.setReadTimeout(4_000);
        try (InputStream response = request.getInputStream()) {
            byte[] bytes = response.readNBytes(THUMBNAIL_LIMIT + 1);
            if (bytes.length > THUMBNAIL_LIMIT) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 1920 || bounds.outHeight > 1920) {
                return null;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } finally {
            request.disconnect();
        }
    }

    private void openLibraryItem(Section section, YouTubeAccountApi.Item item) {
        String id = item.id();
        switch (section) {
            case PLAYLISTS -> {
                playlistId = id;
                playlistTitle = item.title();
                loadLibrary(Section.VIDEOS, id, "");
            }
            case SUBSCRIPTIONS -> {
                if (id.matches("UC[A-Za-z0-9_-]{22}")) {
                    openInBrowser(BrowserAddress.YOUTUBE_HOME + "channel/" + id);
                }
            }
            case RECENT, VIDEOS -> {
                if (BrowserAddress.isVideoId(id)) {
                    openInBrowser(BrowserAddress.YOUTUBE_HOME + "watch?v=" + id);
                }
            }
        }
    }

    private void openInBrowser(String url) {
        setResult(RESULT_OK, new Intent().setData(Uri.parse(url)));
        finish();
    }

    private void remember(YouTubeAccountApi.Identity identity, String channel, int message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String previousSubject = preferences.getString(SAVED_SUBJECT, null);
        if (previousSubject != null && !previousSubject.equals(identity.subject())) {
            render(R.string.account_mismatch, false, false);
            return;
        }
        SharedPreferences.Editor changes = preferences.edit().putString(SAVED_SUBJECT, identity.subject())
                .putString(SAVED_EMAIL, identity.email());
        if (channel != null) {
            changes.putString(SAVED_CHANNEL, channel);
        }
        changes.apply();
        render(message, false, message == R.string.account_connected);
    }

    private void showFailure(Exception exception) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (exception instanceof ApiException api) {
            int message = switch (api.getStatusCode()) {
                case CommonStatusCodes.DEVELOPER_ERROR -> R.string.account_setup_failed;
                case CommonStatusCodes.CANCELED -> R.string.account_sign_in_interrupted;
                default -> R.string.account_failed;
            };
            render(message, false, false);
            if (message == R.string.account_failed) {
                status.setText(getString(R.string.account_sign_in_error, api.getStatusCode()));
            }
        } else {
            render(R.string.account_failed, false, false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CONSENT_REQUEST) {
            return;
        }
        waitingForConsent = false;
        if (data == null) {
            render(R.string.account_sign_in_interrupted, false, false);
            return;
        }
        try {
            AuthorizationResult result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
            if (resultCode == RESULT_OK) {
                handleAuthorization(result, false);
            } else {
                render(R.string.account_sign_in_interrupted, false, false);
            }
        } catch (ApiException exception) {
            showFailure(exception);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(CONSENT_PENDING, waitingForConsent);
        state.putInt(SELECTED_TAB, screen.ordinal());
        state.putString(SELECTED_PLAYLIST, playlistId);
        state.putString(SELECTED_PLAYLIST_TITLE, playlistTitle);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        thumbnailWorkers.shutdownNow();
        super.onDestroy();
    }
}
