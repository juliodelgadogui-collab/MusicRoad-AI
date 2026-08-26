from pathlib import Path

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# The 1.7.1 workaround used raw screen coordinates. Some automotive Android
# builds expose a compatibility-scaled/virtual display where raw coordinates do
# not match View#getGlobalVisibleRect(), so every hit test misses. Route using
# window-local coordinates and descendant rectangles instead.
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')

old = '''    private boolean touchInside(View view, float rawX, float rawY) {
        if (view == null || !view.isShown() || !view.isEnabled()) return false;
        android.graphics.Rect rect = new android.graphics.Rect();
        return view.getGlobalVisibleRect(rect) && rect.contains(Math.round(rawX), Math.round(rawY));
    }

    private View findTouchTarget(float rawX, float rawY) {
        for (int i = touchTargets.size() - 1; i >= 0; i--) {
            View view = touchTargets.get(i);
            if (view != null && view.hasOnClickListeners() && touchInside(view, rawX, rawY)) return view;
        }
        return null;
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event == null) return false;
        final int action = event.getActionMasked();
        final float rawX = event.getRawX();
        final float rawY = event.getRawY();

        if (action == android.view.MotionEvent.ACTION_DOWN) {
            routedTouchTarget = findTouchTarget(rawX, rawY);
            if (routedTouchTarget != null) {
                routedTouchTarget.setPressed(true);
                return true;
            }
        } else if (routedTouchTarget != null) {
            View target = routedTouchTarget;
            if (action == android.view.MotionEvent.ACTION_MOVE) {
                target.setPressed(touchInside(target, rawX, rawY));
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP) {
                boolean inside = touchInside(target, rawX, rawY);
                routedTouchTarget = null;
                target.setPressed(false);
                if (inside) target.performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_CANCEL) {
                routedTouchTarget = null;
                target.setPressed(false);
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }
'''

new = '''    // TOUCH_COMPAT_V173: window-local routing for automotive virtual displays.
    private void windowToRoot(float windowX, float windowY, float[] out) {
        if (out == null || out.length < 2) return;
        if (root == null) {
            out[0] = windowX;
            out[1] = windowY;
            return;
        }
        int[] rootLocation = new int[2];
        root.getLocationInWindow(rootLocation);
        out[0] = windowX - rootLocation[0];
        out[1] = windowY - rootLocation[1];
    }

    private boolean touchInside(View view, float rootX, float rootY) {
        if (view == null || root == null || !view.isShown() || !view.isEnabled()) return false;
        android.graphics.Rect rect = new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight());
        try {
            root.offsetDescendantRectToMyCoords(view, rect);
        } catch (Throwable ignored) {
            return false;
        }
        // A slightly larger hit box is deliberate on in-dash resistive panels.
        int slop = dp(7);
        rect.inset(-slop, -slop);
        return rect.contains(Math.round(rootX), Math.round(rootY));
    }

    private View findTouchTarget(float rootX, float rootY) {
        for (int i = touchTargets.size() - 1; i >= 0; i--) {
            View view = touchTargets.get(i);
            if (view != null && view.hasOnClickListeners() && touchInside(view, rootX, rootY)) return view;
        }
        return null;
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event == null) return false;
        final int action = event.getActionMasked();
        float[] pt = new float[2];
        windowToRoot(event.getX(), event.getY(), pt);
        final float rootX = pt[0];
        final float rootY = pt[1];

        if (action == android.view.MotionEvent.ACTION_DOWN) {
            routedTouchTarget = findTouchTarget(rootX, rootY);
            if (routedTouchTarget != null) {
                routedTouchTarget.setPressed(true);
                return true;
            }
        } else if (routedTouchTarget != null) {
            View target = routedTouchTarget;
            if (action == android.view.MotionEvent.ACTION_MOVE) {
                target.setPressed(touchInside(target, rootX, rootY));
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_UP) {
                boolean inside = touchInside(target, rootX, rootY);
                routedTouchTarget = null;
                target.setPressed(false);
                if (inside) target.performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_CANCEL) {
                routedTouchTarget = null;
                target.setPressed(false);
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {
        if (event != null && event.getActionMasked() == android.view.MotionEvent.ACTION_BUTTON_PRESS) {
            float[] pt = new float[2];
            windowToRoot(event.getX(), event.getY(), pt);
            View target = findTouchTarget(pt[0], pt[1]);
            if (target != null) {
                target.performClick();
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }
'''

if old not in s:
    raise SystemExit('1.7.3 touch routing anchor not found')
s = s.replace(old, new, 1)

# Explicitly clear non-touchable/focusable window flags in case a vendor ROM
# carries compatibility flags into the launched Activity window.
old = '''        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
'''
new = '''        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
'''
if old not in s:
    raise SystemExit('1.7.3 window flags anchor not found')
s = s.replace(old, new, 1)

# Make registered controls explicit Android touch/focus targets. This also helps
# head units that present the panel as a mouse/trackpad source instead of a
# normal touchscreen source.
old = '''    private void registerTouchTarget(View view) {
        if (view != null) touchTargets.add(view);
    }
'''
new = '''    private void registerTouchTarget(View view) {
        if (view == null) return;
        view.setClickable(true);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(10));
        touchTargets.add(view);
    }
'''
if old not in s:
    raise SystemExit('1.7.3 registerTouchTarget anchor not found')
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')

# Distinguish the real-device touch compatibility build.
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 14', 'versionCode 15')
s = s.replace("versionName '1.7.2'", "versionName '1.7.3'")
if "versionName '1.7.3'" not in s or 'versionCode 15' not in s:
    raise SystemExit('1.7.3 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.3 automotive touch compatibility fix applied')
