package local.parkbrowser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GitHubUpdates {
    static final String RELEASES_PAGE = "https://github.com/DenAzick/den/releases/latest";
    private static final URI API = URI.create("https://api.github.com/repos/DenAzick/den/releases/latest");
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Pattern VERSION = Pattern.compile("v?(\\d{1,8})\\.(\\d{1,8})(?:\\.(\\d{1,8}))?");

    private GitHubUpdates() {}

    static String latestApkVersion(String currentVersion) throws IOException, JSONException {
        HttpsURLConnection request = (HttpsURLConnection) API.toURL().openConnection();
        request.setInstanceFollowRedirects(false);
        request.setConnectTimeout(7_000);
        request.setReadTimeout(7_000);
        request.setRequestProperty("Accept", "application/vnd.github+json");
        request.setRequestProperty("User-Agent", "den/" + currentVersion);
        try {
            int status = request.getResponseCode();
            if (status == 404) {
                return null;
            }
            if (status != 200) {
                throw new IOException("GitHub returned HTTP " + status);
            }
            try (InputStream response = request.getInputStream()) {
                byte[] body = response.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("GitHub release response is too large");
                }
                return apkVersion(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            }
        } finally {
            request.disconnect();
        }
    }

    private static String apkVersion(JSONObject release) throws JSONException {
        String tag = release.getString("tag_name");
        if (release.optBoolean("draft") || release.optBoolean("prerelease") || !VERSION.matcher(tag).matches()) {
            return null;
        }
        JSONArray assets = release.getJSONArray("assets");
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if ("den.apk".equals(asset.optString("name")) && "uploaded".equals(asset.optString("state"))) {
                return tag.startsWith("v") ? tag.substring(1) : tag;
            }
        }
        return null;
    }

    static boolean isNewer(String candidate, String installed) {
        Matcher next = VERSION.matcher(candidate);
        Matcher current = VERSION.matcher(installed);
        if (!next.matches() || !current.matches()) {
            return false;
        }
        for (int part = 1; part <= 3; part++) {
            int nextPart = next.group(part) == null ? 0 : Integer.parseInt(next.group(part));
            int currentPart = current.group(part) == null ? 0 : Integer.parseInt(current.group(part));
            if (nextPart != currentPart) {
                return nextPart > currentPart;
            }
        }
        return false;
    }
}
