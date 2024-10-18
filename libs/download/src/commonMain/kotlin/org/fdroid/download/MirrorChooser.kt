package org.fdroid.download

import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode.Companion.Forbidden
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.Url
import io.ktor.utils.io.errors.IOException
import mu.KotlinLogging

public interface MirrorChooser {
    public fun orderMirrors(downloadRequest: DownloadRequest): List<Mirror>
    public suspend fun <T> mirrorRequest(
        downloadRequest: DownloadRequest,
        request: suspend (mirror: Mirror, url: Url) -> T,
    ): T
}

internal abstract class MirrorChooserImpl : MirrorChooser {

    companion object {
        protected val log = KotlinLogging.logger {}
    }

    /**
     * Executes the given request on the best mirror and tries the next best ones if that fails.
     */
    override suspend fun <T> mirrorRequest(
        downloadRequest: DownloadRequest,
        request: suspend (mirror: Mirror, url: Url) -> T,
    ): T {
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

    protected fun throwOnLastMirror(e: Exception, wasLastMirror: Boolean) {
        log.info {
            val info = if (e is ResponseException) e.response.status.toString()
            else e::class.simpleName ?: ""
            if (wasLastMirror) "Last mirror, rethrowing... ($info)"
            else "Trying other mirror now... ($info)"
        }
        if (wasLastMirror) throw e
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

internal class MirrorChooserWithParameters constructor(
    private val mirrorParameterManager: MirrorParameterManager? = null
) : MirrorChooserImpl() {

    // added to support testing but may be useful for other situations
    private var regionalPropertyOverride: Boolean = false
    private var worldwidePropertyOverride: Boolean = false
    private var locationPropertyOverride: List<String> = listOf()

    fun setRegionalPropertyOverride(regionalProperty: Boolean) {
        regionalPropertyOverride = regionalProperty
    }

    fun setWorldwidePropertyOverride(worldwideProperty: Boolean) {
        worldwidePropertyOverride = worldwideProperty
    }

    fun setLocationPropertyOverride(locationProperty: List<String>) {
        locationPropertyOverride = locationProperty
    }

    override suspend fun <T> mirrorRequest(
        downloadRequest: DownloadRequest,
        request: suspend (mirror: Mirror, url: Url) -> T,
    ): T {
        val mirrors = if (downloadRequest.proxy == null) {
            val orderedMirrors = orderMirrors(downloadRequest)
            // if we don't use a proxy, filter out onion mirrors (won't work without Orbot)
            val filteredMirrors = orderedMirrors.filter { mirror -> !mirror.isOnion() }
            if (filteredMirrors.isEmpty()) {
                if (mirrorParameterManager != null
                    && (!mirrorParameterManager.preferRegionalMirrors()
                            || !mirrorParameterManager.preferWorldwideMirrors())
                ) {
                    // if we have no non-onion mirrors because mirrors were excluded, keep empty list
                    filteredMirrors
                } else {
                    // if we only have onion mirrors, take what we have and expect errors
                    downloadRequest.mirrors
                }
            } else {
                filteredMirrors
            }
        } else {
            orderMirrors(downloadRequest)
        }

        if (mirrors.size == 0) {
            error("No valid mirrors were found. Check settings.")
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
                val result = request(mirror, url)
                mirrorParameterManager?.incrementMirrorSuccessCount(url.toString())
                return result;
            } catch (e: ResponseException) {
                mirrorParameterManager?.incrementMirrorErrorCount(url.toString())
                // don't try other mirrors if we got Forbidden response, but supplied credentials
                if (downloadRequest.hasCredentials && e.response.status == Forbidden) throw e
                // don't try other mirrors if we got NotFount response and downloaded a repo
                if (downloadRequest.tryFirstMirror != null && e.response.status == NotFound) throw e
                // also throw if this is the last mirror to try, otherwise try next
                throwOnLastMirror(e, index == mirrors.size - 1)
            } catch (e: IOException) {
                mirrorParameterManager?.incrementMirrorErrorCount(url.toString())
                throwOnLastMirror(e, index == mirrors.size - 1)
            } catch (e: SocketTimeoutException) {
                mirrorParameterManager?.incrementMirrorErrorCount(url.toString())
                throwOnLastMirror(e, index == mirrors.size - 1)
            } catch (e: NoResumeException) {
                mirrorParameterManager?.incrementMirrorErrorCount(url.toString())
                // continue to next mirror, if we need to resume, but this one doesn't support it
                throwOnLastMirror(e, index == mirrors.size - 1)
            }
        }
        error("Reached code that was thought to be unreachable.")
    }

    override fun orderMirrors(downloadRequest: DownloadRequest): List<Mirror> {
        val errorComparator = Comparator { mirror1: Mirror, mirror2: Mirror ->
            // if no parameter manager is available, default to 0 (should return equal)
            val success1 = mirrorParameterManager?.getMirrorSuccessCount(mirror1.baseUrl) ?: 0
            val error1 = mirrorParameterManager?.getMirrorErrorCount(mirror1.baseUrl) ?: 0
            val success2 = mirrorParameterManager?.getMirrorSuccessCount(mirror2.baseUrl) ?: 0
            val error2 = mirrorParameterManager?.getMirrorErrorCount(mirror2.baseUrl) ?: 0

            // prefer mirrors with more sucessful connections
            if ((success1 - error1) > (success2 - error2)) {
                1
            } else if ((success1 - error1) < (success2 - error2)) {
                -1
            } else {
                0
            }
        }

        var mirrorList: MutableList<Mirror> = mutableListOf<Mirror>()

        if (mirrorParameterManager != null
            && !mirrorParameterManager.getCurrentLocations().isNullOrEmpty()
        ) {
            // if we have access to mirror parameters and the current location,
            // then use that information to sort the mirror list
            val mirrorFilteredList: List<Mirror> = sortMirrorsByLocation(
                mirrorParameterManager.preferRegionalMirrors(),
                mirrorParameterManager.preferWorldwideMirrors(),
                downloadRequest.mirrors,
                mirrorParameterManager.getCurrentLocations(),
                errorComparator
            )
            mirrorList.addAll(mirrorFilteredList)
        } else if (!locationPropertyOverride.isNullOrEmpty()) {
            // if testing overrides have been set, then use those settings to
            // sort the mirror list
            val mirrorFilteredList: List<Mirror> = sortMirrorsByLocation(
                regionalPropertyOverride,
                worldwidePropertyOverride,
                downloadRequest.mirrors,
                locationPropertyOverride,
                errorComparator
            )
            mirrorList.addAll(mirrorFilteredList)
        } else {
            // default to sorting the mirror list based on success/failure
            val mirrorCompleteList: List<Mirror> =
                downloadRequest.mirrors.toMutableList().sortedWith(errorComparator)
            mirrorList.addAll(mirrorCompleteList)
        }

        return mirrorList
    }

    fun sortMirrorsByLocation(
        regionalMirrorsPreferred: Boolean,
        worldwideMirrorsPreferred: Boolean,
        availableMirrorList: List<Mirror>,
        currentLocationList: List<String>,
        mirrorComparator: Comparator<Mirror>
    ): List<Mirror> {
        var mirrorList: MutableList<Mirror> = mutableListOf<Mirror>()
        val sortedList: List<Mirror> = availableMirrorList.toMutableList().sortedWith(mirrorComparator)

        if (!regionalMirrorsPreferred && !worldwideMirrorsPreferred) {
            return sortedList
        }

        val regionalList: List<Mirror> = sortedList.filter { mirror ->
            !mirror.location.isNullOrEmpty() && currentLocationList.contains(mirror.location)
        }
        val worldwideList: List<Mirror> = sortedList.filter { mirror ->
            !mirror.location.isNullOrEmpty() && !currentLocationList.contains(mirror.location)
        }
        val unknownList: List<Mirror> = sortedList.filter { mirror ->
            mirror.location.isNullOrEmpty()
        }

        if (regionalMirrorsPreferred) {
            mirrorList.addAll(regionalList)
            mirrorList.addAll(worldwideList)
            mirrorList.addAll(unknownList)
        } else {
            mirrorList.addAll(worldwideList)
            mirrorList.addAll(regionalList)
            mirrorList.addAll(unknownList)
        }

        return mirrorList
    }
}
