package com.example.bezellists

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import dev.bezel.client.Bezel
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

const val FACET = "lists/v1"
const val CLIENT = "Lists (Android) v0.3"

// ---------------------------------------------------------------- store
// Offline-first: the item cache renders instantly on launch, and every
// mutation is committed to the on-disk outbox BEFORE any network is
// attempted — killing the app mid-write loses nothing; the op replays
// on next launch.

private object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("bezel", Context.MODE_PRIVATE)

    fun getConfig(ctx: Context, key: String): String = prefs(ctx).getString(key, "") ?: ""

    /** `ttl` is the token lifetime observed at connect (exp − now), the
     * lifetime every refresh preserves; 0 means the token never expires. */
    fun setConfig(ctx: Context, server: String, token: String, ttl: Long) {
        prefs(ctx).edit()
            .putString("server", server).putString("token", token).putLong("ttl", ttl)
            .apply()
    }

    fun setToken(ctx: Context, token: String) {
        prefs(ctx).edit().putString("token", token).apply()
    }

    fun getTtl(ctx: Context): Long = prefs(ctx).getLong("ttl", 0L)

    /** The device's iroh identity: 32 random bytes, minted once, kept
     * forever — so source.addr names this phone stably. */
    fun identityHex(ctx: Context): String {
        prefs(ctx).getString("identity", null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("identity", hex).apply()
        return hex
    }

    fun loadItems(ctx: Context): List<JSONObject> =
        parseArray(prefs(ctx).getString("cache", null))

    fun saveItems(ctx: Context, items: List<JSONObject>) {
        prefs(ctx).edit().putString("cache", JSONArray(items).toString()).apply()
    }

    fun hasCache(ctx: Context): Boolean = prefs(ctx).contains("cache")

    fun loadOutbox(ctx: Context): List<JSONObject> =
        parseArray(prefs(ctx).getString("outbox", null))

    /** Durable BEFORE returning: commit(), not apply(). This is the
     * moment a mutation becomes kill-proof. */
    @SuppressLint("ApplySharedPref")
    fun saveOutbox(ctx: Context, ops: List<JSONObject>) {
        prefs(ctx).edit().putString("outbox", JSONArray(ops).toString()).commit()
    }

    fun enqueue(ctx: Context, op: JSONObject) = saveOutbox(ctx, loadOutbox(ctx) + op)

    private fun parseArray(s: String?): List<JSONObject> {
        val arr = JSONArray(s ?: "[]")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }
}

// ---------------------------------------------------------------- capability
// Tokens carry their own expiry; the app refreshes at half-life via
// POST /v1/capabilities/refresh, always requesting the lifetime the
// admin chose at mint time — refresh moves time, not privilege.

/** The token's exp claim (unix seconds), or null when it never expires
 * (or the string isn't a bezel token — treated the same: never refresh). */
fun tokenExp(token: String): Long? = try {
    val payload = token.split(".").getOrNull(1)
    val json = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE)))
    if (json.isNull("exp")) null else json.getLong("exp")
} catch (_: Exception) {
    null
}

private val refreshInFlight = AtomicBoolean(false)

private class Refresh(val token: String?, val expired: Boolean)

/** Refresh when less than half the recorded ttl remains. Returns the
 * fresh token to persist and surface, or flags the token as dead; both
 * null/false when there is nothing to do. Transport failures stay
 * silent — the next sync re-evaluates from scratch. */
private fun maybeRefresh(ctx: Context, current: String): Refresh {
    val none = Refresh(null, false)
    val ttl = Store.getTtl(ctx)
    if (ttl <= 0) return none
    val exp = tokenExp(current) ?: return none
    if (exp - System.currentTimeMillis() / 1000 >= ttl / 2) return none
    if (!refreshInFlight.compareAndSet(false, true)) return none
    try {
        val r = Bezel.refreshCapability(ttl)
        return if (r.optBoolean("ok")) {
            val fresh = r.getString("token")
            Store.setToken(ctx, fresh)
            Refresh(fresh, false)
        } else {
            Refresh(null, r.optString("error").contains("401"))
        }
    } finally {
        refreshInFlight.set(false)
    }
}

// ---------------------------------------------------------------- api

private fun registerFacet() {
    // 409 = exists, 403 = narrower token; both fine.
    val schema = JSONObject(
        """{"type":"object","required":["list","name"],"properties":{
            "list":{"type":"string","minLength":1},
            "name":{"type":"string","minLength":1},
            "description":{"type":"string"},
            "link":{"type":"string"},
            "attributes":{"type":"object","additionalProperties":{"anyOf":[
                {"type":["string","number","boolean","null"]},{"type":"array"}]}}
        },"additionalProperties":false}"""
    )
    val body = JSONObject()
        .put("facet", "facet")
        .put("body", JSONObject().put("name", FACET).put("strict", true).put("schema", schema))
    Bezel.request("POST", "/v1/items", body.toString())
}

