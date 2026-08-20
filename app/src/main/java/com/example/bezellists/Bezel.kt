package com.example.bezellists

import org.json.JSONObject

/**
 * The native bezel client: real Iroh QUIC, HTTP/1.1 per bi-stream.
 * All calls block — invoke from Dispatchers.IO only.
 */
object Bezel {
    init {
        System.loadLibrary("bezel_client")
    }

    private external fun nativeConfigure(
        server: String,
        token: String,
        clientName: String,
        identityHex: String,
    ): String

    private external fun nativeRequest(method: String, path: String, body: String?): String

    /** Returns null on success, an error message otherwise. */
    fun configure(server: String, token: String, clientName: String, identityHex: String): String? =
        nativeConfigure(server, token, clientName, identityHex).ifEmpty { null }

    /** Response envelope: {"status": n, "body": …} or {"status": 0, "error": …}. */
    fun request(method: String, path: String, body: String? = null): JSONObject =
        JSONObject(nativeRequest(method, path, body))
}
