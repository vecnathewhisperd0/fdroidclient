package org.fdroid.repo

import android.content.Context
import android.net.Uri
import android.os.Build.VERSION.SDK_INT
import android.os.UserManager
import android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
import android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY
import androidx.annotation.AnyThread
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat.getSystemService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import org.fdroid.database.AppOverviewItem
import org.fdroid.database.FDroidDatabase
import org.fdroid.database.MinimalApp
import org.fdroid.database.NewRepository
import org.fdroid.database.Repository
import org.fdroid.database.RepositoryDaoInt
import org.fdroid.download.DownloaderFactory
import org.fdroid.download.HttpManager
import org.fdroid.download.HttpManager.Companion.isInvalidHttpUrl
import org.fdroid.download.NotFoundException
import org.fdroid.index.IndexFormatVersion
import org.fdroid.index.RepoUriBuilder
import org.fdroid.index.SigningException
import org.fdroid.index.TempFileProvider
import org.fdroid.repo.AddRepoError.ErrorType.INVALID_FINGERPRINT
import org.fdroid.repo.AddRepoError.ErrorType.INVALID_INDEX
import org.fdroid.repo.AddRepoError.ErrorType.IO_ERROR
import org.fdroid.repo.AddRepoError.ErrorType.IS_ARCHIVE_REPO
import org.fdroid.repo.AddRepoError.ErrorType.UNKNOWN_SOURCES_DISALLOWED
import java.io.IOException
import java.net.Proxy
import kotlin.coroutines.CoroutineContext

internal const val REPO_ID = 0L

public sealed class AddRepoState

public object None : AddRepoState()

public class Fetching(
    public val fetchUrl: String,
    public val receivedRepo: Repository?,
    public val apps: List<MinimalApp>,
    public val fetchResult: FetchResult?,
    /**
     * true if fetching is complete.
     */
    public val done: Boolean = false,
) : AddRepoState() {
    override fun toString(): String {
        return "Fetching(fetchUrl=$fetchUrl, repo=${receivedRepo?.address}, apps=${apps.size}, " +
            "fetchResult=$fetchResult, done=$done)"
    }
}

public object Adding : AddRepoState()

public class Added(
    public val repo: Repository,
) : AddRepoState()

public data class AddRepoError(
    public val errorType: ErrorType,
    public val exception: Exception? = null,
) : AddRepoState() {
    public enum class ErrorType {
        UNKNOWN_SOURCES_DISALLOWED,
        INVALID_FINGERPRINT,
        IS_ARCHIVE_REPO,
        INVALID_INDEX,
        IO_ERROR,
    }
}

public sealed class FetchResult {
    public data object IsNewRepository : FetchResult()
    public data object IsNewRepoAndNewMirror : FetchResult()
    public data class IsNewMirror(internal val existingRepoId: Long) : FetchResult()

    public data class IsExistingRepository(val existingRepoId: Long) : FetchResult()
    public data class IsExistingMirror(val existingRepoId: Long) : FetchResult()
}

