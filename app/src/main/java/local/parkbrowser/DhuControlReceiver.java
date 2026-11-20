package local.parkbrowser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.regex.Pattern;

/** Relays requests to the ADB helper. Only the helper sends commands to its local DHU process. */
public final class DhuControlReceiver extends BroadcastReceiver {
    private static final String SYNC = "local.parkbrowser.dhu.SYNC";
    private static final String END = "local.parkbrowser.dhu.END";
    private static final Pattern SESSION_ID = Pattern.compile("[a-f0-9]{32}");
    private static final long SESSION_TIMEOUT_MS = 12_000;

    // The receiver and browser menu both access this state on the app's main thread.
    private static String session;
    private static long lastContact;
    private static boolean appliedUnrestricted;
    private static Boolean requestedUnrestricted;

    @Override
    public void onReceive(Context context, Intent intent) {
        String incoming = intent.getStringExtra("session");
        if (!isOrderedBroadcast() || incoming == null || !SESSION_ID.matcher(incoming).matches()) {
            return;
        }
        if (END.equals(intent.getAction()) && incoming.equals(session)) {
            session = null;
            requestedUnrestricted = null;
        }
        if (!SYNC.equals(intent.getAction())) {
            return;
        }
        if (!isConnected()) {
            session = incoming;
            requestedUnrestricted = null;
        } else if (!incoming.equals(session)) {
            setResultData("den-dhu:busy");
            return;
        }
        lastContact = SystemClock.elapsedRealtime();
        appliedUnrestricted = intent.getBooleanExtra("unrestricted", false);
        if (requestedUnrestricted != null && requestedUnrestricted == appliedUnrestricted) {
            requestedUnrestricted = null;
        }
        if (requestedUnrestricted == null) {
            setResultData("den-dhu:idle");
        } else {
            setResultData(requestedUnrestricted ? "den-dhu:unrestricted" : "den-dhu:restricted");
        }
    }

    static boolean isConnected() {
        return session != null && SystemClock.elapsedRealtime() - lastContact < SESSION_TIMEOUT_MS;
    }

    static boolean isUnrestricted() {
        return requestedUnrestricted == null ? appliedUnrestricted : requestedUnrestricted;
    }

    static boolean toggleRestrictions() {
        if (!isConnected()) {
            return false;
        }
        requestedUnrestricted = !isUnrestricted();
        return true;
    }
}
