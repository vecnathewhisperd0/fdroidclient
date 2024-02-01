package org.fdroid.download

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun getHttpClientEngineFactory(customDns: Dns?): HttpClientEngineFactory<*> {
    return CIO
}
