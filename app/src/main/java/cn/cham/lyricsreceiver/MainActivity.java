package cn.cham.lyricsreceiver;

import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends AppCompatActivity {
    private static final int MATTE = 0xff242428;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private ImageView preview;
    private TextView emptyHint;
    private MaterialButton saveButton, shareButton;
    private File composite;
    private Bitmap displayed;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean light = (getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);
        makeUi();
        if (isShare(getIntent())) receive(getIntent()); else restore();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isShare(intent)) receive(intent);
    }

    private boolean isShare(Intent intent) {
        return intent != null && ("com.instagram.share.ADD_TO_STORY".equals(intent.getAction())
            || "com.facebook.stories.ADD_TO_STORY".equals(intent.getAction())
            || Intent.ACTION_SEND.equals(intent.getAction()));
    }

    private void makeUi() {
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        } else {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });
        }
        preview = findViewById(R.id.preview);
        emptyHint = findViewById(R.id.empty_hint);
        saveButton = findViewById(R.id.save);
        shareButton = findViewById(R.id.share);
        saveButton.setOnClickListener(v -> saveCurrent());
        shareButton.setOnClickListener(v -> shareCurrent());
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private Uri uriExtra(Intent intent, String key) {
        Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
        if (value instanceof Uri) return (Uri) value;
        if (value instanceof String && ((String) value).length() < 8192) return Uri.parse((String) value);
        return null;
    }

    private void receive(Intent intent) {
        final int token = generation.incrementAndGet();
        setBusy(true);
        final Uri front, back;
        try {
            Uri asset = uriExtra(intent, "interactive_asset_uri");
            if (asset == null) asset = uriExtra(intent, "foreground_media");
            if (asset == null) asset = uriExtra(intent, Intent.EXTRA_STREAM);
            if (asset == null) asset = intent.getData();
            if (asset == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
                asset = intent.getClipData().getItemAt(0).getUri();
            front = asset;
            back = !Intent.ACTION_SEND.equals(intent.getAction()) && !same(intent.getData(), front) ? intent.getData() : null;
        } catch (RuntimeException e) {
            fail("接收失败");
            return;
        }
        worker.execute(() -> {
            File frontFile = null, backFile = null, combined = null;
            if (front != null) try { frontFile = importImage(front, "lyrics"); }
                catch (Exception ignored) { }
            if (back != null) try { backFile = importImage(back, "background"); }
                catch (Exception ignored) { }
            if (frontFile != null && (back == null || backFile != null)) try {
                combined = backFile != null ? compose(frontFile, backFile) : opaqueImage(frontFile);
            } catch (Exception ignored) { }
            final File c = combined;
            if (generation.get() == token) {
                remember(c);
                getPreferences(MODE_PRIVATE).edit().putString("source_sticker", name(frontFile))
                    .putString("source_background", name(backFile)).commit();
            }
            runOnUiThread(() -> {
                if (isDestroyed() || generation.get() != token) return;
                applyImage(c);
                if (c == null) toast("图片接收失败");
            });
        });
    }

    private boolean same(Uri a, Uri b) { return a != null && a.equals(b); }

    private File newImage(String prefix, String extension) throws IOException {
        File directory = new File(getFilesDir(), "images");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本地图片目录");
        return new File(directory, prefix + "_" + UUID.randomUUID().toString().replace("-", "") + extension);
    }

    private File importImage(Uri uri, String prefix) throws IOException {
        if (!"content".equals(uri.getScheme())) throw new IOException("仅接受 content:// 图片 URI");
        File temp = newImage(prefix, ".png");
        try {
            try (InputStream input = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(temp)) {
                if (input == null) throw new IOException("图片流为空");
                copy(input, out);
            }
            BitmapFactory.Options info = bounds(temp);
            String mime = info.outMimeType;
            String suffix;
            if ("image/png".equals(mime)) suffix = ".png";
            else if ("image/jpeg".equals(mime)) suffix = ".jpg";
            else if ("image/webp".equals(mime)) suffix = ".webp";
            else if ("image/gif".equals(mime)) suffix = ".gif";
            else throw new IOException("不支持的图片格式：" + mime);
            if (!".png".equals(suffix)) {
                File renamed = new File(temp.getParentFile(), temp.getName().replace(".png", suffix));
                if (!temp.renameTo(renamed)) throw new IOException("无法保存原图");
                temp = renamed;
            }
            return temp;
        } catch (IOException | RuntimeException e) { temp.delete(); throw e; }
    }

    private void copy(InputStream input, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768];
        long count = 0;
        int size;
        while ((size = input.read(buffer)) != -1) {
            count += size;
            if (count > MAX_BYTES) throw new IOException("单张图片超过 64 MB");
            if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
            out.write(buffer, 0, size);
        }
    }

    private BitmapFactory.Options bounds(File file) throws IOException {
        BitmapFactory.Options info = new BitmapFactory.Options();
        info.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), info);
        if (info.outWidth <= 0 || info.outHeight <= 0 || (long) info.outWidth * info.outHeight > 100_000_000L)
            throw new IOException("图片无效或尺寸过大");
        return info;
    }

    private Bitmap decode(File file, int maxSide) throws IOException {
        BitmapFactory.Options info = bounds(file);
        info.inJustDecodeBounds = false;
        info.inSampleSize = 1;
        while (Math.max(info.outWidth, info.outHeight) / info.inSampleSize > maxSide) info.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), info);
        if (bitmap == null) throw new IOException("图片解码失败");
        return bitmap;
    }

    private File compose(File frontFile, File backFile) throws IOException {
        Bitmap front = null, back = null, output = null;
        try {
            front = decode(frontFile, 4096);
            back = decode(backFile, 2048);
            int width = Math.round(front.getWidth() * 1.2f);
            int height = Math.round(front.getHeight() * 1.2f);
            output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            output.setHasAlpha(false);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(MATTE);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            float cover = Math.max((float) width / back.getWidth(), (float) height / back.getHeight());
            float bw = back.getWidth() * cover, bh = back.getHeight() * cover;
            canvas.drawBitmap(back, null, new RectF((width-bw)/2, (height-bh)/2, (width+bw)/2, (height+bh)/2), paint);
            canvas.drawBitmap(front, (width-front.getWidth())/2f, (height-front.getHeight())/2f, paint);
            return writeBitmap(output, "combined");
        } finally {
            if (front != null) front.recycle();
            if (back != null) back.recycle();
            if (output != null) output.recycle();
        }
    }

    private File opaqueImage(File source) throws IOException {
        BitmapFactory.Options info = bounds(source);
        if ((long) info.outWidth * info.outHeight > 25_000_000L) throw new IOException("图片尺寸过大");
        Bitmap input = BitmapFactory.decodeFile(source.getAbsolutePath());
        if (input == null) throw new IOException("图片解码失败");
        Bitmap output = null;
        try {
            if (!input.hasAlpha()) return source;
            output = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
            output.setHasAlpha(false);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(MATTE);
            canvas.drawBitmap(input, 0, 0, null);
            return writeBitmap(output, "image");
        } finally {
            input.recycle();
            if (output != null) output.recycle();
        }
    }

    private File writeBitmap(Bitmap image, String prefix) throws IOException {
        File file = newImage(prefix, ".png");
        try (OutputStream out = new FileOutputStream(file)) {
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG 写入失败");
        } catch (IOException | RuntimeException e) { file.delete(); throw e; }
        return file;
    }

    private void applyImage(File combined) {
        composite = combined;
        setBusy(false);
        showSelected();
    }

    private File current() { return composite; }

    private void showSelected() {
        File file = current();
        Bitmap old = displayed;
        try {
            displayed = file == null ? null : decode(file, 1400);
            preview.setImageBitmap(displayed);
        } catch (IOException | RuntimeException e) {
            displayed = null;
            preview.setImageDrawable(null);
            toast("预览失败");
        }
        if (old != null && old != displayed) old.recycle();
        emptyHint.setVisibility(displayed == null ? View.VISIBLE : View.GONE);
        preview.setVisibility(displayed == null ? View.GONE : View.VISIBLE);
        saveButton.setEnabled(file != null && !busy);
        shareButton.setEnabled(file != null && !busy);
    }

    private void setBusy(boolean value) {
        busy = value;
        saveButton.setEnabled(!value && current() != null);
        shareButton.setEnabled(!value && current() != null);
    }

    private void saveCurrent() {
        File file = current();
        if (file == null || busy) return;
        final int token = generation.get();
        setBusy(true);
        worker.execute(() -> {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "Apple-Music-Share_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + file.getName().substring(file.getName().lastIndexOf('.')));
                values.put(MediaStore.Images.Media.MIME_TYPE, mime(file));
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Apple-Music-Share");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("相册未返回保存位置");
                try (InputStream input = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IOException("无法写入相册");
                    copy(input, out);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                runOnUiThread(() -> { if (!isDestroyed()) { if (generation.get() == token) setBusy(false); toast("已保存"); } });
            } catch (Exception e) {
                if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                runOnUiThread(() -> { if (!isDestroyed() && generation.get() == token) fail("保存失败"); });
            }
        });
    }

    private String mime(File file) {
        String name = file.getName();
        return name.endsWith(".jpg") ? "image/jpeg" : name.endsWith(".webp") ? "image/webp"
            : name.endsWith(".gif") ? "image/gif" : "image/png";
    }

    private void shareCurrent() {
        File file = current();
        if (file == null || busy) return;
        Uri uri = new Uri.Builder().scheme("content").authority(ImageProvider.AUTHORITY)
            .appendPath("images").appendPath(file.getName()).build();
        Intent send = new Intent(Intent.ACTION_SEND).setType(mime(file))
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(getContentResolver(), "图片", uri));
        Intent chooser = Intent.createChooser(send, "分享图片");
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new android.content.ComponentName[]{getComponentName()});
        try { startActivity(chooser); } catch (RuntimeException e) { fail("分享失败"); }
    }

    private void remember(File combined) {
        getPreferences(MODE_PRIVATE).edit().putString("composite", name(combined))
            .remove("sticker").remove("diagnostic").remove("background").commit();
    }

    private String name(File file) { return file == null ? "" : file.getName(); }

    private File stored(String key) {
        String name = getPreferences(MODE_PRIVATE).getString(key, "");
        if (name == null || !name.matches("[a-zA-Z0-9_-]+\\.(png|jpg|webp|gif)")) return null;
        File file = new File(new File(getFilesDir(), "images"), name);
        return file.isFile() ? file : null;
    }

    private void restore() {
        File previousFront = stored("sticker"), previousCombined = stored("composite");
        File rawFront = stored("source_sticker");
        final File front = rawFront != null ? rawFront : previousFront;
        File storedBack = stored("source_background");
        final File background = storedBack != null ? storedBack
            : getPreferences(MODE_PRIVATE).contains("source_sticker") ? null : cachedBackground(front);
        setBusy(true);
        final int token = generation.incrementAndGet();
        worker.execute(() -> {
            File combined = previousCombined;
            if (front != null) try {
                combined = background != null ? compose(front, background) : opaqueImage(front);
            } catch (Exception ignored) { }
            final File ready = combined;
            if (generation.get() == token) {
                remember(ready);
                getPreferences(MODE_PRIVATE).edit().putString("source_sticker", name(front))
                    .putString("source_background", name(background)).commit();
            }
            runOnUiThread(() -> {
                if (!isDestroyed() && generation.get() == token) applyImage(ready);
            });
        });
    }

    private File cachedBackground(File front) {
        if (front == null) return null;
        File[] files = new File(getFilesDir(), "images").listFiles();
        File result = null;
        long nearest = 30_000;
        if (files != null) for (File file : files) {
            long delta = file.lastModified() - front.lastModified();
            if (file.getName().startsWith("background_") && delta >= 0 && delta < nearest) {
                result = file;
                nearest = delta;
            }
        }
        return result;
    }

    private void fail(String message) {
        setBusy(false);
        toast(message);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() {
        generation.incrementAndGet();
        worker.shutdownNow();
        preview.setImageDrawable(null);
        if (displayed != null) displayed.recycle();
        super.onDestroy();
    }
}
