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
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class SirenService extends Service {
    private static final String CHANNEL_ID = "aceite_medico_sirene";
    private static final int NOTIFICATION_ID = 8127;
    private static final int SAMPLE_RATE = 44100;

    private volatile boolean running = false;
    private Thread audioThread;
    private AudioTrack audioTrack;
    private PowerManager.WakeLock wakeLock;

    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, SirenService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        if (context == null) return;
        context.stopService(new Intent(context, SirenService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

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
                .setContentText("Alerta médico ativo - toque para responder")
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
        } catch (Exception ignored) {}

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "AceiteMedico:SirenWakeLock"
                );
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

        startAudio();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alerta médico",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Mantém a sirene do alerta médico ativa e abre a tela de resposta.");
            channel.setSound(null, null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true);
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
            } catch (Exception ignored) {}
            wakeLock = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
