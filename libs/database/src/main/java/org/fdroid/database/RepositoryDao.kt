package org.fdroid.database

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.fdroid.database.DbDiffUtils.diffAndUpdateListTable
import org.fdroid.database.DbDiffUtils.diffAndUpdateTable
import org.fdroid.index.IndexFormatVersion
import org.fdroid.index.IndexParser.json
import org.fdroid.index.v2.IndexV2Updater
import org.fdroid.index.v2.MirrorV2
import org.fdroid.index.v2.ReflectionDiffer.applyDiff
import org.fdroid.index.v2.RepoV2

public interface RepositoryDao {
    /**
     * Inserts a new [InitialRepository] from a fixture.
     *
     * @return the [Repository.repoId] of the inserted repo.
     */
    public fun insert(initialRepo: InitialRepository): Long

    /**
     * Inserts a new repository into the database.
     */
    public fun insert(newRepository: NewRepository): Long

    /**
     * Returns the repository with the given [repoId] or null, if none was found with that ID.
     */
    public fun getRepository(repoId: Long): Repository?

    /**
     * Returns a list of all [Repository]s in the database.
     */
    public fun getRepositories(): List<Repository>

    /**
     * Same as [getRepositories], but does return a [LiveData].
     */
    public fun getLiveRepositories(): LiveData<List<Repository>>

    /**
     * Returns a live data of all categories declared by all [Repository]s.
     */
    public fun getLiveCategories(): LiveData<List<Category>>

    /**
     * Enables or disables the repository with the given [repoId].
     * Data from disabled repositories is ignored in many queries.
     */
    public fun setRepositoryEnabled(repoId: Long, enabled: Boolean)

    /**
     * Updates the user-defined mirrors of the repository with the given [repoId].
     * The existing mirrors get overwritten with the given [mirrors].
     */
    public fun updateUserMirrors(repoId: Long, mirrors: List<String>)

    /**
     * Updates the user name and password (for basic authentication)
     * of the repository with the given [repoId].
     * The existing user name and password get overwritten with the given [username] and [password].
     */
    public fun updateUsernameAndPassword(repoId: Long, username: String?, password: String?)

    /**
     * Updates the disabled mirrors of the repository with the given [repoId].
     * The existing disabled mirrors get overwritten with the given [disabledMirrors].
     */
    public fun updateDisabledMirrors(repoId: Long, disabledMirrors: List<String>)

    /**
     * Removes a [Repository] with the given [repoId] with all associated data from the database.
     */
    public fun deleteRepository(repoId: Long)

    /**
     * Removes all repos and their preferences.
     */
    public fun clearAll()
}

@Dao
internal interface RepositoryDaoInt : RepositoryDao {

    @Insert(onConflict = REPLACE)
    fun insertOrReplace(repository: CoreRepository): Long

    @Update
    fun update(repository: CoreRepository)

    @Insert(onConflict = REPLACE)
    fun insertMirrors(mirrors: List<Mirror>)

    @Insert(onConflict = REPLACE)
    fun insertAntiFeatures(repoFeature: List<AntiFeature>)

    @Insert(onConflict = REPLACE)
    fun insertCategories(repoFeature: List<Category>)

    @Insert(onConflict = REPLACE)
    fun insertReleaseChannels(repoFeature: List<ReleaseChannel>)

    @Insert(onConflict = REPLACE)
    fun insert(repositoryPreferences: RepositoryPreferences)

    @Transaction
    override fun insert(initialRepo: InitialRepository): Long {
        val repo = CoreRepository(
            name = mapOf("en-US" to initialRepo.name),
            address = initialRepo.address,
            icon = null,
            timestamp = -1,
            version = initialRepo.version,
            formatVersion = null,
            maxAge = null,
            description = mapOf("en-US" to initialRepo.description),
            certificate = initialRepo.certificate,
        )
        val repoId = insertOrReplace(repo)
        val currentMinWeight = getMinRepositoryWeight()
        val repositoryPreferences = RepositoryPreferences(
            repoId = repoId,
            weight = currentMinWeight - 2,
            lastUpdated = null,
            enabled = initialRepo.enabled,
        )
        insert(repositoryPreferences)
        insertMirrors(initialRepo.mirrors.map { url -> Mirror(repoId, url, null) })
        return repoId
    }

