package com.ssiuuk.ezbookkeeping.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sending changes saved on the phone, against a stand-in for the server. */
@RunWith(AndroidJUnit4.class)
public class SyncTest {
    private static final long NOW = 1_800_000_000_000L;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final LocalStore store = LocalStore.get(context);
    private Server server;

    @Before
    public void setUp() {
        server = server("test-" + UUID.randomUUID(), "demo");
    }

    @After
    public void tearDown() {
        store.removeAll(server.id);
    }

    @Test
    public void editAfterTheAddWasSentChangesTheServersTransaction() throws Exception {
        LocalStore.Change queued = Sync.queue(context, server, null, LocalStore.KIND_ADD, null, newBody("Coffee"), null, null);
        // the add screen is still open on it while it is sent
        LocalStore.Change open = store.change(queued.id);
        FakeServer remote = new FakeServer();
        Sync.send(store, store.change(queued.id), remote, NOW);
        assertNull(store.change(queued.id));

        LocalStore.Change saved = Sync.saveEdit(context, server, open, newBody("Coffee and cake"), new JSONObject());

        assertEquals(LocalStore.KIND_MODIFY, saved.kind);
        assertEquals("1001", saved.transactionId);
        assertEquals("1001", saved.body.getString("id"));
        assertFalse(saved.body.has("clientSessionId"));
        assertEquals("Coffee", saved.base.getString("comment"));
        assertEquals(1, Sync.changes(context, server).size());
    }

    @Test
    public void editDuringTheAddIsSentAfterwardsAsAChange() throws Exception {
        LocalStore.Change queued = Sync.queue(context, server, null, LocalStore.KIND_ADD, null, newBody("Coffee"), null, null);
        LocalStore.Change open = store.change(queued.id);
        FakeServer remote = new FakeServer();
        // the user saves a newer version while the add is on its way
        remote.whileAdding = () -> Sync.saveEdit(context, server, open, newBody("Coffee and cake"), new JSONObject());
        Sync.send(store, store.change(queued.id), remote, NOW);

        LocalStore.Change latest = store.change(queued.id);
        assertNotNull(latest);
        assertEquals(LocalStore.KIND_MODIFY, latest.kind);
        assertEquals("1001", latest.transactionId);
        assertEquals("Coffee and cake", latest.body.getString("comment"));
        assertEquals("Coffee", latest.base.getString("comment"));
        assertEquals(1, remote.adds);

        // the next round sends the newer version as a change to that transaction
        Sync.send(store, latest, remote, NOW);
        assertNull(store.change(queued.id));
        assertEquals("Coffee and cake", remote.transactions.get(0).getString("comment"));
        assertEquals(1, remote.adds);
    }

    @Test
    public void addDeletedWhileOnItsWayIsDeletedOnTheServer() throws Exception {
        LocalStore.Change queued = Sync.queue(context, server, null, LocalStore.KIND_ADD, null, newBody("Coffee"), null, null);
        LocalStore.Change open = store.change(queued.id);
        FakeServer remote = new FakeServer();
        remote.whileAdding = () -> Sync.deleteEdit(context, server, open);

        // one run of the background sending, as the worker does it
        assertTrue(drain(remote));

        assertEquals(1, remote.adds);
        assertEquals(1, remote.deletes);
        assertTrue(remote.transactions.isEmpty());
        assertTrue(Sync.changes(context, server).isEmpty());
    }

    @Test
    public void lostAnswersNeverAddItTwice() throws Exception {
        FakeServer remote = new FakeServer();
        LocalStore.Change change = Sync.queue(context, server, null, LocalStore.KIND_ADD, null, newBody("Coffee"), null, null);

        // the add reaches the server but its answer is lost, twice, a few minutes apart
        remote.loseAnswers = 2;
        sendLosingAnswer(change.id, remote, NOW);
        sendLosingAnswer(change.id, remote, NOW + 3 * 60_000);
        // by now the server's duplicate check has forgotten the first one
        sendLosingAnswer(change.id, remote, NOW + 6 * 60_000);

        assertEquals(1, remote.transactions.size());
        assertEquals(LocalStore.STATE_CONFLICT, store.change(change.id).state);
    }

