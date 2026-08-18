package com.aceitemedico.sirene;

import android.content.Context;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class AceiteMedicoSirenePlugin implements FlutterPlugin, MethodChannel.MethodCallHandler {
    private MethodChannel channel;
    private Context context;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        channel = new MethodChannel(binding.getBinaryMessenger(), "aceite_medico_sirene");
        channel.setMethodCallHandler(this);

        // On Android 14+, full-screen alerts may require one explicit user grant.
        // Open the official system page automatically only when that grant is missing.
        SirenService.ensureFullScreenIntentAccess(context);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "startSiren":
                SirenService.start(context);
                result.success(null);
                break;
            case "stopSiren":
                SirenService.stop(context);
                result.success(null);
                break;
            case "prepareUrgentAlert":
                result.success(SirenService.ensureFullScreenIntentAccess(context));
                break;
            case "canUseFullScreenIntent":
                result.success(SirenService.canUseFullScreenIntent(context));
                break;
            default:
                result.notImplemented();
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        if (channel != null) {
            channel.setMethodCallHandler(null);
        }
        channel = null;
        context = null;
    }
}
