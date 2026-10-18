package com.ssiuuk.ezbookkeeping.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The pieces of server data the native screens show, read from the API's JSON. */
public final class Models {
    public static final int TRANSACTION_MODIFY_BALANCE = 1;
    public static final int TRANSACTION_INCOME = 2;
    public static final int TRANSACTION_EXPENSE = 3;
    public static final int TRANSACTION_TRANSFER = 4;

    public static final int CATEGORY_INCOME = 1;
    public static final int CATEGORY_EXPENSE = 2;
    public static final int CATEGORY_TRANSFER = 3;

    private Models() {
    }

    public static int categoryTypeOf(int transactionType) {
        switch (transactionType) {
            case TRANSACTION_INCOME:
                return CATEGORY_INCOME;
            case TRANSACTION_TRANSFER:
                return CATEGORY_TRANSFER;
            default:
                return CATEGORY_EXPENSE;
        }
    }

    public static final class Account {
        public String id;
        public String name;
        public String parentId;
        public int category;
        public int type;
        public String color;
        public String currency;
        public long balance;
        public boolean hidden;
        public boolean isAsset;
        public boolean isLiability;
        public String sharedBy;
        public final List<Account> subAccounts = new ArrayList<>();

        public static Account parse(JSONObject json) {
            Account account = new Account();
            account.id = json.optString("id");
            account.name = json.optString("name");
            account.parentId = json.optString("parentId", "0");
            account.category = json.optInt("category");
            account.type = json.optInt("type");
            account.color = json.optString("color");
            account.currency = json.optString("currency");
            account.balance = parseLong(json.optString("balance", "0"));
            account.hidden = json.optBoolean("hidden");
            account.isAsset = json.optBoolean("isAsset");
            account.isLiability = json.optBoolean("isLiability");
            account.sharedBy = json.optString("sharedBy", "");

            JSONArray subAccounts = json.optJSONArray("subAccounts");

            if (subAccounts != null) {
                for (int i = 0; i < subAccounts.length(); i++) {
                    JSONObject sub = subAccounts.optJSONObject(i);

                    if (sub != null) {
                        account.subAccounts.add(parse(sub));
                    }
                }
            }

            return account;
        }

        /** Accounts with sub-accounts hold their money in the sub-accounts, so transactions use those. */
        public boolean hasSubAccounts() {
            return !subAccounts.isEmpty();
        }

        public long getTotalBalance() {
            if (!hasSubAccounts()) {
                return balance;
            }

            long total = 0;

            for (Account sub : subAccounts) {
                total += sub.balance;
            }

            return total;
        }

        public static List<Account> parseList(Object result) {
            List<Account> list = new ArrayList<>();
            JSONArray array = result instanceof JSONArray ? (JSONArray) result : new JSONArray();

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    list.add(parse(json));
                }
            }

