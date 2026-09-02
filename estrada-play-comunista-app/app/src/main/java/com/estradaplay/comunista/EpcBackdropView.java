package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * AUTOMOTIVE_RED_GOLD_V200
 * Lightweight native backdrop for the EPC automotive identity.
 * No bitmap, no WebView and no decorative asset download at runtime.
 */
final class EpcBackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    EpcBackdropView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        paint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{Color.rgb(5, 4, 5), Color.rgb(13, 6, 8), Color.rgb(4, 4, 5)},
                new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Deep red diagonal architectural planes.
        path.reset();
        path.moveTo(0, h * .08f);
        path.lineTo(w * .34f, 0);
        path.lineTo(w * .12f, h * .34f);
        path.lineTo(0, h * .27f);
        path.close();
        paint.setColor(Color.argb(150, 116, 9, 24));
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(w, h * .16f);
        path.lineTo(w * .80f, h * .24f);
        path.lineTo(w * .58f, h);
        path.lineTo(w, h);
        path.close();
        paint.setColor(Color.argb(58, 117, 10, 25));
        canvas.drawPath(path, paint);

        // Fine red construction lines.
        line.setColor(Color.argb(70, 211, 39, 54));
        for (int i = -2; i < 6; i++) {
            float x = w * (i * .22f);
            canvas.drawLine(x, h, x + w * .72f, 0, line);
        }

        // Road-like glow entering the composition from below.
        paint.setShader(new LinearGradient(w * .48f, h, w * .57f, h * .38f,
                new int[]{Color.argb(0, 255, 42, 48), Color.argb(155, 201, 25, 39), Color.argb(0, 201, 25, 39)},
                null, Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(w * .42f, h);
        path.cubicTo(w * .47f, h * .82f, w * .63f, h * .67f, w * .55f, h * .42f);
        path.lineTo(w * .59f, h * .42f);
        path.cubicTo(w * .68f, h * .68f, w * .52f, h * .84f, w * .49f, h);
        path.close();
        canvas.drawPath(path, paint);
        paint.setShader(null);

        // Small gold star accent, deliberately subtle.
        drawStar(canvas, w * .11f, h * .24f, Math.max(dp(8), w * .018f));
    }

    private void drawStar(Canvas canvas, float cx, float cy, float r) {
        Path s = new Path();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = (i & 1) == 0 ? r : r * .43f;
            float x = cx + (float)Math.cos(a) * rr;
            float y = cy + (float)Math.sin(a) * rr;
            if (i == 0) s.moveTo(x, y); else s.lineTo(x, y);
        }
        s.close();
        paint.setColor(Color.argb(210, 226, 185, 76));
        canvas.drawPath(s, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
