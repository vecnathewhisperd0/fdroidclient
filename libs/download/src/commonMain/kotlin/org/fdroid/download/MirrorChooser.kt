package org.fdroid.download

import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode.Companion.Forbidden
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.Url
import io.ktor.utils.io.errors.IOException
import mu.KotlinLogging
import org.fdroid.fdroid.SocketFactoryManager

public interface MirrorChooser {
    public fun orderMirrors(downloadRequest: DownloadRequest): List<Mirror>
    public suspend fun <T> mirrorRequest(
        downloadRequest: DownloadRequest,
        request: suspend (mirror: Mirror, url: Url) -> T,
    ): T
}

internal abstract class MirrorChooserImpl : MirrorChooser {

    var sniRequired = false

    companion object {
        protected val log = KotlinLogging.logger {}
    }

    /**
     * Executes the given request on the best mirror and tries the next best ones if that fails.
     */
    override suspend fun <T> mirrorRequest(
        downloadRequest: DownloadRequest,
        request: suspend (mirror: Mirror, url: Url) -> T
    ): T {
        sniRequired = false
        val mirrors = if (downloadRequest.proxy == null) {
            // if we don't use a proxy, filter out onion mirrors (won't work without Orbot)
            val orderedMirrors =
                orderMirrors(downloadRequest).filter { mirror -> !mirror.isOnion() }
            // if we only have onion mirrors, take what we have and expect errors
            orderedMirrors.ifEmpty { downloadRequest.mirrors }
        } else {
            orderMirrors(downloadRequest)
        }
        mirrors.forEachIndexed { index, mirror ->
            val ipfsCidV1 = downloadRequest.indexFile.ipfsCidV1
            val url = if (mirror.isIpfsGateway) {
                if (ipfsCidV1 == null) {
                    val e = IOException("Got IPFS gateway without CID")
                    throwOnLastMirror(e, index == mirrors.size - 1)
                    return@forEachIndexed
                } else mirror.getUrl(ipfsCidV1)
            } else {
                mirror.getUrl(downloadRequest.indexFile.name)
            }
            try {
                return request(mirror, url)
            } catch (e: ResponseException) {
                // don't try other mirrors if we got Forbidden response, but supplied credentials
                if (downloadRequest.hasCredentials && e.response.status == Forbidden) throw e
                // don't try other mirrors if we got NotFount response and downloaded a repo
                if (downloadRequest.tryFirstMirror != null && e.response.status == NotFound) throw e
                // also throw if this is the last mirror to try, otherwise try next
                throwOnLastMirror(e, index == mirrors.size - 1)
            } catch (e: IOException) {
                throwOnLastMirror(e, index == mirrors.size - 1)
            } catch (e: SocketTimeoutException) {
                throwOnLastMirror(e, index == mirrors.size - 1)
            } catch (e: NoResumeException) {
                // continue to next mirror, if we need to resume, but this one doesn't support it
                throwOnLastMirror(e, index == mirrors.size - 1)
            }
        }
        error("Reached code that was thought to be unreachable.")
    }

    private fun throwOnLastMirror(e: Exception, wasLastMirror: Boolean) {
        log.info {
            val info = if (e is ResponseException) e.response.status.toString()
            else e::class.simpleName ?: ""
            if (wasLastMirror) "Last mirror, rethrowing... ($info)"
            else "Trying other mirror now... ($info)"
        }
        if (wasLastMirror) {
            throw e
        }
    }
}

internal class MirrorChooserRandom : MirrorChooserImpl() {

    /**
     * Returns a list of mirrors with the best mirrors first.
     */
    override fun orderMirrors(downloadRequest: DownloadRequest): List<Mirror> {
        // simple random selection for now
        return downloadRequest.mirrors.toMutableList().apply { shuffle() }.also { mirrors ->
            // respect the mirror to try first, if set
            if (downloadRequest.tryFirstMirror != null) {
                mirrors.sortBy { if (it == downloadRequest.tryFirstMirror) 0 else 1 }
            }
        }
    }
}

internal class MirrorChooserSni constructor(
    private val socketFactoryManager: SocketFactoryManager? = null
) : MirrorChooserImpl() {

    /**
     * Returns a list of mirrors that puts mirrors that require SNI first.
     */
    override fun orderMirrors(downloadRequest: DownloadRequest): List<Mirror> {
        // shuffle list and split into mirrors that work with/without sni
        val withSniList: List<Mirror> = downloadRequest.mirrors.toMutableList().apply { shuffle() }.filter { mirror ->  mirror.worksWithoutSni == false }
        val withoutSniList: List<Mirror> = downloadRequest.mirrors.toMutableList().apply { shuffle() }.filter { mirror ->  mirror.worksWithoutSni == true }
        if (socketFactoryManager != null && !socketFactoryManager.sniEnabled()) {
            return withoutSniList + withSniList
        } else {
            // if the socket factory manager is null, there is no mechanism to disable sni,
            // so continue as if sni is enabled
            return withSniList + withoutSniList
        }
    }
}
