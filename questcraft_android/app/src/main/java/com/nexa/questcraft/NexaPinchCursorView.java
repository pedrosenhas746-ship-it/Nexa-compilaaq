package com.nexa.questcraft;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import pojlib.NexaXRBridge;

/**
 * PhoneXR-style pinch cursor.
 *
 * A small white orb/ring floats at the stabilized point between thumb and index.
 * Pinch closes at 0.30 palm widths and only releases after 0.48, matching the
 * hysteresis used by PhoneXR so clicks do not chatter near the threshold.
 */
public final class NexaPinchCursorView extends View implements Choreographer.FrameCallback {
    private static final long TRACKING_FRESH_MS = 280L;
    private static final float PINCH_CLOSE = 0.30f;
    private static final float PINCH_OPEN = 0.48f;

    public interface PinchListener {
        void onPinch(int hand, float xPx, float yPx);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean[] pinching = new boolean[2];
    private final float density;
    private boolean running;
    private PinchListener listener;

    public NexaPinchCursorView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    public void setPinchListener(PinchListener listener) {
        this.listener = listener;
    }

    public void resume() {
        if (running) return;
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void pause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        pinching[0] = pinching[1] = false;
        invalidate();
    }

    public void destroy() {
        pause();
        listener = null;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) return;

        NexaXRBridge.LocalSnapshot s = NexaXRBridge.getLocalSnapshot();
        long now = SystemClock.uptimeMillis();
        boolean fresh = s.trackingTimestampMs > 0
                && now - s.trackingTimestampMs >= 0
                && now - s.trackingTimestampMs <= TRACKING_FRESH_MS
                && s.cursor != null && s.cursor.length >= 4
                && s.pinch != null && s.pinch.length >= 8;

        if (!fresh) {
            pinching[0] = pinching[1] = false;
        } else {
            for (int hand = 0; hand < 2; hand++) {
                boolean valid = (s.validHands & (1 << hand)) != 0;
                if (!valid) {
                    pinching[hand] = false;
                    continue;
                }
                float gap = s.pinch[hand * 4];
                boolean before = pinching[hand];
                boolean after = before ? gap < PINCH_OPEN : gap < PINCH_CLOSE;
                pinching[hand] = after;
                if (after && !before && listener != null) {
                    float nx = s.cursor[hand * 2];
                    float ny = s.cursor[hand * 2 + 1];
                    if (nx >= 0f && nx <= 1f && ny >= 0f && ny <= 1f) {
                        listener.onPinch(hand, nx * getWidth(), ny * getHeight());
                    }
                }
            }
        }

        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        NexaXRBridge.LocalSnapshot s = NexaXRBridge.getLocalSnapshot();
        long now = SystemClock.uptimeMillis();
        if (s.trackingTimestampMs <= 0 || now - s.trackingTimestampMs > TRACKING_FRESH_MS
                || s.cursor == null || s.cursor.length < 4) return;

        for (int hand = 0; hand < 2; hand++) {
            if ((s.validHands & (1 << hand)) == 0) continue;
            float nx = s.cursor[hand * 2];
            float ny = s.cursor[hand * 2 + 1];
            if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) continue;

            float x = nx * getWidth();
            float y = ny * getHeight();

            // PhoneXR visual: dark outer ring, white centre; smaller when pinched.
            float inner = (pinching[hand] ? 5.5f : 8.5f) * density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(115, 0, 0, 0));
            canvas.drawCircle(x, y, inner * 1.45f, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, inner, paint);

            if (pinching[hand]) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f * density);
                paint.setColor(Color.argb(210, 255, 255, 255));
                canvas.drawCircle(x, y, inner * 1.9f, paint);
            }
        }
    }
}
