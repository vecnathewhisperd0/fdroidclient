package org.fdroid.repo

import android.net.Uri
import androidx.core.os.LocaleListCompat
import kotlinx.serialization.SerializationException
import org.fdroid.database.Repository
import org.fdroid.download.DownloaderFactory
import org.fdroid.index.IndexConverter
import org.fdroid.index.IndexFormatVersion
import org.fdroid.index.IndexParser
import org.fdroid.index.RepoUriBuilder
import org.fdroid.index.SigningException
import org.fdroid.index.TempFileProvider
import org.fdroid.index.parseV1
import org.fdroid.index.v1.IndexV1Verifier
import org.fdroid.index.v1.SIGNED_FILE_NAME
import org.fdroid.index.v2.FileV2

internal class RepoV1Fetcher(
    private val tempFileProvider: TempFileProvider,
    private val downloaderFactory: DownloaderFactory,
    private val repoUriBuilder: RepoUriBuilder,
) : RepoFetcher {

    private val locales: LocaleListCompat = LocaleListCompat.getDefault()

    @Throws(SigningException::class, SerializationException::class)
    override suspend fun fetchRepo(
        uri: Uri,
        repo: Repository,
        receiver: RepoPreviewReceiver,
        fingerprint: String?,
    ) {
        // download and verify index-v1.jar
        val indexFile = tempFileProvider.createTempFile()
        val entryDownloader = downloaderFactory.create(
            repo = repo,
            uri = repoUriBuilder.getUri(repo, SIGNED_FILE_NAME),
            indexFile = FileV2.fromPath("/$SIGNED_FILE_NAME"),
            destFile = indexFile,
        )
        val (cert, indexV1) = try {
            entryDownloader.download()
            val verifier = IndexV1Verifier(indexFile, null, fingerprint)
            verifier.getStreamAndVerify { inputStream ->
                IndexParser.parseV1(inputStream)
            }
        } finally {
            indexFile.delete()
        }
        val version = indexV1.repo.version
        val indexV2 = IndexConverter().toIndexV2(indexV1)
        val receivedRepo = RepoV2StreamReceiver.getRepository(
            repo = indexV2.repo,
            version = version.toLong(),
            formatVersion = IndexFormatVersion.ONE,
            certificate = cert,
            username = repo.username,
            password = repo.password,
        )
        receiver.onRepoReceived(receivedRepo)
        indexV2.packages.forEach { (packageName, packageV2) ->
            val app = RepoV2StreamReceiver.getAppOverViewItem(packageName, packageV2, locales)
            receiver.onAppReceived(app)
        }
    }
}
