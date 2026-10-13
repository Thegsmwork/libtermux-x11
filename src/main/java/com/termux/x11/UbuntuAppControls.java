package com.termux.x11;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Event-driven recovery controls for the private Ubuntu session, no desktop panel. */
final class UbuntuAppControls {
    private final MainActivity activity;
    private final File directory;
    private FileObserver observer;
    private HandlerThread thread;
    private volatile Handler reader;
    private boolean hadWindows;
    private long sessionStamp;
    private final Map<Long, Button> buttons = new HashMap<>();
    private final Set<Long> restoring = new HashSet<>();
    private final UbuntuRestoreTransition transition;
    private long restoreGeneration;
    private final Runnable load = this::readSnapshot;

    private static final class Window {
        final long id;
        final String app, title;
        final Bitmap icon;
        Window(long id, String app, String title, Bitmap icon) {
            this.id = id; this.app = app; this.title = title; this.icon = icon;
        }
    }

    UbuntuAppControls(MainActivity activity) {
        this.activity = activity;
        transition = new UbuntuRestoreTransition(activity.getLorieView(), activity.findViewById(R.id.frame));
        directory = new File(activity.getFilesDir(), "libtermux/sessions/ubuntu/tmp/linux-apps/bridge");
        activity.findViewById(R.id.app_installed_apps_button).setOnClickListener(v -> activity.startActivity(new Intent()
                .setClassName(activity.getPackageName(), activity.getPackageName() + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
    }

    void start() {
        if (reader != null) return;
        thread = new HandlerThread("Ubuntu window controls");
        thread.start();
        reader = new Handler(thread.getLooper());
        observer = new FileObserver(directory.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.DELETE) {
            @Override public void onEvent(int event, String path) {
                if ("windows.json".equals(path) || "daemon-ready.json".equals(path)) refresh();
            }
        };
        observer.startWatching();
        refresh();
    }

    synchronized void refresh() {
        if (reader == null) return;
        reader.removeCallbacks(load);
        reader.postDelayed(load, 30);
    }

    private byte[] readFile(String name, int limit) throws Exception {
        File file = new File(directory, name);
        File expected = new File(activity.getFilesDir().getCanonicalFile(),
                "libtermux/sessions/ubuntu/tmp/linux-apps/bridge/" + name);
        if (!file.getCanonicalPath().equals(expected.getPath())) throw new IllegalStateException("Invalid session file");
        byte[] bytes = new byte[limit + 1];
        int size = 0;
        try (FileInputStream input = new FileInputStream(Os.open(file.getPath(),
                OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK | 0x80000, 0))) {
            android.system.StructStat stat = Os.fstat(input.getFD());
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size < 1 || stat.st_size > limit)
                throw new IllegalStateException("Invalid session file size or type");
            while (size < bytes.length) {
                int n = input.read(bytes, size, bytes.length - size);
                if (n < 0) break;
                size += n;
            }
        }
        if (size > limit) throw new IllegalStateException("Session file too large");
        return java.util.Arrays.copyOf(bytes, size);
    }

    private Bitmap readIcon(long id) {
        try {
            byte[] bytes = readFile("window-" + id + ".png", 65536);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 64 || bounds.outHeight > 64) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) { return null; }
    }

    private void readSnapshot() {
        final Handler owner = reader;
        boolean valid = false;
        final long stamp = new File(directory, "daemon-ready.json").lastModified();
        int count = 0;
        List<Window> minimized = new ArrayList<>();
        try {
            if (!new File(directory, "daemon-ready.json").isFile()) throw new IllegalStateException("Session unavailable");
            JSONObject value = new JSONObject(new String(readFile("windows.json", 65536), java.nio.charset.StandardCharsets.UTF_8));
            if (value.getInt("version") != 1 || !"app".equals(value.getString("mode")))
                throw new IllegalStateException("App session is unavailable");
            JSONArray windows = value.getJSONArray("windows");
            count = windows.length();
            if (count > 64) throw new IllegalStateException("Too many windows");
            for (int index = 0; index < count; index++) {
                JSONObject window = windows.getJSONObject(index);
                long id = window.getLong("id");
                if (id < 1 || id > 0xffffffffL) throw new IllegalStateException("Invalid window");
                if (window.getBoolean("minimized")) {
                    String title = window.optString("title", "App window");
                    String app = window.optString("app", title);
                    minimized.add(new Window(id, app.isEmpty() ? "App window" : app, title, readIcon(id)));
                }
            }
            valid = true;
        } catch (Exception ignored) {
            // A stopped session or an atomic file replacement must hide stale controls.
        }
        final boolean available = valid;
        final int windows = count;
        MainActivity.handler.post(() -> {
            if (owner == null || reader != owner || activity.isDestroyed()) return;
            boolean enabled = available && activity.getIntent().getBooleanExtra("ubuntu_app_controls", false)
                    && !activity.isInPictureInPictureMode() && activity.getLorieView().connected();
            if (available && sessionStamp != stamp) {
                sessionStamp = stamp;
                hadWindows = false;
                buttons.clear();
                transition.cancel();
                restoring.clear();
                ((LinearLayout) activity.findViewById(R.id.app_window_buttons)).removeAllViews();
            }
            if (enabled && windows > 0) hadWindows = true;
            render(minimized);
            View controls = activity.findViewById(R.id.app_window_controls);
            controls.setVisibility(enabled && (!minimized.isEmpty() || !restoring.isEmpty()) ? View.VISIBLE : View.GONE);
            activity.findViewById(R.id.app_empty_state).setVisibility(enabled && hadWindows && windows == 0 ? View.VISIBLE : View.GONE);
            controls.post(activity::positionAppWindowControls);
        });
    }

    private void render(List<Window> windows) {
        LinearLayout row = activity.findViewById(R.id.app_window_buttons);
        Set<Long> ids = new HashSet<>();
        for (Window window : windows) {
            ids.add(window.id);
            Button button = buttons.get(window.id);
            if (button == null) {
                button = appButton(window);
                buttons.put(window.id, button);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.setMargins(dp(4), 0, dp(4), 0);
                row.addView(button, params);
            }
            button.setText(activity.getString(R.string.ubuntu_restore_app, window.app));
            button.setTooltipText(window.title);
            button.setContentDescription(button.getText() + ": " + window.title);
            button.setEnabled(restoring.isEmpty());
            button.setAlpha(button.isEnabled() ? 1f : .55f);
        }
        for (long id : new HashSet<>(buttons.keySet())) {
            if (!ids.contains(id) && !restoring.contains(id)) row.removeView(buttons.remove(id));
        }
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private Button appButton(Window window) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(Color.WHITE);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinHeight(dp(56));
        button.setMinWidth(dp(56));
        button.setMaxWidth(dp(280));
        button.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
        Drawable icon = window.icon == null ? activity.getDrawable(android.R.drawable.sym_def_app_icon)
                : new BitmapDrawable(activity.getResources(), window.icon);
        icon.setBounds(0, 0, dp(28), dp(28));
        button.setCompoundDrawableTintList(null);
        button.setCompoundDrawablesRelative(icon, null, null, null);
        button.setCompoundDrawablePadding(dp(10));
        float[] hsv = {210f, .7f, .85f};
        if (window.icon != null) {
            long red = 0, green = 0, blue = 0, weight = 0;
            float[] pixelHsv = new float[3];
            for (int y = 0; y < window.icon.getHeight(); y++) {
                for (int x = 0; x < window.icon.getWidth(); x++) {
                    int color = window.icon.getPixel(x, y);
                    Color.colorToHSV(color, pixelHsv);
                    if (Color.alpha(color) < 128 || pixelHsv[1] < .25f || pixelHsv[2] < .25f) continue;
                    int w = Math.round(pixelHsv[1] * Color.alpha(color));
                    red += Color.red(color) * w; green += Color.green(color) * w; blue += Color.blue(color) * w; weight += w;
                }
            }
            if (weight > 0) Color.colorToHSV(Color.rgb((int)(red/weight), (int)(green/weight), (int)(blue/weight)), hsv);
        }
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(dp(28));
        surface.setColor(Color.HSVToColor(new float[]{hsv[0], .45f, .24f}));
        surface.setStroke(dp(1), Color.HSVToColor(new float[]{hsv[0], .45f, .95f}));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), surface, null));
        button.setElevation(dp(4));
        button.setOnClickListener(v -> restore(window.id));
        return button;
    }

    private void restore(long window) {
        if (!restoring.isEmpty()) return;
        restoring.add(window);
        Button button = buttons.get(window);
        if (button != null) { button.setEnabled(false); button.setAlpha(.55f); }
        refresh();
        transition.prepare(() -> sendRestore(window));
    }

    private void finishRestore(long window, int result, Bundle data) {
        restoring.remove(window);
        if (result != 0 && reader != null && !activity.isDestroyed()) Toast.makeText(activity,
                data == null ? activity.getString(R.string.ubuntu_restore_failed)
                        : data.getString("error", activity.getString(R.string.ubuntu_restore_failed)), Toast.LENGTH_LONG).show();
        refresh();
    }

    private void sendRestore(long window) {
        long owner = ++restoreGeneration;
        try {
            activity.startService(new Intent()
                    .setClassName(activity.getPackageName(), activity.getPackageName() + ".runtime.RuntimeService")
                    .setAction("com.bat.linuxapps.RESTORE_WINDOW")
                    .putExtra("window_id", window)
                    .putExtra("restore_result", new ResultReceiver(MainActivity.handler) {
                        @Override protected void onReceiveResult(int result, Bundle data) {
                            if (owner != restoreGeneration) return;
                            transition.restored(result == 0, () -> finishRestore(window, result, data));
                        }
                    }));
        } catch (Exception failure) {
            transition.cancel();
            finishRestore(window, 1, null);
        }
    }

    synchronized void stop() {
        restoreGeneration++;
        transition.cancel();
        restoring.clear();
        if (observer != null) observer.stopWatching();
        if (reader != null) reader.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        observer = null; reader = null; thread = null;
        activity.findViewById(R.id.app_window_controls).setVisibility(View.GONE);
        activity.findViewById(R.id.app_empty_state).setVisibility(View.GONE);
    }
}
