package local.parkbrowser;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import javax.net.ssl.HttpsURLConnection;

final class YouTubeHubView {
    private static final int BASE = 0xFF0E191B;
    private static final int PANEL = 0xFF192C2F;
    private static final int TEXT = 0xFFF0F9F6;
    private static final int MUTED = 0xFFADCCCA;
    private static final int MINT = 0xFF8DE7CB;
    private static final int THUMBNAIL_LIMIT = 1_048_576;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"));

    final LinearLayout root;
    final LinearLayout body;
    private final Activity activity;
    private final ScrollView scroll;
    private final Button[] tabs;
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> thumbnails = new LruCache<>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getAllocationByteCount();
        }
    };

    YouTubeHubView(Activity activity, IntConsumer select, Consumer<String> search, Consumer<View> menu) {
        this.activity = activity;
        root = column();
        root.setBackgroundColor(BASE);
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            var insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsets.CONSUMED;
        });

        LinearLayout header = row();
        header.setPadding(dp(16), dp(4), dp(8), dp(2));
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView wordmark = label(activity.getString(R.string.app_name), 27, TEXT, true);
        wordmark.setLetterSpacing(0.07f);
        wordmark.setPadding(dp(9), 0, 0, 0);
        header.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        Button overflow = button("⋮", false);
        overflow.setTextSize(25);
        overflow.setContentDescription(activity.getString(R.string.browser_menu));
        overflow.setOnClickListener(menu::accept);
        header.addView(overflow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        LinearLayout searchBox = row();
        searchBox.setBackground(shape(PANEL, 16));
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setHint(R.string.hub_search);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(14), 0, 0, 0);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            search.accept(input.getText().toString());
            return true;
        });
        searchBox.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageButton submit = new ImageButton(activity);
        submit.setImageResource(android.R.drawable.ic_menu_search);
        submit.setImageTintList(ColorStateList.valueOf(MINT));
        submit.setContentDescription(activity.getString(R.string.hub_search));
        submit.setBackground(pressable(PANEL, 16));
        submit.setOnClickListener(view -> search.accept(input.getText().toString()));
        searchBox.addView(submit, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.setMargins(dp(16), dp(2), dp(16), dp(12));
        root.addView(searchBox, searchParams);

        LinearLayout navigation = row();
        navigation.setPadding(dp(4), dp(4), dp(4), dp(4));
        navigation.setBackground(shape(PANEL, 16));
        int[] labels = { R.string.hub_home, R.string.account_subscriptions, R.string.hub_profile };
        tabs = new Button[labels.length];
        for (int index = 0; index < labels.length; index++) {
            int section = index;
            tabs[index] = button(activity.getString(labels[index]), false);
            tabs[index].setTextSize(14);
            tabs[index].setOnClickListener(view -> select.accept(section));
            navigation.addView(tabs[index], new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, -2);
        navParams.setMargins(dp(16), 0, dp(16), dp(3));
        root.addView(navigation, navParams);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        body = column();
        body.setPadding(dp(16), dp(16), dp(16), dp(22));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    void activate(int section) {
        for (int index = 0; index < tabs.length; index++) {
            boolean selected = section == index;
            tabs[index].setSelected(selected);
            tabs[index].setTextColor(selected ? BASE : MUTED);
            tabs[index].setBackground(pressable(selected ? MINT : PANEL, 13));
        }
    }

    void reset() {
        body.removeAllViews();
        scroll.scrollTo(0, 0);
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    TextView title(String value) {
        TextView text = label(value, 24, TEXT, true);
        text.setAccessibilityHeading(true);
        text.setPadding(0, 0, 0, dp(7));
        return text;
    }

    TextView note(String value) {
        TextView text = label(value, 14, MUTED, false);
        text.setPadding(0, dp(2), 0, dp(12));
        return text;
    }

    Button button(String title, boolean accent) {
        Button button = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        button.setText(title);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTextColor(accent ? BASE : TEXT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(6), dp(4), dp(6), dp(4));
        button.setBackground(pressable(accent ? MINT : PANEL, 14));
        return button;
    }

    void action(LinearLayout parent, String title, boolean primary, Runnable run) {
        Button button = button(title, primary);
        button.setOnClickListener(view -> run.run());
        append(parent, button);
    }

    void profile(LinearLayout parent, String name, String handle, String image) {
        LinearLayout card = row();
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(shape(PANEL, 22));
        ImageView avatar = imageView(18);
        avatar.setImageResource(R.mipmap.ic_launcher);
        card.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout identity = column();
        identity.setPadding(dp(13), 0, 0, 0);
        identity.addView(label(name, 21, TEXT, true));
        if (!handle.isBlank()) {
            identity.addView(label(handle, 14, MUTED, false));
        }
        card.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        append(parent, card);
        thumbnail(avatar, image);
    }

    void item(LinearLayout parent, YouTubeAccountApi.Item item, boolean video, boolean channel, Runnable open) {
        LinearLayout card = video ? column() : row();
        card.setPadding(dp(9), dp(9), dp(9), dp(9));
        card.setBackground(pressable(PANEL, 20));
        card.setFocusable(true);
        card.setOnClickListener(view -> open.run());

        ImageView image;
        if (video) {
            image = new ImageView(activity) {
                @Override
                protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = View.MeasureSpec.getSize(widthSpec);
                    setMeasuredDimension(width, Math.min(dp(330), width * 9 / 16));
                }
            };
            styleImage(image, 13);
            card.addView(image, new LinearLayout.LayoutParams(-1, -2));
        } else {
            image = imageView(13);
            card.addView(image, new LinearLayout.LayoutParams(dp(channel ? 55 : 90), dp(55)));
        }

        String subtitle = subtitle(item);
        LinearLayout copy = column();
        copy.setPadding(dp(video ? 3 : 12), dp(video ? 10 : 2), dp(3), dp(2));
        TextView title = label(item.title(), video ? 17 : 16, TEXT, true);
        title.setMaxLines(video ? 3 : 2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(title);
        if (!subtitle.isBlank()) {
            TextView info = label(subtitle, 13, MUTED, false);
            info.setMaxLines(1);
            info.setEllipsize(TextUtils.TruncateAt.END);
            info.setPadding(0, dp(3), 0, 0);
            info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            copy.addView(info);
        }
        card.setContentDescription(subtitle.isBlank() ? item.title() : item.title() + ", " + subtitle);
        card.addView(copy, video ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        append(parent, card);
        thumbnail(image, item.thumbnail());
    }

    private String subtitle(YouTubeAccountApi.Item item) {
        if (item.published().length() >= 10) {
            try {
                String date = LocalDate.parse(item.published().substring(0, 10)).format(DAY);
                return item.channel().isBlank() ? date : item.channel() + " · " + date;
            } catch (DateTimeParseException ignored) {
                // Use the channel title when a publication date is unavailable.
            }
        }
        return item.channel();
    }

    void append(LinearLayout parent, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(9);
        parent.addView(view, params);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) {
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return label;
    }

    private ImageView imageView(int radius) {
        ImageView image = new ImageView(activity);
        styleImage(image, radius);
        return image;
    }

    private void styleImage(ImageView image, int radius) {
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(shape(BASE, radius));
        image.setClipToOutline(true);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable pressable(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x388DE7CB), shape(color, radius), null);
    }

    private int dp(int number) {
        return Math.round(number * activity.getResources().getDisplayMetrics().density);
    }

    private void thumbnail(ImageView view, String url) {
        if (!BrowserAddress.isHttps(url)) {
            return;
        }
        URI uri = URI.create(url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ((uri.getPort() != -1 && uri.getPort() != 443) || !(host.endsWith(".ytimg.com")
                || host.equals("yt3.ggpht.com") || host.equals("yt3.googleusercontent.com"))) {
            return;
        }
        Bitmap cached = thumbnails.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        images.execute(() -> {
            if (!view.isAttachedToWindow()) {
                return;
            }
            try {
                Bitmap loaded = readThumbnail(uri);
                if (loaded != null) {
                    activity.runOnUiThread(() -> {
                        if (!activity.isDestroyed() && view.isAttachedToWindow()) {
                            thumbnails.put(url, loaded);
                            view.setImageBitmap(loaded);
                        }
                    });
                }
            } catch (IOException ignored) {
                // Keep the label visible when a preview cannot load.
            }
        });
    }

    private Bitmap readThumbnail(URI uri) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4_000);
        connection.setReadTimeout(4_000);
        try (InputStream stream = connection.getInputStream()) {
            byte[] bytes = stream.readNBytes(THUMBNAIL_LIMIT + 1);
            if (bytes.length > THUMBNAIL_LIMIT) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 1920 || bounds.outHeight > 1920) {
                return null;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } finally {
            connection.disconnect();
        }
    }

    void clearImages() {
        thumbnails.evictAll();
    }

    void close() {
        images.shutdownNow();
    }
}