    /** One try at sending; a lost answer is the network failing after the server did the work, as the worker sees it. */
    private void sendLosingAnswer(long changeId, FakeServer remote, long now) throws Exception {
        LocalStore.Change change = store.change(changeId);

        if (!change.isWaiting()) {
            return;
        }

        remote.now = now;

        try {
            Sync.send(store, change, remote, now);
        } catch (ApiException ex) {
            assertTrue(ex.unreachable);
        }
    }

    private boolean drain(FakeServer remote) {
        return Sync.drain(store, id -> id.equals(server.id) ? server : null, (s, change) -> remote, "Unexpected");
    }

    @Test
    public void removingAReceiptIsAChange() throws Exception {
        JSONObject servers = serverCopy("1001", "Coffee").put("pictures", new JSONArray().put(new JSONObject().put("pictureId", "p1")));
        JSONObject withReceipt = newBody("Coffee").put("pictureIds", new JSONArray().put("p1"));
        JSONObject withoutReceipt = newBody("Coffee").put("pictureIds", new JSONArray());

        assertTrue(Sync.same(servers, withReceipt));
        assertFalse(Sync.same(servers, withoutReceipt));
        assertFalse(Sync.same(servers, new JSONObject(withReceipt.toString()).put("hideAmount", true)));
        assertFalse(Sync.same(servers, new JSONObject(withReceipt.toString()).put("utcOffset", 60)));
        assertFalse(Sync.same(servers, new JSONObject(withReceipt.toString())
            .put("geoLocation", new JSONObject().put("latitude", 51.5).put("longitude", -0.1))));

        // the removal is sent, not taken as already on the server
        FakeServer remote = new FakeServer();
        remote.transactions.add(servers);
        LocalStore.Change change = Sync.queue(context, server, null, LocalStore.KIND_MODIFY, "1001",
            withoutReceipt.put("id", "1001"), servers, null);
        Sync.send(store, store.change(change.id), remote, NOW);
        assertEquals(1, remote.modifies);
    }

    @Test
    public void receiptChangedOnTheServerIsAConflict() throws Exception {
        JSONObject base = serverCopy("1001", "Coffee").put("pictures", new JSONArray().put(new JSONObject().put("pictureId", "p1")));
        JSONObject current = serverCopy("1001", "Coffee").put("pictures", new JSONArray().put(new JSONObject().put("pictureId", "p2")));
        FakeServer remote = new FakeServer();
        remote.transactions.add(current);
        LocalStore.Change change = Sync.queue(context, server, null, LocalStore.KIND_MODIFY, "1001",
            newBody("Coffee and cake").put("id", "1001").put("pictureIds", new JSONArray().put("p1")), base, null);
        Sync.send(store, store.change(change.id), remote, NOW);

        assertEquals(LocalStore.STATE_CONFLICT, store.change(change.id).state);
        assertEquals(0, remote.modifies);
        assertTrue(Sync.differences(base, current).contains("pictureIds"));
    }

    @Test
    public void changesWaitForTheUserWhoMadeThem() throws Exception {
        Sync.queue(context, server, null, LocalStore.KIND_ADD, null, newBody("Coffee"), null, null);
        Server alex = server(server.id, "alex");

        assertEquals(1, Sync.changes(context, server).size());
        assertEquals(0, Sync.changes(context, alex).size());
        assertEquals(1, store.changes(server.id).size());
    }

    @Test
    public void similarTransactionAfterARetryIsLeftToTheUser() throws Exception {
        FakeServer remote = new FakeServer();
        remote.transactions.add(serverCopy("900", "Coffee"));
        LocalStore.Change change = Sync.queue(context, server, null, LocalStore.KIND_ADD, null, newBody("Coffee"), null, null);
        change = store.change(change.id);
        change.attempts = 1;
        change.lastAttemptAt = NOW - 3_600_000;
        store.updateIfUnchanged(change);
        Sync.send(store, store.change(change.id), remote, NOW);

        LocalStore.Change held = store.change(change.id);
        assertEquals(LocalStore.STATE_CONFLICT, held.state);
        assertEquals("900", held.serverCopy.getString("id"));
        assertEquals(0, remote.adds);

        // adding it anyway sends it as a new transaction
        Sync.keepMine(context, held);
        Sync.send(store, store.change(change.id), remote, NOW);
        assertEquals(1, remote.adds);
        assertNull(store.change(change.id));
    }

