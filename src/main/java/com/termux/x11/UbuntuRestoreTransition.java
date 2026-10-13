package com.termux.x11;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Keeps an uncomposited X11 remap hidden until its repaint can be crossfaded. */
final class UbuntuRestoreTransition {
    private final SurfaceView surface;
    private final ImageView cover;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::reveal;
    private final Runnable poll = this::inspect;
    private long generation;
    private boolean active;
    private Bitmap sample;
    private Frames frames;
    private Runnable finished;

    UbuntuRestoreTransition(SurfaceView surface, FrameLayout frame) {
        this.surface = surface;
        cover = new ImageView(surface.getContext());
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cover.setClickable(true); // Do not send input to a window hidden by the transition.
        cover.setVisibility(View.GONE);
        frame.addView(cover);
    }

    void prepare(Runnable request) {
        cancel();
        if (!surface.getHolder().getSurface().isValid() || surface.getWidth() == 0 || surface.getHeight() == 0) {
            request.run();
            return;
        }
        active = true;
        long owner = generation;
        // At most 8 MiB, only for this transition; no persistent app previews.
        float scale = Math.min(1f, (float) Math.sqrt(2073600d / ((long) surface.getWidth() * surface.getHeight())));
        Bitmap before = Bitmap.createBitmap(Math.max(1, Math.round(surface.getWidth() * scale)),
                Math.max(1, Math.round(surface.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surface, before, result -> {
                if (owner != generation) { before.recycle(); return; }
                if (result == PixelCopy.SUCCESS) {
                    int[] baseline = new int[64 * 64];
                    for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++)
                        baseline[y * 64 + x] = before.getPixel(x * before.getWidth() / 64, y * before.getHeight() / 64);
                    frames = new Frames(baseline, SystemClock.uptimeMillis());
                    sample = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(surface.getWidth(), surface.getHeight());
                    params.leftMargin = surface.getLeft(); params.topMargin = surface.getTop();
                    cover.setLayoutParams(params);
                    cover.setImageBitmap(before);
                    cover.setAlpha(1f);
                    cover.setVisibility(View.VISIBLE);
                    // Dispatch after a traversal has drawn the cover, before Linux remaps.
                    cover.postOnAnimation(() -> cover.post(() -> { if (owner == generation) request.run(); }));
                } else {
                    before.recycle();
                    cancel();
                    request.run();
                }
            }, handler);
        } catch (IllegalArgumentException failure) {
            before.recycle(); cancel(); request.run();
        }
    }

    void restored(boolean success, Runnable completion) {
        if (!active) { completion.run(); return; }
        finished = completion;
        if (!success || frames == null) { cancel(); return; }
        frames.started = frames.stableSince = SystemClock.uptimeMillis();
        handler.postDelayed(timeout, 1500); // A changing app must never leave the cover stuck.
        inspect();
    }

    private void inspect() {
        long owner = generation;
        Bitmap target = sample;
        if (!active || target == null) return;
        if (!surface.getHolder().getSurface().isValid()
                || surface.getWidth() != cover.getWidth() || surface.getHeight() != cover.getHeight()) {
            cancel(); return;
        }
        try {
            PixelCopy.request(surface, target, result -> {
                if (owner != generation) { target.recycle(); return; }
                if (result != PixelCopy.SUCCESS) { cancel(); return; }
                int[] pixels = new int[64 * 64];
                target.getPixels(pixels, 0, 64, 0, 0, 64, 64);
                if (frames.accept(pixels, SystemClock.uptimeMillis())) reveal();
                else handler.postDelayed(poll, 32);
            }, handler);
        } catch (IllegalArgumentException failure) { cancel(); }
    }

    private void reveal() {
        if (!active) return;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(poll);
        generation++; // Ignore any PixelCopy finishing after the reveal has started.
        sample = null;
        Log.d("UbuntuRestore", "Crossfade after repaint, wait=" + (SystemClock.uptimeMillis() - frames.started) + "ms");
        if (!ValueAnimator.areAnimatorsEnabled()) { cancel(); return; }
        cover.animate().alpha(0f).setDuration(180).setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::cancel).start();
    }

    void cancel() {
        generation++;
        active = false;
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(poll);
        cover.animate().withEndAction(null).cancel();
        cover.setVisibility(View.GONE);
        cover.setImageDrawable(null);
        sample = null; frames = null;
        Runnable completion = finished;
        finished = null;
        if (completion != null) completion.run();
    }

    static final class Frames {
        final int[] baseline;
        int[] previous;
        long started, stableSince;
        boolean changed;
        Frames(int[] baseline, long now) {
            this.baseline = baseline; previous = baseline; started = stableSince = now;
        }
        boolean accept(int[] pixels, long now) {
            changed = difference(baseline, pixels) > pixels.length / 50;
            if (difference(previous, pixels) > pixels.length / 50) stableSince = now;
            previous = pixels;
            // ponytail: 64px repaint heuristic ignores cursors; use an X11 presentation fence if clients need it.
            return (changed && now - stableSince >= 120) || now - started >= 1500;
        }
        private static int difference(int[] a, int[] b) {
            int changed = 0;
            for (int i = 0; i < a.length; i++) {
                int x = a[i], y = b[i];
                if (Math.abs((x >> 16 & 255) - (y >> 16 & 255)) > 12
                        || Math.abs((x >> 8 & 255) - (y >> 8 & 255)) > 12
                        || Math.abs((x & 255) - (y & 255)) > 12) changed++;
            }
            return changed;
        }
    }
}
