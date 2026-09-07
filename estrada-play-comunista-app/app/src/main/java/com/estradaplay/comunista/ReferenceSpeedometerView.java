package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Compact responsive speedometer used by the Universal cockpit. */
final class ReferenceSpeedometerView extends View {
    private final Paint arcBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcRed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint muted = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint status = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint limit = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double speed;
    private int limitKmh;
    private boolean gpsAvailable = true;

    ReferenceSpeedometerView(Context context) {
        super(context);
        setWillNotDraw(false);
        arcBg.setStyle(Paint.Style.STROKE);
        arcBg.setStrokeCap(Paint.Cap.ROUND);
        arcBg.setColor(Color.rgb(67, 65, 70));
        arcRed.setStyle(Paint.Style.STROKE);
        arcRed.setStrokeCap(Paint.Cap.ROUND);
        arcRed.setColor(Color.rgb(227, 13, 39));
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setColor(Color.rgb(225, 221, 222));
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        muted.setColor(Color.rgb(176, 166, 168));
        muted.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.CENTER);
        status.setFakeBoldText(true);
        limit.setTextAlign(Paint.Align.CENTER);
        limit.setFakeBoldText(true);
    }

    void setSpeed(double value) {
        speed = Math.max(0, Math.min(240, Double.isFinite(value) ? value : 0));
        invalidate();
    }

    void setLimit(int value) {
        limitKmh = Math.max(0, Math.min(180, value));
        invalidate();
    }

    void setGpsAvailable(boolean value) {
        gpsAvailable = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float size = Math.max(1f, Math.min(w, h));
        float cx = w / 2f;
        float cy = h * .49f;
        float radius = size * .36f;
        float stroke = Math.max(size * .022f, 2f);
        arcBg.setStrokeWidth(stroke);
        arcRed.setStrokeWidth(stroke);

        RectF oval = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        final float start = 145f;
        final float sweep = 250f;
        canvas.drawArc(oval, start, sweep, false, arcBg);
        canvas.drawArc(oval, start, (float) (sweep * Math.min(1.0, speed / 200.0)), false, arcRed);

        for (int i = 0; i <= 20; i++) {
            double angle = Math.toRadians(start + sweep * i / 20f);
            float outer = radius - stroke * .15f;
            float inner = radius - (i % 4 == 0 ? stroke * 1.8f : stroke * 1.05f);
            float x1 = cx + (float) Math.cos(angle) * inner;
            float y1 = cy + (float) Math.sin(angle) * inner;
            float x2 = cx + (float) Math.cos(angle) * outer;
            float y2 = cy + (float) Math.sin(angle) * outer;
            tick.setStrokeWidth(i % 4 == 0 ? Math.max(1.8f, size * .008f) : Math.max(1.2f, size * .005f));
            canvas.drawLine(x1, y1, x2, y2, tick);

            if (i % 4 == 0) {
                muted.setTextSize(size * .050f);
                float labelRadius = radius - stroke * 3.25f;
                float lx = cx + (float) Math.cos(angle) * labelRadius;
                float ly = cy + (float) Math.sin(angle) * labelRadius - muted.ascent() / 3f;
                canvas.drawText(String.valueOf(i * 10), lx, ly, muted);
            }
        }

        boolean over = limitKmh > 0 && speed > limitKmh + 2;
        text.setColor(over ? Color.rgb(255, 76, 76) : Color.WHITE);
        text.setTextSize(size * .235f);
        canvas.drawText(String.valueOf(Math.round(speed)), cx, cy + text.getTextSize() * .20f, text);

        muted.setTextSize(size * .058f);
        canvas.drawText("km/h", cx, cy + text.getTextSize() * .66f, muted);

        if (limitKmh > 0) {
            limit.setColor(Color.rgb(232, 191, 71));
            limit.setTextSize(size * .049f);
            canvas.drawText("LIM " + limitKmh, cx, cy - radius * .62f, limit);
        }

        status.setColor(gpsAvailable ? Color.rgb(25, 222, 116) : Color.rgb(242, 181, 65));
        status.setTextSize(size * .046f);
        canvas.drawText(gpsAvailable ? "● GPS" : "● BUSCANDO", cx, cy + radius * .83f, status);
    }
}
