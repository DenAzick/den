package local.parkbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Map;

import org.json.JSONException;

public class BrowserActivity extends Activity {
    private static final int OPEN_YOUTUBE_ACCOUNT = 2;
    private static final String ACCOUNT_SCREEN_OPEN = "account_screen_open";
    private static final String HIDE_PARKING_TOOLBAR = "hide_parking_toolbar";
    private static final String HIDE_PARKING_LABEL = "hide_parking_label";
    private static final String PHONE_DEMO_MODE = "phone_demo_mode";
    private static final String SELECTED_VIDEO = "selected_video";
    private static final String LAST_UPDATE_CHECK = "last_update_check";
    private static final long UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1_000L;
    private static final int SURFACE = Color.rgb(17, 28, 29);
    private static final int CONTROL = Color.rgb(32, 52, 53);
    private static final int TEXT = Color.rgb(241, 250, 247);
    private static final int ACCENT = Color.rgb(92, 219, 190);
    private static final int MUTED = Color.rgb(181, 205, 201);

    private LinearLayout browserUi;
    private LinearLayout toolbar;
    private FrameLayout page;
    private FrameLayout fullscreen;
    private LinearLayout errorPanel;
    private TextView errorText;
    private TextView notice;
    private EditText address;
    private Button back;
    private Button parkingControls;
    private ProgressBar progress;
    private WebView webView;
    private SharedPreferences settings;
    private SharedPreferences deviceState;
    private final SharedPreferences.OnSharedPreferenceChangeListener videoSelection = (preferences, key) -> {
        if (SELECTED_VIDEO.equals(key) && this.resumed) {
            playSelectedVideo();
        }
    };
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private boolean resumed;
    private boolean checkingForUpdates;
    private boolean updateRequestedManually;
    private boolean accountScreenOpen;
    private String carVideoId;
    private String currentUrl = BrowserAddress.YOUTUBE_HOME;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = getSharedPreferences("parkbrowser_settings", MODE_PRIVATE);
        deviceState = getSharedPreferences("den_device_state", MODE_PRIVATE);
        accountScreenOpen = state != null && state.getBoolean(ACCOUNT_SCREEN_OPEN);
        if (!settings.contains(HIDE_PARKING_TOOLBAR)) {
            SharedPreferences previous = getSharedPreferences("ParkedBrowserActivity", MODE_PRIVATE);
            if (previous.contains(HIDE_PARKING_TOOLBAR)) {
                settings.edit().putBoolean(HIDE_PARKING_TOOLBAR,
                        previous.getBoolean(HIDE_PARKING_TOOLBAR, false)).apply();
            }
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (state != null && canOpenPage(state.getString("url"))) {
            currentUrl = state.getString("url");
            String previousVideo = state.getString(SELECTED_VIDEO);
            if (isCarActivity() && BrowserAddress.isVideoId(previousVideo)) {
                carVideoId = previousVideo;
            }
        }
        createUi();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
            if (fullscreenCallback != null) {
                exitFullscreen();
            } else if (carVideoId != null) {
                carVideoId = null;
                applyToolbarSettings();
                navigate(currentUrl);
            } else if (!goBack()) {
                finish();
            }
        });
        if (state == null) {
            handleSharedVideo(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedVideo(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (isCarActivity()) {
            return;
        }
        applyToolbarSettings();
        if (webView == null) {
            createWebView();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (isCarActivity()) {
            deviceState.registerOnSharedPreferenceChangeListener(videoSelection);
            if (!playSelectedVideo()) {
                applyToolbarSettings();
                if (webView == null) {
                    createWebView();
                }
            }
        }
        if (webView != null) {
            webView.onResume();
        }
        if (!isCarActivity() && !phoneDemoMode()) {
            checkForUpdates(false);
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (isCarActivity()) {
            deviceState.unregisterOnSharedPreferenceChangeListener(videoSelection);
        }
        exitFullscreen();
        if (webView != null) {
            webView.evaluateJavascript("document.querySelectorAll('audio, video').forEach(el => el.pause());", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (isCarActivity()) {
            // The car host stops parked activities when it leaves the parked state.
            destroyWebView();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyWebView();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("url", currentUrl);
        state.putString(SELECTED_VIDEO, carVideoId);
        state.putBoolean(ACCOUNT_SCREEN_OPEN, accountScreenOpen);
        super.onSaveInstanceState(state);
    }

    protected boolean isCarActivity() {
        return false;
    }

    private void createUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsets.CONSUMED;
        });

        browserUi = new LinearLayout(this);
        browserUi.setOrientation(LinearLayout.VERTICAL);
        root.addView(browserUi, new FrameLayout.LayoutParams(-1, -1));

        toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setFocusableInTouchMode(true);
        browserUi.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        back = button("‹", getString(R.string.back));
        back.setEnabled(false);
        back.setOnClickListener(view -> goBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(16);
        address.setTextColor(TEXT);
        address.setHintTextColor(MUTED);
        address.setHint(isCarActivity() ? R.string.parking_address_hint : R.string.address_hint);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setBackground(rounded(CONTROL, 10));
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setSelectAllOnFocus(true);
        address.setOnEditorActionListener((text, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitAddress();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        addressParams.setMargins(dp(4), 0, dp(4), 0);
        toolbar.addView(address, addressParams);

        Button go = button("→", getString(R.string.go));
        go.setOnClickListener(view -> submitAddress());
        toolbar.addView(go, new LinearLayout.LayoutParams(dp(48), dp(48)));

        Button home = button(getString(R.string.home), null);
        home.setTextSize(14);
        home.setTextColor(ACCENT);
        home.setPadding(dp(8), 0, dp(8), 0);
        home.setOnClickListener(view -> navigate(BrowserAddress.YOUTUBE_HOME));
        toolbar.addView(home, new LinearLayout.LayoutParams(-2, dp(48)));

        if (!isCarActivity()) {
            Button menu = button("⋮", getString(R.string.browser_menu));
            menu.setOnClickListener(view -> showBrowserMenu(menu));
            toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        addParkingControls();

        notice = new TextView(this);
        notice.setTextColor(MUTED);
        notice.setTextSize(13);
        notice.setPadding(dp(16), 0, dp(16), dp(5));
        browserUi.addView(notice, new LinearLayout.LayoutParams(-1, -2));
        resetNotice();

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);
        browserUi.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        page = new FrameLayout(this);
        browserUi.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        createErrorPanel();

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);
        root.addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        toolbar.requestFocus();
    }

    private void createErrorPanel() {
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setPadding(dp(24), dp(12), dp(24), dp(12));
        errorPanel.setBackgroundColor(SURFACE);
        errorPanel.setVisibility(View.GONE);
        page.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));

        errorText = new TextView(this);
        errorText.setTextColor(TEXT);
        errorText.setTextSize(17);
        errorText.setGravity(Gravity.CENTER);
        errorPanel.addView(errorText, new LinearLayout.LayoutParams(-1, -2));

        Button retry = button(getString(R.string.retry), null);
        retry.setTextSize(16);
        retry.setTextColor(ACCENT);
        retry.setPadding(dp(12), 0, dp(12), 0);
        retry.setOnClickListener(view -> navigate(currentUrl));
        errorPanel.addView(retry, new LinearLayout.LayoutParams(-2, dp(48)));
    }

    private void addParkingControls() {
        parkingControls = button("", null);
        parkingControls.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        parkingControls.setPadding(dp(16), 0, dp(16), 0);
        parkingControls.setTextColor(ACCENT);
        parkingControls.setTextSize(14);
        parkingControls.setOnClickListener(view -> setParkingToolbarVisible(toolbar.getVisibility() != View.VISIBLE));
        parkingControls.setOnLongClickListener(view -> {
            showBrowserMenu(parkingControls);
            return true;
        });
        browserUi.addView(parkingControls, new LinearLayout.LayoutParams(-1, dp(48)));
        parkingControls.setVisibility(View.GONE);
    }

    private void setParkingToolbarVisible(boolean visible) {
        if (!visible && address.hasFocus()) {
            dismissAddressKeyboard();
            parkingControls.requestFocus();
        }
        toolbar.setVisibility(visible ? View.VISIBLE : View.GONE);
        boolean showParkingLabel = isCarActivity() && !settings.getBoolean(HIDE_PARKING_LABEL, false);
        int label = showParkingLabel
                ? (visible ? R.string.parking_hide_toolbar : R.string.parking_show_toolbar)
                : (visible ? R.string.toolbar_hide : R.string.toolbar_show);
        parkingControls.setText(label);
        int hint = isCarActivity() ? R.string.parking_menu_hint : R.string.demo_menu_hint;
        parkingControls.setContentDescription(getString(label) + ". " + getString(hint));
    }

    private boolean hideToolbarOnStart() {
        return settings.getBoolean(HIDE_PARKING_TOOLBAR, false);
    }

    private boolean phoneDemoMode() {
        return !isCarActivity() && settings.getBoolean(PHONE_DEMO_MODE, false);
    }

    private void applyToolbarSettings() {
        boolean embeddedVideo = carVideoId != null;
        parkingControls.setVisibility(!embeddedVideo && (isCarActivity() || phoneDemoMode()) ? View.VISIBLE : View.GONE);
        setParkingToolbarVisible(!embeddedVideo && (isCarActivity() ? !hideToolbarOnStart() : !phoneDemoMode()));
        progress.setVisibility(embeddedVideo ? View.GONE : View.INVISIBLE);
    }

    private void showBrowserMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (isCarActivity()) {
            menu.getMenu().add(R.string.parking_hide_on_start).setCheckable(true)
                    .setChecked(hideToolbarOnStart()).setOnMenuItemClickListener(item -> {
                        boolean hide = !item.isChecked();
                        settings.edit().putBoolean(HIDE_PARKING_TOOLBAR, hide).apply();
                        setParkingToolbarVisible(!hide);
                        return true;
                    });
            menu.getMenu().add(R.string.parking_hide_label).setCheckable(true)
                    .setChecked(settings.getBoolean(HIDE_PARKING_LABEL, false)).setOnMenuItemClickListener(item -> {
                        settings.edit().putBoolean(HIDE_PARKING_LABEL, !item.isChecked()).apply();
                        setParkingToolbarVisible(toolbar.getVisibility() == View.VISIBLE);
                        return true;
                    });
        } else {
            String video = BrowserAddress.sharedVideoId(currentUrl);
            if (video != null) {
                menu.getMenu().add(R.string.send_video).setOnMenuItemClickListener(item -> {
                    queueVideo(video);
                    return true;
                });
            }
            menu.getMenu().add(R.string.demo_mode).setCheckable(true).setChecked(phoneDemoMode())
                    .setOnMenuItemClickListener(item -> {
                        settings.edit().putBoolean(PHONE_DEMO_MODE, !item.isChecked()).apply();
                        applyToolbarSettings();
                        return true;
                    });
            if (DhuControlReceiver.isConnected()) {
                menu.getMenu().add(R.string.dhu_unrestricted).setCheckable(true)
                        .setChecked(DhuControlReceiver.isUnrestricted()).setOnMenuItemClickListener(item -> {
                            boolean requested = DhuControlReceiver.toggleRestrictions();
                            showNotice(requested ? R.string.dhu_command_queued : R.string.dhu_disconnected);
                            return true;
                        });
            }
            menu.getMenu().add(R.string.updates_check).setOnMenuItemClickListener(item -> {
                checkForUpdates(true);
                return true;
            });
        }
        menu.getMenu().add(isCarActivity() ? R.string.youtube_account_phone : R.string.youtube_account)
                .setOnMenuItemClickListener(item -> {
                    openYouTubeAccount();
                    return true;
                });
        menu.show();
    }

    private void openYouTubeAccount() {
        if (isCarActivity()) {
            showNotice(R.string.account_on_phone);
            return;
        }
        if (!accountScreenOpen) {
            accountScreenOpen = true;
            startActivityForResult(new Intent(this, YouTubeAccountActivity.class), OPEN_YOUTUBE_ACCOUNT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != OPEN_YOUTUBE_ACCOUNT) {
            return;
        }
        accountScreenOpen = false;
        if (!isCarActivity() && resultCode == RESULT_OK && data != null) {
            String destination = data.getDataString();
            if (BrowserAddress.isYouTube(destination)) {
                navigate(destination);
                return;
            }
        }
    }

    private void openExternalPage(Uri url, int failureMessage) {
        Intent account = new Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE);
        Intent genericWebPage = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo browser = getPackageManager().resolveActivity(genericWebPage, PackageManager.MATCH_DEFAULT_ONLY);
        if (browser != null && browser.activityInfo != null && !"android".equals(browser.activityInfo.packageName)) {
            account.setPackage(browser.activityInfo.packageName);
        }
        Bundle customTab = new Bundle();
        customTab.putBinder("android.support.customtabs.extra.SESSION", null);
        account.putExtras(customTab);
        try {
            startActivity(account);
        } catch (ActivityNotFoundException exception) {
            showNotice(failureMessage);
        }
    }

    private void handleSharedVideo(Intent intent) {
        if (isCarActivity() || intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        String video = BrowserAddress.sharedVideoId(intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
        if (video == null) {
            Toast.makeText(this, R.string.video_link_needed, Toast.LENGTH_SHORT).show();
            return;
        }
        queueVideo(video);
    }

    private void queueVideo(String video) {
        if (webView != null) {
            webView.evaluateJavascript("document.querySelectorAll('audio, video').forEach(el => el.pause());", null);
        }
        deviceState.edit().putString(SELECTED_VIDEO, video).apply();
        Toast.makeText(this, R.string.video_queued, Toast.LENGTH_SHORT).show();
    }

    private boolean playSelectedVideo() {
        String video = deviceState.getString(SELECTED_VIDEO, null);
        if (!BrowserAddress.isVideoId(video)) {
            return false;
        }
        deviceState.edit().remove(SELECTED_VIDEO).apply();
        exitFullscreen();
        carVideoId = video;
        currentUrl = BrowserAddress.YOUTUBE_HOME + "watch?v=" + video;
        applyToolbarSettings();
        if (webView == null) {
            createWebView();
        } else {
            loadPage();
        }
        return true;
    }

    private void checkForUpdates(boolean manual) {
        if (checkingForUpdates) {
            updateRequestedManually |= manual;
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - deviceState.getLong(LAST_UPDATE_CHECK, 0);
        if (!manual && elapsed >= 0 && elapsed < UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        checkingForUpdates = true;
        updateRequestedManually = manual;
        deviceState.edit().putLong(LAST_UPDATE_CHECK, now).apply();
        if (manual) {
            showNotice(R.string.updates_checking);
        }
        new Thread(() -> {
            try {
                String installed = getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)).versionName;
                String latest = GitHubUpdates.latestApkVersion(installed);
                runOnUiThread(() -> showUpdateResult(installed, latest, false));
            } catch (IOException | JSONException | PackageManager.NameNotFoundException exception) {
                runOnUiThread(() -> showUpdateResult(null, null, true));
            }
        }, "den-updates").start();
    }

    private void showUpdateResult(String installed, String latest, boolean failed) {
        boolean manual = updateRequestedManually;
        checkingForUpdates = false;
        updateRequestedManually = false;
        if (!resumed || isFinishing() || isDestroyed()) {
            return;
        }
        if (latest != null && GitHubUpdates.isNewer(latest, installed)) {
            resetNotice();
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.updates_available, latest))
                    .setMessage(R.string.updates_install_prompt)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.updates_open, (dialog, button) -> openExternalPage(
                            Uri.parse(GitHubUpdates.RELEASES_PAGE), R.string.updates_browser_error))
                    .show();
        } else if (manual) {
            String message = failed ? getString(R.string.updates_failed) : latest == null
                    ? getString(R.string.updates_unpublished) : getString(R.string.updates_current, installed);
            notice.setText(message);
            notice.setVisibility(View.VISIBLE);
        }
    }

    private Button button(String text, String description) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        if (!isCarActivity()) {
            button.setBackgroundResource(R.drawable.phone_button);
        }
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(24);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        if (description != null) {
            button.setContentDescription(description);
        }
        return button;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void createWebView() {
        try {
            webView = new WebView(this);
        } catch (RuntimeException exception) {
            showError(R.string.webview_error);
            return;
        }
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebViewClient(new BrowserClient());
        webView.setWebChromeClient(new BrowserChrome());
        page.addView(webView, 0, new FrameLayout.LayoutParams(-1, -1));
        loadPage();
    }

    private void destroyWebView() {
        exitFullscreen();
        if (webView != null) {
            WebView discarded = webView;
            webView = null;
            back.setEnabled(false);
            page.removeView(discarded);
            discarded.destroy();
        }
    }

    private void submitAddress() {
        String destination = BrowserAddress.resolve(address.getText().toString());
        if (destination == null) {
            showNotice(R.string.invalid_address);
            return;
        }
        navigate(destination);
    }

    private void navigate(String destination) {
        if (BrowserAddress.isGoogleSignIn(destination)) {
            dismissAddressKeyboard();
            showNotice(R.string.account_on_phone);
            return;
        }
        if (!canOpenPage(destination)) {
            showNotice(R.string.parking_youtube_only);
            return;
        }
        currentUrl = destination;
        dismissAddressKeyboard();
        if (webView == null) {
            createWebView();
        } else {
            loadPage();
        }
    }

    private void dismissAddressKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
    }

    private boolean canOpenPage(String url) {
        return isCarActivity() ? BrowserAddress.isYouTube(url) : BrowserAddress.isHttps(url);
    }

    private void loadPage() {
        errorPanel.setVisibility(View.GONE);
        resetNotice();
        address.setText(currentUrl);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(carVideoId == null);
        if (carVideoId == null) {
            webView.loadUrl(currentUrl);
        } else {
            webView.loadUrl("https://www.youtube.com/embed/" + carVideoId + "?autoplay=1&playsinline=1",
                    Map.of("Referer", "https://" + getPackageName() + "/"));
        }
    }

    private boolean goBack() {
        if (webView == null || !webView.canGoBack()) {
            return false;
        }
        errorPanel.setVisibility(View.GONE);
        resetNotice();
        webView.goBack();
        return true;
    }

    private void resetNotice() {
        notice.setVisibility(View.GONE);
    }

    private void showNotice(int message) {
        notice.setText(message);
        notice.setVisibility(View.VISIBLE);
    }

    private void showError(int message) {
        progress.setVisibility(View.INVISIBLE);
        errorText.setText(message);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void enterFullscreen(View video, WebChromeClient.CustomViewCallback callback) {
        if (!resumed || fullscreenCallback != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullscreenCallback = callback;
        fullscreen.addView(video, new FrameLayout.LayoutParams(-1, -1));
        Button close = button("×", getString(R.string.exit_fullscreen));
        close.setBackground(rounded(0xCC203435, 10));
        close.setOnClickListener(view -> exitFullscreen());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
        closeParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        fullscreen.addView(close, closeParams);
        browserUi.setVisibility(View.INVISIBLE);
        fullscreen.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isCarActivity()) {
            WindowInsetsController controller = getWindow().getInsetsController();
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private void exitFullscreen() {
        if (fullscreenCallback == null) {
            return;
        }
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        fullscreen.removeAllViews();
        fullscreen.setVisibility(View.GONE);
        browserUi.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isCarActivity()) {
            getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
        }
        callback.onCustomViewHidden();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BrowserClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (view != webView) {
                return;
            }
            if (BrowserAddress.isGoogleSignIn(url)) {
                view.stopLoading();
                showNotice(R.string.account_on_phone);
                return;
            }
            if (!canOpenPage(url)) {
                view.stopLoading();
                showNotice(isCarActivity() ? R.string.parking_youtube_only : R.string.blocked_link);
                return;
            }
            if (carVideoId != null && !("/embed/" + carVideoId).equals(Uri.parse(url).getPath())) {
                carVideoId = null;
                applyToolbarSettings();
                view.getSettings().setMediaPlaybackRequiresUserGesture(true);
            }
            if (carVideoId == null) {
                currentUrl = url;
            }
            errorPanel.setVisibility(View.GONE);
            resetNotice();
            if (!address.hasFocus()) {
                address.setText(currentUrl);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) {
                return false;
            }
            String url = request.getUrl().toString();
            if (!BrowserAddress.isHttps(url)) {
                showNotice(R.string.blocked_link);
                return true;
            }
            if (BrowserAddress.isGoogleSignIn(url)) {
                showNotice(R.string.account_on_phone);
                return true;
            }
            if (!canOpenPage(url)) {
                showNotice(R.string.parking_youtube_only);
                return true;
            }
            return false;
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (view == webView && carVideoId == null && canOpenPage(url)) {
                currentUrl = url;
                if (!address.hasFocus()) {
                    address.setText(url);
                }
                back.setEnabled(view.canGoBack());
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (view == webView && request.isForMainFrame()) {
                showError(error.getErrorCode() == ERROR_FAILED_SSL_HANDSHAKE
                        ? R.string.certificate_error : R.string.load_error);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == webView) {
                destroyWebView();
                showError(R.string.renderer_error);
            }
            return true;
        }
    }

    private final class BrowserChrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int percent) {
            if (view == webView && carVideoId == null) {
                progress.setProgress(percent);
                progress.setVisibility(percent < 100 && errorPanel.getVisibility() != View.VISIBLE
                        ? View.VISIBLE : View.INVISIBLE);
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            enterFullscreen(view, callback);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }
    }
}
