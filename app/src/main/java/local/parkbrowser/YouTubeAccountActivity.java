package local.parkbrowser;

import android.accounts.Account;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.LruCache;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONException;

import local.parkbrowser.YouTubeAccountApi.Channel;
import local.parkbrowser.YouTubeAccountApi.Item;
import local.parkbrowser.YouTubeAccountApi.Page;
import local.parkbrowser.YouTubeAccountApi.Section;

public final class YouTubeAccountActivity extends Activity {
    static final int HOME = 0;
    static final int SUBSCRIPTIONS = 1;
    static final int PROFILE = 2;
    static final String SECTION = "local.parkbrowser.SECTION";
    static final String CONNECT_NOW = "local.parkbrowser.CONNECT_NOW";
    private static final int CONSENT_REQUEST = 1;
    private static final String SAVED_SUBJECT = "subject";
    private static final String SAVED_EMAIL = "email";
    private static final String SAVED_CHANNEL = "channel";
    private static final String CONSENT_PENDING = "consent_pending";
    private static final String ACTIVE_SECTION = "active_section";
    private static final String YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly";
    private static final String OFFICIAL_YOUTUBE = "https://www.youtube.com/";
    private static final List<Scope> SCOPES = List.of(new Scope("openid"),
            new Scope("https://www.googleapis.com/auth/userinfo.email"), new Scope(YOUTUBE_READONLY));

    private record Shelf(Section section, String id, String title) {}

