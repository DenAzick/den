package local.parkbrowser;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BrowserAddress {
    static final String YOUTUBE_HOME = "https://m.youtube.com/";

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");
    private static final Pattern DOMAIN_WITHOUT_SCHEME = Pattern.compile(
            "^(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]+(?::[0-9]+)?(?:[/?#].*)?$");
    private static final Pattern SHARED_LINK = Pattern.compile("https://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ID = Pattern.compile("[a-zA-Z0-9_-]{11}");

    private BrowserAddress() {}

    static String resolve(String input) {
        String value = input.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (DOMAIN_WITHOUT_SCHEME.matcher(value).matches()) {
            value = "https://" + value;
        } else if (!SCHEME.matcher(value).find() && !value.startsWith("//")) {
            return YOUTUBE_HOME + "results?search_query="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
        return isHttps(value) ? value : null;
    }

    static boolean isHttps(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = new URI(value);
            int port = uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && (port == -1 ? uri.getHost().equals(uri.getRawAuthority()) : port > 0 && port <= 65535);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    static boolean isYouTube(String value) {
        if (!isHttps(value)) {
            return false;
        }
        URI address = URI.create(value);
        if (address.getPort() != -1 && address.getPort() != 443) {
            return false;
        }
        String host = address.getHost().toLowerCase(Locale.ROOT);
        return host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be");
    }

    static String sharedVideoId(CharSequence text) {
        if (text == null || text.length() > 8_192) {
            return null;
        }
        Matcher links = SHARED_LINK.matcher(text);
        while (links.find()) {
            String video = youtubeVideoId(links.group());
            if (video != null) {
                return video;
            }
        }
        return null;
    }

    static boolean isVideoId(String video) {
        return video != null && VIDEO_ID.matcher(video).matches();
    }

    private static String youtubeVideoId(String link) {
        if (!isYouTube(link)) {
            return null;
        }
        URI address = URI.create(link);
        String path = address.getPath();
        if ("youtu.be".equalsIgnoreCase(address.getHost()) && path.startsWith("/")) {
            String id = path.substring(1);
            return isVideoId(id) ? id : null;
        }
        if ("/watch".equals(path) && address.getRawQuery() != null) {
            for (String parameter : address.getRawQuery().split("&")) {
                if (parameter.startsWith("v=") && isVideoId(parameter.substring(2))) {
                    return parameter.substring(2);
                }
            }
        }
        for (String prefix : new String[]{"/shorts/", "/live/", "/embed/"}) {
            if (path.startsWith(prefix) && isVideoId(path.substring(prefix.length()))) {
                return path.substring(prefix.length());
            }
        }
        return null;
    }
}
