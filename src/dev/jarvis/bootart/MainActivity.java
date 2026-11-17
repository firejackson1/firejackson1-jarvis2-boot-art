package dev.jarvis.bootart;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_ZIP = 11;
    private static final String STOCK = "/system/media/bootanimation.zip";
    private static final String ROOT_SETUP =
        "set -eu; [ \"$(getprop ro.product.device)\" = jarvis2 ]; " +
        "[ -d /data/adb/modules ]; module=/data/adb/modules/jarvis_boot_animation; " +
        "[ ! -L \"$module\" ]; ";
    private static final String APPLY = ROOT_SETUP +
        "stage=$(mktemp -d /data/adb/jarvis_boot_animation.XXXXXX); " +
        "trap 'rm -rf \"$stage\"' EXIT; chmod 0755 \"$stage\"; " +
        "mkdir -p \"$stage/system/media\"; chmod 0755 \"$stage/system\" \"$stage/system/media\"; " +
        "cat > \"$stage/system/media/bootanimation.zip\"; " +
        "chmod 0644 \"$stage/system/media/bootanimation.zip\"; " +
        "chcon u:object_r:system_file:s0 \"$stage/system/media/bootanimation.zip\"; " +
        "actual=$(sha256sum \"$stage/system/media/bootanimation.zip\"); " +
        "[ \"${actual%% *}\" = %s ]; " +
        "printf 'id=jarvis_boot_animation\\nname=Jarvis Boot Art\\nversion=0.1\\nversionCode=1\\nauthor=Jarvis\\ndescription=Boot animation override\\n' > \"$stage/module.prop\"; " +
        "chmod 0644 \"$stage/module.prop\"; " +
        "if [ -e \"$module\" ]; then " +
        "grep -qx id=jarvis_boot_animation \"$module/module.prop\"; " +
        "mv -f \"$stage/system/media/bootanimation.zip\" \"$module/system/media/bootanimation.zip\"; " +
        "rm -f \"$module/disable\" \"$module/remove\"; " +
        "else mv \"$stage\" \"$module\"; fi; echo BOOT_ART_OK";
    private static final String RESTORE = ROOT_SETUP +
        "if [ -e \"$module\" ]; then grep -qx id=jarvis_boot_animation \"$module/module.prop\"; " +
        "touch \"$module/disable\"; fi; echo BOOT_ART_OK";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ImageView preview;
    private Button pick, sample, previewButton, apply, restore;
    private File selected;
    private volatile int previewGeneration;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        selected = new File(getFilesDir(), "animation.zip");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(40), dp(24), dp(40), dp(24));
        layout.setBackgroundColor(0xff10192b);
        setContentView(layout);

        TextView title = new TextView(this);
        title.setText("Jarvis Boot Art");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        layout.addView(title);

        preview = new ImageView(this);
        preview.setBackgroundColor(0xff080d16);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("Boot animation preview");
        layout.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));

        status = new TextView(this);
        status.setTextSize(17);
        status.setTextColor(0xffd4e5fa);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setText(selected.isFile() ? "Imported animation ready" : "Import a bootanimation.zip");
        layout.addView(status);

        LinearLayout bar = new LinearLayout(this);
        layout.addView(bar);
        pick = button(bar, "Import ZIP", this::pickFile);
        sample = button(bar, "Preview current", () -> startPreview(new File(STOCK)));
        previewButton = button(bar, "Preview import", () -> startPreview(selected));
        apply = button(bar, "Apply import", () -> confirm("Apply your animation?",
                "Magisk will ask for root. Your animation will appear on the next startup.",
                () -> changeAnimation(true)));
        restore = button(bar, "Restore stock", () -> confirm("Restore the stock animation?",
                "This disables the Jarvis override for the next startup.", () -> changeAnimation(false)));
        updateButtons();
        pick.requestFocus();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private Button button(LinearLayout bar, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setFocusable(true);
        bar.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void pickFile() {
        new AlertDialog.Builder(this).setTitle("Import animation")
            .setItems(new String[]{"Choose a file", "Use ZIP copied to the TV"}, (dialog, which) -> {
                if (which == 0) openDocument();
                else importStaged();
            }).setNegativeButton("Cancel", null).show();
    }

    private void openDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, PICK_ZIP);
        } catch (ActivityNotFoundException missingPicker) {
            importStaged();
        }
    }

    private void importStaged() {
        File importDir = getExternalFilesDir(null);
        if (importDir == null) {
            status.setText("App storage is unavailable");
            return;
        }
        File staged = new File(importDir, "bootanimation.zip");
        if (staged.isFile()) importFile(Uri.fromFile(staged));
        else status.setText("Copy bootanimation.zip to " + importDir + " and choose Use ZIP copied to the TV");
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == PICK_ZIP && result == RESULT_OK && intent != null && intent.getData() != null)
            importFile(intent.getData());
    }

    private void importFile(Uri uri) {
        stopPreview();
        setBusy("Checking ZIP…");
        worker.execute(() -> {
            File incoming = new File(getCacheDir(), "incoming.zip");
            File validated = new File(getFilesDir(), "validated.zip");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open file");
                try (OutputStream out = new FileOutputStream(incoming)) {
                    Animation.copyLimited(in, out, Animation.MAX_ZIP);
                }
                try (Animation animation = new Animation(incoming)) {
                    animation.normalize(validated);
                    if (validated.length() > Animation.MAX_ZIP) throw new IOException("Repacked ZIP exceeds 48 MB");
                    Files.move(validated.toPath(), selected.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    finishWork("Imported " + animation.frames.size() + " frames at " + animation.fps + " fps");
                }
            } catch (Exception e) {
                finishWork("Import failed. " + message(e));
            } finally {
                incoming.delete();
                validated.delete();
            }
        });
    }

    private void startPreview(File file) {
        stopPreview();
        int generation = previewGeneration;
        worker.execute(() -> {
            try (Animation animation = new Animation(file)) {
                ui.post(() -> {
                    if (generation == previewGeneration) status.setText("Frame preview · " +
                            animation.width + " × " + animation.height + " · " + animation.fps + " fps");
                });
                for (String frame : animation.frames) {
                    if (generation != previewGeneration) return;
                    long started = SystemClock.uptimeMillis();
                    byte[] bytes;
                    try (InputStream in = animation.zip.getInputStream(animation.zip.getEntry(frame))) {
                        bytes = Animation.readLimited(in, 6 * 1024 * 1024);
                    }
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    if (bitmap == null) throw new IOException("Cannot decode " + frame);
                    ui.post(() -> { if (generation == previewGeneration) preview.setImageBitmap(bitmap); });
                    SystemClock.sleep(Math.max(0, 1000L / animation.fps - (SystemClock.uptimeMillis() - started)));
                }
            } catch (Exception e) {
                ui.post(() -> { if (generation == previewGeneration) status.setText("Preview failed. " + message(e)); });
            }
        });
    }

    private void stopPreview() { previewGeneration++; }

    private void changeAnimation(boolean enable) {
        stopPreview();
        setBusy(enable ? "Applying animation…" : "Restoring stock…");
        worker.execute(() -> {
            try {
                String script = enable ? APPLY.replace("%s", digest(selected)) : RESTORE;
                executeRoot(script, enable ? selected : null);
                finishWork(enable ? "Animation applied. Reboot when ready." : "Stock animation restored for the next reboot.");
            } catch (Exception e) {
                finishWork("Magisk operation failed. " + message(e));
            }
        });
    }

    private static String digest(File file) throws Exception {
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] chunk = new byte[32768];
            for (int count; (count = in.read(chunk)) != -1;) sha.update(chunk, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : sha.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static void executeRoot(String script, File payload) throws Exception {
        java.lang.Process process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
        Thread timeout = new Thread(() -> {
            try {
                if (!process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException ignored) { }
        }, "Magisk timeout");
        timeout.start();
        try {
            IOException uploadError = null;
            try (OutputStream out = process.getOutputStream()) {
                if (payload != null) {
                    try (InputStream in = new FileInputStream(payload)) {
                        Animation.copyLimited(in, out, Animation.MAX_ZIP);
                    }
                }
            } catch (IOException e) {
                uploadError = e;
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(Animation.readLimited(in, 16384), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            if (process.waitFor() != 0 || !output.endsWith("BOOT_ART_OK"))
                throw new IOException(output.isEmpty() ? "Check the Magisk root prompt." : output, uploadError);
            if (uploadError != null) throw uploadError;
        } finally {
            timeout.interrupt();
            process.destroy();
        }
    }

    private void confirm(String title, String detail, Runnable callback) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail)
            .setNegativeButton("Cancel", null).setPositiveButton("Continue", (dialog, which) -> callback.run()).show();
    }

    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void setBusy(String text) { busy = true; status.setText(text); updateButtons(); }
    private void finishWork(String text) {
        ui.post(() -> { if (!isDestroyed()) { busy = false; status.setText(text); updateButtons(); } });
    }
    private void updateButtons() {
        pick.setEnabled(!busy);
        sample.setEnabled(!busy);
        previewButton.setEnabled(!busy && selected.isFile());
        apply.setEnabled(!busy && selected.isFile());
        restore.setEnabled(!busy);
    }

    @Override protected void onPause() { stopPreview(); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}
