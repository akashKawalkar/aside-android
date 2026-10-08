package com.akash.aside.watch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Starts the nightly job on GitHub when the phone finds the schedule missing. The token can do only that, on only this repository. */
object GitHub {
    private const val REPO = "akashKawalkar/aside"
    private const val WORKFLOW = "nightly.yml"
    private const val BRANCH = "main"

    sealed interface Outcome {
        data object Ok : Outcome
        /** GitHub said no (bad token, missing permission): trying again will not help until the token is fixed. */
        data class Rejected(val message: String) : Outcome
        /** No connection or GitHub having trouble: try again later. */
        data class Transient(val message: String) : Outcome
    }

    suspend fun dispatch(token: String, kind: String): Outcome =
        call("POST", "/repos/$REPO/actions/workflows/$WORKFLOW/dispatches", token,
            JSONObject().put("ref", BRANCH).put("inputs", JSONObject().put("kind", kind)))

    /** Asks GitHub whether this token can see the workflow. Does not start anything. */
    suspend fun check(token: String): Outcome = call("GET", "/repos/$REPO/actions/workflows/$WORKFLOW", token, null)

    private suspend fun call(method: String, path: String, token: String, body: JSONObject?): Outcome = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://api.github.com$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            when {
                code in 200..299 -> Outcome.Ok
                code >= 500 || code == 429 -> Outcome.Transient("GitHub is busy ($code)")
                else -> Outcome.Rejected(explain(code, text))
            }
        } catch (e: IOException) {
            Outcome.Transient(e.message ?: "No connection")
        }
    }

    private fun explain(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        return when (code) {
            401 -> "GitHub does not accept this token (expired or mistyped)"
            403, 404 -> "This token cannot reach the nightly job. It needs Actions: read and write on the aside repository"
            422 -> "GitHub refused the request: $msg"
            else -> "GitHub said $code: $msg"
        }
    }
}
