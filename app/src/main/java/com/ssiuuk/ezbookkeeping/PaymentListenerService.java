package com.ssiuuk.ezbookkeeping;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationManagerCompat;

import com.ssiuuk.ezbookkeeping.data.PaymentNotification;
import com.ssiuuk.ezbookkeeping.data.PendingPayments;

import java.util.Arrays;

/** Picks up purchases from Google Wallet's notifications and keeps them for review. */
public class PaymentListenerService extends NotificationListenerService {
    public static boolean hasAccess(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.getPackageName());
    }

    public static ComponentName component(Context context) {
        return new ComponentName(context, PaymentListenerService.class);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        if (!Arrays.asList(PaymentNotification.WALLET_PACKAGES).contains(notification.getPackageName())) {
            return;
        }

        Bundle extras = notification.getNotification().extras;

        if (extras == null || (notification.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }

        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

        if (text == null) {
            text = extras.getCharSequence(Notification.EXTRA_TEXT);
        }

        PaymentNotification payment = PaymentNotification.parse(title == null ? null : title.toString(), text == null ? null : text.toString());

        if (payment != null && PendingPayments.add(this, payment, notification.getPostTime(), notification.getPackageName()) != null) {
            Ui.dataChanged();
        }
    }
}
