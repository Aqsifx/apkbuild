package com.mt5.replaystudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import android.media.MediaScannerConnection;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILE = 1001;
    private static final int REQ_MIC = 1002;
    private static final int REQ_STORAGE = 1003;

    private WebView web;
    private WebViewAssetLoader loader;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private long lastBack = 0;

    /** Page me inject hota hai: blob downloads (PNG, CSV, JSON, video) ko Android Downloads me save karta hai. */
    private static final String INJECT_JS =
            "(function(){if(window.__abHooked)return;window.__abHooked=1;" +
            "var blobs={};var co=URL.createObjectURL;" +
            "URL.createObjectURL=function(b){var u=co.call(URL,b);blobs[u]=b;return u};" +
            "var oc=HTMLAnchorElement.prototype.click;" +
            "HTMLAnchorElement.prototype.click=function(){" +
            " var a=this;" +
            " if(a.hasAttribute('download')&&a.href&&a.href.indexOf('blob:')===0){" +
            "  var send=function(b){var fr=new FileReader();fr.onload=function(){" +
            "   var s=String(fr.result),i=s.indexOf(','),d=s.substring(i+1),n=a.download||('file-'+Date.now());" +
            "   AndroidBridge.begin(n,b.type||'application/octet-stream');" +
            "   var C=786432;for(var p=0;p<d.length;p+=C){AndroidBridge.chunk(d.substring(p,p+C))}" +
            "   AndroidBridge.end()};fr.readAsDataURL(b)};" +
            "  var b=blobs[a.href];" +
            "  if(b)send(b);else fetch(a.href).then(function(r){return r.blob()}).then(send);" +
            "  return}" +
            " return oc.call(a)};" +
            "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF10141C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage
        s.setDatabaseEnabled(true);            // IndexedDB
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // App ke andar hi rahe; bahar ke links system browser me
                if (HOST.equals(r.getUrl().getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                v.evaluateJavascript(INJECT_JS, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(INJECT_JS, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // confirm()/alert() - app account delete ke liye confirm() use karti hai
            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton(android.R.string.ok, (d, w) -> r.confirm())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            // CSV / logo / JSON import ke liye file picker
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                boolean images = false;
                String[] acc = p.getAcceptTypes();
                if (acc != null) {
                    for (String a : acc) if (a != null && a.startsWith("image/")) images = true;
                }
                // CSV/JSON ke liye "*/*" - warna kai file managers .csv ko grey kar dete hain
                i.setType(images ? "image/*" : "*/*");
                if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "File chunein"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }

            // Mic (audio record) permission
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> {
                    if (!HOST.equals(req.getOrigin().getHost())) { req.deny(); return; }
                    boolean wantsMic = false;
                    for (String r : req.getResources())
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                    if (!wantsMic) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingPermission = req;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) { return true; }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_MIC && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else
                pendingPermission.deny();
            pendingPermission = null;
        }
        super.onRequestPermissionsResult(req, perms, results);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "Bahar nikalne ke liye dobara Back dabayein", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidBridge");
            web.destroy();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // JS -> Android file save bridge (chunked). Video -> MP4 (H.264+AAC) -> Gallery
    // ------------------------------------------------------------------
    private class Bridge {
        private OutputStream os;
        private File tmp;
        private String name = "file";
        private String mime = "application/octet-stream";
        private final Handler ui = new Handler(Looper.getMainLooper());

        private void toast(String m) {
            ui.post(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show());
        }

        private String clean(String n) {
            n = n == null ? "file" : n.replaceAll("[\\\\/:*?\"<>|]", "_");
            return n.isEmpty() ? "file" : n;
        }

        /** "level|charging" e.g. "83|1" */
        @JavascriptInterface
        public String battery() {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return "100|0";
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100), sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean ch = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            return (l * 100 / Math.max(1, sc)) + "|" + (ch ? "1" : "0");
        }

        @JavascriptInterface
        public synchronized void begin(String fileName, String mimeType) {
            closeQuiet();
            name = clean(fileName);
            mime = mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType;
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> requestPermissions(
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
                    toast("Storage permission de kar dobara save karein");
                    os = null;
                    return;
                }
                tmp = File.createTempFile("rec", ".tmp", getCacheDir());
                os = new FileOutputStream(tmp);
            } catch (Exception e) {
                os = null;
                toast("Save fail: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public synchronized void chunk(String b64) {
            if (os == null) return;
            try {
                os.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                closeQuiet();
                toast("Save fail: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public synchronized void end() {
            if (os == null) return;
            try {
                os.flush();
                os.close();
            } catch (Exception e) {
                toast("Save fail: " + e.getMessage());
                os = null;
                return;
            }
            os = null;
            final File f = tmp;
            final String n = name, m = mime;
            tmp = null;
            new Thread(() -> finish(f, n, m)).start();
        }

        private void finish(File src, String n, String m) {
            File work = src;
            String outName = n, outMime = m;
            try {
                if (m.startsWith("video/")) {
                    toast("Video MP4 me convert ho rahi hai, thora intezar karein...");
                    String base = n.contains(".") ? n.substring(0, n.lastIndexOf('.')) : n;
                    File out = new File(getCacheDir(), "conv_" + System.nanoTime() + ".mp4");
                    try {
                        if (m.contains("mp4")) Mp4Tools.remux(src.getPath(), out.getPath());
                        else Mp4Tools.transcodeWebm(src.getPath(), out.getPath(), getCacheDir());
                        work = out;
                        outName = base + ".mp4";
                        outMime = "video/mp4";
                    } catch (Throwable th) {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        toast("MP4 convert fail (" + th.getMessage() + "). Asli file save ho rahi hai.");
                    }
                }
                String where = saveToStore(work, outName, outMime);
                toast("Saved: " + where + "/" + outName);
            } catch (Exception e) {
                toast("Save fail: " + e.getMessage());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                src.delete();
                if (work != src) {
                    //noinspection ResultOfMethodCallIgnored
                    work.delete();
                }
            }
        }

        /** Video -> Movies (Gallery), image -> Pictures (Gallery), baqi -> Downloads */
        private String saveToStore(File f, String fname, String mt) throws IOException {
            final String sub = "MT5 Replay Studio";
            boolean vid = mt.startsWith("video/"), img = mt.startsWith("image/");
            String dir = vid ? Environment.DIRECTORY_MOVIES : img ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri coll = vid ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : img ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mt);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + sub);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri u = getContentResolver().insert(coll, cv);
                if (u == null) throw new IOException("MediaStore insert fail");
                try (OutputStream o = getContentResolver().openOutputStream(u);
                     InputStream in = new FileInputStream(f)) {
                    copy(in, o);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(u, done, null, null);
            } else {
                File d = new File(Environment.getExternalStoragePublicDirectory(dir), sub);
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                File o = new File(d, fname);
                try (OutputStream os2 = new FileOutputStream(o); InputStream in = new FileInputStream(f)) {
                    copy(in, os2);
                }
                MediaScannerConnection.scanFile(MainActivity.this, new String[]{o.getPath()}, new String[]{mt}, null);
            }
            return (vid ? "Gallery (Movies/" : img ? "Gallery (Pictures/" : "Downloads/") + sub + (vid || img ? ")" : "");
        }

        private void copy(InputStream in, OutputStream o) throws IOException {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            o.flush();
        }

        private void closeQuiet() {
            try { if (os != null) os.close(); } catch (Exception ignored) { }
            os = null;
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                tmp = null;
            }
        }
    }
}
