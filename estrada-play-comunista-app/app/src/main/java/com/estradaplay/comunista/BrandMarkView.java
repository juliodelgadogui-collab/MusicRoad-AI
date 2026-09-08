package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Estrada Play premium road mark. The old star/political symbol is intentionally gone. */
final class BrandMarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path road = new Path();
    private final Path lane = new Path();

    BrandMarkView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        EstradaTheme theme = EstradaTheme.get(getContext());
        float w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float left = (w - size) / 2f, top = (h - size) / 2f;
        float radius = size * .25f;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                theme.primary, theme.secondary, Shader.TileMode.CLAMP));
        paint.setShadowLayer(size * .12f, 0f, size * .055f, Color.argb(90, 0, 0, 0));
        canvas.drawRoundRect(new RectF(left, top, left + size, top + size), radius, radius, paint);
        paint.clearShadowLayer();
        paint.setShader(null);

        road.reset();
        road.moveTo(left + size * .34f, top + size * .82f);
        road.cubicTo(left + size * .38f, top + size * .62f,
                left + size * .45f, top + size * .44f,
                left + size * .62f, top + size * .18f);
        road.lineTo(left + size * .76f, top + size * .18f);
        road.cubicTo(left + size * .56f, top + size * .50f,
                left + size * .50f, top + size * .67f,
                left + size * .48f, top + size * .82f);
        road.close();
        paint.setColor(Color.argb(245, 250, 252, 255));
        canvas.drawPath(road, paint);

        lane.reset();
        lane.moveTo(left + size * .47f, top + size * .76f);
        lane.cubicTo(left + size * .49f, top + size * .61f,
                left + size * .55f, top + size * .44f,
                left + size * .67f, top + size * .25f);
        stroke.setColor(theme.primary);
        stroke.setStrokeWidth(Math.max(2f, size * .045f));
        canvas.drawPath(lane, stroke);

        paint.setColor(Color.argb(230, 255, 255, 255));
        canvas.drawCircle(left + size * .27f, top + size * .29f, size * .055f, paint);
    }
}