    private final ExecutorService data = Executors.newSingleThreadExecutor();
    private final LruCache<Shelf, Page> firstPages = new LruCache<>(8);
    private SharedPreferences preferences;
    private YouTubeHubView hub;
    private LinearLayout rows;
    private LinearLayout footer;
    private Channel channel;
    private Shelf collection;
    private Future<?> pageWork;
    private int activeSection;
    private int viewGeneration;
    private String status;
    private boolean working;
    private boolean connected;
    private boolean waitingForConsent;
    private boolean fromBrowser;
    private boolean resumed;
    private DenUpdateCheck updates;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("den_youtube_account", MODE_PRIVATE);
        ComponentName caller = getCallingActivity();
        fromBrowser = caller != null && getPackageName().equals(caller.getPackageName())
                && BrowserActivity.class.getName().equals(caller.getClassName());
        int initial = fromBrowser ? getIntent().getIntExtra(SECTION, HOME) : HOME;
        activeSection = Math.max(HOME, Math.min(PROFILE, state == null ? initial : state.getInt(ACTIVE_SECTION, initial)));
        waitingForConsent = state != null && state.getBoolean(CONSENT_PENDING);
        hub = new YouTubeHubView(this, this::selectSection, this::search, this::showMenu);
        updates = new DenUpdateCheck(this, () -> resumed, message -> {
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(hub.root);
        hub.root.requestFocus();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, () -> {
            if (collection != null) {
                selectSection(activeSection);
            } else {
                finish();
            }
        });
        if (waitingForConsent) {
            accountState(R.string.account_checking, true, false);
        } else if (savedEmail() != null) {
            authorize(false);
        } else if (fromBrowser && state == null && getIntent().getBooleanExtra(CONNECT_NOW, false)) {
            authorize(true);
        } else {
            accountState(R.string.account_intro, false, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updates.check(false);
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    private void selectSection(int section) {
        if (activeSection != section || collection != null) {
            activeSection = section;
            collection = null;
            showSection();
        }
    }

    private void accountState(int message, boolean pending, boolean allowed) {
        accountState(getString(message), pending, allowed);
    }

    private void accountState(String message, boolean pending, boolean allowed) {
        status = message;
        working = pending;
        connected = allowed;
        if (!connected) {
            firstPages.evictAll();
        }
        showSection();
    }

    private void showSection() {
        viewGeneration++;
        if (pageWork != null) {
            pageWork.cancel(true);
            pageWork = null;
        }
        hub.activate(activeSection);
        hub.reset();
        if (activeSection == PROFILE && collection == null) {
            showProfile();
            return;
        }
        Shelf shelf = collection;
        if (shelf == null) {
            shelf = activeSection == HOME ? new Shelf(Section.RECENT, null, getString(R.string.account_recent_from_subscriptions))
                    : new Shelf(Section.SUBSCRIPTIONS, null, getString(R.string.account_subscriptions));
        }
        hub.body.addView(hub.title(shelf.title()));
        if (collection != null) {
            hub.action(hub.body, getString(R.string.back), false, () -> selectSection(activeSection));
        } else if (activeSection == HOME) {
            if (connected) {
                hub.body.addView(hub.note(getString(R.string.hub_feed_hint)));
            }
            hub.action(hub.body, getString(R.string.account_recommendations_official), false,
                    () -> openOfficialYouTube(OFFICIAL_YOUTUBE));
        } else if (connected) {
            hub.body.addView(hub.note(getString(R.string.hub_channels_hint)));
        }
        if (!connected) {
            showAccountStatus();
            return;
        }
        rows = hub.column();
        footer = hub.column();
        footer.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        hub.body.addView(rows);
        hub.body.addView(footer);
        Page cached = firstPages.get(shelf);
        if (cached == null) {
            loadPage(shelf, "");
        } else {
            showPage(shelf, cached);
        }
    }

    private void showProfile() {
        hub.body.addView(hub.title(getString(R.string.hub_profile)));
        String email = savedEmail();
        if (email != null) {
            String name = channel == null ? preferences.getString(SAVED_CHANNEL, "") : channel.title();
            String handle = channel == null || channel.handle().isBlank() ? email : channel.handle();
            hub.profile(hub.body, name.isBlank() ? email : name, name.isBlank() ? "" : handle,
                    channel == null ? "" : channel.thumbnail());
        }
        showAccountStatus();
        if (connected) {
            if (channel != null && BrowserAddress.isChannelId(channel.id())) {
                hub.action(hub.body, getString(R.string.hub_my_channel), true,
                        () -> openInBrowser(BrowserAddress.YOUTUBE_HOME + "channel/" + channel.id()));
            }
            hub.action(hub.body, getString(R.string.account_playlists), false,
                    () -> openShelf(new Shelf(Section.PLAYLISTS, null, getString(R.string.account_playlists))));
            if (channel != null && !channel.likes().isBlank()) {
                hub.action(hub.body, getString(R.string.hub_liked), false,
                        () -> openShelf(new Shelf(Section.VIDEOS, channel.likes(), getString(R.string.hub_liked))));
            }
        }
        hub.action(hub.body, getString(R.string.hub_history_downloads), false,
                () -> openOfficialYouTube(OFFICIAL_YOUTUBE + "feed/you"));
        if (email != null && !working) {
            hub.action(hub.body, getString(R.string.account_forget), false, () -> {
                preferences.edit().clear().apply();
                channel = null;
                hub.clearImages();
                accountState(R.string.account_intro, false, false);
            });
        }
    }

    private void showAccountStatus() {
        hub.body.addView(hub.note(status));
        if (!working && !connected) {
            int action = savedEmail() == null ? R.string.account_connect : R.string.retry;
            hub.action(hub.body, getString(action), true, () -> authorize(true));
        }
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.account_open_youtube_site).setOnMenuItemClickListener(item -> {
            openInBrowser(BrowserAddress.YOUTUBE_HOME);
            return true;
        });
        menu.getMenu().add(R.string.updates_check).setOnMenuItemClickListener(item -> {
            updates.check(true);
            return true;
        });
        menu.show();
    }

    private void search(String input) {
        String destination = BrowserAddress.resolve(input);
        if (destination != null) {
            openInBrowser(destination);
        } else {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show();
        }
    }

    private void openShelf(Shelf shelf) {
        collection = shelf;
        showSection();
    }

    private void loadPage(Shelf shelf, String pageToken) {
        int request = ++viewGeneration;
        footer.removeAllViews();
        footer.addView(hub.note(getString(R.string.account_library_loading)));
        Identity.getAuthorizationClient(this).authorize(authorizationRequest()).addOnCompleteListener(task -> {
            if (!isActive(request)) {
                return;
            }
            if (!task.isSuccessful()) {
                pageFailed(shelf, pageToken);
                return;
            }
            AuthorizationResult result = task.getResult();
            String token = result.hasResolution() ? null : accessToken(result);
            if (token == null) {
                accountState(R.string.account_confirm_again, false, false);
                return;
            }
            pageWork = data.submit(() -> {
                try {
                    Page page = YouTubeAccountApi.readPage(token, shelf.section(), shelf.id(), pageToken);
                    runOnUiThread(() -> {
                        if (isActive(request)) {
                            if (pageToken.isEmpty()) {
                                firstPages.put(shelf, page);
                            }
                            showPage(shelf, page);
                        }
                    });
                } catch (IOException | JSONException exception) {
                    runOnUiThread(() -> {
                        if (isActive(request)) {
                            pageFailed(shelf, pageToken);
                        }
                    });
                }
            });
        });
    }

    private boolean isActive(int generation) {
        return !isFinishing() && !isDestroyed() && generation == viewGeneration;
    }

    private void showPage(Shelf shelf, Page page) {
        boolean channelList = shelf.section() == Section.SUBSCRIPTIONS;
        boolean videoList = !channelList && shelf.section() != Section.PLAYLISTS;
        for (Item item : page.items()) {
            hub.item(rows, item, videoList, channelList, () -> openItem(shelf.section(), item));
        }
        footer.removeAllViews();
        if (rows.getChildCount() == 0) {
            footer.addView(hub.note(getString(R.string.account_empty_list)));
        }
        if (!page.next().isEmpty()) {
            int label = shelf.section() == Section.RECENT ? R.string.account_more_channels : R.string.account_next_page;
            hub.action(footer, getString(label), false, () -> loadPage(shelf, page.next()));
        }
    }

    private void openItem(Section section, Item item) {
        switch (section) {
            case PLAYLISTS -> openShelf(new Shelf(Section.VIDEOS, item.id(), item.title()));
            case SUBSCRIPTIONS -> {
                if (BrowserAddress.isChannelId(item.id())) {
                    openShelf(new Shelf(Section.CHANNEL_VIDEOS, item.id(), item.title()));
                }
            }
            case RECENT, CHANNEL_VIDEOS, VIDEOS -> {
                if (BrowserAddress.isVideoId(item.id())) {
                    openInBrowser(BrowserAddress.YOUTUBE_HOME + "watch?v=" + item.id());
                }
            }
        }
    }

    private void pageFailed(Shelf shelf, String pageToken) {
        footer.removeAllViews();
        footer.addView(hub.note(getString(R.string.account_library_failed)));
        hub.action(footer, getString(R.string.retry), false, () -> loadPage(shelf, pageToken));
    }

    private void openInBrowser(String url) {
        if (fromBrowser) {
            setResult(RESULT_OK, new Intent().setData(Uri.parse(url)));
            finish();
        } else {
            startActivity(new Intent(this, BrowserActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
                    .putExtra(BrowserActivity.FROM_HUB, true));
        }
    }

    private void openOfficialYouTube(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube"));
        } catch (ActivityNotFoundException exception) {
            Intent app = getPackageManager().getLaunchIntentForPackage("com.google.android.youtube");
            if (app != null) {
                startActivity(app);
            } else {
                Toast.makeText(this, R.string.account_youtube_app_unavailable, Toast.LENGTH_LONG).show();
            }
        }
    }

    private String savedEmail() {
        return preferences.getString(SAVED_EMAIL, null);
    }

    private AuthorizationRequest authorizationRequest() {
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder().setRequestedScopes(SCOPES);
        String email = savedEmail();
        if (email != null) {
            builder.setAccount(new Account(email, "com.google"));
        }
        return builder.build();
    }

    private void authorize(boolean interactive) {
        accountState(R.string.account_checking, true, false);
        Identity.getAuthorizationClient(this).authorize(authorizationRequest()).addOnCompleteListener(task -> {
            if (!isFinishing() && !isDestroyed()) {
                if (task.isSuccessful()) {
                    acceptAuthorization(task.getResult(), interactive);
                } else {
                    showFailure(task.getException());
                }
            }
        });
    }

    private void acceptAuthorization(AuthorizationResult result, boolean interactive) {
        if (result.hasResolution()) {
            if (!interactive) {
                accountState(R.string.account_confirm_again, false, false);
                return;
            }
            try {
                waitingForConsent = true;
                startIntentSenderForResult(result.getPendingIntent().getIntentSender(), CONSENT_REQUEST, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException exception) {
                waitingForConsent = false;
                showFailure(exception);
            }
            return;
        }
        String token = accessToken(result);
        if (token == null) {
            accountState(R.string.account_confirm_again, false, false);
            return;
        }
        data.execute(() -> {
            try {
                YouTubeAccountApi.Identity identity = YouTubeAccountApi.loadIdentity(token);
                try {
                    Channel ownChannel = YouTubeAccountApi.loadChannel(token);
                    runOnUiThread(() -> remember(identity, ownChannel, R.string.account_connected));
                } catch (YouTubeAccountApi.ServiceDisabledException exception) {
                    runOnUiThread(() -> remember(identity, null, R.string.account_api_disabled));
                } catch (IOException | JSONException exception) {
                    runOnUiThread(() -> remember(identity, null, R.string.account_library_failed));
                }
            } catch (IOException | JSONException exception) {
                runOnUiThread(() -> showFailure(exception));
            }
        });
    }

    private String accessToken(AuthorizationResult result) {
        return result.getGrantedScopes().contains(YOUTUBE_READONLY) ? result.getAccessToken() : null;
    }

    private void remember(YouTubeAccountApi.Identity identity, Channel ownChannel, int message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        String previousSubject = preferences.getString(SAVED_SUBJECT, null);
        if (previousSubject != null && !previousSubject.equals(identity.subject())) {
            accountState(R.string.account_mismatch, false, false);
            return;
        }
        SharedPreferences.Editor changes = preferences.edit().putString(SAVED_SUBJECT, identity.subject())
                .putString(SAVED_EMAIL, identity.email());
        if (ownChannel != null) {
            changes.putString(SAVED_CHANNEL, ownChannel.title());
        }
        changes.apply();
        channel = ownChannel;
        accountState(message, false, message == R.string.account_connected);
    }

    private void showFailure(Exception exception) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (exception instanceof ApiException api) {
            switch (api.getStatusCode()) {
                case CommonStatusCodes.DEVELOPER_ERROR -> accountState(R.string.account_setup_failed, false, false);
                case CommonStatusCodes.CANCELED -> accountState(R.string.account_sign_in_interrupted, false, false);
                default -> accountState(getString(R.string.account_sign_in_error, api.getStatusCode()), false, false);
            }
        } else {
            accountState(R.string.account_failed, false, false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultIntent) {
        super.onActivityResult(requestCode, resultCode, resultIntent);
        if (requestCode != CONSENT_REQUEST) {
            return;
        }
        waitingForConsent = false;
        if (resultIntent == null) {
            accountState(R.string.account_sign_in_interrupted, false, false);
            return;
        }
        try {
            AuthorizationResult result = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(resultIntent);
            if (resultCode == RESULT_OK) {
                acceptAuthorization(result, false);
            } else {
                accountState(R.string.account_sign_in_interrupted, false, false);
            }
        } catch (ApiException exception) {
            showFailure(exception);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(CONSENT_PENDING, waitingForConsent);
        state.putInt(ACTIVE_SECTION, activeSection);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        data.shutdownNow();
        if (hub != null) {
            hub.close();
        }
        super.onDestroy();
    }
}
