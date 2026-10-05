package de.mrm.codepool;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.mrm.codepool.core.SessionBuffer;

public final class MainActivity extends Activity {
    private static final String DISPLAY_PREFS = "display";
    private static final String KEY_ANIMATION = "animation_enabled";
    private static final String KEY_FOLLOW = "follow_enabled";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, output, count;
    private Button start, stop;
    private ScrollView scroll;
    private CheckBox follow, animationToggle;
    private FieldView field;
    private View animationStop;
    private long version = -1;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        View root = findViewById(R.id.root);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    pad + insets.getSystemWindowInsetLeft(),
                    pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(),
                    pad + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });
        root.requestApplyInsets();

        status = findViewById(R.id.status);
        output = findViewById(R.id.output);
        count = findViewById(R.id.count);

        start = findViewById(R.id.start);
        stop = findViewById(R.id.stop);

        scroll = findViewById(R.id.scroll);
        follow = findViewById(R.id.follow);
        animationToggle = findViewById(R.id.animation_toggle);

        field = findViewById(R.id.field);
        animationStop = findViewById(R.id.animation_stop);

        SharedPreferences prefs = getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        boolean animationEnabled = prefs.getBoolean(KEY_ANIMATION, false);
        boolean followEnabled = prefs.getBoolean(KEY_FOLLOW, true);

        follow.setChecked(followEnabled);
        animationToggle.setChecked(animationEnabled);

        if (animationEnabled) {
            field.restartAnimation();
            animationStop.setVisibility(View.VISIBLE);
        } else {
            field.stopAnimation();
            animationStop.setVisibility(View.GONE);
        }

        scroll.setOnClickListener(v -> {
            boolean enabled = field.isAnimationEnabled();
            if (enabled) {
                field.stopAnimation();
            } else {
                field.restartAnimation();
            }
            animationToggle.setChecked(field.isAnimationEnabled());
            animationStop.setVisibility(field.isAnimationEnabled() ? View.VISIBLE : View.GONE);
            saveDisplay();
        });

        animationStop.setOnClickListener(v -> {
            field.stopAnimation();
            animationToggle.setChecked(false);
            animationStop.setVisibility(View.GONE);
            saveDisplay();
        });

        animationToggle.setOnCheckedChangeListener((button, checked) -> {
            field.setAnimationEnabled(checked);
            animationStop.setVisibility(checked ? View.VISIBLE : View.GONE);
            saveDisplay();
        });

        follow.setOnCheckedChangeListener((button, checked) -> {
            saveDisplay();
        });

        if (state != null && state.getBoolean("field_enabled", false)) {
            field.restartAnimation();
            animationToggle.setChecked(true);
            animationStop.setVisibility(View.VISIBLE);
            saveDisplay();
        }

        start.setOnClickListener(v -> requestStart());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, SimulationService.class));
            render();
        });

        findViewById(R.id.clear).setOnClickListener(v -> {
            SimulationService.BUFFER.clear();
            render();
        });

        findViewById(R.id.battery).setOnClickListener(v -> showBackgroundHelp());
        findViewById(R.id.update).setOnClickListener(v -> startActivity(new Intent(this, UpdateActivity.class)));
    }

    private void saveDisplay() {
        getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ANIMATION, field.isAnimationEnabled())
                .putBoolean(KEY_FOLLOW, follow.isChecked())
                .apply();
    }

    private void requestStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 436);
            return;
        }

        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle("Benachrichtigungen erlauben")
                    .setMessage("Aktiviere Benachrichtigungen, damit du den laufenden Dienst sehen und jederzeit stoppen kannst.")
                    .setPositiveButton("Einstellungen",
                            (d, w) -> openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())))
                    .setNegativeButton("Zurück", null)
                    .show();
            return;
        }

        NotificationChannel channel = getSystemService(NotificationManager.class)
                .getNotificationChannel(SimulationService.CHANNEL);

        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            new AlertDialog.Builder(this)
                    .setTitle("Laufende Simulation sichtbar machen")
                    .setMessage("Der Benachrichtigungskanal „Laufende Simulation“ ist deaktiviert. Aktiviere ihn für den sichtbaren Status und Stopp-Schalter.")
                    .setPositiveButton("Kanal-Einstellungen",
                            (d, w) -> openSettings(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, SimulationService.CHANNEL)))
                    .setNegativeButton("Zurück", null)
                    .show();
            return;
        }

        try {
            startForegroundService(new Intent(this, SimulationService.class)
                    .setAction(SimulationService.START));
        } catch (RuntimeException e) {
            SimulationService.BUFFER.state(false, "Start nicht möglich: " + e.getClass().getSimpleName());
        }
        render();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 436) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                requestStart();
            } else {
                Toast.makeText(this,
                        "Zum Start bitte Benachrichtigungen in den App-Einstellungen erlauben.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showBackgroundHelp() {
        boolean exempt = getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(getPackageName());

        new AlertDialog.Builder(this)
                .setTitle("Bei ausgeschaltetem Bildschirm")
                .setMessage(
                        (exempt ? "Akkuoptimierung ist für diese App bereits ausgenommen.\n\n"
                                : "Wähle in den Akku-Einstellungen „Alle Apps“, dann „Mr M Flow“ und „Nicht optimieren“. Je nach Lenovo-Version heißt die Option „Uneingeschränkt“.\n\n")
                                + "Die Simulation läuft mit einer sichtbaren Benachrichtigung weiter. Dauerbetrieb erhöht den Akkuverbrauch. Lenovo-Energiesparen und erzwungenes Beenden können den Dienst stoppen. Nach einem Neustart bitte erneut auf Starten tippen.")
                .setPositiveButton("Akku-Einstellungen",
                        (d, w) -> openSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                .setNegativeButton("Schließen", null)
                .show();
    }

    private void openSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Bitte die Android-Einstellungen manuell öffnen.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void render() {
        SessionBuffer.Snapshot s = SimulationService.BUFFER.snapshot();
        if (s.version == version) return;
        version = s.version;

        status.setText(s.status);
        start.setEnabled(!s.active);
        stop.setEnabled(s.active);
        count.setText(getString(R.string.counter, s.count));
        output.setText(s.text.isEmpty() ? getString(R.string.empty_output) : s.text);

        if (follow.isChecked()) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("field_enabled", field.isAnimationEnabled());
        saveDisplay();
        super.onSaveInstanceState(state);
    }

    @Override public void onResume() {
        super.onResume();
        field.setForeground(true);
        handler.post(refresh);
    }

    @Override public void onPause() {
        field.setForeground(false);
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
