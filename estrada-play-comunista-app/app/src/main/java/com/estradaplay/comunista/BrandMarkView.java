package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class BrandMarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path road = new Path();
    private final Path lane = new Path();
    private final Path star = new Path();

    BrandMarkView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        float r = size * 0.24f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(185, 15, 34));
        paint.setShadowLayer(size * 0.10f, 0f, size * 0.045f, 0x55000000);
        canvas.drawRoundRect(new RectF(left, top, left + size, top + size), r, r, paint);
        paint.clearShadowLayer();

        road.reset();
        road.moveTo(left + size * 0.50f, top + size * 0.16f);
        road.lineTo(left + size * 0.79f, top + size * 0.86f);
        road.lineTo(left + size * 0.21f, top + size * 0.86f);
        road.close();
        paint.setColor(Color.WHITE);
        canvas.drawPath(road, paint);

        lane.reset();
        lane.moveTo(left + size * 0.50f, top + size * 0.35f);
        lane.lineTo(left + size * 0.60f, top + size * 0.86f);
        lane.lineTo(left + size * 0.40f, top + size * 0.86f);
        lane.close();
        paint.setColor(Color.rgb(185, 15, 34));
        canvas.drawPath(lane, paint);

        float cx = left + size * 0.265f;
        float cy = top + size * 0.245f;
        float outer = size * 0.105f;
        float inner = outer * 0.44f;
        star.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = (i % 2 == 0) ? outer : inner;
            float x = cx + (float)Math.cos(a) * rr;
            float y = cy + (float)Math.sin(a) * rr;
            if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.close();
        paint.setColor(Color.rgb(241, 200, 75));
        canvas.drawPath(star, paint);
    }
}