private fun fetchItems(): Pair<List<JSONObject>?, String?> {
    val r = Bezel.request("GET", "/v1/items?facet=lists/v1&limit=1000")
    if (r.getInt("status") != 200) {
        return null to (r.optString("error").ifEmpty { "status ${r.getInt("status")}" })
    }
    val arr = r.getJSONObject("body").getJSONArray("items")
    return (0 until arr.length()).map { arr.getJSONObject(it) } to null
}

// ---------------------------------------------------------------- outbox

/** Send one op. Returns null to keep it queued (transport failure), or a
 * disposition string when it leaves the queue ("ok" or a drop reason —
 * permanent rejections like schema violations don't retry forever). */
private fun sendOp(op: JSONObject): String? {
    fun transportDown(r: JSONObject) = r.getInt("status") == 0
    return when (op.getString("op")) {
        "create" -> {
            val req = JSONObject().put("facet", FACET).put("body", op.getJSONObject("body"))
            val r = Bezel.request("POST", "/v1/items", req.toString())
            when {
                r.getInt("status") == 201 -> "ok"
                transportDown(r) -> null
                else -> "dropped create: " + detail(r)
            }
        }
        "update" -> {
            val id = op.getString("id")
            var revision = op.getLong("revision")
            repeat(2) { attempt ->
                val req = JSONObject().put("body", op.getJSONObject("body")).put("revision", revision)
                val r = Bezel.request("PUT", "/v1/items/$id", req.toString())
                when {
                    r.getInt("status") == 200 -> return "ok"
                    r.getInt("status") == 409 && attempt == 0 -> {
                        // Someone wrote meanwhile: take their revision, replay ours on top.
                        val fresh = Bezel.request("GET", "/v1/items/$id")
                        if (fresh.getInt("status") != 200) return "dropped update: item gone"
                        revision = fresh.getJSONObject("body").getLong("revision")
                    }
                    transportDown(r) -> return null
                    else -> return "dropped update: " + detail(r)
                }
            }
            "dropped update: revision conflict persisted"
        }
        "delete" -> {
            val r = Bezel.request("DELETE", "/v1/items/${op.getString("id")}")
            when {
                r.getInt("status") == 204 || r.getInt("status") == 404 -> "ok"
                transportDown(r) -> null
                else -> "dropped delete: " + detail(r)
            }
        }
        else -> "dropped unknown op"
    }
}

private fun detail(r: JSONObject): String =
    r.optJSONObject("body")?.optString("detail")?.ifEmpty { null }
        ?: r.optString("error").ifEmpty { "status ${r.getInt("status")}" }

/** Drain in order; stop at the first transport failure so ordering holds.
 * Returns the last drop reason, if any op was rejected permanently. */
private fun drainOutbox(ctx: Context): String? {
    var dropReason: String? = null
    var box = Store.loadOutbox(ctx)
    while (box.isNotEmpty()) {
        val disposition = sendOp(box.first()) ?: break
        if (disposition != "ok") dropReason = disposition
        box = box.drop(1)
        Store.saveOutbox(ctx, box)
    }
    return dropReason
}

/** The truth the user sees: the server snapshot with every queued op
 * replayed on top, so a mutation is visible the instant it's enqueued. */
private fun applyPending(server: List<JSONObject>, ops: List<JSONObject>): List<JSONObject> {
    var result = server
    for (op in ops) {
        result = when (op.getString("op")) {
            "create" -> result + JSONObject()
                .put("id", op.getString("tmp"))
                .put("body", op.getJSONObject("body"))
                .put("revision", 0L)
            "update" -> result.map {
                if (it.getString("id") == op.getString("id")) {
                    JSONObject(it.toString()).put("body", op.getJSONObject("body"))
                } else it
            }
            "delete" -> result.filter { it.getString("id") != op.getString("id") }
            else -> result
        }
    }
    return result
}

// ---------------------------------------------------------------- root

private sealed class Screen {
    data object Main : Screen()
    data class Editor(val item: JSONObject?) : Screen()
    data object Settings : Screen()
}

