package com.pocketplayer.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native helpers for Pocket Player:
 *  - scan(): finds every song and video on the phone (MediaStore)
 *  - thumbs(): makes album-art / video thumbnails, reported through the "thumb" event
 *  - audioOutput(): name of connected Bluetooth earbuds, or "" (plus an "audioDevices" event on changes)
 *  - keepAlive(): starts/stops the foreground service that keeps playback running with the screen off
 */
@CapacitorPlugin(
        name = "PocketNative",
        permissions = {
                @Permission(alias = "audio", strings = {"android.permission.READ_MEDIA_AUDIO"}),
                @Permission(alias = "video", strings = {"android.permission.READ_MEDIA_VIDEO"}),
                @Permission(alias = "storage", strings = {Manifest.permission.READ_EXTERNAL_STORAGE}),
                @Permission(alias = "write", strings = {Manifest.permission.WRITE_EXTERNAL_STORAGE})
        }
)
public class PocketNativePlugin extends Plugin {

    private static final String[] COLUMNS = {
            "_id", "_display_name", "title", "artist", "album", "duration", "mime_type", "_size", "date_added", "_data"
    };

    @Override
    public void load() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    emitDevices();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    emitDevices();
                }
            }, null);
        }
    }

    /* ---------------- media scan ---------------- */

    private String[] mediaAliases() {
        return Build.VERSION.SDK_INT >= 33 ? new String[]{"audio", "video"} : new String[]{"storage"};
    }

    private boolean granted(String alias) {
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    @PluginMethod
    public void scan(PluginCall call) {
        boolean all = true;
        for (String a : mediaAliases()) {
            if (!granted(a)) all = false;
        }
        if (!all) {
            requestPermissionForAliases(mediaAliases(), call, "scanPermissionsResult");
            return;
        }
        runScan(call);
    }

    @PermissionCallback
    private void scanPermissionsResult(PluginCall call) {
        runScan(call);
    }

    private void runScan(final PluginCall call) {
        final boolean audioOk = Build.VERSION.SDK_INT >= 33 ? granted("audio") : granted("storage");
        final boolean videoOk = Build.VERSION.SDK_INT >= 33 ? granted("video") : audioOk;
        if (!audioOk && !videoOk) {
            call.reject("Permission to read music and videos was not given", "DENIED");
            return;
        }
        new Thread(() -> {
            try {
                JSArray items = new JSArray();
                if (audioOk) query(items, "audio");
                if (videoOk) query(items, "video");
                JSObject ret = new JSObject();
                ret.put("items", items);
                ret.put("audio", audioOk);
                ret.put("video", videoOk);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Scan failed: " + e.getMessage());
            }
        }).start();
    }

    private static String str(Cursor c, int i) {
        if (i < 0) return "";
        String s = c.getString(i);
        return s == null ? "" : s;
    }

    private static long num(Cursor c, int i) {
        if (i < 0 || c.isNull(i)) return 0;
        return c.getLong(i);
    }

    private void query(JSArray out, String kind) throws Exception {
        boolean isAudio = kind.equals("audio");
        Uri base = isAudio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String selection = isAudio ? "(is_music != 0 OR is_podcast != 0)" : null;
        Cursor c = getContext().getContentResolver().query(base, COLUMNS, selection, null, "date_added DESC");
        if (c == null) return;
        try {
            int iId = c.getColumnIndex("_id");
            int iName = c.getColumnIndex("_display_name");
            int iTitle = c.getColumnIndex("title");
            int iArtist = c.getColumnIndex("artist");
            int iAlbum = c.getColumnIndex("album");
            int iDur = c.getColumnIndex("duration");
            int iMime = c.getColumnIndex("mime_type");
            int iSize = c.getColumnIndex("_size");
            int iAdded = c.getColumnIndex("date_added");
            int iData = c.getColumnIndex("_data");
            while (c.moveToNext()) {
                long id = num(c, iId);
                JSObject o = new JSObject();
                o.put("id", id);
                o.put("kind", kind);
                o.put("name", str(c, iName));
                o.put("title", str(c, iTitle));
                o.put("artist", str(c, iArtist));
                o.put("album", str(c, iAlbum));
                o.put("duration", num(c, iDur));
                o.put("mime", str(c, iMime));
                o.put("size", num(c, iSize));
                o.put("added", num(c, iAdded));
                o.put("path", str(c, iData));
                o.put("uri", ContentUris.withAppendedId(base, id).toString());
                out.put(o);
            }
        } finally {
            c.close();
        }
    }

    /* ---------------- thumbnails ---------------- */

    @PluginMethod
    public void thumbs(PluginCall call) {
        final JSArray list = call.getArray("items");
        call.resolve();
        if (list == null) return;
        new Thread(() -> {
            File dir = new File(getContext().getCacheDir(), "thumbs");
            if (!dir.exists()) dir.mkdirs();
            for (int i = 0; i < list.length(); i++) {
                try {
                    JSONObject it = list.getJSONObject(i);
                    String key = it.getString("key");
                    long id = it.getLong("id");
                    String kind = it.getString("kind");
                    File f = new File(dir, kind + "_" + id + ".jpg");
                    File none = new File(dir, kind + "_" + id + ".none");
                    if (none.exists()) continue;
                    if (!f.exists() || f.length() == 0) {
                        Bitmap b = makeThumb(kind, id);
                        if (b == null) {
                            none.createNewFile();
                            continue;
                        }
                        FileOutputStream fo = new FileOutputStream(f);
                        try {
                            b.compress(Bitmap.CompressFormat.JPEG, 82, fo);
                        } finally {
                            fo.close();
                        }
                    }
                    JSObject ev = new JSObject();
                    ev.put("key", key);
                    ev.put("path", f.getAbsolutePath());
                    notifyListeners("thumb", ev);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private Bitmap makeThumb(String kind, long id) {
        boolean isAudio = kind.equals("audio");
        Uri base = isAudio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri uri = ContentUris.withAppendedId(base, id);
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return getContext().getContentResolver().loadThumbnail(uri, new Size(256, 256), null);
            } catch (Exception ignored) {
            }
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(getContext(), uri);
            if (isAudio) {
                byte[] art = r.getEmbeddedPicture();
                if (art != null) return scale(BitmapFactory.decodeByteArray(art, 0, art.length));
            } else {
                return scale(r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Bitmap scale(Bitmap b) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        float k = 256f / Math.max(w, h);
        if (k >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
    }

    /* ---------------- Bluetooth earbuds ---------------- */

    @PluginMethod
    public void audioOutput(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("bluetooth", bluetoothName());
        call.resolve(ret);
    }

    private void emitDevices() {
        JSObject ev = new JSObject();
        ev.put("bluetooth", bluetoothName());
        notifyListeners("audioDevices", ev);
    }

    private String bluetoothName() {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "";
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            boolean bt = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    || (Build.VERSION.SDK_INT >= 28 && t == AudioDeviceInfo.TYPE_HEARING_AID)
                    || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
            if (bt) {
                CharSequence n = d.getProductName();
                return n != null && n.length() > 0 ? n.toString() : "Bluetooth earbuds";
            }
        }
        return "";
    }

    /* ---------------- background playback ---------------- */

    @PluginMethod
    public void keepAlive(PluginCall call) {
        Boolean onObj = call.getBoolean("on", false);
        Boolean micObj = call.getBoolean("mic", false);
        boolean on = onObj != null && onObj;
        boolean mic = micObj != null && micObj;
        String title = call.getString("title", "Pocket Player");
        String text = call.getString("text", "");
        Context ctx = getContext();
        Intent intent = new Intent(ctx, PlaybackService.class);
        try {
            if (on) {
                if (PlaybackService.running && (!mic || PlaybackService.micActive)) {
                    PlaybackService.update(ctx, title, text);
                } else {
                    intent.putExtra("title", title);
                    intent.putExtra("text", text);
                    intent.putExtra("mic", mic);
                    ContextCompat.startForegroundService(ctx, intent);
                }
            } else {
                ctx.stopService(intent);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Background playback couldn't start: " + e.getMessage());
        }
    }

    /* ================= online: web requests ================= */

    private static String readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total > max) break;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Fetches a web page or API answer as text (radio stations, podcasts, lyrics). */
    @PluginMethod
    public void webGet(final PluginCall call) {
        final String url = call.getString("url", "");
        new Thread(() -> {
            JSObject ret = new JSObject();
            HttpURLConnection c = null;
            try {
                c = StreamProxy.connect(url, null, 20000);
                int code = c.getResponseCode();
                ret.put("status", code);
                if (code >= 400) { ret.put("ok", false); ret.put("error", "HTTP " + code); }
                else { ret.put("ok", true); ret.put("text", readAll(c.getInputStream(), 8_000_000)); }
            } catch (Exception e) {
                ret.put("ok", false); ret.put("error", "Couldn't connect");
            } finally {
                if (c != null) c.disconnect();
            }
            call.resolve(ret);
        }).start();
    }

    /** Looks at a link before downloading: what kind of file it is, or the page it opens. */
    @PluginMethod
    public void webHead(final PluginCall call) {
        final String url = call.getString("url", "");
        new Thread(() -> {
            JSObject ret = new JSObject();
            HttpURLConnection c = null;
            try {
                c = StreamProxy.connect(url, null, 20000);
                int code = c.getResponseCode();
                String type = c.getContentType();
                ret.put("status", code);
                ret.put("ok", code < 400);
                ret.put("url", c.getURL().toString());
                ret.put("type", type == null ? "" : type);
                String cl = c.getHeaderField("Content-Length");
                long len = -1;
                try { if (cl != null) len = Long.parseLong(cl.trim()); } catch (Exception ignored) { }
                ret.put("length", len);
                String cd = c.getHeaderField("Content-Disposition");
                ret.put("disposition", cd == null ? "" : cd);
                if (code < 400 && type != null && (type.contains("html") || type.contains("xml") || type.contains("rss"))) {
                    ret.put("body", readAll(c.getInputStream(), 3_000_000));
                }
            } catch (Exception e) {
                ret.put("ok", false); ret.put("status", 0);
            } finally {
                if (c != null) c.disconnect();
            }
            call.resolve(ret);
        }).start();
    }

    /* ================= downloads (Android's download manager) ================= */

    private static String cleanName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 150) s = s.substring(0, 150);
        return s.isEmpty() ? "download" : s;
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    private static boolean isVideoName(String name) {
        String e = extOf(name);
        return e.equals("mp4") || e.equals("m4v") || e.equals("mov") || e.equals("webm") || e.equals("mkv") || e.equals("3gp") || e.equals("avi");
    }

    private final Set<Long> scanned = new HashSet<>();

    @PluginMethod
    public void dlEnqueue(PluginCall call) {
        String url = call.getString("url", "");
        String name = cleanName(call.getString("name", "download"));
        String subdir = call.getString("subdir", "");
        Boolean mediaObj = call.getBoolean("media", true);
        boolean media = mediaObj != null && mediaObj;
        try {
            DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.addRequestHeader("User-Agent", StreamProxy.UA);
            r.setTitle(name);
            r.setDescription("Pocket Player");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            StringBuilder rel = new StringBuilder();
            if (media) {
                rel.append("Pocket Player/");
                for (String part : subdir.split("[\\\\/]+")) if (!part.trim().isEmpty()) rel.append(cleanName(part)).append('/');
            }
            rel.append(name);
            String dir = !media ? Environment.DIRECTORY_DOWNLOADS : isVideoName(name) ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC;
            r.setDestinationInExternalPublicDir(dir, rel.toString());
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name));
            if (mime != null) r.setMimeType(mime);
            if (Build.VERSION.SDK_INT < 29) r.allowScanningByMediaScanner();
            long id = dm.enqueue(r);
            JSObject ret = new JSObject();
            ret.put("id", String.valueOf(id));
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Couldn't start the download: " + e.getMessage());
        }
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "There isn't enough space on this phone.";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "The phone's storage isn't available.";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "A file with that name already exists.";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS: return "The link redirected too many times.";
            case DownloadManager.ERROR_HTTP_DATA_ERROR: return "The connection dropped. Press Retry.";
            case DownloadManager.ERROR_CANNOT_RESUME: return "The download couldn't continue. Press Retry.";
            default:
                if (reason >= 400 && reason < 600) return "The website said no (error " + reason + ").";
                return "The download stopped.";
        }
    }

    @PluginMethod
    public void dlStatus(PluginCall call) {
        JSArray ids = call.getArray("ids");
        JSArray out = new JSArray();
        try {
            if (ids == null || ids.length() == 0) { JSObject r = new JSObject(); r.put("items", out); call.resolve(r); return; }
            long[] list = new long[ids.length()];
            for (int i = 0; i < ids.length(); i++) list[i] = Long.parseLong(ids.getString(i));
            DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(list));
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        long id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                        int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        String local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                        JSObject o = new JSObject();
                        o.put("id", String.valueOf(id));
                        o.put("received", got);
                        o.put("total", total);
                        String status = st == DownloadManager.STATUS_SUCCESSFUL ? "done" : st == DownloadManager.STATUS_FAILED ? "failed"
                                : st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PAUSED ? "running" : "queued";
                        o.put("status", status);
                        if (st == DownloadManager.STATUS_FAILED) o.put("reason", reasonText(reason));
                        if (local != null) {
                            String path = Uri.parse(local).getPath();
                            o.put("path", path == null ? "" : path);
                            if (st == DownloadManager.STATUS_SUCCESSFUL && path != null && !scanned.contains(id)) {
                                scanned.add(id);
                                MediaScannerConnection.scanFile(getContext(), new String[]{path}, null, null);
                            }
                        }
                        out.put(o);
                    }
                } finally {
                    c.close();
                }
            }
            JSObject r = new JSObject();
            r.put("items", out);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Couldn't read download progress");
        }
    }

    @PluginMethod
    public void dlCancel(PluginCall call) {
        try {
            DownloadManager dm = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
            dm.remove(Long.parseLong(call.getString("id", "0")));
        } catch (Exception ignored) {
        }
        call.resolve();
    }

    /* ================= small text files (lyrics) ================= */

    private File textFile(String key) {
        String k = key == null ? "" : key.replaceAll("[^A-Za-z0-9._-]", "_");
        File dir = new File(getContext().getFilesDir(), "text");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, k.isEmpty() ? "_" : k);
    }

    @PluginMethod
    public void readText(PluginCall call) {
        JSObject ret = new JSObject();
        try {
            File f = textFile(call.getString("key", ""));
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                try { ret.put("text", readAll(in, 2_000_000)); } finally { in.close(); }
            }
        } catch (Exception ignored) {
        }
        call.resolve(ret);
    }

    @PluginMethod
    public void writeText(PluginCall call) {
        try {
            File f = textFile(call.getString("key", ""));
            String text = call.getString("text", "");
            if (text == null || text.trim().isEmpty()) { f.delete(); call.resolve(); return; }
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(text.getBytes(StandardCharsets.UTF_8)); } finally { out.close(); }
            call.resolve();
        } catch (Exception e) {
            call.reject("Couldn't save");
        }
    }

    /* ================= songs sent from another device ================= */

    private static class Incoming {
        File file;
        FileOutputStream out;
        String name;
    }

    private final Map<String, Incoming> incoming = new HashMap<>();

    @PluginMethod
    public void recvOpen(PluginCall call) {
        try {
            File dir = new File(getContext().getCacheDir(), "incoming");
            if (!dir.exists()) dir.mkdirs();
            Incoming in = new Incoming();
            in.name = cleanName(call.getString("name", "song"));
            in.file = new File(dir, UUID.randomUUID().toString());
            in.out = new FileOutputStream(in.file);
            String id = UUID.randomUUID().toString();
            synchronized (incoming) { incoming.put(id, in); }
            JSObject ret = new JSObject();
            ret.put("id", id);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Couldn't start saving");
        }
    }

    @PluginMethod
    public void recvWrite(PluginCall call) {
        Incoming in;
        synchronized (incoming) { in = incoming.get(call.getString("id", "")); }
        if (in == null) { call.reject("closed"); return; }
        try {
            byte[] data = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            synchronized (in) { in.out.write(data); }
            call.resolve();
        } catch (Exception e) {
            call.reject("Couldn't write");
        }
    }

    @PluginMethod
    public void recvAbort(PluginCall call) {
        Incoming in;
        synchronized (incoming) { in = incoming.remove(call.getString("id", "")); }
        if (in != null) {
            try { in.out.close(); } catch (Exception ignored) { }
            in.file.delete();
        }
        call.resolve();
    }

    @PluginMethod
    public void recvClose(PluginCall call) {
        if (Build.VERSION.SDK_INT < 29 && !granted("write")) {
            requestPermissionForAlias("write", call, "recvClosePermission");
            return;
        }
        finishIncoming(call);
    }

    @PermissionCallback
    private void recvClosePermission(PluginCall call) {
        finishIncoming(call);
    }

    private void finishIncoming(final PluginCall call) {
        final Incoming in;
        synchronized (incoming) { in = incoming.remove(call.getString("id", "")); }
        if (in == null) { call.reject("closed"); return; }
        final String kind = call.getString("kind", "audio");
        new Thread(() -> {
            try {
                in.out.close();
                boolean video = "video".equals(kind) || isVideoName(in.name);
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(in.name));
                if (mime == null) mime = video ? "video/mp4" : "audio/mpeg";
                String savedName = in.name;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContext().getContentResolver();
                    Uri coll = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            : MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, in.name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC) + "/Pocket Player/Received");
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    Uri item = cr.insert(coll, v);
                    if (item == null) throw new Exception("insert failed");
                    OutputStream os = cr.openOutputStream(item);
                    FileInputStream is = new FileInputStream(in.file);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        is.close();
                        if (os != null) os.close();
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    cr.update(item, done, null, null);
                    try {
                        Cursor q = cr.query(item, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                        if (q != null) { if (q.moveToFirst()) savedName = q.getString(0); q.close(); }
                    } catch (Exception ignored) {
                    }
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_MUSIC), "Pocket Player/Received");
                    if (!dir.exists()) dir.mkdirs();
                    File dest = new File(dir, in.name);
                    String base = in.name, ext = "";
                    int dot = in.name.lastIndexOf('.');
                    if (dot > 0) { base = in.name.substring(0, dot); ext = in.name.substring(dot); }
                    for (int i = 2; dest.exists(); i++) dest = new File(dir, base + " (" + i + ")" + ext);
                    FileInputStream is = new FileInputStream(in.file);
                    FileOutputStream os = new FileOutputStream(dest);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    } finally {
                        is.close();
                        os.close();
                    }
                    savedName = dest.getName();
                    MediaScannerConnection.scanFile(getContext(), new String[]{dest.getAbsolutePath()}, null, null);
                }
                in.file.delete();
                JSObject ret = new JSObject();
                ret.put("name", savedName);
                call.resolve(ret);
            } catch (Exception e) {
                in.file.delete();
                call.reject("Couldn't save the file: " + e.getMessage());
            }
        }).start();
    }

    /* ================= keep the screen on (drive mode) ================= */

    @PluginMethod
    public void screenOn(final PluginCall call) {
        Boolean onObj = call.getBoolean("on", false);
        final boolean on = onObj != null && onObj;
        if (getActivity() == null) { call.resolve(); return; }
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            call.resolve();
        });
    }
}
