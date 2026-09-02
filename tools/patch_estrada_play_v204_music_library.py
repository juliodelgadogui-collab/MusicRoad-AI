#!/usr/bin/env python3
from pathlib import Path

APP = Path('estrada-play-comunista-app')
BUILD = APP / 'app/build.gradle'
PLAYER = APP / 'app/src/main/java/com/estradaplay/comunista/MusicPlayerActivity.java'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing marker: {label}')
    return text.replace(old, new, 1)

# Version bump.
build = BUILD.read_text(encoding='utf-8')
build = replace_once(build, 'versionCode 203', 'versionCode 204', 'versionCode 203')
build = replace_once(build, "versionName '2.0.3'", "versionName '2.0.4'", 'versionName 2.0.3')
BUILD.write_text(build, encoding='utf-8')

p = PLAYER.read_text(encoding='utf-8')

p = replace_once(
    p,
    'private LinearLayout root, listBox; private TextView title,artist,state,count; private LibraryStore library; private boolean registered;',
    'private LinearLayout root, listBox; private ScrollView musicScroll; private TextView title,artist,state,count; private LibraryStore library; private boolean registered;',
    'player fields')

p = replace_once(
    p,
    'ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));\n        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(14),dp(18),dp(24));scroll.addView(root,new ScrollView.LayoutParams(-1,-2));',
    'musicScroll=new ScrollView(this);musicScroll.setFillViewport(true);musicScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);frame.addView(musicScroll,new FrameLayout.LayoutParams(-1,-1));\n        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(14),dp(18),dp(24));musicScroll.addView(root,new ScrollView.LayoutParams(-1,-2));',
    'main music scroll')

p = replace_once(
    p,
    'Button manage=small("BIBLIOTECA");head.addView(manage,new LinearLayout.LayoutParams(dp(112),dp(46)));manage.setOnClickListener(v->openLibrary());root.addView(head);',
    '// MUSIC_LIBRARY_NAV_V204: Biblioteca means the local library, never the download manager.\n        Button manage=small("BIBLIOTECA");head.addView(manage,new LinearLayout.LayoutParams(dp(112),dp(46)));manage.setOnClickListener(v->showOfflineLibrary());root.addView(head);',
    'library button')

p = replace_once(
    p,
    'LinearLayout player=playerCard();LinearLayout.LayoutParams pp=landscape?new LinearLayout.LayoutParams(0,dp(350),.42f):new LinearLayout.LayoutParams(-1,-2);body.addView(player,pp);',
    'LinearLayout player=playerCard();LinearLayout.LayoutParams pp=landscape?new LinearLayout.LayoutParams(0,dp(350),.42f):new LinearLayout.LayoutParams(-1,dp(350));body.addView(player,pp);',
    'portrait player height')

p = replace_once(
    p,
    'Button add=small("ADICIONAR MÚSICAS");LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(50));ap.setMargins(0,dp(10),0,0);empty.addView(add,ap);add.setOnClickListener(v->openLibrary());listBox.addView(empty);return;}',
    'Button add=small("ADICIONAR / BAIXAR MÚSICAS");LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(50));ap.setMargins(0,dp(10),0,0);empty.addView(add,ap);add.setOnClickListener(v->openDownloads());listBox.addView(empty);return;}',
    'empty add music action')

p = replace_once(
    p,
    'private void openLibrary(){Intent i=new Intent(this,MainActivity.class);i.putExtra("open","library");startActivity(i);}\n    private void openStorage(){startActivity(new Intent(this,MusicStorageActivity.class));}',
    '''private void showOfflineLibrary(){
        if(musicScroll==null||listBox==null||root==null)return;
        listBox.post(()->{
            try{
                Rect r=new Rect();
                listBox.getDrawingRect(r);
                root.offsetDescendantRectToMyCoords(listBox,r);
                musicScroll.smoothScrollTo(0,Math.max(0,r.top-dp(12)));
            }catch(Throwable ignored){musicScroll.fullScroll(View.FOCUS_DOWN);}
        });
    }
    private void openDownloads(){Intent i=new Intent(this,MainActivity.class);i.putExtra("open","library");startActivity(i);}
    private void openStorage(){startActivity(new Intent(this,MusicStorageActivity.class));}''',
    'library/download methods')

PLAYER.write_text(p, encoding='utf-8')
print('EPC 2.0.4 music library navigation patch applied')
