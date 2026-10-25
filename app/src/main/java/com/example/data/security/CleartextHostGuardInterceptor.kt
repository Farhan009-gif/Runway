package com.example.data.security

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class CleartextHostNotApprovedException(
    val host: String,
    val requestUrl: String
) : IOException("Unencrypted HTTP connection to '$host' is blocked until you explicitly approve this host.")

/**
 * Enforces HTTPS-by-default across all requests while allowing cleartext HTTP strictly
 * for hosts/IPs that the user has explicitly added to the Approved Cleartext Host List.
 */
class CleartextHostGuardInterceptor(
    private val approvedHostsProvider: () -> Set<String>
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.scheme.equals("http", ignoreCase = true)) {
            val host = url.host.trim().lowercase()
            val approvedHosts = approvedHostsProvider()
            if (host !in approvedHosts) {
                throw CleartextHostNotApprovedException(
                    host = host,
                    requestUrl = "${url.scheme}://${url.host}:${url.port}"
                )
            }
        }
        return chain.proceed(request)
    }
}
