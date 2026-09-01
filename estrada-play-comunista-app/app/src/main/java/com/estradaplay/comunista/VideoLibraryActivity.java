package com.estradaplay.comunista;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class VideoLibraryActivity extends ComponentActivity {
    private final int BG = Color.rgb(7, 4, 6);
    private final int PANEL = Color.rgb(24, 9, 13);
    private final int BORDER = Color.rgb(88, 34, 43);
    private final int TEXT = Color.rgb(247, 239, 226);
    private final int MUTED = Color.rgb(179, 154, 149);
    private final int RED = Color.rgb(196, 20, 41);
    private final int GREEN = Color.rgb(68, 213, 132);

    private static final class Item {
        long id;
        String name;
        long date;
        long size;
        Uri uri;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(UnifiedAppShell.wrap(this,"central",root));

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView over = text("ARQUIVO LOCAL", 10, RED, true); over.setLetterSpacing(0.14f); page.addView(over);
        page.addView(text("MEUS VÍDEOS", 28, TEXT, true));
        TextView path = text("Galeria / Movies / EstradaPlayComunista", 12, MUTED, false); page.addView(path); margins(path, 0, 4, 0, 14);

        List<Item> items = queryVideos();
        TextView count = text(items.size() + (items.size() == 1 ? " gravação encontrada" : " gravações encontradas"), 12, GREEN, true);
        page.addView(count); margins(count, 0, 0, 0, 10);

        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("Nenhum vídeo público ainda", 17, TEXT, true));
            empty.addView(text("Depois de gravar, o arquivo aparece aqui e também na Galeria do Android.", 12, MUTED, false));
            page.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (Item item : items) page.addView(videoCard(item), new LinearLayout.LayoutParams(-1, -2));
        }

        Button gallery = button("ABRIR GALERIA DO CELULAR", false);
        page.addView(gallery, new LinearLayout.LayoutParams(-1, dp(52))); margins(gallery, 0, 16, 0, 0);
        gallery.setOnClickListener(v -> openGallery());
        Button back = button("VOLTAR", true);
        page.addView(back, new LinearLayout.LayoutParams(-1, dp(52))); margins(back, 0, 8, 0, 0);
        back.setOnClickListener(v -> finish());
    }

    private LinearLayout videoCard(Item item) {
        LinearLayout card = card();
        TextView name = text(item.name, 15, TEXT, true); card.addView(name);
        String meta = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(item.date * 1000L));
        if (item.size > 0) meta += "  ·  " + Formatter.formatShortFileSize(this, item.size);
        TextView detail = text(meta, 11, MUTED, false); card.addView(detail); margins(detail, 0, 3, 0, 10);
        Button play = button("REPRODUZIR", true); card.addView(play, new LinearLayout.LayoutParams(-1, dp(46)));
        play.setOnClickListener(v -> play(item.uri));
        return card;
    }

    private List<Item> queryVideos() {
        List<Item> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE};
        String selection = null;
        String[] args = null;
        if (Build.VERSION.SDK_INT >= 29) {
            selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{Environment.DIRECTORY_MOVIES + "/EstradaPlayComunista/%"};
        } else {
            projection = new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATA};
        }
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int dataCol = Build.VERSION.SDK_INT < 29 ? c.getColumnIndex(MediaStore.Video.Media.DATA) : -1;
            while (c.moveToNext()) {
                if (dataCol >= 0) {
                    String data = c.getString(dataCol);
                    if (data == null || !data.replace('\\', '/').contains("/Movies/EstradaPlayComunista/")) continue;
                }
                Item i = new Item();
                i.id = c.getLong(idCol); i.name = c.getString(nameCol); i.date = c.getLong(dateCol); i.size = c.getLong(sizeCol);
                i.uri = ContentUris.withAppendedId(collection, i.id);
                out.add(i);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void play(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable e) { Toast.makeText(this, "Nenhum player de vídeo disponível.", Toast.LENGTH_SHORT).show(); }
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "Abra a Galeria e procure Movies/EstradaPlayComunista.", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(14), dp(13), dp(14), dp(13));
        v.setBackground(box(PANEL, 14, BORDER));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(8)); v.setLayoutParams(p);
        return v;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setTextSize(12); b.setTextColor(TEXT); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setAllCaps(false);
        b.setBackground(box(primary ? RED : Color.rgb(24, 17, 20), 14, primary ? RED : BORDER)); return b;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(0, 1.08f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private GradientDrawable box(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return; LinearLayout.LayoutParams p = (LinearLayout.LayoutParams)v.getLayoutParams(); p.setMargins(dp(l), dp(t), dp(r), dp(b)); v.setLayoutParams(p);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
