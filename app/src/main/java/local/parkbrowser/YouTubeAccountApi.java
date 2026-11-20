package local.parkbrowser;

import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class YouTubeAccountApi {
    private static final String YOUTUBE_API = "https://www.googleapis.com/youtube/v3/";
    private static final URI USER_INFO = URI.create("https://www.googleapis.com/oauth2/v3/userinfo");
    private static final URI YOUTUBE_CHANNEL = URI.create(YOUTUBE_API + "channels"
            + "?part=snippet&mine=true&maxResults=1&fields=items%28snippet%28title%29%29");
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    record Identity(String subject, String email) {}
    record Item(String id, String title, String thumbnail, String channel, String published) {}
    record Page(List<Item> items, String next) {}

    static final class ServiceDisabledException extends IOException {
        ServiceDisabledException() {
            super("YouTube Data API is disabled for the OAuth project");
        }
    }

    enum Section {
        RECENT("", ""),
        SUBSCRIPTIONS("subscriptions", "items(snippet(title,thumbnails,resourceId(channelId)))"),
        PLAYLISTS("playlists", "items(id,snippet(title,thumbnails))"),
        VIDEOS("playlistItems", "items(snippet(title,thumbnails,videoOwnerChannelTitle,resourceId(videoId)),"
                + "contentDetails(videoPublishedAt))");

        final String endpoint;
        final String fields;

        Section(String endpoint, String fields) {
            this.endpoint = endpoint;
            this.fields = fields;
        }

        String itemId(JSONObject item, JSONObject snippet) {
            JSONObject resource = snippet.optJSONObject("resourceId");
            return switch (this) {
                case SUBSCRIPTIONS -> resource == null ? "" : resource.optString("channelId");
                case PLAYLISTS -> item.optString("id");
                case RECENT, VIDEOS -> resource == null ? "" : resource.optString("videoId");
            };
        }
    }

    private YouTubeAccountApi() {}

    static Identity loadIdentity(String accessToken) throws IOException, JSONException {
        JSONObject account = read(USER_INFO, accessToken);
        String subject = account.getString("sub");
        String email = account.getString("email");
        if (subject.isBlank() || email.isBlank() || !account.optBoolean("email_verified")) {
            throw new IOException("Google did not return a verified account");
        }
        return new Identity(subject, email);
    }

    static String loadChannel(String accessToken) throws IOException, JSONException {
        JSONArray channels = read(YOUTUBE_CHANNEL, accessToken).optJSONArray("items");
        return channels == null || channels.length() == 0 ? ""
                : channels.getJSONObject(0).getJSONObject("snippet").getString("title");
    }

    static Page readPage(String accessToken, Section section, String playlistId, String pageToken)
            throws IOException, JSONException {
        return section == Section.RECENT ? loadRecentUploads(accessToken)
                : readPage(accessToken, section, playlistId, pageToken, 24);
    }

    private static Page loadRecentUploads(String accessToken) throws IOException, JSONException {
        List<String> channels = readPage(accessToken, Section.SUBSCRIPTIONS, null, "", 8).items().stream()
                .map(Item::id).filter(id -> id.matches("UC[A-Za-z0-9_-]{22}")).toList();
        if (channels.isEmpty()) {
            return new Page(List.of(), "");
        }
        Uri endpoint = Uri.parse(YOUTUBE_API + "channels").buildUpon().appendQueryParameter("part", "contentDetails")
                .appendQueryParameter("id", String.join(",", channels))
                .appendQueryParameter("fields", "items(contentDetails(relatedPlaylists(uploads)))").build();
        JSONArray channelRows = read(URI.create(endpoint.toString()), accessToken).optJSONArray("items");
        Map<String, Item> videos = new LinkedHashMap<>();
        IOException unreadable = null;
        for (int i = 0; channelRows != null && i < channelRows.length(); i++) {
            JSONObject related = channelRows.getJSONObject(i).getJSONObject("contentDetails")
                    .optJSONObject("relatedPlaylists");
            String uploads = related == null ? "" : related.optString("uploads");
            if (uploads.isEmpty()) {
                continue;
            }
            try {
                for (Item video : readPage(accessToken, Section.VIDEOS, uploads, "", 4).items()) {
                    videos.putIfAbsent(video.id(), video);
                }
            } catch (IOException exception) {
                unreadable = exception;
            }
        }
        if (videos.isEmpty() && unreadable != null) {
            throw unreadable;
        }
        List<Item> recent = videos.values().stream()
                .sorted(Comparator.comparing(Item::published).reversed()).limit(24).toList();
        return new Page(recent, "");
    }

    private static Page readPage(String accessToken, Section section, String playlistId, String pageToken, int limit)
            throws IOException, JSONException {
        Uri.Builder search = Uri.parse(YOUTUBE_API + section.endpoint).buildUpon()
                .appendQueryParameter("part", section == Section.VIDEOS ? "snippet,contentDetails" : "snippet")
                .appendQueryParameter("maxResults", Integer.toString(limit))
                .appendQueryParameter("fields", "nextPageToken," + section.fields);
        if (section == Section.VIDEOS) {
            search.appendQueryParameter("playlistId", playlistId);
        } else {
            search.appendQueryParameter("mine", "true");
        }
        if (!pageToken.isEmpty()) {
            search.appendQueryParameter("pageToken", pageToken);
        }
        JSONObject response = read(URI.create(search.build().toString()), accessToken);
        JSONArray rows = response.optJSONArray("items");
        List<Item> items = new ArrayList<>();
        for (int index = 0; rows != null && index < rows.length(); index++) {
            JSONObject item = rows.getJSONObject(index);
            JSONObject snippet = item.getJSONObject("snippet");
            String id = section.itemId(item, snippet);
            String title = snippet.optString("title");
            if (!id.isEmpty() && !title.isBlank() && (section != Section.VIDEOS || BrowserAddress.isVideoId(id))) {
                JSONObject details = item.optJSONObject("contentDetails");
                String published = details == null ? "" : details.optString("videoPublishedAt");
                items.add(new Item(id, title, thumbnail(snippet), snippet.optString("videoOwnerChannelTitle"), published));
            }
        }
        return new Page(items, response.optString("nextPageToken"));
    }

    private static String thumbnail(JSONObject snippet) {
        JSONObject sizes = snippet.optJSONObject("thumbnails");
        if (sizes != null) {
            for (String size : List.of("high", "medium", "default")) {
                JSONObject picture = sizes.optJSONObject(size);
                if (picture != null && !picture.optString("url").isBlank()) {
                    return picture.optString("url");
                }
            }
        }
        return "";
    }

    private static JSONObject read(URI endpoint, String accessToken) throws IOException, JSONException {
        HttpsURLConnection request = (HttpsURLConnection) endpoint.toURL().openConnection();
        request.setInstanceFollowRedirects(false);
        request.setConnectTimeout(7_000);
        request.setReadTimeout(7_000);
        request.setRequestProperty("Accept", "application/json");
        request.setRequestProperty("Authorization", "Bearer " + accessToken);
        try {
            int status = request.getResponseCode();
            if (status == 403 && isYouTubeServiceDisabled(request.getErrorStream())) {
                throw new ServiceDisabledException();
            }
            if (status != 200) {
                throw new IOException("Google returned HTTP " + status);
            }
            try (InputStream response = request.getInputStream()) {
                byte[] body = response.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("Google response is too large");
                }
                return new JSONObject(new String(body, StandardCharsets.UTF_8));
            }
        } finally {
            request.disconnect();
        }
    }

    private static boolean isYouTubeServiceDisabled(InputStream stream) {
        if (stream == null) {
            return false;
        }
        try (stream) {
            byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return false;
            }
            JSONObject error = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONObject("error");
            for (String key : List.of("errors", "details")) {
                JSONArray entries = error.optJSONArray(key);
                for (int index = 0; entries != null && index < entries.length(); index++) {
                    String reason = entries.getJSONObject(index).optString("reason");
                    if ("accessNotConfigured".equals(reason) || "SERVICE_DISABLED".equals(reason)) {
                        return true;
                    }
                }
            }
        } catch (IOException | JSONException ignored) {
            return false;
        }
        return false;
    }
}
