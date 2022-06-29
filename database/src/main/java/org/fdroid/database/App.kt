package org.fdroid.database

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat.getLocales
import androidx.core.os.LocaleListCompat
import androidx.room.ColumnInfo
import androidx.room.DatabaseView
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Ignore
import androidx.room.Relation
import org.fdroid.LocaleChooser.getBestLocale
import org.fdroid.database.Converters.fromStringToMapOfLocalizedTextV2
import org.fdroid.index.v2.FileV2
import org.fdroid.index.v2.LocalizedFileListV2
import org.fdroid.index.v2.LocalizedFileV2
import org.fdroid.index.v2.LocalizedTextV2
import org.fdroid.index.v2.MetadataV2
import org.fdroid.index.v2.Screenshots

@Entity(
    primaryKeys = ["repoId", "packageId"],
    foreignKeys = [ForeignKey(
        entity = CoreRepository::class,
        parentColumns = ["repoId"],
        childColumns = ["repoId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
public data class AppMetadata(
    val repoId: Long,
    val packageId: String,
    val added: Long,
    val lastUpdated: Long,
    val name: LocalizedTextV2? = null,
    val summary: LocalizedTextV2? = null,
    val description: LocalizedTextV2? = null,
    val localizedName: String? = null,
    val localizedSummary: String? = null,
    val webSite: String? = null,
    val changelog: String? = null,
    val license: String? = null,
    val sourceCode: String? = null,
    val issueTracker: String? = null,
    val translation: String? = null,
    val preferredSigner: String? = null, // TODO use platformSig if an APK matches it
    val video: LocalizedTextV2? = null,
    val authorName: String? = null,
    val authorEmail: String? = null,
    val authorWebSite: String? = null,
    val authorPhone: String? = null,
    val donate: List<String>? = null,
    val liberapayID: String? = null,
    val liberapay: String? = null,
    val openCollective: String? = null,
    val bitcoin: String? = null,
    val litecoin: String? = null,
    val flattrID: String? = null,
    val categories: List<String>? = null,
    /**
     * Whether the app is compatible with the current device.
     * This value will be computed and is always false until that happened.
     * So to always get correct data, this MUST happen within the same transaction
     * that adds the [AppMetadata].
     */
    val isCompatible: Boolean,
)

internal fun MetadataV2.toAppMetadata(
    repoId: Long,
    packageId: String,
    isCompatible: Boolean = false,
    locales: LocaleListCompat = getLocales(Resources.getSystem().configuration),
) = AppMetadata(
    repoId = repoId,
    packageId = packageId,
    added = added,
    lastUpdated = lastUpdated,
    name = name,
    summary = summary,
    description = description,
    localizedName = name.getBestLocale(locales),
    localizedSummary = summary.getBestLocale(locales),
    webSite = webSite,
    changelog = changelog,
    license = license,
    sourceCode = sourceCode,
    issueTracker = issueTracker,
    translation = translation,
    preferredSigner = preferredSigner,
    video = video,
    authorName = authorName,
    authorEmail = authorEmail,
    authorWebSite = authorWebSite,
    authorPhone = authorPhone,
    donate = donate,
    liberapayID = liberapayID,
    liberapay = liberapay,
    openCollective = openCollective,
    bitcoin = bitcoin,
    litecoin = litecoin,
    flattrID = flattrID,
    categories = categories,
    isCompatible = isCompatible,
)

@Entity
@Fts4(contentEntity = AppMetadata::class)
internal data class AppMetadataFts(
    val repoId: Long,
    val packageId: String,
    @ColumnInfo(name = "localizedName")
    val name: String? = null,
    @ColumnInfo(name = "localizedSummary")
    val summary: String? = null,
)

public data class App internal constructor(
    @Embedded val metadata: AppMetadata,
    @Relation(
        parentColumn = "packageId",
        entityColumn = "packageId",
    )
    private val localizedFiles: List<LocalizedFile>? = null,
    @Relation(
        parentColumn = "packageId",
        entityColumn = "packageId",
    )
    private val localizedFileLists: List<LocalizedFileList>? = null,
) {
    val icon: LocalizedFileV2? get() = getLocalizedFile("icon")
    val featureGraphic: LocalizedFileV2? get() = getLocalizedFile("featureGraphic")
    val promoGraphic: LocalizedFileV2? get() = getLocalizedFile("promoGraphic")
    val tvBanner: LocalizedFileV2? get() = getLocalizedFile("tvBanner")
    val screenshots: Screenshots?
        get() = if (localizedFileLists.isNullOrEmpty()) null else Screenshots(
            phone = getLocalizedFileList("phone"),
            sevenInch = getLocalizedFileList("sevenInch"),
            tenInch = getLocalizedFileList("tenInch"),
            wear = getLocalizedFileList("wear"),
            tv = getLocalizedFileList("tv"),
        ).takeIf { !it.isNull }

    private fun getLocalizedFile(type: String): LocalizedFileV2? {
        return localizedFiles?.filter { localizedFile ->
            localizedFile.repoId == metadata.repoId && localizedFile.type == type
        }?.toLocalizedFileV2()
    }

    private fun getLocalizedFileList(type: String): LocalizedFileListV2? {
        val map = HashMap<String, List<FileV2>>()
        localizedFileLists?.iterator()?.forEach { file ->
            if (file.repoId != metadata.repoId || file.type != type) return@forEach
            val list = map.getOrPut(file.locale) { ArrayList() } as ArrayList
            list.add(FileV2(
                name = file.name,
                sha256 = file.sha256,
                size = file.size,
            ))
        }
        return map.ifEmpty { null }
    }

    public val name: String? get() = metadata.localizedName
    public val summary: String? get() = metadata.localizedSummary
    public fun getDescription(localeList: LocaleListCompat): String? =
        metadata.description.getBestLocale(localeList)

    public fun getVideo(localeList: LocaleListCompat): String? =
        metadata.video.getBestLocale(localeList)

    public fun getIcon(localeList: LocaleListCompat): FileV2? = icon.getBestLocale(localeList)
    public fun getFeatureGraphic(localeList: LocaleListCompat): FileV2? =
        featureGraphic.getBestLocale(localeList)

    public fun getPromoGraphic(localeList: LocaleListCompat): FileV2? =
        promoGraphic.getBestLocale(localeList)

    public fun getTvBanner(localeList: LocaleListCompat): FileV2? =
        tvBanner.getBestLocale(localeList)

    // TODO remove ?.map { it.name } when client can handle FileV2
    public fun getPhoneScreenshots(localeList: LocaleListCompat): List<String> =
        screenshots?.phone.getBestLocale(localeList)?.map { it.name } ?: emptyList()

    public fun getSevenInchScreenshots(localeList: LocaleListCompat): List<String> =
        screenshots?.sevenInch.getBestLocale(localeList)?.map { it.name } ?: emptyList()

    public fun getTenInchScreenshots(localeList: LocaleListCompat): List<String> =
        screenshots?.tenInch.getBestLocale(localeList)?.map { it.name } ?: emptyList()

    public fun getTvScreenshots(localeList: LocaleListCompat): List<String> =
        screenshots?.tv.getBestLocale(localeList)?.map { it.name } ?: emptyList()

    public fun getWearScreenshots(localeList: LocaleListCompat): List<String> =
        screenshots?.wear.getBestLocale(localeList)?.map { it.name } ?: emptyList()
}

public data class AppOverviewItem(
    public val repoId: Long,
    public val packageId: String,
    public val added: Long,
    public val lastUpdated: Long,
    @ColumnInfo(name = "localizedName")
    public val name: String? = null,
    @ColumnInfo(name = "localizedSummary")
    public val summary: String? = null,
    internal val antiFeatures: Map<String, LocalizedTextV2>? = null,
    @Relation(
        parentColumn = "packageId",
        entityColumn = "packageId",
    )
    internal val localizedIcon: List<LocalizedIcon>? = null,
) {
    public fun getIcon(localeList: LocaleListCompat): FileV2? = localizedIcon?.filter { icon ->
        icon.repoId == repoId
    }?.toLocalizedFileV2().getBestLocale(localeList)

    val antiFeatureNames: List<String> get() = antiFeatures?.map { it.key } ?: emptyList()
}

public data class AppListItem constructor(
    public val repoId: Long,
    public val packageId: String,
    @ColumnInfo(name = "localizedName")
    public val name: String? = null,
    @ColumnInfo(name = "localizedSummary")
    public val summary: String? = null,
    internal val antiFeatures: String?,
    @Relation(
        parentColumn = "packageId",
        entityColumn = "packageId",
    )
    internal val localizedIcon: List<LocalizedIcon>?,
    /**
     * If true, this this app has at least one version that is compatible with this device.
     */
    public val isCompatible: Boolean,
    /**
     * The name of the installed version, null if this app is not installed.
     */
    @get:Ignore
    public val installedVersionName: String? = null,
    @get:Ignore
    public val installedVersionCode: Long? = null,
) {
    public fun getAntiFeatureNames(): List<String> {
        return fromStringToMapOfLocalizedTextV2(antiFeatures)?.map { it.key } ?: emptyList()
    }

    public fun getIcon(localeList: LocaleListCompat): FileV2? = localizedIcon?.filter { icon ->
        icon.repoId == repoId
    }?.toLocalizedFileV2().getBestLocale(localeList)
}

public data class UpdatableApp(
    public val packageId: String,
    public val installedVersionCode: Long,
    public val upgrade: AppVersion,
    /**
     * If true, this is not necessarily an update (contrary to the class name),
     * but an app with the `KnownVuln` anti-feature.
     */
    public val hasKnownVulnerability: Boolean,
    public val name: String? = null,
    public val summary: String? = null,
    internal val localizedIcon: List<LocalizedIcon>? = null,
) {
    public fun getIcon(localeList: LocaleListCompat): FileV2? = localizedIcon?.filter { icon ->
        icon.repoId == upgrade.repoId
    }?.toLocalizedFileV2().getBestLocale(localeList)
}

internal interface IFile {
    val type: String
    val locale: String
    val name: String
    val sha256: String?
    val size: Long?
}

@Entity(
    primaryKeys = ["repoId", "packageId", "type", "locale"],
    foreignKeys = [ForeignKey(
        entity = AppMetadata::class,
        parentColumns = ["repoId", "packageId"],
        childColumns = ["repoId", "packageId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class LocalizedFile(
    val repoId: Long,
    val packageId: String,
    override val type: String,
    override val locale: String,
    override val name: String,
    override val sha256: String? = null,
    override val size: Long? = null,
) : IFile

internal fun LocalizedFileV2.toLocalizedFile(
    repoId: Long,
    packageId: String,
    type: String,
): List<LocalizedFile> = map { (locale, file) ->
    LocalizedFile(
        repoId = repoId,
        packageId = packageId,
        type = type,
        locale = locale,
        name = file.name,
        sha256 = file.sha256,
        size = file.size,
    )
}

internal fun List<IFile>.toLocalizedFileV2(): LocalizedFileV2? = associate { file ->
    file.locale to FileV2(
        name = file.name,
        sha256 = file.sha256,
        size = file.size,
    )
}.ifEmpty { null }

// We can't restrict this query further (e.g. only from enabled repos or max weight),
// because we are using this via @Relation on packageName for specific repos.
// When filtering the result for only the repoId we are interested in, we'd get no icons.
@DatabaseView("SELECT * FROM LocalizedFile WHERE type='icon'")
public data class LocalizedIcon(
    val repoId: Long,
    val packageId: String,
    override val type: String,
    override val locale: String,
    override val name: String,
    override val sha256: String? = null,
    override val size: Long? = null,
) : IFile

@Entity(
    primaryKeys = ["repoId", "packageId", "type", "locale", "name"],
    foreignKeys = [ForeignKey(
        entity = AppMetadata::class,
        parentColumns = ["repoId", "packageId"],
        childColumns = ["repoId", "packageId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class LocalizedFileList(
    val repoId: Long,
    val packageId: String,
    val type: String,
    val locale: String,
    val name: String,
    val sha256: String? = null,
    val size: Long? = null,
)

internal fun LocalizedFileListV2.toLocalizedFileList(
    repoId: Long,
    packageId: String,
    type: String,
): List<LocalizedFileList> = flatMap { (locale, files) ->
    files.map { file -> file.toLocalizedFileList(repoId, packageId, type, locale) }
}

internal fun FileV2.toLocalizedFileList(
    repoId: Long,
    packageId: String,
    type: String,
    locale: String,
) = LocalizedFileList(
    repoId = repoId,
    packageId = packageId,
    type = type,
    locale = locale,
    name = name,
    sha256 = sha256,
    size = size,
)
