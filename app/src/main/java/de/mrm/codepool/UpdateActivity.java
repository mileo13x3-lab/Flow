package de.mrm.codepool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.mrm.codepool.core.UpdatePolicy;

public final class UpdateActivity extends Activity {
    private static final int PICK = 701, ALLOW = 702, INSTALL = 703;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button choose, install;
    private File pending;
    private boolean handedToInstaller, busy, foreground;
    private volatile boolean disposed;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_update);

        status = findViewById(R.id.update_status);
        choose = findViewById(R.id.choose_apk);
        install = findViewById(R.id.install_apk);

        findViewById(R.id.update_back).setOnClickListener(v -> finish());

        View root = findViewById(R.id.update_root);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(
                    pad + insets.getSystemWindowInsetLeft(),
                    pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(),
                    pad + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        root.requestApplyInsets();

        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            ((TextView) findViewById(R.id.installed_version)).setText("Installiert: " + info.versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        choose.setOnClickListener(v -> pick());
        install.setOnClickListener(v -> requestInstall());

        try {
            File[] files = UpdateFileProvider.directory(this).listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.lastModified() < System.currentTimeMillis() - 86400000L && !f.equals(pending)) {
                        f.delete();
                    }
                }
            }
        } catch (IllegalStateException e) {
            choose.setEnabled(false);
            install.setEnabled(false);
            status.setText("Keine APK-Auswahl möglich. Bitte die APK in deiner Dateien-App öffnen.");
            return;
        }

        if (saved != null) {
            if (saved.getBoolean("busy")) {
                status.setText("Prüfung durch Ansichtswechsel unterbrochen. Bitte APK erneut auswählen oder erneut installieren.");
            }

            String name = saved.getString("pending");
            handedToInstaller = saved.getBoolean("installer");

            if (name != null && name.matches("[a-f0-9-]{36}\\.apk")) {
                pending = new File(UpdateFileProvider.directory(this), name);
                if (pending.isFile()) {
                    status.setText("Update-Datei ausgewählt. Zum Fortfahren erneut prüfen und installieren.");
                    install.setEnabled(true);
                } else {
                    pending = null;
                }
            }
        }
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");

        try {
            startActivityForResult(intent, PICK);
        } catch (ActivityNotFoundException e) {
            status.setText("Keine Dateiauswahl verfügbar. Öffne die APK in deiner Dateien-App.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) {
            importApk(data.getData());
        }

        if (request == ALLOW) {
            if (getPackageManager().canRequestPackageInstalls()) {
                requestInstall();
            } else {
                status.setText("Installation nicht erlaubt. Du kannst sie später erneut anfordern oder die APK in der Dateien-App öffnen.");
            }
        }

        if (request == INSTALL) {
            if (result == RESULT_OK) {
                status.setText("Android hat die Installation abgeschlossen. Öffne die App erneut und starte die Ausgabe.");
            } else {
                status.setText("Installation abgebrochen oder nicht abgeschlossen. Die bisherige App bleibt verfügbar.");
            }
        }
    }

    private void importApk(Uri uri) {
        if (pending != null && !handedToInstaller) {
            pending.delete();
        }

        pending = null;
        handedToInstaller = false;

        busy = true;
        choose.setEnabled(false);
        install.setEnabled(false);
        status.setText("APK wird lokal gelesen und geprüft …");

        worker.execute(() -> {
            File destination = null;
            try {
                File file = new File(UpdateFileProvider.directory(this), UUID.randomUUID() + ".apk");
                destination = file;

                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(file)) {
                    UpdatePolicy.copy(in, out, 32L * 1024 * 1024);
                }

                PackageInfo info = checkApk(file);

                if (disposed) {
                    file.delete();
                    return;
                }

                runOnUiThread(() -> {
                    if (disposed) {
                        file.delete();
                        return;
                    }

                    pending = file;
                    busy = false;
                    choose.setEnabled(true);
                    install.setEnabled(true);

                    status.setText(
                            "Update " + info.versionName + " bereit. App-ID, Versionsnummer und Signaturschlüssel passen.\n\n" +
                                    "Android prüft und installiert die APK nach deiner Bestätigung. Die laufende Ausgabe wird dabei beendet; danach erneut starten."
                    );
                });

            } catch (Exception e) {
                if (destination != null) destination.delete();
                runOnUiThread(() -> {
                    if (!disposed) {
                        busy = false;
                        choose.setEnabled(true);
                        status.setText("Update nicht möglich: " + readable(e));
                    }
                });
            }
        });
    }

    private String readable(Exception e) {
        return e.getMessage() == null ? "Datei nicht lesbar oder keine gültige APK." : e.getMessage();
    }

    PackageInfo checkApk(File file) throws Exception {
        if (!file.isFile() || file.length() == 0 || file.length() > 32L * 1024 * 1024) {
            throw new IOException("APK fehlt, ist leer oder zu groß.");
        }

        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;

        PackageManager pm = getPackageManager();
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);

        if (candidate == null) {
            throw new IOException("Keine gültige, eigenständige APK.");
        }

        PackageInfo current = pm.getPackageInfo(getPackageName(), flags);
        UpdatePolicy.validate(
                current.packageName,
                version(current),
                signers(current),
                candidate.packageName,
                version(candidate),
                signers(candidate)
        );

        if (candidate.applicationInfo != null && candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            throw new IOException("Dieses Update benötigt eine neuere Android-Version.");
        }

        return candidate;
    }

    private static long version(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    private static Set<String> signers(PackageInfo p) {
        android.content.pm.Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (p.signingInfo == null ? null : p.signingInfo.getApkContentsSigners())
                : p.signatures;

        Set<String> values = new HashSet<>();
        if (signatures != null) {
            for (android.content.pm.Signature s : signatures) {
                values.add(s.toCharsString());
            }
        }
        return values;
    }

    private void requestInstall() {
        if (pending == null || busy) return;

        File selected = pending;
        busy = true;
        choose.setEnabled(false);
        install.setEnabled(false);
        status.setText("Update wird erneut geprüft …");

        worker.execute(() -> {
            try {
                checkApk(selected);

                runOnUiThread(() -> {
                    if (disposed) return;

                    busy = false;
                    choose.setEnabled(true);
                    install.setEnabled(true);

                    if (!foreground) {
                        status.setText("APK geprüft. Zum Fortfahren auf Prüfen und installieren tippen.");
                        return;
                    }

                    launchInstaller(selected);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!disposed) {
                        busy = false;
                        choose.setEnabled(true);
                        install.setEnabled(true);
                        status.setText("Update nicht möglich: " + readable(e));
                    }
                });
            }
        });
    }

    private void launchInstaller(File selected) {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this)
                        .setTitle("Updates erlauben")
                        .setMessage("Aktiviere in Android „Dieser Quelle vertrauen“ bzw. „Aus dieser Quelle zulassen“ für Mr M Flow. Danach bestätigst du die Installation selbst.")
                        .setPositiveButton("Einstellungen",
                                (d, w) -> {
                                    try {
                                        startActivityForResult(
                                                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                        Uri.parse("package:" + getPackageName())),
                                                ALLOW
                                        );
                                    } catch (ActivityNotFoundException e) {
                                        status.setText("Öffne die APK bitte direkt in der Dateien-App.");
                                    }
                                })
                        .setNegativeButton("Abbrechen", null)
                        .show();
                return;
            }

            startActivityForResult(installerIntent(this, selected), INSTALL);
            handedToInstaller = true;

        } catch (Exception e) {
            status.setText("Update nicht möglich: " + readable(e));
        }
    }

    static Intent installerIntent(Context c, File file) {
        Uri uri = UpdateFileProvider.uri(c, file);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);

        intent.setClipData(ClipData.newRawUri("Update-APK", uri));
        return intent;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("busy", busy);
        if (pending != null) {
            state.putString("pending", pending.getName());
        }
        state.putBoolean("installer", handedToInstaller);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override protected void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        disposed = true;
        worker.shutdownNow();
        if (isFinishing() && pending != null && !handedToInstaller) {
            pending.delete();
        }
        super.onDestroy();
    }
}