            return list;
        }

        /** Every account money can be booked to: plain accounts and sub-accounts, without hidden ones. */
        public static List<Account> selectable(List<Account> accounts) {
            List<Account> list = new ArrayList<>();

            for (Account account : accounts) {
                if (account.hidden) {
                    continue;
                }

                if (account.hasSubAccounts()) {
                    for (Account sub : account.subAccounts) {
                        if (!sub.hidden) {
                            if (sub.name == null || sub.name.isEmpty()) {
                                sub.name = account.name;
                            } else if (!sub.name.startsWith(account.name)) {
                                sub.name = account.name + " · " + sub.name;
                            }

                            list.add(sub);
                        }
                    }
                } else {
                    list.add(account);
                }
            }

            return list;
        }
    }

    public static final class Category {
        public String id;
        public String name;
        public String parentId;
        public int type;
        public String color;
        public boolean hidden;
        public final List<Category> subCategories = new ArrayList<>();

        public static Category parse(JSONObject json) {
            Category category = new Category();
            category.id = json.optString("id");
            category.name = json.optString("name");
            category.parentId = json.optString("parentId", "0");
            category.type = json.optInt("type");
            category.color = json.optString("color");
            category.hidden = json.optBoolean("hidden");

            JSONArray subCategories = json.optJSONArray("subCategories");

            if (subCategories != null) {
                for (int i = 0; i < subCategories.length(); i++) {
                    JSONObject sub = subCategories.optJSONObject(i);

                    if (sub != null) {
                        category.subCategories.add(parse(sub));
                    }
                }
            }

            return category;
        }

        /** The category list comes back grouped by category type, as {"1": [...], "2": [...]}. */
        public static List<Category> parseByType(Object result, int type) {
            List<Category> list = new ArrayList<>();

            if (!(result instanceof JSONObject)) {
                return list;
            }

            JSONArray array = ((JSONObject) result).optJSONArray(String.valueOf(type));

            if (array == null) {
                return list;
            }

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    Category category = parse(json);

                    if (!category.hidden) {
                        list.add(category);
                    }
                }
            }

            return list;
        }
    }

    public static final class Tag {
        public String id;
        public String name;
        public String groupId;
        public boolean hidden;

        public static Tag parse(JSONObject json) {
            Tag tag = new Tag();
            tag.id = json.optString("id");
            tag.name = json.optString("name");
            tag.groupId = json.optString("groupId", "0");
            tag.hidden = json.optBoolean("hidden");
            return tag;
        }

        public static List<Tag> parseList(Object result) {
            List<Tag> list = new ArrayList<>();
            JSONArray array = result instanceof JSONArray ? (JSONArray) result : new JSONArray();

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    list.add(parse(json));
                }
            }

            return list;
        }
    }

    /** A named group of tags, such as the "Merchants" group the app uses for merchants. */
    public static final class TagGroup {
        public String id;
        public String name;

        public static TagGroup parse(JSONObject json) {
            TagGroup group = new TagGroup();
            group.id = json.optString("id");
            group.name = json.optString("name");
            return group;
        }

        public static List<TagGroup> parseList(Object result) {
            List<TagGroup> list = new ArrayList<>();
            JSONArray array = result instanceof JSONArray ? (JSONArray) result : new JSONArray();

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    list.add(parse(json));
                }
            }

            return list;
        }
    }

    public static final class Project {
        public String id;
        public String name;
        public boolean hidden;

        public static List<Project> parseList(Object result) {
            List<Project> list = new ArrayList<>();
            JSONArray array = result instanceof JSONArray ? (JSONArray) result : new JSONArray();

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    Project project = new Project();
                    project.id = json.optString("id");
                    project.name = json.optString("name");
                    project.hidden = json.optBoolean("hidden");
                    list.add(project);
                }
            }

            return list;
        }
    }

    /** A picture uploaded for a transaction. */
    public static final class Picture {
        public String id;
        public String url;

        public static Picture parse(Object result) {
            JSONObject json = result instanceof JSONObject ? (JSONObject) result : new JSONObject();
            Picture picture = new Picture();
            picture.id = json.optString("pictureId");
            picture.url = json.optString("originalUrl");
            return picture;
        }
    }

    public static final class Book {
        public String id;
        public String name;
        public String ownerNickname;
        public boolean isPersonal;
        public boolean isOwner;

        public static List<Book> parseList(Object result) {
            List<Book> list = new ArrayList<>();
            JSONArray array = result instanceof JSONArray ? (JSONArray) result : new JSONArray();

            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);

                if (json != null) {
                    Book book = new Book();
                    book.id = json.optString("id");
                    book.name = json.optString("name");
                    book.ownerNickname = json.optString("ownerNickname");
                    book.isPersonal = json.optBoolean("isPersonal");
                    book.isOwner = json.optBoolean("isOwner");
                    list.add(book);
                }
            }

            return list;
        }
    }

    public static final class Transaction {
        public String id;
        public String timeSequenceId;
        public int type;
        public String categoryId;
        public String categoryName;
        public String categoryColor;
        public long time;
        public int utcOffset;
        public String sourceAccountId;
        public String sourceAccountName;
        public String sourceCurrency;
        public String destinationAccountId;
        public String destinationAccountName;
        public String destinationCurrency;
        public long sourceAmount;
        public long destinationAmount;
        public boolean hideAmount;
        public final List<String> tagIds = new ArrayList<>();
        public final List<String> tagNames = new ArrayList<>();
        public String comment;
        public boolean editable;
        public JSONObject geoLocation;
        public final List<String> pictureIds = new ArrayList<>();
        public final List<String> pictureUrls = new ArrayList<>();
        public String projectId;

        public static Transaction parse(JSONObject json) {
            Transaction transaction = new Transaction();
            transaction.id = json.optString("id");
            transaction.timeSequenceId = json.optString("timeSequenceId");
            transaction.type = json.optInt("type");
            transaction.categoryId = json.optString("categoryId", "0");
            transaction.time = json.optLong("time");
            transaction.utcOffset = json.optInt("utcOffset");
            transaction.sourceAccountId = json.optString("sourceAccountId");
            transaction.destinationAccountId = json.optString("destinationAccountId", "0");
            transaction.sourceAmount = json.optLong("sourceAmount");
            transaction.destinationAmount = json.optLong("destinationAmount");
            transaction.hideAmount = json.optBoolean("hideAmount");
            transaction.comment = json.optString("comment", "");
            transaction.editable = json.optBoolean("editable", true);
            transaction.geoLocation = json.optJSONObject("geoLocation");
            transaction.projectId = json.optString("projectId", "0");

            JSONObject category = json.optJSONObject("category");

            if (category != null) {
                transaction.categoryName = category.optString("name");
                transaction.categoryColor = category.optString("color");
            }

            JSONObject source = json.optJSONObject("sourceAccount");

            if (source != null) {
                transaction.sourceAccountName = source.optString("name");
                transaction.sourceCurrency = source.optString("currency");
            }

            JSONObject destination = json.optJSONObject("destinationAccount");

            if (destination != null) {
                transaction.destinationAccountName = destination.optString("name");
                transaction.destinationCurrency = destination.optString("currency");
            }

            JSONArray tagIds = json.optJSONArray("tagIds");

            if (tagIds != null) {
                for (int i = 0; i < tagIds.length(); i++) {
                    transaction.tagIds.add(tagIds.optString(i));
                }
            }

            JSONArray tags = json.optJSONArray("tags");

            if (tags != null) {
                for (int i = 0; i < tags.length(); i++) {
                    JSONObject tag = tags.optJSONObject(i);

                    if (tag != null) {
                        transaction.tagNames.add(tag.optString("name"));
                    }
                }
            }

            JSONArray pictures = json.optJSONArray("pictures");

            if (pictures != null) {
                for (int i = 0; i < pictures.length(); i++) {
                    JSONObject picture = pictures.optJSONObject(i);

                    if (picture != null) {
                        transaction.pictureIds.add(picture.optString("pictureId"));
                        transaction.pictureUrls.add(picture.optString("originalUrl"));
                    }
                }
            }

            return transaction;
        }
    }

    public static final class TransactionPage {
        public final List<Transaction> items = new ArrayList<>();
        public String nextTimeSequenceId;

        public static TransactionPage parse(Object result) {
            TransactionPage page = new TransactionPage();

            if (!(result instanceof JSONObject)) {
                return page;
            }

            JSONObject json = (JSONObject) result;
            JSONArray items = json.optJSONArray("items");

            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);

                    if (item != null) {
                        page.items.add(Transaction.parse(item));
                    }
                }
            }

            if (!json.isNull("nextTimeSequenceId")) {
                String next = json.optString("nextTimeSequenceId", "");
                page.nextTimeSequenceId = next.isEmpty() || next.equals("0") ? null : next;
            }

            return page;
        }
    }

    /** Income and expense of one period, per currency, from transactions/amounts.json. */
    public static final class PeriodAmounts {
        public final List<String> currencies = new ArrayList<>();
        public final List<Long> incomes = new ArrayList<>();
        public final List<Long> expenses = new ArrayList<>();

        public static java.util.Map<String, PeriodAmounts> parseAll(Object result) throws JSONException {
            java.util.Map<String, PeriodAmounts> map = new java.util.HashMap<>();

            if (!(result instanceof JSONObject)) {
                return map;
            }

            JSONObject json = (JSONObject) result;
            Iterator<String> keys = json.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject item = json.getJSONObject(key);
                JSONArray amounts = item.optJSONArray("amounts");
                PeriodAmounts period = new PeriodAmounts();

                if (amounts != null) {
                    for (int i = 0; i < amounts.length(); i++) {
                        JSONObject amount = amounts.getJSONObject(i);
                        period.currencies.add(amount.optString("currency"));
                        period.incomes.add(parseLong(amount.optString("incomeAmount", "0")));
                        period.expenses.add(parseLong(amount.optString("expenseAmount", "0")));
                    }
                }

                map.put(key, period);
            }

            return map;
        }
    }

    /** Exchange rates against one base currency, for showing totals in the user's default currency. */
    public static final class ExchangeRates {
        public final java.util.Map<String, Double> rates = new java.util.HashMap<>();

        public static ExchangeRates parse(Object result) {
            ExchangeRates exchangeRates = new ExchangeRates();

            if (!(result instanceof JSONObject)) {
                return exchangeRates;
            }

            JSONObject json = (JSONObject) result;
            exchangeRates.rates.put(json.optString("baseCurrency"), 1.0);
            JSONArray array = json.optJSONArray("exchangeRates");

            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject rate = array.optJSONObject(i);

                    if (rate != null) {
                        try {
                            exchangeRates.rates.put(rate.optString("currency"), Double.parseDouble(rate.optString("rate")));
                        } catch (NumberFormatException ex) {
                            // skip a rate the server couldn't give
                        }
                    }
                }
            }

            return exchangeRates;
        }

        /** Converts an amount in cents, or returns null when either rate is unknown. */
        public Long convert(long amount, String from, String to) {
            if (from.equals(to)) {
                return amount;
            }

            Double fromRate = rates.get(from);
            Double toRate = rates.get(to);

            if (fromRate == null || toRate == null || fromRate == 0) {
                return null;
            }

            return Math.round(amount / fromRate * toRate);
        }
    }

    static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
