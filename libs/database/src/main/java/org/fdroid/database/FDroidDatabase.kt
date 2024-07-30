package org.fdroid.database

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat.getLocales
import androidx.core.os.LocaleListCompat
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.fdroid.LocaleChooser.getBestLocale
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.Callable

@Database(
    // When bumping this version, please make sure to add one (or more) migration(s) below!
    // Consider also providing tests for that migration.
    // Don't forget to commit the new schema to the git repo as well.
    version = 5,
    entities = [
        // repo
        CoreRepository::class,
        Mirror::class,
        AntiFeature::class,
        Category::class,
        ReleaseChannel::class,
        RepositoryPreferences::class,
        // packages
        AppMetadata::class,
        AppMetadataFts::class,
        LocalizedFile::class,
        LocalizedFileList::class,
        // versions
        Version::class,
        VersionedString::class,
        // app user preferences
        AppPrefs::class,
    ],
    views = [
        LocalizedIcon::class,
        HighestVersion::class,
        PreferredRepo::class,
    ],
    exportSchema = true,
    autoMigrations = [
        AutoMigration(1, 2, MultiRepoMigration::class),
        // 2 to 3 is a manual migration
        AutoMigration(3, 4),
        AutoMigration(4, 5),
        // add future migrations here (if they are easy enough to be done automatically)
    ],
)
@TypeConverters(Converters::class)
internal abstract class FDroidDatabaseInt : RoomDatabase(), FDroidDatabase, Closeable {
    abstract override fun getRepositoryDao(): RepositoryDaoInt
    abstract override fun getAppDao(): AppDaoInt
    abstract override fun getVersionDao(): VersionDaoInt
    abstract override fun getAppPrefsDao(): AppPrefsDaoInt
    override fun afterLocalesChanged(locales: LocaleListCompat) {
        val appDao = getAppDao()
        runInTransaction {
            appDao.getAppMetadata().forEach { appMetadata ->
                appDao.updateAppMetadata(
                    repoId = appMetadata.repoId,
                    packageName = appMetadata.packageName,
                    name = appMetadata.name.getBestLocale(locales),
                    summary = appMetadata.summary.getBestLocale(locales),
                )
            }
        }
    }

    /**
     * Call this after updating the data belonging to the given [repoId],
     * so the [AppMetadata.isCompatible] can be recalculated in case new versions were added.
     */
    fun afterUpdatingRepo(repoId: Long) {
        getAppDao().updateCompatibility(repoId)
    }

    override fun clearAllAppData() {
        runInTransaction {
            getAppDao().clearAll()
            getRepositoryDao().resetTimestamps()
        }
    }
}

/**
 * The F-Droid database offering methods to retrieve the various data access objects.
 */
public interface FDroidDatabase {
    public fun getRepositoryDao(): RepositoryDao
    public fun getAppDao(): AppDao
    public fun getVersionDao(): VersionDao
    public fun getAppPrefsDao(): AppPrefsDao

    /**
     * Call this after the system [Locale]s have changed.
     * If this isn't called, the cached localized app metadata (e.g. name, summary) will be wrong.
     */
    public fun afterLocalesChanged(
        locales: LocaleListCompat = getLocales(Resources.getSystem().configuration),
    )

    /**
     * Call this to run all of the given [body] inside a database transaction.
     * Please run as little code as possible to keep the time the database is blocked minimal.
     */
    public fun runInTransaction(body: Runnable)

    /**
     * Like [runInTransaction], but can return something.
     */
    public fun <V> runInTransaction(body: Callable<V>): V

    /**
     * Removes all apps and associated data (such as versions) from all repositories.
     * The repository data and app preferences are kept as-is.
     * Only the timestamp of the last repo update gets reset, so we won't try to apply diffs.
     */
    public fun clearAllAppData()
}