@Composable
fun ListsApp() {
    val ctx = LocalContext.current
    var server by remember { mutableStateOf(Store.getConfig(ctx, "server")) }
    var token by remember { mutableStateOf(Store.getConfig(ctx, "token")) }
    var connected by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("not connected") }
    var items by remember { mutableStateOf(Store.loadItems(ctx)) }
    var haveData by remember { mutableStateOf(Store.hasCache(ctx)) }
    var outbox by remember { mutableStateOf(Store.loadOutbox(ctx)) }
    var selected by remember { mutableStateOf<String?>(null) }
    // A just-created, still-empty list: it exists only while selected.
    var draftList by remember { mutableStateOf<String?>(null) }
    var screen by remember {
        mutableStateOf<Screen>(
            if (Store.getConfig(ctx, "server").isBlank()) Screen.Settings else Screen.Main
        )
    }
    var syncTick by remember { mutableStateOf(0) }

    /** Durable-first mutation: commit to the outbox, show it, then sync. */
    fun mutate(op: JSONObject) {
        Store.enqueue(ctx, op)
        outbox = Store.loadOutbox(ctx)
        syncTick += 1
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        val refreshed = maybeRefresh(ctx, token)
        if (refreshed.token != null || refreshed.expired) {
            withContext(Dispatchers.Main) {
                refreshed.token?.let { token = it }
                if (refreshed.expired) status = "token expired · paste a new one"
            }
        }
        val drop = drainOutbox(ctx)
        val (fetched, err) = fetchItems()
        withContext(Dispatchers.Main) {
            outbox = Store.loadOutbox(ctx)
            if (fetched != null) {
                items = fetched
                haveData = true
                Store.saveItems(ctx, fetched)
                status = drop ?: if (outbox.isEmpty()) "synced" else "${outbox.size} pending"
            } else {
                status = "offline: $err" + if (outbox.isEmpty()) "" else " · ${outbox.size} queued"
            }
        }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) { connecting = true; status = "dialing…" }
        val err = Bezel.configure(server, token, CLIENT, Store.identityHex(ctx))
        if (err == null) {
            registerFacet()
            withContext(Dispatchers.Main) { connected = true; status = "syncing…" }
            sync()
        } else {
            withContext(Dispatchers.Main) { status = err }
        }
        withContext(Dispatchers.Main) { connecting = false }
    }

    // Auto-connect with saved config; poll while connected.
    LaunchedEffect(Unit) {
        if (server.isNotBlank() && token.isNotBlank()) connect()
        while (true) {
            delay(10_000)
            if (connected) sync()
        }
    }
    LaunchedEffect(syncTick) { if (syncTick > 0 && connected) sync() }

    val shown = remember(items, outbox) { applyPending(items, outbox) }
    val lists = remember(shown, draftList) {
        (shown.map { it.getJSONObject("body").getString("list") } +
            listOfNotNull(draftList)).distinct().sorted()
    }

    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }

    // Sub-screens slide in from the right; going back slides them out.
    fun depth(s: Screen) = if (s is Screen.Main) 0 else 1
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (depth(targetState) > depth(initialState)) {
                (slideInHorizontally { it } + fadeIn())
                    .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn())
                    .togetherWith(slideOutHorizontally { it } + fadeOut())
            }
        },
        label = "screen",
    ) { s ->
        when (s) {
        is Screen.Main -> MainScreen(
            items = shown,
            lists = lists,
            selected = selected,
            haveData = haveData,
            statusLine = if (status == "synced") null else status,
            onSelect = { pick ->
                // A draft list vanishes the moment you look away, unless
                // an entry landed in it meanwhile.
                if (draftList != null && pick != draftList &&
                    shown.none { it.getJSONObject("body").getString("list") == draftList }
                ) draftList = null
                selected = pick
            },
            onNewList = { name -> draftList = name; selected = name },
            onOpen = { screen = Screen.Editor(it) },
            onAdd = { screen = Screen.Editor(null) },
            onSettings = { screen = Screen.Settings },
            onDelete = { item ->
                mutate(JSONObject().put("op", "delete").put("id", item.getString("id")))
            },
        )
        is Screen.Editor -> EditorScreen(
            item = s.item,
            defaultList = selected ?: "",
            lists = lists,
            onSave = { body ->
                if (s.item == null) {
                    mutate(JSONObject()
                        .put("op", "create")
                        .put("tmp", "pending-${UUID.randomUUID()}")
                        .put("body", body))
                } else {
                    mutate(JSONObject()
                        .put("op", "update")
                        .put("id", s.item.getString("id"))
                        .put("revision", s.item.getLong("revision"))
                        .put("body", body))
                }
                screen = Screen.Main
            },
            onBack = { screen = Screen.Main },
        )
        is Screen.Settings -> SettingsScreen(
            server = server,
            token = token,
            status = status,
            connecting = connecting,
            onConnect = { newServer, newToken ->
                server = newServer; token = newToken
                // The lifetime the admin chose, captured while it's observable.
                val ttl = tokenExp(newToken)
                    ?.let { maxOf(1L, it - System.currentTimeMillis() / 1000) } ?: 0L
                Store.setConfig(ctx, newServer, newToken, ttl)
                syncTick += 0 // connect below handles its own sync
                screen = Screen.Main
            },
            onBack = { screen = Screen.Main },
        )
        }
    }

    // Reconnect when settings hand back a (possibly new) server/token.
    LaunchedEffect(server, token) {
        if (server.isNotBlank() && token.isNotBlank() && !connected && !connecting) connect()
    }
}
