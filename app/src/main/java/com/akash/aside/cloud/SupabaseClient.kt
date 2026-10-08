package com.akash.aside.cloud

import android.content.Context
import com.akash.aside.BuildConfig
import com.akash.aside.Prefs
import com.akash.aside.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Talks to Supabase with nothing but the standard library: sign in once, then call the database functions
 * (never tables). The session is refreshed quietly; the user is asked again only if the refresh is refused.
 */
class SupabaseClient(
    private val ctx: Context,
    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/'),
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
) : Remote {
    private val refreshLock = Mutex()

    val configured get() = baseUrl.isNotBlank() && anonKey.isNotBlank()
    val signedIn get() = Prefs.session(ctx) != null
    val email get() = Prefs.session(ctx)?.email.orEmpty()

    // --- sign-in ---

    suspend fun signIn(email: String, password: String) {
        val (code, body) = send("POST", "/auth/v1/token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password), null)
        if (code == 400 || code == 401) throw RejectedError(code, "Wrong email or password")
        if (code !in 200..299) throw failure(code, body)
        saveSession(JSONObject(body), fallbackEmail = email.trim())
    }

    fun signOut() = Prefs.setSession(ctx, null)

    private fun saveSession(json: JSONObject, fallbackEmail: String = "") {
        val expiresIn = json.optLong("expires_in", 3600)
        Prefs.setSession(
            ctx,
            Prefs.Session(
                access = json.getString("access_token"),
                refresh = json.getString("refresh_token"),
                expiresAt = System.currentTimeMillis() + expiresIn * 1000,
                email = json.optJSONObject("user")?.optString("email").takeUnless { it.isNullOrEmpty() } ?: fallbackEmail,
            ),
        )
    }

    /** A token that is valid for at least another minute, refreshing it if needed. */
    private suspend fun token(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val s = Prefs.session(ctx) ?: throw AuthRequired("Not signed in")
        if (!forceRefresh && s.access.isNotEmpty() && s.expiresAt - System.currentTimeMillis() > 60_000) return@withLock s.access
        val (code, body) = send("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh), null)
        if (code == 400 || code == 401) {
            Prefs.setSession(ctx, null)
            throw AuthRequired("Signed out. Sign in again")
        }
        if (code !in 200..299) throw failure(code, body)
        saveSession(JSONObject(body), fallbackEmail = s.email)
        Prefs.session(ctx)!!.access
    }

    // --- the database functions ---

    override suspend fun capture(item: Item) {
        rpc(
            "capture_item",
            JSONObject()
                .put("p_client_id", item.id)
                .put("p_kind", item.kind)
                .put("p_text", item.text)
                .put("p_due_at", item.dueAt?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
                .put("p_created_at", Instant.ofEpochMilli(item.createdAt).toString()),
        )
    }

    override suspend fun delete(id: String) {
        rpc("delete_item", JSONObject().put("p_client_id", id))
    }

    private suspend fun rpc(name: String, args: JSONObject): String {
        var (code, body) = send("POST", "/rest/v1/rpc/$name", args, token())
        if (code == 401) {                       // the token may have been revoked or aged out: one fresh try
            val r = send("POST", "/rest/v1/rpc/$name", args, token(forceRefresh = true))
            code = r.first; body = r.second
            if (code == 401) throw AuthRequired("Signed out. Sign in again")
        }
        if (code !in 200..299) throw failure(code, body)
        return body
    }

    // --- plumbing ---

    private fun failure(code: Int, body: String): Exception {
        val text = runCatching { JSONObject(body).let { it.optString("message").ifEmpty { it.optString("msg") } } }.getOrNull()
            .takeUnless { it.isNullOrEmpty() } ?: body.take(200)
        return if (code >= 500 || code == 429 || code == 408) TransientError("Server busy ($code)") else RejectedError(code, text)
    }

    private suspend fun send(method: String, path: String, body: JSONObject?, bearer: String?): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            if (!configured) throw RejectedError(0, "The cloud address is not set up in this build")
            try {
                val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("apikey", anonKey)
                    // The old "anon" key is itself a JWT and may be sent as the bearer. The newer publishable key
                    // (sb_publishable_...) is not a JWT and goes only in the apikey header.
                    val token = bearer ?: anonKey.takeIf { it.startsWith("eyJ") }
                    if (token != null) setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        doOutput = true
                        outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                code to text
            } catch (e: IOException) {
                throw TransientError(e.message ?: "No connection", e)
            }
        }

    /** Words typed into the search box, matched on the server against every note, including ones written elsewhere. */
    data class CloudNote(val clientId: String?, val kind: String, val text: String, val createdAt: Long)

    suspend fun searchNotes(query: String, limit: Int = 50): List<CloudNote> {
        val out = rpc("search_notes", JSONObject().put("p_query", query).put("p_limit", limit))
        val arr = JSONArray(out)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CloudNote(
                clientId = o.optString("client_id").takeUnless { id -> id.isEmpty() || id == "null" },
                kind = o.getString("kind"),
                text = o.getString("text"),
                createdAt = Instant.parse(o.getString("created_at").replace(" ", "T").let { s -> if (s.endsWith("+00:00")) s.dropLast(6) + "Z" else s }).toEpochMilli(),
            )
        }
    }
}
