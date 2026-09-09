package com.estradaplay.comunista;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.Formatter;
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

/** Premium local dashcam library. Keeps legacy-folder reads so older recordings are never lost. */
public final class VideoLibraryActivity extends ComponentActivity {
    private static final String NEW_FOLDER = Environment.DIRECTORY_MOVIES + "/EstradaPlay/";
    private static final String LEGACY_FOLDER = Environment.DIRECTORY_MOVIES + "/EstradaPlayComunista/";

    private EstradaTheme theme;

    private static final class Item {
        long id;
        String name;
        long date;
        long size;
        Uri uri;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(theme.background);
        setContentView(UnifiedAppShell.wrap(this, "central", root));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        page.addView(PremiumUi.overline(this, "DASHCAM LOCAL", theme.secondary));
        TextView title = PremiumUi.text(this, "Meus vídeos", 28, theme.text, true);
        page.addView(title);
        margins(title, 0, 4, 0, 2);
        TextView path = PremiumUi.text(this, "Galeria / Movies / EstradaPlay", 11, theme.muted, false);
        page.addView(path);
        margins(path, 0, 0, 0, 14);

        List<Item> items = queryVideos();
        TextView count = PremiumUi.text(this,
                items.size() + (items.size() == 1 ? " gravação encontrada" : " gravações encontradas"),
                11, theme.success, true);
        page.addView(count);
        margins(count, 0, 0, 0, 10);

        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(PremiumUi.text(this, "Nenhum vídeo ainda", 17, theme.text, true));
            TextView detail = PremiumUi.text(this,
                    "Depois de gravar, o arquivo aparece aqui e também na Galeria do Android.",
                    11, theme.muted, false);
            empty.addView(detail);
            margins(detail, 0, 5, 0, 0);
            page.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (Item item : items) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
                p.setMargins(0, 0, 0, dp(8));
                page.addView(videoCard(item), p);
            }
        }

        Button gallery = PremiumUi.button(this, "ABRIR GALERIA DO CELULAR", false);
        page.addView(gallery, new LinearLayout.LayoutParams(-1, dp(50)));
        margins(gallery, 0, 14, 0, 0);
        gallery.setOnClickListener(v -> openGallery());

        Button back = PremiumUi.button(this, "VOLTAR", true);
        page.addView(back, new LinearLayout.LayoutParams(-1, dp(50)));
        margins(back, 0, 8, 0, 0);
        back.setOnClickListener(v -> finish());
    }

    private LinearLayout videoCard(Item item) {
        LinearLayout card = card();
        card.addView(PremiumUi.text(this, cleanName(item.name), 15, theme.text, true));
        String meta = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(item.date * 1000L));
        if (item.size > 0) meta += "  ·  " + Formatter.formatShortFileSize(this, item.size);
        TextView detail = PremiumUi.text(this, meta, 10, theme.muted, false);
        card.addView(detail);
        margins(detail, 0, 4, 0, 10);
        Button play = PremiumUi.button(this, "REPRODUZIR", true);
        card.addView(play, new LinearLayout.LayoutParams(-1, dp(44)));
        play.setOnClickListener(v -> play(item.uri));
        return card;
    }

    private List<Item> queryVideos() {
        List<Item> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE
        };
        String selection = null;
        String[] args = null;
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "(" + MediaStore.Video.Media.RELATIVE_PATH + " = ? OR "
                    + MediaStore.Video.Media.RELATIVE_PATH + " = ?)";
            args = new String[]{NEW_FOLDER, LEGACY_FOLDER};
        } else {
            projection = new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATA
            };
        }

        try (Cursor c = getContentResolver().query(collection, projection, selection, args,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int dataCol = Build.VERSION.SDK_INT < 29 ? c.getColumnIndex(MediaStore.Video.Media.DATA) : -1;
            while (c.moveToNext()) {
                if (dataCol >= 0) {
                    String data = c.getString(dataCol);
                    String normalized = data == null ? "" : data.replace('\\', '/');
                    if (!normalized.contains("/Movies/EstradaPlay/")
                            && !normalized.contains("/Movies/EstradaPlayComunista/")) continue;
                }
                Item i = new Item();
                i.id = c.getLong(idCol);
                i.name = c.getString(nameCol);
                i.date = c.getLong(dateCol);
                i.size = c.getLong(sizeCol);
                i.uri = ContentUris.withAppendedId(collection, i.id);
                out.add(i);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String cleanName(String raw) {
        String value = raw == null ? "Vídeo" : raw;
        if (value.startsWith("EPC-AUTO-")) return "EP-AUTO-" + value.substring("EPC-AUTO-".length());
        if (value.startsWith("EPC-SALVO-")) return "EP-SALVO-" + value.substring("EPC-SALVO-".length());
        if (value.startsWith("EPC-")) return "EP-" + value.substring("EPC-".length());
        return value;
    }

    private void play(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "Nenhum player de vídeo disponível.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "Abra a Galeria e procure Movies/EstradaPlay.", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout card() {
        LinearLayout v = PremiumUi.col(this);
        v.setPadding(dp(14), dp(13), dp(14), dp(13));
        v.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        return v;
    }

    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(p);
    }

    private int dp(float value) { return PremiumUi.dp(this, value); }
}
