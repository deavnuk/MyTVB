package com.mytvb.network.interceptor

import com.mytvb.network.session.RequestSessionScope
import com.mytvb.network.session.RequestSessionSnapshot
import okhttp3.Interceptor
import okhttp3.Response
import java.net.ProtocolException

/** Stamp before OkHttp's bridge adds cookies; do not expose this tag as an HTTP header. */
class SessionScopeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Login/refresh/nav may deliberately replace credentials. Do not cancel
        // those flows, third-party resources, or writes with ambiguous outcomes.
        val host = original.url.host
        val path = original.url.encodedPath
        if (original.method != "GET" ||
            !(host == "bilibili.com" || host.endsWith(".bilibili.com")) ||
            host == "passport.bilibili.com" || host == "www.bilibili.com" ||
            path == "/x/web-interface/nav"
        ) return chain.proceed(original)
        val request = chain.request().newBuilder()
            .tag(RequestSessionSnapshot::class.java, RequestSessionScope.capture())
            .build()
        return chain.proceed(request)
    }
}

/** Runs inside BridgeInterceptor, before stale Set-Cookie can mutate the current account. */
class SessionScopeResponseInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        try {
            chain.request().tag(RequestSessionSnapshot::class.java)
                ?.let(RequestSessionScope::requireCurrent)
            return response
        } catch (error: Exception) {
            response.close()
            // OkHttp's dispatcher expects IO failures; translate back at the app boundary.
            throw ProtocolException("Obsolete session response").apply { initCause(error) }
        }
    }
}