@OptIn(DelicateCoroutinesApi::class)
internal class RepoAdder(
    private val context: Context,
    private val db: FDroidDatabase,
    private val tempFileProvider: TempFileProvider,
    private val downloaderFactory: DownloaderFactory,
    private val httpManager: HttpManager,
    private val repoUriGetter: RepoUriGetter = RepoUriGetter,
    private val repoUriBuilder: RepoUriBuilder = defaultRepoUriBuilder,
    private val coroutineContext: CoroutineContext = Dispatchers.IO,
) {
    private val log = KotlinLogging.logger {}
    private val repositoryDao = db.getRepositoryDao() as RepositoryDaoInt

    internal val addRepoState: MutableStateFlow<AddRepoState> = MutableStateFlow(None)

    private var fetchJob: Job? = null

    internal fun fetchRepository(url: String, proxy: Proxy?) {
        fetchJob = GlobalScope.launch(coroutineContext) {
            fetchRepositoryInt(url, proxy)
        }
    }

    @WorkerThread
    @VisibleForTesting
    internal suspend fun fetchRepositoryInt(
        url: String,
        proxy: Proxy? = null,
    ) {
        if (hasDisallowInstallUnknownSources(context)) {
            addRepoState.value = AddRepoError(UNKNOWN_SOURCES_DISALLOWED)
            return
        }
        // get repo url and fingerprint
        val nUri = repoUriGetter.getUri(url)
        log.info("Parsed URI: $nUri")
        if (nUri.uri.scheme !in listOf("content", "file") &&
            isInvalidHttpUrl(nUri.uri.toString())
        ) {
            val e = IllegalArgumentException("Unsupported URI: ${nUri.uri}")
            addRepoState.value = AddRepoError(INVALID_INDEX, e)
            return
        }
        if (nUri.uri.lastPathSegment == "archive") {
            addRepoState.value = AddRepoError(IS_ARCHIVE_REPO)
            return
        }
        val fetchUrl = nUri.uri.toString().trimEnd('/')

        // some plumping to receive the repo preview
        var receivedRepo: Repository? = null
        val apps = ArrayList<AppOverviewItem>()
        var fetchResult: FetchResult? = null

        val receiver = object : RepoPreviewReceiver {
            override fun onRepoReceived(repo: Repository) {
                receivedRepo = repo
                if (repo.address in knownRepos) {
                    val knownFingerprint = knownRepos[repo.address]
                    if (knownFingerprint != repo.fingerprint) throw SigningException(
                        "Known fingerprint different from given one: ${repo.fingerprint}"
                    )
                }
                fetchResult = getFetchResult(fetchUrl, repo)
                addRepoState.value = Fetching(fetchUrl, receivedRepo, apps.toList(), fetchResult)
            }

            override fun onAppReceived(app: AppOverviewItem) {
                apps.add(app)
                addRepoState.value = Fetching(fetchUrl, receivedRepo, apps.toList(), fetchResult)
            }
        }
        // set a state early, so the ui can show progress animation
        addRepoState.value = Fetching(fetchUrl, receivedRepo, apps, fetchResult)

        // try fetching repo with v2 format first and fallback to v1
        try {
            fetchRepo(nUri.uri, nUri.fingerprint, proxy, nUri.username, nUri.password, receiver)
        } catch (e: SigningException) {
            log.error(e) { "Error verifying repo with given fingerprint." }
            addRepoState.value = AddRepoError(INVALID_FINGERPRINT, e)
            return
        } catch (e: IOException) {
            log.error(e) { "Error fetching repo." }
            addRepoState.value = AddRepoError(IO_ERROR, e)
            return
        } catch (e: SerializationException) {
            log.error(e) { "Error fetching repo." }
            addRepoState.value = AddRepoError(INVALID_INDEX, e)
            return
        } catch (e: NotFoundException) { // v1 repos can also have 404
            log.error(e) { "Error fetching repo." }
            addRepoState.value = AddRepoError(INVALID_INDEX, e)
            return
        }
        // set final result
        val finalRepo = receivedRepo
        if (finalRepo == null) {
            addRepoState.value = AddRepoError(INVALID_INDEX)
        } else {
            addRepoState.value = Fetching(fetchUrl, finalRepo, apps, fetchResult, done = true)
        }
    }

    private suspend fun fetchRepo(
        uri: Uri,
        fingerprint: String?,
        proxy: Proxy?,
        username: String?,
        password: String?,
        receiver: RepoPreviewReceiver,
    ) {
        try {
            val repo =
                getTempRepo(uri, IndexFormatVersion.TWO, username, password)
            val repoFetcher = RepoV2Fetcher(
                tempFileProvider, downloaderFactory, httpManager, repoUriBuilder, proxy
            )
            repoFetcher.fetchRepo(uri, repo, receiver, fingerprint)
        } catch (e: NotFoundException) {
            log.warn(e) { "Did not find v2 repo, trying v1 now." }
            // try to fetch v1 repo
            val repo =
                getTempRepo(uri, IndexFormatVersion.ONE, username, password)
            val repoFetcher = RepoV1Fetcher(tempFileProvider, downloaderFactory, repoUriBuilder)
            repoFetcher.fetchRepo(uri, repo, receiver, fingerprint)
        }
    }

    private fun getFetchResult(fetchUrlIn: String, fetchedRepo: Repository): FetchResult {
        // Note the delicate difference between fetchedRepo (from the network) and
        // existingRepo (from the database) in this function!
        val cert = fetchedRepo.certificate ?: error("Certificate was null")
        val existingRepo = repositoryDao.getRepository(cert)
        val fetchUrl = fetchUrlIn.trimEnd('/')

        // is completely new
        if (existingRepo == null) {
            val isFetchedRepoAddress = fetchUrl == fetchedRepo.address.trimEnd('/')
            val isFetchedRepoDefinedMirror =
                fetchedRepo.mirrors.find { fetchUrl == it.url.trimEnd('/') } != null

            val isUserMirror = !isFetchedRepoAddress && !isFetchedRepoDefinedMirror
            return if (isUserMirror) {
                FetchResult.IsNewRepoAndNewMirror
            } else {
                FetchResult.IsNewRepository
            }
        }

        // is existing repo, is canonical address
        val isExistingRepoAddress = fetchUrl == existingRepo.address.trimEnd('/')
        if (isExistingRepoAddress) {
            return FetchResult.IsExistingRepository(existingRepo.repoId)
        }

        // is existing repo, is mirror
        val isNewMirror = existingRepo.getAllMirrors()
            .find { fetchUrl == it.url.toString().trimEnd('/') } == null
        return if (isNewMirror) {
            FetchResult.IsNewMirror(existingRepo.repoId)
        } else {
            FetchResult.IsExistingMirror(existingRepo.repoId)
        }
    }

    @WorkerThread
    internal fun addFetchedRepository(): Repository? {
        // prevent double calls (e.g. caused by double tapping a UI button)
        if (addRepoState.compareAndSet(Adding, Adding)) return null

        // cancel fetch preview job, so it stops emitting new states
        fetchJob?.cancel()

        // get current state before changing it
        val state = (addRepoState.value as? Fetching)
            ?: throw IllegalStateException("Unexpected state: ${addRepoState.value}")
        addRepoState.value = Adding

        val repo = state.receivedRepo
            ?: throw IllegalStateException("No repo: ${addRepoState.value}")
        val fetchResult = state.fetchResult
            ?: throw IllegalStateException("No fetchResult: ${addRepoState.value}")

        val modifiedRepo: Repository = when (fetchResult) {
            is FetchResult.IsExistingRepository -> error("Repo exists: $fetchResult")
            is FetchResult.IsExistingMirror -> error("Mirror exists: $fetchResult")
            is FetchResult.IsNewRepository, is FetchResult.IsNewRepoAndNewMirror -> {
                // reset the timestamp of the actual repo,
                // so a following repo update will pick this up
                val newRepo = NewRepository(
                    name = repo.repository.name,
                    icon = repo.repository.icon ?: emptyMap(),
                    address = repo.address,
                    formatVersion = repo.formatVersion,
                    certificate = repo.certificate ?: error("Repo had no certificate"),
                    username = repo.username,
                    password = repo.password,
                )
                db.runInTransaction<Repository> {
                    // add the repo
                    val repoId = repositoryDao.insert(newRepo)

                    // add user mirror
                    // this can happen if the user was adding a mirror URL, and they originally had
                    // neither the repo nor the mirror added
                    if (fetchResult is FetchResult.IsNewRepoAndNewMirror) {
                        val userMirrors = listOf(state.fetchUrl)
                        repositoryDao.updateUserMirrors(repoId, userMirrors)
                    }
                    repositoryDao.getRepository(repoId) ?: error("New repository not found in DB")
                }
            }

            is FetchResult.IsNewMirror -> {
                val repoId = fetchResult.existingRepoId
                db.runInTransaction<Repository> {
                    val existingRepo = repositoryDao.getRepository(repoId)
                        ?: error("No repo with $repoId")
                    val userMirrors = existingRepo.userMirrors.toMutableList().apply {
                        add(state.fetchUrl)
                    }
                    repositoryDao.updateUserMirrors(repoId, userMirrors)
                    existingRepo
                }
            }
        }
        addRepoState.value = Added(modifiedRepo)
        return modifiedRepo
    }

    internal fun abortAddingRepo() {
        addRepoState.value = None
        fetchJob?.cancel()
    }

    @AnyThread
    internal suspend fun addArchiveRepo(repo: Repository, proxy: Proxy? = null): Long? =
        withContext(coroutineContext) {
            if (repo.isArchiveRepo) error { "Repo ${repo.address} is already an archive repo." }

            var archiveRepoId: Long? = null
            val address = repo.address.replace(Regex("repo/?$"), "archive")

            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            val receiver = object : RepoPreviewReceiver {
                override fun onRepoReceived(archiveRepo: Repository) {
                    // reset the timestamp of the actual repo,
                    // so a following repo update will pick this up
                    val newRepo = NewRepository(
                        name = archiveRepo.repository.name,
                        icon = archiveRepo.repository.icon ?: emptyMap(),
                        address = archiveRepo.address,
                        formatVersion = archiveRepo.formatVersion,
                        certificate = archiveRepo.certificate ?: error("Repo had no certificate"),
                        username = archiveRepo.username,
                        password = archiveRepo.password,
                    )
                    db.runInTransaction {
                        val repoId = repositoryDao.insert(newRepo)
                        repositoryDao.setWeight(repoId, repo.weight - 1)
                        archiveRepoId = repoId
                    }
                    cancel("expected") // no need to continue downloading the entire repo
                }

                override fun onAppReceived(app: AppOverviewItem) {
                    // no-op
                }
            }
            val uri = Uri.parse(address)
            fetchRepo(uri, repo.fingerprint, proxy, repo.username, repo.password, receiver)
            return@withContext archiveRepoId
        }

    private fun hasDisallowInstallUnknownSources(context: Context): Boolean {
        val userManager = getSystemService(context, UserManager::class.java)
            ?: error("No UserManager available.")
        return if (SDK_INT < 29) userManager.hasUserRestriction(DISALLOW_INSTALL_UNKNOWN_SOURCES)
        else userManager.hasUserRestriction(DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            userManager.hasUserRestriction(DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    }

    private fun getTempRepo(
        uri: Uri,
        indexFormatVersion: IndexFormatVersion,
        username: String?,
        password: String?,
    ) = Repository(
        repoId = REPO_ID,
        address = uri.toString(),
        timestamp = -1L,
        formatVersion = indexFormatVersion,
        certificate = "This is fake and will be replaced by real cert before saving in DB.",
        version = 0L,
        weight = 0,
        lastUpdated = -1L,
        username = username,
        password = password,
    )

}

internal val defaultRepoUriBuilder = RepoUriBuilder { repo, pathElements ->
    val builder = Uri.parse(repo.address).buildUpon()
    pathElements.forEach { builder.appendEncodedPath(it) }
    builder.build()
}
