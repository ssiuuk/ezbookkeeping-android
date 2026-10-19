package com.ssiuuk.ezbookkeeping;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.StaleObjectException;
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
            type(By.res(packageName, "server_address"), serverUrl);
            type(By.res(packageName, "server_name"), "Home");
            hideKeyboard();
            tapBelowKeyboard(By.res(packageName, "connect_button"));

            find(By.res(packageName, "login_name"));
            shot("02-sign-in");
            type(By.res(packageName, "login_name"), "demo");
            type(By.res(packageName, "password"), "demo123456");
            hideKeyboard();
            tapBelowKeyboard(By.res(packageName, "sign_in_button"));

            find(By.res(packageName, "bottom_nav"));
            find(By.textContains("Weekly shop"));
            settle();
            shot("03-overview");

            tap(By.res(packageName, "nav_transactions"));
            find(By.textContains("Dinner with friends"));
            settle();
            shot("04-transactions");

            tap(By.res(packageName, "nav_accounts"));
            find(By.text("Savings"));
            settle();
            shot("05-accounts");

            tap(By.res(packageName, "nav_more"));
            find(By.text("Reports"));
            shot("06-more");

            tap(By.res(packageName, "nav_overview"));
            tap(By.res(packageName, "fab"));
            find(By.res(packageName, "amount"));
            hideKeyboard();
            // the test server has a shared "Family" book, so the book field is offered
            find(By.res(packageName, "book"));
            settle();
            shot("07-add-transaction");

            type(By.res(packageName, "amount"), "12.50");
            hideKeyboard();
            tap(By.res(packageName, "category"));
            find(By.text("Restaurants"));
            settle();
            shot("08-choose-category");
            tap(By.text("Restaurants"));

            tap(By.res(packageName, "account"));
            find(By.text("Credit cards"));
            settle();
            shot("09-choose-account");
            tap(By.text("Credit card"));

            scrollTo(By.res(packageName, "project")).click();
            tap(By.text("Holiday"));
            find(By.res(packageName, "project").text("Holiday"));

            scrollTo(By.res(packageName, "merchant")).click();
            tap(By.text("Add a merchant"));
            type(By.clazz("android.widget.EditText").focused(true), "Corner Café");
            tap(By.text("Add"));
            find(By.res(packageName, "merchant").text("Corner Café"));

            scrollTo(By.res(packageName, "comment")).setText("Lunch");
            hideKeyboard();
            settle();
            shot("10-filled-transaction");
            scrollTo(By.res(packageName, "save_button")).click();

            find(By.textContains("Lunch"));
            settle();
            shot("11-overview-after-adding");

            tap(By.textContains("Lunch"));
            find(By.res(packageName, "merchant").text("Corner Café"));
            scrollTo(By.res(packageName, "project").text("Holiday"));
            settle();
            shot("12-edit-transaction");
            // a saved transaction can be moved to the family book
            scrollTo(By.res(packageName, "book"));
            device.pressBack();

            // a new expense in the same category starts with the account used last time
            tap(By.res(packageName, "fab"));
            find(By.res(packageName, "amount"));
            hideKeyboard();
            tap(By.res(packageName, "category"));
            tap(By.text("Restaurants"));
            find(By.res(packageName, "account").text("Credit card"));
            settle();
            shot("13-remembered-account");

            // a new category can be added to a group straight from the category list
            tap(By.res(packageName, "category"));
            scrollSheetTo("Choose a category", By.text("Add a category")).click();
            tap(By.text("Food"));
            type(By.clazz("android.widget.EditText").focused(true), "Bakery");
            tap(By.text("Add"));
            find(By.res(packageName, "category").textContains("Bakery"));

            // a Google Wallet purchase waits under To review, with the shop's last category and that category's account
            PendingPayments.add(context, PaymentNotification.parse("Corner Café", "$18.20 with Visa •••• 1234"), System.currentTimeMillis(), "test");
            backTo(By.res(packageName, "review_card"));
            find(By.text("1 purchase to review"));
            settle();
            shot("14-to-review");
            find(By.res(packageName, "review_list")).findObject(By.text("Corner Café")).click();
            find(By.res(packageName, "category").textContains("Restaurants"));
            find(By.res(packageName, "account").text("Credit card"));
            find(By.res(packageName, "amount").text("18.20"));
            settle();
            shot("15-review-purchase");
            scrollTo(By.res(packageName, "merchant").text("Corner Café"));
            scrollTo(By.res(packageName, "save_button")).click();

            if (!device.wait(Until.gone(By.res(packageName, "review_card")), TIMEOUT)) {
                throw new AssertionError("The saved purchase is still waiting for review");
            }

            tap(By.res(packageName, "action_settings"));
            find(By.text("Theme"));
            shot("16-settings");
            device.pressBack();

            tap(By.res(packageName, "nav_more"));
            tap(By.text("Reports"));
            device.wait(Until.hasObject(By.pkg(packageName).clazz("android.webkit.WebView")), TIMEOUT);
            // the web app is a large bundle, so give it time to start and draw the report
            device.wait(Until.hasObject(By.pkg(packageName).textContains("Expense")), TIMEOUT);
            Thread.sleep(3000);
            shot("17-web-reports");

            for (int i = 0; i < 5 && !device.hasObject(By.res(packageName, "bottom_nav")); i++) {
                // a system "isn't responding" dialog would swallow the back press
                dismissSystemDialogs();
                device.pressBack();
                device.waitForIdle();
                Thread.sleep(1000);
            }

            // switch to dark through the app's own setting; changing the system night mode restarts Play services on the emulator
            tap(By.res(packageName, "action_settings"));
            tap(By.text("Theme"));
            tap(By.text("Dark"));
            find(By.text("Theme"));
            settle();
            shot("18-settings-dark");
            device.pressBack();

            tap(By.res(packageName, "nav_overview"));
            // the purchases saved above are now the latest transactions
            find(By.textContains("Lunch"));
            settle();
            shot("19-overview-dark");

            // move the lunch to the family book, where the category and card have the same names
            find(By.textContains("Lunch")).click();
            find(By.res(packageName, "merchant").text("Corner Café"));
            scrollTo(By.res(packageName, "book")).click();
            find(By.text("Family")).click();
            find(By.text("Change")).click();
            find(By.res(packageName, "category").textContains("Restaurants"));
            // a shared account shows its owner, as in "Credit card (Alex)"
            find(By.res(packageName, "account").textStartsWith("Credit card"));
            settle();
            shot("20-move-to-family");
            scrollTo(By.res(packageName, "save_button")).click();

            if (!device.wait(Until.gone(By.textContains("Lunch")), TIMEOUT)) {
                throw new AssertionError("The moved transaction is still in my book");
            }
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
            dismissSystemDialogs();
            device.pressBack();
            UiObject2 found = device.wait(Until.findObject(selector), 3000);

            if (found != null) {
                return found;
            }
        }

        return find(selector);
    }

    /** Scrolls the form until the field is on screen, since the add screen is taller than the phone. */
    /** Scrolls the list in the choice sheet with the given title until the selector shows. */
    private UiObject2 scrollSheetTo(String title, BySelector selector) {
        find(By.text(title));
        UiObject2 found = device.findObject(selector);

        for (int i = 0; i < 6 && (found == null || found.getVisibleBounds().height() < 20); i++) {
            // a short list fits on screen and isn't scrollable at all
            UiObject2 sheet = device.findObject(By.scrollable(true).hasDescendant(By.text(title)));

            if (sheet == null) {
                break;
            }

            sheet.scroll(Direction.DOWN, 0.6f);
            device.waitForIdle();
            found = device.findObject(selector);
        }

        if (found == null) {
            throw new AssertionError("Not found in the sheet: " + selector);
        }

        return found;
    }

    private UiObject2 scrollTo(BySelector selector) {
        UiObject2 scroll = find(By.res(packageName, "scroll"));
        UiObject2 found = device.findObject(selector);

        for (int i = 0; i < 6 && (found == null || found.getVisibleBounds().height() < 20); i++) {
            try {
                scroll.scroll(Direction.DOWN, 0.6f);
            } catch (StaleObjectException e) {
                // a system dialog over the app makes the scroll view stale; close it and look again
                dismissSystemDialogs();
                scroll = find(By.res(packageName, "scroll"));
            }

            device.waitForIdle();
            found = device.findObject(selector);
        }

        if (found == null) {
            throw new AssertionError("Not found on screen: " + selector);
        }

        return found;
    }

    // fields in a dialog can be rebuilt while it opens, so look the field up again if it goes stale
    private void type(BySelector selector, String text) {
        for (int attempt = 1; ; attempt++) {
            try {
                find(selector).setText(text);
                return;
            } catch (StaleObjectException e) {
                if (attempt == 3) {
                    throw e;
                }
                device.waitForIdle();
            }
        }
    }

    /** Clicks what the selector finds, finding it again if the screen redrew it in between. */
    private void tap(BySelector selector) {
        for (int attempt = 1; ; attempt++) {
            try {
                find(selector).click();
                return;
            } catch (StaleObjectException e) {
                if (attempt == 3) {
                    throw e;
                }

                device.waitForIdle();
            }
        }
    }

    private UiObject2 find(BySelector selector) {
        long deadline = System.currentTimeMillis() + TIMEOUT;

        while (System.currentTimeMillis() < deadline) {
            UiObject2 object = device.wait(Until.findObject(selector), 2000);

            if (object != null) {
                return object;
            }

            dismissSystemDialogs();
        }

        throw new AssertionError("Not found on screen: " + selector);
    }

    /** A freshly booted emulator sometimes shows "Pixel Launcher isn't responding" over the app; keep waiting. */
    private void dismissSystemDialogs() {
        UiObject2 wait = device.findObject(By.res("android", "aerr_wait"));

        if (wait != null) {
            wait.click();
            device.waitForIdle();
        }
    }

    // Pressing back with no keyboard showing would leave the screen, so only press it when one is up.
    // the keyboard can open after hideKeyboard looked for it and cover a button at the bottom of the form
    private void tapBelowKeyboard(BySelector selector) {
        // the keyboard can also come back after being closed, so keep closing it (or scroll the form) a few times
        for (int i = 0; i < 4 && !device.wait(Until.hasObject(selector), 3000); i++) {
            if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) {
                device.pressBack();
            } else {
                UiObject2 scroll = device.findObject(By.res(packageName, "scroll"));

                if (scroll != null) {
                    scroll.scroll(Direction.DOWN, 0.6f);
                }
            }

            device.waitForIdle();
        }

        tap(selector);
    }

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
