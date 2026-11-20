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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class YouTubeAccountApi {
    private static final String YOUTUBE_API = "https://www.googleapis.com/youtube/v3/";
    private static final URI USER_INFO = URI.create("https://www.googleapis.com/oauth2/v3/userinfo");
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    record Identity(String subject, String email) {}
    record Channel(String id, String title, String handle, String thumbnail, String likes) {}
    record Item(String id, String title, String channel, String published, String thumbnail) {}
    record Page(List<Item> items, String next) {}

    static final class ServiceDisabledException extends IOException {
        ServiceDisabledException() {
            super("YouTube Data API is disabled for the OAuth project");
        }
    }

    enum Section {
        RECENT("", ""),
        CHANNEL_VIDEOS("", ""),
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
                case RECENT, CHANNEL_VIDEOS, VIDEOS -> resource == null ? "" : resource.optString("videoId");
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

    static Channel loadChannel(String accessToken) throws IOException, JSONException {
        Uri request = Uri.parse(YOUTUBE_API + "channels").buildUpon()
                .appendQueryParameter("part", "snippet,contentDetails").appendQueryParameter("mine", "true")
                .appendQueryParameter("maxResults", "1").appendQueryParameter("fields",
                        "items(id,snippet(title,customUrl,thumbnails),contentDetails(relatedPlaylists(likes)))").build();
        JSONArray channels = read(URI.create(request.toString()), accessToken).optJSONArray("items");
        if (channels == null || channels.length() == 0) {
            return new Channel("", "", "", "", "");
        }
        JSONObject channel = channels.getJSONObject(0);
        JSONObject snippet = channel.getJSONObject("snippet");
        JSONObject details = channel.optJSONObject("contentDetails");
        JSONObject playlists = details == null ? null : details.optJSONObject("relatedPlaylists");
        return new Channel(channel.optString("id"), snippet.optString("title"), snippet.optString("customUrl"),
                thumbnail(snippet), playlists == null ? "" : playlists.optString("likes"));
    }

    static Page readPage(String accessToken, Section section, String collectionId, String pageToken)
            throws IOException, JSONException {
        if (section == Section.CHANNEL_VIDEOS) {
            List<String> uploads = uploadsPlaylists(accessToken, List.of(collectionId));
            return uploads.isEmpty() ? new Page(List.of(), "")
                    : readCollection(accessToken, Section.VIDEOS, uploads.get(0), pageToken, 24);
        }
        if (section == Section.RECENT) {
            return recentUploads(accessToken, pageToken);
        }
        return readCollection(accessToken, section, collectionId, pageToken, 24);
    }

    private static Page recentUploads(String accessToken, String pageToken) throws IOException, JSONException {
        Page subscriptions = readCollection(accessToken, Section.SUBSCRIPTIONS, null, pageToken, 8);
        List<String> channels = subscriptions.items().stream().map(Item::id)
                .filter(BrowserAddress::isChannelId).toList();
        List<String> uploads = uploadsPlaylists(accessToken, channels);
        if (uploads.isEmpty()) {
            return new Page(List.of(), subscriptions.next());
        }
        List<Callable<Page>> requests = new ArrayList<>();
        for (String playlist : uploads) {
            requests.add(() -> readCollection(accessToken, Section.VIDEOS, playlist, "", 4));
        }
        Map<String, Item> videos = new LinkedHashMap<>();
        boolean anyLoaded = false;
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(4, requests.size()));
        try {
            for (var result : workers.invokeAll(requests, 12, TimeUnit.SECONDS)) {
                if (!result.isCancelled()) {
                    try {
                        for (Item video : result.get().items()) {
                            videos.putIfAbsent(video.id(), video);
                        }
                        anyLoaded = true;
                    } catch (ExecutionException ignored) {
                        // Videos from the other channels can still be displayed.
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("YouTube feed request interrupted", exception);
        } finally {
            workers.shutdownNow();
        }
        if (!anyLoaded) {
            throw new IOException("No uploads playlist could be loaded");
        }
        return new Page(videos.values().stream().sorted(Comparator.comparing(Item::published).reversed()).toList(),
                subscriptions.next());
    }

    private static List<String> uploadsPlaylists(String token, List<String> channels) throws IOException, JSONException {
        if (channels.isEmpty()) {
            return List.of();
        }
        Uri endpoint = Uri.parse(YOUTUBE_API + "channels").buildUpon().appendQueryParameter("part", "contentDetails")
                .appendQueryParameter("id", String.join(",", channels))
                .appendQueryParameter("fields", "items(contentDetails(relatedPlaylists(uploads)))").build();
        JSONArray rows = read(URI.create(endpoint.toString()), token).optJSONArray("items");
        List<String> uploads = new ArrayList<>();
        for (int index = 0; rows != null && index < rows.length(); index++) {
            JSONObject related = rows.getJSONObject(index).getJSONObject("contentDetails")
                    .optJSONObject("relatedPlaylists");
            if (related != null && !related.optString("uploads").isEmpty()) {
                uploads.add(related.getString("uploads"));
            }
        }
        return uploads;
    }

    private static Page readCollection(String accessToken, Section section, String playlistId, String pageToken,
            int pageSize) throws IOException, JSONException {
        Uri.Builder search = Uri.parse(YOUTUBE_API + section.endpoint).buildUpon()
                .appendQueryParameter("part", section == Section.VIDEOS ? "snippet,contentDetails" : "snippet")
                .appendQueryParameter("maxResults", Integer.toString(pageSize))
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
                items.add(new Item(id, title, snippet.optString("videoOwnerChannelTitle"),
                        details == null ? "" : details.optString("videoPublishedAt"), thumbnail(snippet)));
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
