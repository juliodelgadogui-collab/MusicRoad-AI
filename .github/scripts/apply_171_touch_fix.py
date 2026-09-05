from pathlib import Path

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# MainActivity: preserve the existing 1.6 behavior that already prevents an
# automatic notification permission dialog during boot. Add a build marker
# without depending on a fragile three-line text sequence.
p = app / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')
if '        requestNotifications();\n' in s:
    s = s.replace(
        '        requestNotifications();\n',
        '        // TOUCH_INPUT_V171: no automatic runtime permission dialog during boot.\n',
        1,
    )
elif 'TOUCH_INPUT_V171' not in s:
    anchor = '        // Permissions are requested from inside the automotive cockpit.\n'
    if anchor in s:
        s = s.replace(
            anchor,
            '        // TOUCH_INPUT_V171: no automatic runtime permission dialog during boot.\n' + anchor,
            1,
        )
    else:
        boot_call = '        boot();\n'
        if boot_call not in s:
            raise SystemExit('1.7.1 MainActivity boot marker anchor not found')
        s = s.replace(
            boot_call,
            '        // TOUCH_INPUT_V171: no automatic runtime permission dialog during boot.\n' + boot_call,
            1,
        )
p.write_text(s, encoding='utf-8')

# RoadMapActivity: route cockpit button taps before MapLibre/overlay dispatch.
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')

field_anchor = '''    private int lastWidth;
    private int lastHeight;
    private boolean receiverRegistered;
'''
field_new = '''    private int lastWidth;
    private int lastHeight;
    private boolean receiverRegistered;

    // TOUCH_INPUT_V171: explicit cockpit hit targets. Automotive Android builds sometimes
    // dispatch touch to a texture/map layer before sibling controls. The Activity sees the
    // event first and routes only real button regions; map gestures remain untouched elsewhere.
    private final java.util.ArrayList<View> touchTargets = new java.util.ArrayList<>();
    private View routedTouchTarget;
'''
if field_anchor not in s:
    raise SystemExit('1.7.1 RoadMapActivity field anchor not found')
s = s.replace(field_anchor, field_new, 1)

build_anchor = '''        root.removeAllViews();

        float ratio = height <= 0 ? 1.8f : (float)width / (float)height;
'''
build_new = '''        root.removeAllViews();
        touchTargets.clear();
        routedTouchTarget = null;

        float ratio = height <= 0 ? 1.8f : (float)width / (float)height;
'''
if build_anchor not in s:
    raise SystemExit('1.7.1 RoadMapActivity rebuild anchor not found')
s = s.replace(build_anchor, build_new, 1)

nav_anchor = '''        b.setStateListAnimator(null);
        b.setBackground(panel(16, active ? ACCENT_SOFT : Color.TRANSPARENT, active ? ACCENT : BORDER));
        return b;
    }

    private Button action(String text, boolean primary) {
'''
nav_new = '''        b.setStateListAnimator(null);
        b.setBackground(panel(16, active ? ACCENT_SOFT : Color.TRANSPARENT, active ? ACCENT : BORDER));
        registerTouchTarget(b);
        return b;
    }

    private Button action(String text, boolean primary) {
'''
if nav_anchor not in s:
    raise SystemExit('1.7.1 nav button anchor not found')
s = s.replace(nav_anchor, nav_new, 1)

action_anchor = '''        b.setStateListAnimator(null);
        b.setBackground(panel(15, primary ? ACCENT : Color.argb(235, 18, 25, 33), primary ? 0 : BORDER));
        return b;
    }

    private void refreshMapData(double lat, double lon, int reportedCount) {
'''
action_new = '''        b.setStateListAnimator(null);
        b.setBackground(panel(15, primary ? ACCENT : Color.argb(235, 18, 25, 33), primary ? 0 : BORDER));
        registerTouchTarget(b);
        return b;
    }

    private void registerTouchTarget(View view) {
        if (view != null) touchTargets.add(view);
    }

    private boolean touchInside(View view, float rawX, float rawY) {
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

    private void refreshMapData(double lat, double lon, int reportedCount) {
'''
if action_anchor not in s:
    raise SystemExit('1.7.1 action button anchor not found')
s = s.replace(action_anchor, action_new, 1)
p.write_text(s, encoding='utf-8')

# RoadMapView: decorative hazard overlay must never become a touch target.
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapView.java'
s = p.read_text(encoding='utf-8')
overlay_anchor = '''            setWillNotDraw(false);
            setClickable(false);
            ring.setStyle(Paint.Style.STROKE);
'''
overlay_new = '''            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ring.setStyle(Paint.Style.STROKE);
'''
if overlay_anchor not in s:
    raise SystemExit('1.7.1 hazard overlay constructor anchor not found')
s = s.replace(overlay_anchor, overlay_new, 1)

draw_anchor = '''        @Override protected void onDraw(Canvas c) {
'''
draw_new = '''        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            return false;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
'''
if draw_anchor not in s:
    raise SystemExit('1.7.1 hazard overlay draw anchor not found')
s = s.replace(draw_anchor, draw_new, 1)
p.write_text(s, encoding='utf-8')

# Distinguish this input fix from the 1.7.0 redesign build.
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 12', 'versionCode 13')
s = s.replace("versionName '1.7.0'", "versionName '1.7.1'")
if "versionName '1.7.1'" not in s or 'versionCode 13' not in s:
    raise SystemExit('1.7.1 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.1 touch-safe input patch applied')
