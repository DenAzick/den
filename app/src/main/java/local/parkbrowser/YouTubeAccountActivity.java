package local.parkbrowser;

import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    static final String CONNECT_NOW = "local.parkbrowser.CONNECT_NOW";
    private static final int CONSENT_REQUEST = 1;
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
    private boolean waitingForConsent;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Device-local state. Backup rules include only the browser's toolbar preferences.
        preferences = getSharedPreferences("den_youtube_account", MODE_PRIVATE);
        waitingForConsent = state != null && state.getBoolean(CONSENT_PENDING);
        createUi();
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
        subscriptions.setOnClickListener(view -> loadLibrary(Section.SUBSCRIPTIONS, null, ""));
        library.addView(subscriptions);
        Button playlists = phoneButton();
        playlists.setText(R.string.account_playlists);
        playlists.setOnClickListener(view -> loadLibrary(Section.PLAYLISTS, null, ""));
        library.addView(playlists);
        panel.addView(library);
        forget = phoneButton();
        forget.setText(R.string.account_forget);
        forget.setOnClickListener(view -> {
            preferences.edit().clear().apply();
            render(R.string.account_intro, false, false);
        });
        panel.addView(forget);
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
        render(R.string.account_checking, true, true);
        Identity.getAuthorizationClient(this).authorize(authorizationRequest()).addOnCompleteListener(task -> {
            if (isFinishing() || isDestroyed()) {
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
                    runOnUiThread(() -> showLibrary(section, playlistId, page));
                } catch (IOException | JSONException exception) {
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            render(R.string.account_library_failed, false, true);
                        }
                    });
                }
            }, "den-youtube-library").start();
        });
    }

    private void showLibrary(Section section, String playlistId, YouTubeAccountApi.Page page) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        render(R.string.account_connected, false, true);
        int title = switch (section) {
            case SUBSCRIPTIONS -> R.string.account_subscriptions;
            case PLAYLISTS -> R.string.account_playlists;
            case VIDEOS -> R.string.account_playlist_videos;
        };
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(title)
                .setNegativeButton(android.R.string.cancel, null);
        if (page.items().isEmpty()) {
            dialog.setMessage(R.string.account_empty_list);
        } else {
            String[] labels = page.items().stream().map(YouTubeAccountApi.Item::title).toArray(String[]::new);
            dialog.setItems(labels, (window, index) -> openLibraryItem(section, page.items().get(index).id()));
        }
        if (!page.next().isEmpty()) {
            dialog.setNeutralButton(R.string.account_next_page, (window, index) ->
                    loadLibrary(section, playlistId, page.next()));
        }
        dialog.show();
    }

    private void openLibraryItem(Section section, String id) {
        switch (section) {
            case PLAYLISTS -> loadLibrary(Section.VIDEOS, id, "");
            case SUBSCRIPTIONS -> {
                if (id.matches("UC[A-Za-z0-9_-]{22}")) {
                    openInBrowser(BrowserAddress.YOUTUBE_HOME + "channel/" + id);
                }
            }
            case VIDEOS -> {
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
        super.onSaveInstanceState(state);
    }
}