    @Transaction
    override fun insert(newRepository: NewRepository): Long {
        val repo = CoreRepository(
            name = newRepository.name,
            icon = newRepository.icon,
            address = newRepository.address,
            timestamp = -1,
            version = null,
            formatVersion = newRepository.formatVersion,
            maxAge = null,
            certificate = newRepository.certificate,
        )
        val repoId = insertOrReplace(repo)
        val currentMinWeight = getMinRepositoryWeight()
        val repositoryPreferences = RepositoryPreferences(
            repoId = repoId,
            weight = currentMinWeight - 2,
            lastUpdated = null,
            username = newRepository.username,
            password = newRepository.password,
        )
        insert(repositoryPreferences)
        return repoId
    }

    @Transaction
    @VisibleForTesting
    @Deprecated("Use insert instead")
    fun insertEmptyRepo(
        address: String,
        username: String? = null,
        password: String? = null,
        certificate: String = "6789" // just used for testing
    ): Long {
        val repo = CoreRepository(
            name = mapOf("en-US" to address),
            icon = null,
            address = address,
            timestamp = -1,
            version = null,
            formatVersion = null,
            maxAge = null,
            certificate = certificate,
        )
        val repoId = insertOrReplace(repo)
        val currentMinWeight = getMinRepositoryWeight()
        val repositoryPreferences = RepositoryPreferences(
            repoId = repoId,
            weight = currentMinWeight - 2,
            lastUpdated = null,
            username = username,
            password = password,
        )
        insert(repositoryPreferences)
        return repoId
    }

    @Transaction
    @VisibleForTesting
    fun insertOrReplace(repository: RepoV2, version: Long = 0): Long {
        val repoId = insertOrReplace(
            repository.toCoreRepository(
                version = version,
                certificate = "0123", // just for testing
            )
        )
        val currentMinWeight = getMinRepositoryWeight()
        val repositoryPreferences = RepositoryPreferences(repoId, currentMinWeight - 2)
        insert(repositoryPreferences)
        insertRepoTables(repoId, repository)
        return repoId
    }

    @Query("SELECT COALESCE(MIN(weight), ${Int.MAX_VALUE}) FROM ${RepositoryPreferences.TABLE}")
    fun getMinRepositoryWeight(): Int

    @Transaction
    @Query("SELECT * FROM ${CoreRepository.TABLE} WHERE repoId = :repoId")
    override fun getRepository(repoId: Long): Repository?

