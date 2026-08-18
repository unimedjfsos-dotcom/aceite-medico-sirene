package com.aceitemedico.sirene;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;

public class SirenService extends Service {
    // New channel ID so an old/silenced channel cannot keep the urgent alert suppressed.
    private static final String CHANNEL_ID = "aceite_medico_alerta_urgente_v3";
    private static final int NOTIFICATION_ID = 8127;
    private static final int SAMPLE_RATE = 44100;

    private volatile boolean running = false;
    private Thread audioThread;
    private AudioTrack audioTrack;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        if (context == null) return;

        wakeScreen(context);

        Intent intent = new Intent(context, SirenService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception firstError) {
            // Best-effort fallback for OEMs with non-standard background restrictions.
            try {
                context.startService(intent);
            } catch (Exception ignored) {
            }
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            context.stopService(new Intent(context, SirenService.class));
        } catch (Exception ignored) {
        }
    }

    public static boolean canUseFullScreenIntent(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.canUseFullScreenIntent();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Returns true when no extra permission screen is needed.
     * On Android 14+, opens the official system page if full-screen alert access is off.
     */
    public static boolean ensureFullScreenIntentAccess(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < 34 || canUseFullScreenIntent(context)) return true;

        try {
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + context.getPackageName())
            );
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(settingsIntent);
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void wakeScreen(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock screenLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "AceiteMedico:WakeScreen"
                );
                screenLock.acquire(5000L);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        wakeScreen(this);

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent launchPendingIntent = null;
        if (launchIntent != null) {
            launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            launchPendingIntent = PendingIntent.getActivity(
                    this,
                    8128,
                    launchIntent,
                    pendingFlags
            );
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder
                .setContentTitle("SOS UNIMED")
                .setContentText("ALERTA MÉDICO ATIVO - abra para dar ciência")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(Notification.PRIORITY_MAX)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (launchPendingIntent != null) {
            builder.setContentIntent(launchPendingIntent);
            builder.setFullScreenIntent(launchPendingIntent, true);
        }

        Notification notification = builder.build();
        startForeground(NOTIFICATION_ID, notification);

        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
            }
        } catch (Exception ignored) {
        }

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "AceiteMedico:SirenWakeLock"
                );
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }

        startAudio();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alerta médico urgente",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Sirene e abertura em tela cheia para alertas médicos urgentes.");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 150, 300});
            // Audio is generated by SirenService; avoid a second notification sound.
            channel.setSound(null, null);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startAudio() {
        if (running) return;
        running = true;

        audioThread = new Thread(() -> {
            int minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(minBuffer, SAMPLE_RATE / 2);

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            audioTrack = new AudioTrack(
                    attrs,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            try {
                audioTrack.play();
                final int chunk = 2048;
                short[] pcm = new short[chunk];
                double phase = 0.0;
                long start = System.currentTimeMillis();

                while (running && !Thread.currentThread().isInterrupted()) {
                    long elapsed = System.currentTimeMillis() - start;
                    double sweep = (elapsed % 1200L) / 1200.0;
                    double frequency;
                    if (sweep < 0.5) {
                        frequency = 650.0 + (sweep * 2.0) * 350.0;
                    } else {
                        frequency = 1000.0 - ((sweep - 0.5) * 2.0) * 350.0;
                    }

                    double step = 2.0 * Math.PI * frequency / SAMPLE_RATE;
                    for (int i = 0; i < chunk; i++) {
                        double v = Math.sin(phase) * 0.78 + Math.sin(phase * 0.5) * 0.22;
                        pcm[i] = (short) (v * Short.MAX_VALUE * 0.78);
                        phase += step;
                        if (phase > Math.PI * 2.0) {
                            phase -= Math.PI * 2.0;
                        }
                    }
                    audioTrack.write(pcm, 0, pcm.length);
                }
            } catch (Exception ignored) {
            } finally {
                releaseAudioTrack();
            }
        }, "AceiteMedicoSiren");

        audioThread.start();
    }

    private synchronized void releaseAudioTrack() {
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            try { audioTrack.flush(); } catch (Exception ignored) {}
            try { audioTrack.release(); } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    private void stopAudio() {
        running = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        releaseAudioTrack();

        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wakeScreen(this);
        if (!running) {
            startAudio();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAudio();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
