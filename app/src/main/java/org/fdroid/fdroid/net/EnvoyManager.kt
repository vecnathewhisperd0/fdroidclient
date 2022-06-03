package org.fdroid.fdroid.net

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import okhttp3.ConnectionSpec
import okhttp3.internal.tls.OkHostnameVerifier
import org.fdroid.download.HttpManager
import org.fdroid.download.NoDns
import org.fdroid.download.isTor
import org.greatfire.envoy.CronetInterceptor
import org.greatfire.envoy.CronetNetworking

class EnvoyManager @JvmOverloads constructor(
    userAgent: String,
    queryString: String? = null,
    proxyConfig: ProxyConfig? = null,
    private val okHttpClientEngineFactory: HttpClientEngineFactory<*> = getNewOkHttpClientEngineFactory(),
) : HttpManager(
    userAgent,
    queryString,
    proxyConfig,
) {

    private companion object {

        private const val TAG = "EnvoyManager"

        fun getNewOkHttpClientEngineFactory(): HttpClientEngineFactory<*> {
            return object : HttpClientEngineFactory<OkHttpConfig> {
                private val connectionSpecs = listOf(
                    ConnectionSpec.RESTRICTED_TLS, // order matters here, so we put restricted before modern
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.CLEARTEXT, // needed for swap connections, allowed in fdroidclient:app as well
                )

                override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine =
                    OkHttp.create {
                        block()
                        if (CronetNetworking.cronetEngine() == null) {
                            Log.d(TAG, "cronet is not active, check tor and set up config")
                            config {
                                if (proxy.isTor()) { // don't allow DNS requests when using Tor
                                    dns(NoDns())
                                }
                                hostnameVerifier { hostname, session ->
                                    session?.sessionContext?.sessionTimeout = 60
                                    // use default hostname verifier
                                    OkHostnameVerifier.verify(hostname, session)
                                }
                                connectionSpecs(connectionSpecs)
                            }
                        } else {
                            Log.d(TAG, "cronet is active, set up config with interceptor")
                            config {
                                hostnameVerifier { hostname, session ->
                                    session?.sessionContext?.sessionTimeout = 60
                                    // use default hostname verifier
                                    OkHostnameVerifier.verify(hostname, session)
                                }
                                connectionSpecs(connectionSpecs)
                                // interceptor vs network interceptor?
                                addInterceptor(CronetInterceptor())
                            }
                        }
                    }
            }
        }
    }

    override var httpClient: HttpClient = getNewOkHttpClient(proxyConfig)

    private fun getNewOkHttpClient(proxyConfig: ProxyConfig? = null): HttpClient {
        if (CronetNetworking.cronetEngine() == null) {
            Log.d(TAG, "cronet is not active, set up client with proxy")
            this.currentProxy = proxyConfig
            return HttpClient(okHttpClientEngineFactory) {
                followRedirects = false
                expectSuccess = true
                engine {
                    threadsCount = 4
                    pipelining = true
                    proxy = proxyConfig
                }
                install(UserAgent) {
                    agent = userAgent
                }
                install(HttpTimeout)
            }
        } else {
            Log.d(TAG, "cronet is active, set up client with no proxy")
            return HttpClient(okHttpClientEngineFactory) {
                followRedirects = false
                expectSuccess = true
                engine {
                    threadsCount = 4
                    pipelining = true
                }
                install(UserAgent) {
                    agent = userAgent
                }
                install(HttpTimeout)
            }
        }
    }
}