    /**
     * Returns a non-archive repository with the given [certificate], if it exists in the DB.
     */
    @Transaction
    @Query("""SELECT * FROM ${CoreRepository.TABLE}
        WHERE certificate = :certificate AND address NOT LIKE "%/archive" COLLATE NOCASE
        LIMIT 1""")
    fun getRepository(certificate: String): Repository?

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """SELECT * FROM ${CoreRepository.TABLE}
        JOIN ${RepositoryPreferences.TABLE} AS pref USING (repoId)
        ORDER BY pref.weight DESC"""
    )
    override fun getRepositories(): List<Repository>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """SELECT * FROM ${CoreRepository.TABLE}
        JOIN ${RepositoryPreferences.TABLE} AS pref USING (repoId)
        ORDER BY pref.weight DESC"""
    )
    override fun getLiveRepositories(): LiveData<List<Repository>>

    @Query("SELECT * FROM ${RepositoryPreferences.TABLE} WHERE repoId = :repoId")
    fun getRepositoryPreferences(repoId: Long): RepositoryPreferences?

    @RewriteQueriesToDropUnusedColumns
    @Query("""SELECT * FROM ${Category.TABLE}
        JOIN ${RepositoryPreferences.TABLE} AS pref USING (repoId)
        WHERE pref.enabled = 1 GROUP BY id HAVING MAX(pref.weight)""")
    override fun getLiveCategories(): LiveData<List<Category>>

    /**
     * Updates an existing repo with new data from a full index update.
     * Call [clear] first to ensure old data was removed.
     */
    @Transaction
    fun update(
        repoId: Long,
        repository: RepoV2,
        version: Long,
        formatVersion: IndexFormatVersion,
    ) {
        val repo = getRepository(repoId) ?: error("Repo with id $repoId did not exist")
        update(repository.toCoreRepository(repoId, version, formatVersion, repo.certificate))
        insertRepoTables(repoId, repository)
    }

    private fun insertRepoTables(repoId: Long, repository: RepoV2) {
        insertMirrors(repository.mirrors.map { it.toMirror(repoId) })
        insertAntiFeatures(repository.antiFeatures.toRepoAntiFeatures(repoId))
        insertCategories(repository.categories.toRepoCategories(repoId))
        insertReleaseChannels(repository.releaseChannels.toRepoReleaseChannel(repoId))
    }

    @Update
    fun updateRepository(repo: CoreRepository): Int

    @Update
    fun updateRepositoryPreferences(preferences: RepositoryPreferences)

    /**
     * Used to update an existing repository with a given [jsonObject] JSON diff.
     */
    @Transaction
    fun updateRepository(repoId: Long, version: Long, jsonObject: JsonObject) {
        // get existing repo
        val repo = getRepository(repoId) ?: error("Repo $repoId does not exist")
        // update repo with JSON diff
        updateRepository(applyDiff(repo.repository, jsonObject).copy(version = version))
        // replace mirror list (if it is in the diff)
        diffAndUpdateListTable(
            jsonObject = jsonObject,
            jsonObjectKey = "mirrors",
            listParser = { mirrorArray ->
                json.decodeFromJsonElement<List<MirrorV2>>(mirrorArray).map {
                    it.toMirror(repoId)
                }
            },
            deleteList = { deleteMirrors(repoId) },
            insertNewList = { mirrors -> insertMirrors(mirrors) },
        )
        // diff and update the antiFeatures
        diffAndUpdateTable(
            jsonObject = jsonObject,
            jsonObjectKey = "antiFeatures",
            itemList = repo.antiFeatures,
            itemFinder = { key, item -> item.id == key },
            newItem = { key -> AntiFeature(repoId, key, emptyMap(), emptyMap(), emptyMap()) },
            deleteAll = { deleteAntiFeatures(repoId) },
            deleteOne = { key -> deleteAntiFeature(repoId, key) },
            insertReplace = { list -> insertAntiFeatures(list) },
        )
        // diff and update the categories
        diffAndUpdateTable(
            jsonObject = jsonObject,
            jsonObjectKey = "categories",
            itemList = repo.categories,
            itemFinder = { key, item -> item.id == key },
            newItem = { key -> Category(repoId, key, emptyMap(), emptyMap(), emptyMap()) },
            deleteAll = { deleteCategories(repoId) },
            deleteOne = { key -> deleteCategory(repoId, key) },
            insertReplace = { list -> insertCategories(list) },
        )
        // diff and update the releaseChannels
        diffAndUpdateTable(
            jsonObject = jsonObject,
            jsonObjectKey = "releaseChannels",
            itemList = repo.releaseChannels,
            itemFinder = { key, item -> item.id == key },
            newItem = { key -> ReleaseChannel(repoId, key, emptyMap(), emptyMap(), emptyMap()) },
            deleteAll = { deleteReleaseChannels(repoId) },
            deleteOne = { key -> deleteReleaseChannel(repoId, key) },
            insertReplace = { list -> insertReleaseChannels(list) },
        )
    }

    @Transaction
    override fun setRepositoryEnabled(repoId: Long, enabled: Boolean) {
        // When disabling a repository, we need to remove it as preferred repo for all apps,
        // otherwise our queries that ignore disabled repos will not return anything anymore.
        if (!enabled) resetPreferredRepoInAppPrefs(repoId)
        setRepositoryEnabledInternal(repoId, enabled)
    }

    @Query("UPDATE ${RepositoryPreferences.TABLE} SET enabled = :enabled WHERE repoId = :repoId")
    fun setRepositoryEnabledInternal(repoId: Long, enabled: Boolean)

    @Query("UPDATE ${AppPrefs.TABLE} SET preferredRepoId = NULL WHERE preferredRepoId = :repoId")
    fun resetPreferredRepoInAppPrefs(repoId: Long)

    @Query("""UPDATE ${RepositoryPreferences.TABLE} SET userMirrors = :mirrors
        WHERE repoId = :repoId""")
    override fun updateUserMirrors(repoId: Long, mirrors: List<String>)

    @Query("""UPDATE ${RepositoryPreferences.TABLE} SET username = :username, password = :password
        WHERE repoId = :repoId""")
    override fun updateUsernameAndPassword(repoId: Long, username: String?, password: String?)

    @Query("""UPDATE ${RepositoryPreferences.TABLE} SET disabledMirrors = :disabledMirrors
        WHERE repoId = :repoId""")
    override fun updateDisabledMirrors(repoId: Long, disabledMirrors: List<String>)

    /**
     * Changes repository weights/priorities that determine list order and preferred repositories.
     * The lower a repository is in the list, the lower is its priority.
     * If an app is in more than one repo, by default, the repo higher in the list wins.
     *
     * @param repoToReorder this repository will change its position in the list.
     * @param repoTarget the repository in which place the [repoToReorder] shall be moved.
     * If our list is [ A B C D ] and we call reorderRepositories(B, D),
     * then the new list will be [ A C D B ].
     *
     * @throws IllegalArgumentException if one of the repos is an archive repo.
     * Those are expected to be tied to their main repo one down the list
     * and are moved automatically when their main repo moves.
     */
    @Transaction
    fun reorderRepositories(repoToReorder: Repository, repoTarget: Repository) {
        require(!repoToReorder.isArchiveRepo && !repoTarget.isArchiveRepo) {
            "Re-ordering of archive repos is not supported"
        }
        if (repoToReorder.weight > repoTarget.weight) {
            // repoToReorder is higher,
            // so move repos below repoToReorder (and its archive below) two weights up
            shiftRepoWeights(repoTarget.weight, repoToReorder.weight - 2, 2)
        } else if (repoToReorder.weight < repoTarget.weight) {
            // repoToReorder is lower, so move repos above repoToReorder two weights down
            shiftRepoWeights(repoToReorder.weight + 1, repoTarget.weight, -2)
        } else {
            return // both repos have same weight, not re-ordering anything
        }
        // move repoToReorder in place of repoTarget
        setWeight(repoToReorder.repoId, repoTarget.weight)
        // also adjust weight of archive repo, if it exists
        val archiveRepoId = repoToReorder.certificate?.let { getArchiveRepoId(it) }
        if (archiveRepoId != null) {
            setWeight(archiveRepoId, repoTarget.weight - 1)
        }
    }

    @Query("""UPDATE ${RepositoryPreferences.TABLE} SET weight = :weight WHERE repoId = :repoId""")
    fun setWeight(repoId: Long, weight: Int)

    @Query(
        """UPDATE ${RepositoryPreferences.TABLE} SET weight = weight + :offset
        WHERE weight >= :weightFrom AND weight <= :weightTo"""
    )
    fun shiftRepoWeights(weightFrom: Int, weightTo: Int, offset: Int)

    @Query(
        """SELECT repoId FROM ${CoreRepository.TABLE}
        WHERE certificate = :cert AND address LIKE '%/archive' COLLATE NOCASE"""
    )
    fun getArchiveRepoId(cert: String): Long?

    @Transaction
    override fun deleteRepository(repoId: Long) {
        deleteCoreRepository(repoId)
        // we don't use cascading delete for preferences,
        // so we can replace index data on full updates
        deleteRepositoryPreferences(repoId)
        // When deleting a repository, we need to remove it as preferred repo for all apps,
        // otherwise our queries will not return anything anymore.
        resetPreferredRepoInAppPrefs(repoId)
    }

    @Query("DELETE FROM ${CoreRepository.TABLE} WHERE repoId = :repoId")
    fun deleteCoreRepository(repoId: Long)

    @Query("DELETE FROM ${RepositoryPreferences.TABLE} WHERE repoId = :repoId")
    fun deleteRepositoryPreferences(repoId: Long)

    @Query("DELETE FROM ${CoreRepository.TABLE}")
    fun deleteAllCoreRepositories()

    @Query("DELETE FROM ${RepositoryPreferences.TABLE}")
    fun deleteAllRepositoryPreferences()

    /**
     * Used for diffing.
     */
    @Query("DELETE FROM ${Mirror.TABLE} WHERE repoId = :repoId")
    fun deleteMirrors(repoId: Long)

    /**
     * Used for diffing.
     */
    @Query("DELETE FROM ${AntiFeature.TABLE} WHERE repoId = :repoId")
    fun deleteAntiFeatures(repoId: Long)

    /**
     * Used for diffing.
     */
    @Query("DELETE FROM ${AntiFeature.TABLE} WHERE repoId = :repoId AND id = :id")
    fun deleteAntiFeature(repoId: Long, id: String)

    /**
     * Used for diffing.
     */
    @Query("DELETE FROM ${Category.TABLE} WHERE repoId = :repoId")
    fun deleteCategories(repoId: Long)

    /**
     * Used for diffing.
     */
    @Query("DELETE FROM ${Category.TABLE} WHERE repoId = :repoId AND id = :id")
    fun deleteCategory(repoId: Long, id: String)

    /**
     * Used for diffing.
     */
    @Query("DELETE FROM ${ReleaseChannel.TABLE} WHERE repoId = :repoId")
    fun deleteReleaseChannels(repoId: Long)

    /**
     * Used for diffing.
     */
    @Query("DELETE FROM ${ReleaseChannel.TABLE} WHERE repoId = :repoId AND id = :id")
    fun deleteReleaseChannel(repoId: Long, id: String)

    /**
     * Resets timestamps for *all* repos in the database.
     * This will use a full index instead of diffs
     * when updating the repository via [IndexV2Updater].
     */
    @Query("UPDATE ${CoreRepository.TABLE} SET timestamp = -1")
    fun resetTimestamps()

    /**
     * Use when replacing an existing repo with a full index.
     * This removes all existing index data associated with this repo from the database,
     * but does not touch repository preferences.
     * @throws IllegalStateException if no repo with the given [repoId] exists.
     */
    @Transaction
    fun clear(repoId: Long) {
        val repo = getRepository(repoId) ?: error("repo with id $repoId does not exist")
        // this clears all foreign key associated data since the repo gets replaced
        insertOrReplace(repo.repository)
    }

    @Transaction
    override fun clearAll() {
        deleteAllCoreRepositories()
        deleteAllRepositoryPreferences()
    }

    @VisibleForTesting
    @Query("SELECT COUNT(*) FROM ${Mirror.TABLE}")
    fun countMirrors(): Int

    @VisibleForTesting
    @Query("SELECT COUNT(*) FROM ${AntiFeature.TABLE}")
    fun countAntiFeatures(): Int

    @VisibleForTesting
    @Query("SELECT COUNT(*) FROM ${Category.TABLE}")
    fun countCategories(): Int

    @VisibleForTesting
    @Query("SELECT COUNT(*) FROM ${ReleaseChannel.TABLE}")
    fun countReleaseChannels(): Int

}
