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
 * PATRIOTA_GREEN_BLUE_V1
 * Lightweight native automotive backdrop for the Estrada Play Patriota identity.
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
                new int[]{Color.rgb(4, 12, 8), Color.rgb(6, 28, 17), Color.rgb(4, 8, 13)},
                new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Deep green architectural planes.
        path.reset();
        path.moveTo(0, h * .08f);
        path.lineTo(w * .34f, 0);
        path.lineTo(w * .12f, h * .34f);
        path.lineTo(0, h * .27f);
        path.close();
        paint.setColor(Color.argb(150, 0, 92, 46));
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(w, h * .16f);
        path.lineTo(w * .80f, h * .24f);
        path.lineTo(w * .58f, h);
        path.lineTo(w, h);
        path.close();
        paint.setColor(Color.argb(62, 0, 39, 118));
        canvas.drawPath(path, paint);

        // Fine blue construction lines.
        line.setColor(Color.argb(78, 30, 92, 180));
        for (int i = -2; i < 6; i++) {
            float x = w * (i * .22f);
            canvas.drawLine(x, h, x + w * .72f, 0, line);
        }

        // Green road-like glow entering the composition from below.
        paint.setShader(new LinearGradient(w * .48f, h, w * .57f, h * .38f,
                new int[]{Color.argb(0, 0, 156, 59), Color.argb(160, 0, 156, 59), Color.argb(0, 0, 156, 59)},
                null, Shader.TileMode.CLAMP));
        path.reset();
        path.moveTo(w * .42f, h);
        path.cubicTo(w * .47f, h * .82f, w * .63f, h * .67f, w * .55f, h * .42f);
        path.lineTo(w * .59f, h * .42f);
        path.cubicTo(w * .68f, h * .68f, w * .52f, h * .84f, w * .49f, h);
        path.close();
        canvas.drawPath(path, paint);
        paint.setShader(null);

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
        paint.setColor(Color.argb(225, 255, 223, 0));
        canvas.drawPath(s, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
