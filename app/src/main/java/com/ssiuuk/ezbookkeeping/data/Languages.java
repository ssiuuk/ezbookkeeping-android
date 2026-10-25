package com.ssiuuk.ezbookkeeping.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/** The languages the server's web app offers, and the one this app shows (empty: the phone's language). */
public final class Languages {
    /** Language tags, in the order of the server's language list. */
    public static final String[] TAGS = {
        "de", "el", "en", "es", "fr", "it", "ja", "kn", "ko", "nl", "pl",
        "pt-BR", "ro", "ru", "sl", "ta", "th", "tr", "uk", "vi", "zh-Hans", "zh-Hant"
    };

    /** Each language's name in itself, as the server shows it. */
    public static final String[] NAMES = {
        "Deutsch", "Ελληνικά", "English", "Español", "Français", "Italiano", "日本語", "ಕನ್ನಡ", "한국어", "Nederlands", "Polski",
        "Português (Brasil)", "Română", "Русский", "Slovenščina", "தமிழ்", "ไทย", "Türkçe", "Українська", "Tiếng Việt", "简体中文", "繁體中文"
    };

    private static final String PREFS = "languages";
    private static final String KEY_TAG = "tag";

    private Languages() {
    }

    /** The chosen language tag, or "" to follow the phone. */
    public static String current(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // the language can also be changed in Android's own settings
            LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
            return locales.isEmpty() ? "" : matchTag(locales.get(0));
        }

        return prefs(context).getString(KEY_TAG, "");
    }

    public static void choose(Context context, String tag) {
        prefs(context).edit().putString(KEY_TAG, tag).apply();
        AppCompatDelegate.setApplicationLocales(tag.isEmpty() ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(tag));
    }

    public static String name(String tag) {
        for (int i = 0; i < TAGS.length; i++) {
            if (TAGS[i].equals(tag)) {
                return NAMES[i];
            }
        }

        return tag;
    }

    public static Locale locale(Context context) {
        String tag = current(context);
        return tag.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(tag);
    }

    /**
     * A context whose text is in the chosen language, for places outside the app's screens (the widget, error messages),
     * where Android before 13 only knows the phone's language.
     */
    public static Context localized(Context context) {
        String tag = current(context);

        if (tag.isEmpty()) {
            return context;
        }

        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(tag));
        return context.createConfigurationContext(configuration);
    }

    /** The closest tag of the list for a locale, such as zh-Hant for zh-TW. */
    static String matchTag(Locale locale) {
        String language = locale.getLanguage();

        if ("zh".equals(language)) {
            String script = locale.getScript();
            String country = locale.getCountry();
            boolean traditional = "Hant".equals(script) || (script.isEmpty() && ("TW".equals(country) || "HK".equals(country) || "MO".equals(country)));
            return traditional ? "zh-Hant" : "zh-Hans";
        }

        if ("pt".equals(language)) {
            return "pt-BR";
        }

        for (String tag : TAGS) {
            if (tag.equals(language)) {
                return tag;
            }
        }

        return locale.toLanguageTag();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