    @Test
    public void transactionAtAnotherShopIsNotTakenForIt() throws Exception {
        FakeServer remote = new FakeServer();
        remote.transactions.add(serverCopy("900", "Coffee").put("merchant", "Other Café"));
        LocalStore.Change change = Sync.queue(context, server, null, LocalStore.KIND_ADD, null,
            newBody("Coffee").put("merchant", "Corner Café"), null, null);
        change = store.change(change.id);
        change.attempts = 1;
        change.lastAttemptAt = NOW - 3_600_000;
        store.updateIfUnchanged(change);
        Sync.send(store, store.change(change.id), remote, NOW);

        assertEquals(1, remote.adds);
        assertNull(store.change(change.id));
    }

    /** A server that isn't in the app's list, so the app's own background sending leaves these changes alone. */
    private static Server server(String id, String userName) {
        Server server = new Server(id, "Test", "http://test");
        server.token = "token";
        server.userJson = "{\"username\":\"" + userName + "\",\"defaultCurrency\":\"GBP\"}";
        return server;
    }

    private static JSONObject newBody(String comment) throws Exception {
        return new JSONObject()
            .put("clientSessionId", UUID.randomUUID().toString())
            .put("type", 3)
            .put("categoryId", "10")
            .put("time", NOW / 1000)
            .put("utcOffset", 0)
            .put("sourceAccountId", "20")
            .put("destinationAccountId", "0")
            .put("sourceAmount", 480)
            .put("destinationAmount", 0)
            .put("hideAmount", false)
            .put("tagIds", new JSONArray())
            .put("pictureIds", new JSONArray())
            .put("projectId", "0")
            .put("comment", comment);
    }

    /** The server's JSON for a transaction like newBody's. */
    private static JSONObject serverCopy(String id, String comment) throws Exception {
        JSONObject json = newBody(comment);
        json.remove("clientSessionId");
        json.remove("pictureIds");
        return json.put("id", id);
    }

    private interface Step {
        void run() throws Exception;
    }

    /** Keeps transactions in a list and answers the four requests sending uses. */
    private static final class FakeServer implements Sync.Remote {
        final List<JSONObject> transactions = new ArrayList<>();
        Step whileAdding;
        int adds;
        int modifies;
        int deletes;
        /** How many adds are done but answered as if the network failed. */
        int loseAnswers;
        /** The server's clock, for its duplicate check on clientSessionId, which forgets after 5 minutes. */
        long now = NOW;
        private final Map<String, Long> seen = new java.util.HashMap<>();
        private final Map<String, JSONObject> bySession = new java.util.HashMap<>();
        private int nextId = 1001;

        @Override
        public Object call(String method, String path, Map<String, String> query, JSONObject body) throws ApiException {
            try {
                switch (path) {
                    case "v1/transactions/add.json": {
                        adds++;

                        if (whileAdding != null) {
                            whileAdding.run();
                        }

                        String session = body.optString("clientSessionId");
                        Long seenAt = seen.get(session);
                        JSONObject added;

                        if (seenAt != null && now - seenAt < 5 * 60_000) {
                            // the server's duplicate check: the earlier one comes back, and its record is not renewed
                            added = bySession.get(session);
                        } else {
                            added = new JSONObject(body.toString()).put("id", String.valueOf(nextId++));
                            added.remove("clientSessionId");
                            transactions.add(added);
                            seen.put(session, now);
                            bySession.put(session, added);
                        }

                        if (loseAnswers > 0) {
                            loseAnswers--;
                            throw ApiException.unreachable("lost");
                        }

                        return added;
                    }
                    case "v1/transactions/modify.json": {
                        modifies++;
                        JSONObject current = find(body.getString("id"));
                        transactions.set(transactions.indexOf(current), new JSONObject(body.toString()));
                        return body;
                    }
                    case "v1/transactions/delete.json": {
                        deletes++;
                        transactions.remove(find(body.getString("id")));
                        return true;
                    }
                    case "v1/transactions/get.json": {
                        JSONObject current = find(query.get("id"));

                        if (current == null) {
                            throw new ApiException(400, 205001, "Transaction not found");
                        }

                        return current;
                    }
                    case "v1/transactions/list/by_month.json":
                        return new JSONObject().put("items", new JSONArray(transactions));
                    default:
                        throw new ApiException(404, 0, "Not found: " + path);
                }
            } catch (ApiException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }

        private JSONObject find(String id) {
            for (JSONObject transaction : transactions) {
                if (transaction.optString("id").equals(id)) {
                    return transaction;
                }
            }

            return null;
        }
    }
}
