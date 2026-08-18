package com.aceitemedico.sirene;

import android.content.Context;

import androidx.annotation.Keep;

import com.onesignal.notifications.INotificationReceivedEvent;
import com.onesignal.notifications.INotificationServiceExtension;

@Keep
public class NotificationServiceExtension implements INotificationServiceExtension {
    @Override
    public void onNotificationReceived(INotificationReceivedEvent event) {
        Context context = event.getContext();
        if (context != null) {
            SirenService.start(context);
        }
    }
}
