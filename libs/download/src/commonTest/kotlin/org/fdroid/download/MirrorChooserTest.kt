package org.fdroid.download

import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.utils.io.errors.IOException
import org.fdroid.getIndexFile
import org.fdroid.runSuspend
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MirrorChooserTest {

    private val mirrors = listOf(
        Mirror("foo"),
        Mirror("bar"),
        Mirror("42"),
        Mirror("1337"))
    private val mirrorsLocation = listOf(
        Mirror(baseUrl = "local", location = "HERE"),
        Mirror(baseUrl = "remote", location = "THERE"))
    private val downloadRequest = DownloadRequest("foo", mirrors)
    private val downloadRequestLocation = DownloadRequest("location", mirrorsLocation)

    private val ipfsIndexFile = getIndexFile(name = "foo", ipfsCidV1 = "CIDv1")

    @Test
    fun testMirrorChooserDefaultImpl() = runSuspend {
        val mirrorChooser = MirrorChooserRandom()
        val expectedResult = Random.nextInt()

        val result = mirrorChooser.mirrorRequest(downloadRequest) { mirror, url ->
            assertTrue { mirrors.contains(mirror) }
            assertEquals(mirror.getUrl(downloadRequest.indexFile.name), url)
            expectedResult
        }
        assertEquals(expectedResult, result)
    }

    @Test
    fun testFallbackToNextMirrorWithIOException() = runSuspend {
        val mirrorChooser = MirrorChooserRandom()
        val expectedResult = Random.nextInt()

        val result = mirrorChooser.mirrorRequest(downloadRequest) { mirror, url ->
            assertEquals(mirror.getUrl(downloadRequest.indexFile.name), url)
            // fails with all except last mirror
            if (mirror != downloadRequest.mirrors.last()) throw IOException("foo")
            expectedResult
        }
        assertEquals(expectedResult, result)
    }

    @Test
    fun testFallbackToNextMirrorWithSocketTimeoutException() = runSuspend {
        val mirrorChooser = MirrorChooserRandom()
        val expectedResult = Random.nextInt()

        val result = mirrorChooser.mirrorRequest(downloadRequest) { mirror, url ->
            assertEquals(mirror.getUrl(downloadRequest.indexFile.name), url)
            // fails with all except last mirror
            if (mirror != downloadRequest.mirrors.last()) throw SocketTimeoutException("foo")
            expectedResult
        }
        assertEquals(expectedResult, result)
    }

    @Test
    fun testFallbackToNextMirrorWithNoResumeException() = runSuspend {
        val mirrorChooser = MirrorChooserRandom()
        val expectedResult = Random.nextInt()

        val result = mirrorChooser.mirrorRequest(downloadRequest) { mirror, url ->
            assertEquals(mirror.getUrl(downloadRequest.indexFile.name), url)
            // fails with all except last mirror
            if (mirror != downloadRequest.mirrors.last()) throw NoResumeException()
            expectedResult
        }
        assertEquals(expectedResult, result)
    }

    @Test
    fun testMirrorChooserRandom() {
        val mirrorChooser = MirrorChooserRandom()

        val orderedMirrors = mirrorChooser.orderMirrors(downloadRequest)

        // set of input mirrors is equal to set of output mirrors
        assertEquals(mirrors.toSet(), orderedMirrors.toSet())
    }

    @Test
    fun testMirrorChooserRandomRespectsTryFirstMirror() {
        val mirrorChooser = MirrorChooserRandom()

        val tryFirstRequest = downloadRequest.copy(tryFirstMirror = Mirror("42"))
        val orderedMirrors = mirrorChooser.orderMirrors(tryFirstRequest)

        // try-first mirror is first in list
        assertEquals(tryFirstRequest.tryFirstMirror, orderedMirrors[0])
        // set of input mirrors is equal to set of output mirrors
        assertEquals(mirrors.toSet(), orderedMirrors.toSet())
    }

    @Test
    fun testMirrorChooserRandomIgnoresMissingTryFirstMirror() {
        val mirrorChooser = MirrorChooserRandom()

        val tryFirstRequest = downloadRequest.copy(tryFirstMirror = Mirror("missing"))
        val orderedMirrors = mirrorChooser.orderMirrors(tryFirstRequest)

        // set of input mirrors is equal to set of output mirrors
        assertEquals(mirrors.toSet(), orderedMirrors.toSet())
    }

    @Test
    fun testMirrorChooserIgnoresIpfsGatewayIfNoCid() = runSuspend {
        val mirrorChooser = object : MirrorChooserImpl() {
            override fun orderMirrors(downloadRequest: DownloadRequest): List<Mirror> {
                return downloadRequest.mirrors // keep mirror list stable, no random please
            }
        }
        val mirrors = listOf(
            Mirror("http://ipfs.com", isIpfsGateway = true),
            Mirror("http://example.com", isIpfsGateway = false),
        )
        val ipfsRequest = downloadRequest.copy(mirrors = mirrors)

        val result = mirrorChooser.mirrorRequest(ipfsRequest) { _, url ->
            url.toString()
        }
        assertEquals("http://example.com/foo", result)
    }

    @Test
    fun testMirrorChooserThrowsIfOnlyIpfsGateways() = runSuspend {
        val mirrorChooser = MirrorChooserRandom()
        val mirrors = listOf(
            Mirror("foo/bar", isIpfsGateway = true),
            Mirror("bar/foo", isIpfsGateway = true),
        )
        val ipfsRequest = downloadRequest.copy(mirrors = mirrors)

        val e = assertFailsWith<IOException> {
            mirrorChooser.mirrorRequest(ipfsRequest) { _, _ ->
            }
        }
        assertEquals("Got IPFS gateway without CID", e.message)
    }

    @Test
    fun testMirrorChooserLocation() {
        val mirrorChooser = MirrorChooserWithParameters()
        mirrorChooser.setLocationPropertyOverride(listOf("HERE"))

        // test with local mirrors
        mirrorChooser.setLocalPropertyOverride(true)
        mirrorChooser.setRemotePropertyOverride(false)
        val mirrorsLocalList = mirrorChooser.orderMirrors(downloadRequestLocation)
        // confirm the list contains one mirror
        assertEquals(1, mirrorsLocalList.size)
        // mirror that is local should be included
        assertEquals("HERE", mirrorsLocalList.get(0).location)

        // test with remote mirrors
        mirrorChooser.setLocalPropertyOverride(false)
        mirrorChooser.setRemotePropertyOverride(true)
        val mirrorsRemoteList = mirrorChooser.orderMirrors(downloadRequestLocation)
        // confirm the list contains one mirror
        assertEquals(1, mirrorsRemoteList.size)
        // mirror that is remote should be included
        assertEquals("THERE", mirrorsRemoteList.get(0).location)

        // test with all mirrors
        mirrorChooser.setLocalPropertyOverride(true)
        mirrorChooser.setRemotePropertyOverride(true)
        val mirrorsAllList = mirrorChooser.orderMirrors(downloadRequestLocation)
        // confirm the list contains both mirrors
        assertEquals(2, mirrorsAllList.size)
        // local mirror should appear first for the sake of latency
        assertEquals("HERE", mirrorsAllList.get(0).location)
        assertEquals("THERE", mirrorsAllList.get(1).location)
    }

}
