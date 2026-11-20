package local.parkbrowser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.json.JSONException;

final class DenUpdateCheck {
    private static final String LAST_CHECK = "last_update_check";
    private static final long INTERVAL = 24 * 60 * 60 * 1_000L;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final BooleanSupplier isResumed;
    private final Consumer<String> showStatus;
    private boolean checking;
    private boolean manualRequested;

    DenUpdateCheck(Activity activity, BooleanSupplier isResumed, Consumer<String> showStatus) {
        this.activity = activity;
        this.isResumed = isResumed;
        this.showStatus = showStatus;
        prefs = activity.getSharedPreferences("den_device_state", Activity.MODE_PRIVATE);
    }

    void check(boolean manual) {
        if (checking) {
            manualRequested |= manual;
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - prefs.getLong(LAST_CHECK, 0);
        if (!manual && elapsed >= 0 && elapsed < INTERVAL) {
            return;
        }
        checking = true;
        manualRequested = manual;
        prefs.edit().putLong(LAST_CHECK, now).apply();
        if (manual) {
            showStatus.accept(activity.getString(R.string.updates_checking));
        }
        new Thread(() -> {
            try {
                String installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)).versionName;
                String latest = GitHubUpdates.latestApkVersion(installed);
                activity.runOnUiThread(() -> showResult(installed, latest, false));
            } catch (IOException | JSONException | PackageManager.NameNotFoundException exception) {
                activity.runOnUiThread(() -> showResult(null, null, true));
            }
        }, "den-updates").start();
    }

    private void showResult(String installed, String latest, boolean failed) {
        boolean manual = manualRequested;
        checking = false;
        manualRequested = false;
        if (!isResumed.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (latest != null && GitHubUpdates.isNewer(latest, installed)) {
            showStatus.accept(null);
            new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.updates_available, latest))
                    .setMessage(R.string.updates_install_prompt)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.updates_open, (dialog, button) -> openReleasePage())
                    .show();
        } else if (manual) {
            String message = failed ? activity.getString(R.string.updates_failed)
                    : latest == null ? activity.getString(R.string.updates_unpublished)
                    : activity.getString(R.string.updates_current, installed);
            showStatus.accept(message);
        }
    }

    private void openReleasePage() {
        Intent release = new Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdates.RELEASES_PAGE))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        Intent genericWebPage = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo browser = activity.getPackageManager().resolveActivity(genericWebPage, PackageManager.MATCH_DEFAULT_ONLY);
        if (browser != null && browser.activityInfo != null && !"android".equals(browser.activityInfo.packageName)) {
            release.setPackage(browser.activityInfo.packageName);
        }
        Bundle customTab = new Bundle();
        customTab.putBinder("android.support.customtabs.extra.SESSION", null);
        release.putExtras(customTab);
        try {
            activity.startActivity(release);
        } catch (ActivityNotFoundException exception) {
            showStatus.accept(activity.getString(R.string.updates_browser_error));
        }
    }
}
