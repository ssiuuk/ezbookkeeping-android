package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.ssiuuk.ezbookkeeping.data.PaymentNotification;
import com.ssiuuk.ezbookkeeping.data.PendingPayments;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/**
 * Walks through the main screens against a seeded test server and saves a screenshot of each,
 * so changes to the app can be reviewed without a phone. CI pulls the pictures from the emulator.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenshotTest {
    private static final String SCREENS = "/sdcard/Download/ezb-screens";
    private static final long TIMEOUT = 20000;

    private UiDevice device;
    private String packageName;

    @Test
    public void tourOfTheApp() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String serverUrl = arguments.getString("serverUrl", "http://10.0.2.2:8080");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        packageName = context.getPackageName();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.executeShellCommand("mkdir -p " + SCREENS);

        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);

            find(By.res(packageName, "server_address"));
            shot("01-welcome");
            find(By.res(packageName, "server_address")).setText(serverUrl);
            find(By.res(packageName, "server_name")).setText("Home");
            hideKeyboard();
            find(By.res(packageName, "connect_button")).click();

            find(By.res(packageName, "login_name"));
            shot("02-sign-in");
            find(By.res(packageName, "login_name")).setText("demo");
            find(By.res(packageName, "password")).setText("demo123456");
            hideKeyboard();
            find(By.res(packageName, "sign_in_button")).click();

            find(By.res(packageName, "bottom_nav"));
            find(By.textContains("Weekly shop"));
            settle();
            shot("03-overview");

            find(By.res(packageName, "nav_transactions")).click();
            find(By.textContains("Dinner with friends"));
            settle();
            shot("04-transactions");

            find(By.res(packageName, "nav_accounts")).click();
            find(By.text("Savings"));
            settle();
            shot("05-accounts");

            find(By.res(packageName, "nav_more")).click();
            find(By.text("Reports"));
            shot("06-more");

            find(By.res(packageName, "nav_overview")).click();
            find(By.res(packageName, "fab")).click();
            find(By.res(packageName, "amount"));
            hideKeyboard();
            // the test server has a shared "Family" book, so the book field is offered
            find(By.res(packageName, "book"));
            settle();
            shot("07-add-transaction");

            find(By.res(packageName, "amount")).setText("12.50");
            hideKeyboard();
            find(By.res(packageName, "category")).click();
            find(By.text("Restaurants"));
            settle();
            shot("08-choose-category");
            find(By.text("Restaurants")).click();

            find(By.res(packageName, "account")).click();
            find(By.text("Credit cards"));
            settle();
            shot("09-choose-account");
            find(By.text("Credit card")).click();

            scrollTo(By.res(packageName, "project")).click();
            find(By.text("Holiday")).click();
            find(By.res(packageName, "project").text("Holiday"));

            scrollTo(By.res(packageName, "merchant")).click();
            find(By.text("Add a merchant")).click();
            find(By.clazz("android.widget.EditText").focused(true)).setText("Corner Café");
            find(By.text("Add")).click();
            find(By.res(packageName, "merchant").text("Corner Café"));

            scrollTo(By.res(packageName, "comment")).setText("Lunch");
            hideKeyboard();
            settle();
            shot("10-filled-transaction");
            scrollTo(By.res(packageName, "save_button")).click();

            find(By.textContains("Lunch"));
            settle();
            shot("11-overview-after-adding");

            find(By.textContains("Lunch")).click();
            find(By.res(packageName, "merchant").text("Corner Café"));
            scrollTo(By.res(packageName, "project").text("Holiday"));
            settle();
            shot("12-edit-transaction");
            device.pressBack();

            // a new expense in the same category starts with the account used last time
            find(By.res(packageName, "fab")).click();
            find(By.res(packageName, "amount"));
            hideKeyboard();
            find(By.res(packageName, "category")).click();
            find(By.text("Restaurants")).click();
            find(By.res(packageName, "account").text("Credit card"));
            settle();
            shot("13-remembered-account");

            // a Google Wallet purchase waits under To review, with the shop's last category and that category's account
            PendingPayments.add(context, PaymentNotification.parse("Corner Café", "$18.20 with Visa •••• 1234"), System.currentTimeMillis(), "test");
            backTo(By.res(packageName, "review_card"));
            find(By.text("1 purchase to review"));
            settle();
            shot("14-to-review");
            find(By.res(packageName, "review_list")).findObject(By.text("Corner Café")).click();
            find(By.res(packageName, "category").text("Restaurants"));
            find(By.res(packageName, "account").text("Credit card"));
            find(By.res(packageName, "amount").text("18.20"));
            settle();
            shot("15-review-purchase");
            scrollTo(By.res(packageName, "merchant").text("Corner Café"));
            scrollTo(By.res(packageName, "save_button")).click();

            if (!device.wait(Until.gone(By.res(packageName, "review_card")), TIMEOUT)) {
                throw new AssertionError("The saved purchase is still waiting for review");
            }

            find(By.res(packageName, "action_settings")).click();
            find(By.text("Theme"));
            shot("16-settings");
            device.pressBack();

            find(By.res(packageName, "nav_more")).click();
            find(By.text("Reports")).click();
            device.wait(Until.hasObject(By.pkg(packageName).clazz("android.webkit.WebView")), TIMEOUT);
            // the web app is a large bundle, so give it time to start and draw the report
            device.wait(Until.hasObject(By.pkg(packageName).textContains("Expense")), TIMEOUT);
            Thread.sleep(3000);
            shot("17-web-reports");

            for (int i = 0; i < 3 && !device.hasObject(By.res(packageName, "bottom_nav")); i++) {
                device.pressBack();
                device.waitForIdle();
                Thread.sleep(1000);
            }

            // switch to dark through the app's own setting; changing the system night mode restarts Play services on the emulator
            find(By.res(packageName, "action_settings")).click();
            find(By.text("Theme")).click();
            find(By.text("Dark")).click();
            find(By.text("Theme"));
            settle();
            shot("18-settings-dark");
            device.pressBack();

            find(By.res(packageName, "nav_overview")).click();
            find(By.textContains("Weekly shop"));
            settle();
            shot("19-overview-dark");
        } catch (Throwable error) {
            shot("99-failure");
            device.dumpWindowHierarchy(new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalCacheDir(), "hierarchy.xml"));
            device.executeShellCommand("cp " + new File(context.getExternalCacheDir(), "hierarchy.xml").getAbsolutePath() + " " + SCREENS + "/99-hierarchy.xml");
            throw error;
        }
    }

    /** Presses back until the selector shows, since the first press may only close the keyboard. */
    private UiObject2 backTo(BySelector selector) {
        for (int i = 0; i < 3; i++) {
            device.pressBack();
            UiObject2 found = device.wait(Until.findObject(selector), 3000);

            if (found != null) {
                return found;
            }
        }

        return find(selector);
    }

    /** Scrolls the form until the field is on screen, since the add screen is taller than the phone. */
    private UiObject2 scrollTo(BySelector selector) {
        UiObject2 scroll = find(By.res(packageName, "scroll"));
        UiObject2 found = device.findObject(selector);

        for (int i = 0; i < 6 && (found == null || found.getVisibleBounds().height() < 20); i++) {
            scroll.scroll(Direction.DOWN, 0.6f);
            device.waitForIdle();
            found = device.findObject(selector);
        }

        if (found == null) {
            throw new AssertionError("Not found on screen: " + selector);
        }

        return found;
    }

    private UiObject2 find(BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), TIMEOUT);

        if (object == null) {
            throw new AssertionError("Not found on screen: " + selector);
        }

        return object;
    }

    // Pressing back with no keyboard showing would leave the screen, so only press it when one is up.
    private void hideKeyboard() throws IOException, InterruptedException {
        device.waitForIdle();
        String state = device.executeShellCommand("dumpsys input_method");

        boolean shown = state.contains("mInputShown=true")
                || state.contains("isInputViewShown=true")
                || device.hasObject(By.pkg("com.google.android.inputmethod.latin"));

        if (shown) {
            device.pressBack();
            device.waitForIdle();
        }
    }

    private void settle() throws InterruptedException {
        device.waitForIdle();
        Thread.sleep(1500);
    }

    private void shot(String name) throws IOException {
        device.executeShellCommand("screencap -p " + SCREENS + "/" + name + ".png");
    }
}
