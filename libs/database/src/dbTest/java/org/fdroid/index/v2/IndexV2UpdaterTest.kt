package org.fdroid.index.v2

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.fdroid.CompatibilityChecker
import org.fdroid.database.DbTest
import org.fdroid.database.Repository
import org.fdroid.database.TestUtils.assertTimestampRecent
import org.fdroid.download.Downloader
import org.fdroid.download.DownloaderFactory
import org.fdroid.index.IndexFormatVersion.TWO
import org.fdroid.index.IndexUpdateResult
import org.fdroid.index.SigningException
import org.fdroid.index.TempFileProvider
import org.fdroid.test.TestDataEntry
import org.fdroid.test.TestDataMaxV2
import org.fdroid.test.TestDataMidV2
import org.fdroid.test.TestDataMinV2
import org.fdroid.test.VerifierConstants.CERTIFICATE
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
internal class IndexV2UpdaterTest : DbTest() {

    @get:Rule
    var tmpFolder: TemporaryFolder = TemporaryFolder()

    private val tempFileProvider: TempFileProvider = mockk()
    private val downloaderFactory: DownloaderFactory = mockk()
    private val downloader: Downloader = mockk()
    private val compatibilityChecker: CompatibilityChecker = CompatibilityChecker { true }
    private lateinit var indexUpdater: IndexV2Updater

    @Before
    override fun createDb() {
        super.createDb()
        indexUpdater = IndexV2Updater(
            database = db,
            tempFileProvider = tempFileProvider,
            downloaderFactory = downloaderFactory,
            compatibilityChecker = compatibilityChecker,
        )
    }

    @Test
    fun testFullIndexEmptyToMin() {
        val repoId = repoDao.insertEmptyRepo("http://example.org", certificate = CERTIFICATE)
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "index-min-v2.json",
            indexFileV2 = TestDataEntry.emptyToMin.index
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMinV2.index)

