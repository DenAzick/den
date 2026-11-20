package local.parkbrowser;

import android.accounts.Account;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
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
import java.util.List;

import org.json.JSONException;

import local.parkbrowser.YouTubeAccountApi.Section;

public final class YouTubeAccountActivity extends Activity {
    private static final int CONSENT_REQUEST = 1;
    private static final String PREFERENCES = "den_youtube_account";
    private static final String SAVED_SUBJECT = "subject";
    private static final String SAVED_EMAIL = "email";
    private static final String SAVED_CHANNEL = "channel";
    private static final String CONSENT_PENDING = "consent_pending";
    private static final String YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly";
    private static final List<Scope> SCOPES = List.of(new Scope("openid"),
            new Scope("https://www.googleapis.com/auth/userinfo.email"), new Scope(YOUTUBE_READONLY));

    private SharedPreferences preferences;
    private TextView account;
    private TextView status;
    private Button connect;
    private Button forget;
    private LinearLayout library;
    private LinearLayout libraryResults;
    private TextView libraryTitle;
    private LinearLayout libraryItems;
    private LinearLayout libraryFooter;
    private int libraryRequest;
    private boolean waitingForConsent;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Device-local state. Backup rules include only the browser's toolbar preferences.
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        waitingForConsent = state != null && state.getBoolean(CONSENT_PENDING);
        createUi();
        if (waitingForConsent) {
            render(R.string.account_checking, true, false);
        } else if (savedEmail() != null) {
            authorize(false);
        } else {
            render(R.string.account_intro, false, false);
        }
    }

    private void createUi() {
        ScrollView root = new ScrollView(this);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            var insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsets.CONSUMED;
        });
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        root.addView(panel);
        TextView title = new TextView(this);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Headline);
        title.setText(R.string.youtube_account);
        panel.addView(title);
        account = new TextView(this);
        account.setTextIsSelectable(true);
        panel.addView(account);
        status = new TextView(this);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        connect = phoneButton();
        connect.setOnClickListener(view -> authorize(true));
        panel.addView(connect);
        library = new LinearLayout(this);
        library.setOrientation(LinearLayout.VERTICAL);
        Button subscriptions = phoneButton();
        subscriptions.setText(R.string.account_subscriptions);
        subscriptions.setOnClickListener(view -> showSubscriptions());
        library.addView(subscriptions);
        Button playlists = phoneButton();
        playlists.setText(R.string.account_playlists);
        playlists.setOnClickListener(view -> loadLibrary(Section.PLAYLISTS, null, getString(R.string.account_playlists), ""));
        library.addView(playlists);
        LinearLayout shortcuts = new LinearLayout(this);
        Button recent = phoneButton();
        recent.setText(R.string.account_new_uploads);
        recent.setOnClickListener(view -> loadLibrary(Section.RECENT, null,
                getString(R.string.account_recent_from_subscriptions), ""));
        shortcuts.addView(recent, new LinearLayout.LayoutParams(0, -2, 1));
        Button recommendations = phoneButton();
        recommendations.setText(R.string.account_recommendations_official);
        recommendations.setOnClickListener(view -> openOfficialYouTube());
        shortcuts.addView(recommendations, new LinearLayout.LayoutParams(0, -2, 1));
        library.addView(shortcuts);
        panel.addView(library);
        libraryResults = new LinearLayout(this);
        libraryResults.setOrientation(LinearLayout.VERTICAL);
        libraryTitle = new TextView(this);
        libraryTitle.setTextAppearance(android.R.style.TextAppearance_Material_Title);
        libraryTitle.setAccessibilityHeading(true);
        libraryResults.addView(libraryTitle);
        libraryItems = new LinearLayout(this);
        libraryItems.setOrientation(LinearLayout.VERTICAL);
        libraryResults.addView(libraryItems);
        libraryFooter = new LinearLayout(this);
        libraryFooter.setOrientation(LinearLayout.VERTICAL);
        libraryFooter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        libraryResults.addView(libraryFooter);
        forget = phoneButton();
        forget.setText(R.string.account_forget);
        forget.setOnClickListener(view -> {
            preferences.edit().clear().apply();
            render(R.string.account_intro, false, false);
        });
        panel.addView(forget);
        panel.addView(libraryResults);
        setContentView(root);
    }

    private Button phoneButton() {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.phone_button);
        return button;
    }

    private String savedEmail() {
        return preferences.getString(SAVED_EMAIL, null);
    }

    private void render(int message, boolean working, boolean connected) {
        String email = savedEmail();
        String channel = preferences.getString(SAVED_CHANNEL, "");
        if (email != null) {
            String description = getString(R.string.account_saved, email);
            if (!channel.isEmpty()) {
                description += "\n" + getString(R.string.account_channel, channel);
            }
            account.setText(description);
        } else {
            account.setText("");
        }
        account.setVisibility(email == null ? View.GONE : View.VISIBLE);
        status.setText(message);
        connect.setText(email == null ? R.string.account_connect : R.string.retry);
        connect.setVisibility(connected || working ? View.GONE : View.VISIBLE);
        forget.setVisibility(email == null ? View.GONE : View.VISIBLE);
        forget.setEnabled(!working);
        library.setVisibility(connected && !working ? View.VISIBLE : View.GONE);
        libraryResults.setVisibility(library.getVisibility());
        if (!connected) {
            libraryRequest++;
            libraryItems.removeAllViews();
            libraryFooter.removeAllViews();
        }
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

    private void showSubscriptions() {
        loadLibrary(Section.SUBSCRIPTIONS, null, getString(R.string.account_subscriptions), "");
    }

    private void loadLibrary(Section section, String collectionId, String title, String pageToken) {
        int request = ++libraryRequest;
        if (pageToken.isEmpty()) {
            libraryTitle.setText(title);
            libraryItems.removeAllViews();
        }
        libraryFooter.removeAllViews();
        addLibraryStatus(R.string.account_library_loading);
        Identity.getAuthorizationClient(this).authorize(authorizationRequest()).addOnCompleteListener(task -> {
            if (!isLibraryRequestActive(request)) {
                return;
            }
            if (!task.isSuccessful()) {
                libraryLoadFailed(section, collectionId, title, pageToken);
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
                    YouTubeAccountApi.Page page = YouTubeAccountApi.readPage(token, section, collectionId, pageToken);
                    runOnUiThread(() -> {
                        if (isLibraryRequestActive(request)) {
                            showLibrary(section, collectionId, title, page);
                        }
                    });
                } catch (IOException | JSONException exception) {
                    runOnUiThread(() -> {
                        if (isLibraryRequestActive(request)) {
                            libraryLoadFailed(section, collectionId, title, pageToken);
                        }
                    });
                }
            }, "den-youtube-library").start();
        });
    }

    private boolean isLibraryRequestActive(int request) {
        return request == libraryRequest && !isFinishing() && !isDestroyed();
    }

    private void libraryLoadFailed(Section section, String collectionId, String title, String pageToken) {
        libraryFooter.removeAllViews();
        addLibraryStatus(R.string.account_library_failed);
        Button retry = phoneButton();
        retry.setText(R.string.retry);
        retry.setOnClickListener(view -> loadLibrary(section, collectionId, title, pageToken));
        libraryFooter.addView(retry);
    }

    private void addLibraryStatus(int message) {
        TextView note = new TextView(this);
        note.setText(message);
        libraryFooter.addView(note);
    }

    private void showLibrary(Section section, String collectionId, String title, YouTubeAccountApi.Page page) {
        libraryFooter.removeAllViews();
        int rowPadding = Math.round(12 * getResources().getDisplayMetrics().density);
        for (YouTubeAccountApi.Item item : page.items()) {
            Button row = phoneButton();
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(rowPadding, row.getPaddingTop(), rowPadding, row.getPaddingBottom());
            row.setText(item.channel().isBlank() ? item.title() : item.title() + "\n" + item.channel());
            row.setOnClickListener(view -> openLibraryItem(section, item));
            libraryItems.addView(row);
        }
        if (libraryItems.getChildCount() == 0) {
            addLibraryStatus(R.string.account_empty_list);
        }
        if (!page.next().isEmpty()) {
            Button more = phoneButton();
            more.setText(section == Section.RECENT ? R.string.account_more_channels : R.string.account_next_page);
            more.setOnClickListener(view -> loadLibrary(section, collectionId, title, page.next()));
            libraryFooter.addView(more);
        }
    }

    private void openLibraryItem(Section section, YouTubeAccountApi.Item item) {
        String id = item.id();
        switch (section) {
            case PLAYLISTS -> loadLibrary(Section.VIDEOS, id, item.title(), "");
            case SUBSCRIPTIONS -> {
                if (BrowserAddress.isChannelId(id)) {
                    loadLibrary(Section.CHANNEL_VIDEOS, id, item.title(), "");
                }
            }
            case RECENT, CHANNEL_VIDEOS, VIDEOS -> {
                if (BrowserAddress.isVideoId(id)) {
                    openInBrowser(BrowserAddress.YOUTUBE_HOME + "watch?v=" + id);
                }
            }
        }
    }

    private void openOfficialYouTube() {
        Intent youtube = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/"))
                .setPackage("com.google.android.youtube");
        try {
            startActivity(youtube);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.account_youtube_app_unavailable, Toast.LENGTH_LONG).show();
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
        if (message == R.string.account_connected) {
            showSubscriptions();
        }
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
        super.onSaveInstanceState(state);
    }
}