        // check that format version got entered and certificate stayed the same
        val updatedRepo = repoDao.getRepository(repoId) ?: fail()
        assertEquals(TWO, updatedRepo.formatVersion)
        assertEquals(CERTIFICATE, updatedRepo.certificate)
        assertTimestampRecent(repoDao.getRepository(repoId)?.lastUpdated)
    }

    @Test
    fun testFullIndexEmptyToMid() {
        val repoId = repoDao.insertEmptyRepo("http://example.org", certificate = CERTIFICATE)
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-mid/$SIGNED_FILE_NAME",
            jsonPath = "index-mid-v2.json",
            indexFileV2 = TestDataEntry.emptyToMid.index
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMidV2.index)
        assertTimestampRecent(repoDao.getRepository(repoId)?.lastUpdated)
    }

    @Test
    fun testFullIndexEmptyToMax() {
        val repoId = repoDao.insertEmptyRepo("http://example.org", certificate = CERTIFICATE)
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-max/$SIGNED_FILE_NAME",
            jsonPath = "index-max-v2.json",
            indexFileV2 = TestDataEntry.emptyToMax.index
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMaxV2.index)
        assertTimestampRecent(repoDao.getRepository(repoId)?.lastUpdated)
    }

    @Test
    fun testDiffMinToMid() {
        val repoId = streamIndexV2IntoDb("index-min-v2.json")
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-mid/$SIGNED_FILE_NAME",
            jsonPath = "diff-empty-mid/42.json",
            indexFileV2 = TestDataEntry.emptyToMid.diffs["42"] ?: fail()
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMidV2.index)
        assertTimestampRecent(repoDao.getRepository(repoId)?.lastUpdated)
    }

    @Test
    fun testDiffEmptyToMin() {
        val repoId = streamIndexV2IntoDb("index-empty-v2.json")
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "diff-empty-min/23.json",
            indexFileV2 = TestDataEntry.emptyToMin.diffs["23"] ?: fail()
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMinV2.index)
        assertTimestampRecent(repoDao.getRepository(repoId)?.lastUpdated)
    }

    @Test
    fun testDiffMidToMax() {
        val repoId = streamIndexV2IntoDb("index-mid-v2.json")
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-max/$SIGNED_FILE_NAME",
            jsonPath = "diff-empty-max/1337.json",
            indexFileV2 = TestDataEntry.emptyToMax.diffs["1337"] ?: fail()
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMaxV2.index)
        assertTimestampRecent(repoDao.getRepository(repoId)?.lastUpdated)
    }

    @Test
    fun testSameTimestampUnchanged() {
        val repoId = streamIndexV2IntoDb("index-min-v2.json")
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "diff-empty-min/23.json",
            indexFileV2 = TestDataEntry.emptyToMin.diffs["23"] ?: fail()
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Unchanged, result)
        assertDbEquals(repoId, TestDataMinV2.index)
        assertNull(repoDao.getRepository(repoId)?.lastUpdated)
    }

    @Test
    fun testHigherTimestampUnchanged() {
        val repoId = streamIndexV2IntoDb("index-mid-v2.json")
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "diff-empty-min/23.json",
            indexFileV2 = TestDataEntry.emptyToMin.diffs["23"] ?: fail()
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Unchanged, result)
        assertDbEquals(repoId, TestDataMidV2.index)
    }

    @Test
    fun testNoDiffFoundIndexFallback() {
        val repoId = streamIndexV2IntoDb("index-empty-v2.json")
        // fake timestamp of internal repo, so we will fail to find a diff in entry.json
        val newRepo = repoDao.getRepository(repoId)?.repository?.copy(timestamp = 22) ?: fail()
        repoDao.updateRepository(newRepo)
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "index-min-v2.json",
            indexFileV2 = TestDataEntry.emptyToMin.index
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMinV2.index)
    }

    @Test
    fun testWrongFingerprint() {
        val repoId = repoDao.insertEmptyRepo("http://example.org")
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "index-min-v2.json",
            indexFileV2 = TestDataEntry.emptyToMin.index
        )
        val result = indexUpdater.update(repo)
        assertIs<IndexUpdateResult.Error>(result)
        assertIs<SigningException>(result.e)
    }

    /**
     * Ensures that a v1 repo can't use a diff when upgrading to v1,
     * but must use a full index update.
     */
    @Test
    fun testV1ToV2ForcesFullUpdateEvenIfDiffExists() {
        val repoId = streamIndexV1IntoDb("index-min-v1.json")
        val repo = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-mid/$SIGNED_FILE_NAME",
            jsonPath = "index-mid-v2.json",
            indexFileV2 = TestDataEntry.emptyToMid.index,
        )
        val result = indexUpdater.update(repo).noError()
        assertEquals(IndexUpdateResult.Processed, result)
        assertDbEquals(repoId, TestDataMidV2.index)

        // check that format version got upgraded
        val updatedRepo = repoDao.getRepository(repoId) ?: fail()
        assertEquals(TWO, updatedRepo.formatVersion)
    }

    @Test
    @OptIn(DelicateCoroutinesApi::class)
    fun concurrentUpdateTest() {
        val db = spyk(db) // spy on the DB, so we can mock a call to aid in concurrency
        indexUpdater = IndexV2Updater(
            database = db,
            tempFileProvider = tempFileProvider,
            downloaderFactory = downloaderFactory,
            compatibilityChecker = compatibilityChecker,
        )
        val repoId = streamIndexV2IntoDb("index-empty-v2.json")
        val repo1 = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "diff-empty-min/23.json",
            indexFileV2 = TestDataEntry.emptyToMin.diffs["23"] ?: fail()
        )
        val repo2 = prepareUpdate(
            repoId = repoId,
            entryPath = "diff-empty-min/$SIGNED_FILE_NAME",
            jsonPath = "diff-empty-min/23.json",
            indexFileV2 = TestDataEntry.emptyToMin.diffs["23"] ?: fail()
        )
        val latch = CountDownLatch(1)
        val runSlot = slot<Runnable>()
        every { db.runInTransaction(capture(runSlot)) } answers {
            runSlot.captured.run()
            latch.countDown()
        } andThenAnswer {
            latch.await()
            runSlot.captured.run()
        }
        runBlocking {
            GlobalScope.async {
                val result1 = indexUpdater.update(repo1).noError()
                assertEquals(IndexUpdateResult.Processed, result1)

                val entryFile = tmpFolder.newFile()
                val indexFile = tmpFolder.newFile()
                assets.open("diff-empty-min/$SIGNED_FILE_NAME").use { inputStream ->
                    entryFile.outputStream().use { inputStream.copyTo(it) }
                }
                assets.open("diff-empty-min/23.json").use { inputStream ->
                    indexFile.outputStream().use { inputStream.copyTo(it) }
                }
                every { tempFileProvider.createTempFile() } returnsMany listOf(entryFile, indexFile)

                val result2 = indexUpdater.update(repo2)
                assertIs<IndexUpdateResult.Error>(result2)
                assertIs<ConcurrentModificationException>(result2.e)
            }.await()
        }
    }

    private fun prepareUpdate(
        repoId: Long,
        entryPath: String,
        jsonPath: String,
        indexFileV2: EntryFileV2,
    ): Repository {
        val entryFileV2 = FileV2.fromPath("/$SIGNED_FILE_NAME")
        val entryFile = tmpFolder.newFile()
        val indexFile = tmpFolder.newFile()
        val repo = repoDao.getRepository(repoId) ?: fail()
        val entryUri = Uri.parse("${repo.address}/$SIGNED_FILE_NAME")
        val indexUri = Uri.parse("${repo.address}/${indexFileV2.name.trimStart('/')}")

        assets.open(entryPath).use { inputStream ->
            entryFile.outputStream().use { inputStream.copyTo(it) }
        }
        assets.open(jsonPath).use { inputStream ->
            indexFile.outputStream().use { inputStream.copyTo(it) }
        }

        every { tempFileProvider.createTempFile() } returnsMany listOf(entryFile, indexFile)
        every {
            downloaderFactory.createWithTryFirstMirror(repo, entryUri, entryFileV2, any())
        } returns downloader
        every { downloader.download() } just Runs
        every {
            downloaderFactory.createWithTryFirstMirror(repo, indexUri, indexFileV2, any())
        } returns downloader
        every { downloader.download() } just Runs

        return repo
    }

    /**
     * Easier for debugging, if we throw the index error.
     */
    private fun IndexUpdateResult.noError(): IndexUpdateResult {
        if (this is IndexUpdateResult.Error) throw e
        return this
    }

}
