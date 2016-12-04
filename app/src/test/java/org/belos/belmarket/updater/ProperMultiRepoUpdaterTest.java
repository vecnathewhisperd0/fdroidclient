
package org.belos.belmarket.updater;

import android.content.ContentValues;
import android.support.annotation.StringDef;
import android.util.Log;

import org.belos.belmarket.BuildConfig;
import org.belos.belmarket.RepoUpdater;
import org.belos.belmarket.data.Apk;
import org.belos.belmarket.data.ApkProvider;
import org.belos.belmarket.data.App;
import org.belos.belmarket.data.AppProvider;
import org.belos.belmarket.data.Repo;
import org.belos.belmarket.data.RepoProvider;
import org.belos.belmarket.data.Schema;
import org.belos.belmarket.data.Schema.AppMetadataTable;
import org.belos.belmarket.data.Schema.RepoTable.Cols;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

// TODO: Use sdk=24 when Robolectric supports this
@Config(constants = BuildConfig.class, sdk = 23)
@RunWith(RobolectricGradleTestRunner.class)
public class ProperMultiRepoUpdaterTest extends MultiRepoUpdaterTest {
    private static final String TAG = "ProperMultiRepoSupport";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({"Conflicting", "Normal"})
    public @interface RepoIdentifier { }

    /*
     *This test fails due to issue #568 (https://gitlab.com/fdroid/fdroidclient/issues/568).
    @Test
    public void appsRemovedFromRepo() throws RepoUpdater.UpdateException {
        assertEquals(0, AppProvider.Helper.all(context.getContentResolver()).size());

        updateMain();
        Repo repo = RepoProvider.Helper.findByAddress(context, REPO_MAIN_URI);

        assertEquals(3, AppProvider.Helper.all(context.getContentResolver()).size());
        assertEquals(6, ApkProvider.Helper.findByRepo(context, repo, Schema.ApkTable.Cols.ALL).size());
        assertEquals(3, ApkProvider.Helper.findByPackageName(context, "org.adaway").size());
        assertEquals(2, ApkProvider.Helper.findByPackageName(context, "com.uberspot.a2048").size());
        assertEquals(1, ApkProvider.Helper.findByPackageName(context, "siir.es.adbWireless").size());

        RepoUpdater updater = new RepoUpdater(context, RepoProvider.Helper.findByAddress(context, repo.address));
        updateRepo(updater, "multiRepo.conflicting.jar");

        assertEquals(2, AppProvider.Helper.all(context.getContentResolver()).size());
        assertEquals(6, ApkProvider.Helper.findByRepo(context, repo, Schema.ApkTable.Cols.ALL).size());
        assertEquals(4, ApkProvider.Helper.findByPackageName(context, "org.adaway").size());
        assertEquals(2, ApkProvider.Helper.findByPackageName(context, "org.dgtale.icsimport").size());
    }*/

    @Test
    public void mainRepo() throws RepoUpdater.UpdateException {
        assertEmpty();
        updateMain();
        assertMainRepo();
    }

    @Test
    public void archiveRepo() throws RepoUpdater.UpdateException {
        assertEmpty();
        updateArchive();
        assertMainArchiveRepoMetadata();
    }

    @Test
    public void conflictingRepo() throws RepoUpdater.UpdateException {
        assertEmpty();
        updateConflicting();
        assertConflictingRepo();
    }

    private Map<String, App> allApps() {
        List<App> apps = AppProvider.Helper.all(context.getContentResolver());
        Map<String, App> appsIndexedByPackageName = new HashMap<>(apps.size());
        for (App app : apps) {
            appsIndexedByPackageName.put(app.packageName, app);
        }
        return appsIndexedByPackageName;
    }

    @Test
    public void metadataWithRepoPriority() throws RepoUpdater.UpdateException {
        updateConflicting();
        updateMain();
        updateArchive();

        Repo conflictingRepo = RepoProvider.Helper.findByAddress(context, REPO_CONFLICTING_URI);

        assertEquals(1, conflictingRepo.priority);
        assertEquals(2, RepoProvider.Helper.findByAddress(context, REPO_MAIN_URI).priority);
        assertEquals(3, RepoProvider.Helper.findByAddress(context, REPO_ARCHIVE_URI).priority);

        assertMainRepo();
        assertMainArchiveRepoMetadata();
        assertConflictingRepo();

        assertRepoTakesPriority("Conflicting");

        // Make the conflicting repo less important than the main repo.
        ContentValues values = new ContentValues(1);
        values.put(Cols.PRIORITY, 5);
        RepoProvider.Helper.update(context, conflictingRepo, values);
        Repo updatedConflictingRepo = RepoProvider.Helper.findByAddress(context, REPO_CONFLICTING_URI);
        assertEquals(5, updatedConflictingRepo.priority);

        assertRepoTakesPriority("Normal");
    }

    private void assertRepoTakesPriority(@RepoIdentifier String higherPriority) {
        Map<String, App> allApps = allApps();

        // Provided by both the "Main" and "Conflicting" repo, so need to fetch metdata from the
        // repo with the higher "Conflicting" repo has a higher priority.
        App adAway = AppProvider.Helper.findHighestPriorityMetadata(context.getContentResolver(), "org.adaway");
        assertAdAwayMetadata(adAway, higherPriority);
        assertAdAwayMetadata(allApps.get("org.adaway"), higherPriority);


        // This is only provided by the "Main" or "Archive" repo. Both the main and archive repo both
        // pull their metadata from the same build recipe in fdroidserver. The only difference is that
        // the archive repository contains .apks from further back, but their metadata is the same.
        App a2048 = AppProvider.Helper.findHighestPriorityMetadata(context.getContentResolver(), "com.uberspot.a2048");
        assert2048Metadata(a2048, "Normal");
        assert2048Metadata(allApps.get("com.uberspot.a2048"), "Normal");

        // This is only provided by the "Conflicting" repo.
        App calendar = AppProvider.Helper.findHighestPriorityMetadata(context.getContentResolver(), "org.dgtale.icsimport");
        assertCalendarMetadata(calendar, "Conflicting");
        assertCalendarMetadata(allApps.get("org.dgtale.icsimport"), "Conflicting");

        // This is only provided by the "Main" repo.
        App adb = AppProvider.Helper.findHighestPriorityMetadata(context.getContentResolver(), "siir.es.adbWireless");
        assertAdbMetadata(adb, "Normal");
        assertAdbMetadata(allApps.get("siir.es.adbWireless"), "Normal");
    }

    @Test
    public void testCorrectConflictingThenMainThenArchive() throws RepoUpdater.UpdateException {
        assertEmpty();

        updateConflicting();
        updateMain();
        updateArchive();

        assertExpected();
    }

    @Test
    public void testCorrectConflictingThenArchiveThenMain() throws RepoUpdater.UpdateException {
        assertEmpty();

        updateConflicting();
        updateArchive();
        updateMain();

        assertExpected();
    }

    @Test
    public void testCorrectArchiveThenMainThenConflicting() throws RepoUpdater.UpdateException {
        assertEmpty();

        updateArchive();
        updateMain();
        updateConflicting();

        assertExpected();
    }

    @Test
    public void testCorrectArchiveThenConflictingThenMain() throws RepoUpdater.UpdateException {
        assertEmpty();

        updateArchive();
        updateConflicting();
        updateMain();

        assertExpected();
    }

    @Test
    public void testCorrectMainThenArchiveThenConflicting() throws RepoUpdater.UpdateException {
        assertEmpty();

        updateMain();
        updateArchive();
        updateConflicting();

        assertExpected();
    }

    @Test
    public void testCorrectMainThenConflictingThenArchive() throws RepoUpdater.UpdateException {
        assertEmpty();

        updateMain();
        updateConflicting();
        updateArchive();

        assertExpected();
    }

    /**
     * Check that all of the expected apps and apk versions are available in the database. This
     * check will take into account the repository the apks came from, to ensure that each
     * repository indeed contains the apks that it said it would provide.
     */
    private void assertExpected() {
        Log.i(TAG, "Asserting all versions of each .apk are in index.");
        List<Repo> repos = RepoProvider.Helper.all(context);
        assertEquals("Repos", 3, repos.size());

        assertMainRepo(repos);
        assertMainArchiveRepoMetadata(repos);
        assertConflictingRepo(repos);
    }

    private void assertMainRepo() {
        assertMainRepo(RepoProvider.Helper.all(context));
    }

    /**
     * + 2048 (com.uberspot.a2048)
     * - Version 1.96 (19)
     * - Version 1.95 (18)
     * + AdAway (org.adaway)
     * - Version 3.0.2 (54)
     * - Version 3.0.1 (53)
     * - Version 3.0 (52)
     * + adbWireless (siir.es.adbWireless)
     * - Version 1.5.4 (12)
     */
    private void assertMainRepo(List<Repo> allRepos) {
        Repo repo = findRepo(REPO_MAIN, allRepos);

        List<Apk> apks = ApkProvider.Helper.findByRepo(context, repo, Schema.ApkTable.Cols.ALL);
        assertEquals("Apks for main repo", apks.size(), 6);
        assertApksExist(apks, "com.uberspot.a2048", new int[]{18, 19});
        assertApksExist(apks, "org.adaway", new int[]{52, 53, 54});
        assertApksExist(apks, "siir.es.adbWireless", new int[]{12});

        assert2048Metadata(repo, "Normal");
        assertAdAwayMetadata(repo, "Normal");
        assertAdbMetadata(repo, "Normal");
    }

    private void assert2048Metadata(Repo repo, @RepoIdentifier String id) {
        App a2048 = AppProvider.Helper.findSpecificApp(context.getContentResolver(), "com.uberspot.a2048", repo.getId(), AppMetadataTable.Cols.ALL);
        assert2048Metadata(a2048, id);
    }

    /**
     * @param id An identifier that we've put in the metadata for each repo to ensure that
     *           we can identify the metadata is coming from the correct repo.
     */
    private void assert2048Metadata(App a2048, @RepoIdentifier String id) {
        assertNotNull(a2048);
        assertEquals("2048", a2048.name);
        assertEquals(String.format("<p>2048 from %s repo.</p>", id), a2048.description);
        assertEquals(String.format("Puzzle game (%s)", id), a2048.summary);
        assertEquals(String.format("https://github.com/uberspot/2048-android?%s", id), a2048.webURL);
        assertEquals(String.format("https://github.com/uberspot/2048-android?code&%s", id), a2048.sourceURL);
        assertEquals(String.format("https://github.com/uberspot/2048-android/issues?%s", id), a2048.trackerURL);
    }

    private void assertAdAwayMetadata(Repo repo, @RepoIdentifier String id) {
        App adaway = AppProvider.Helper.findSpecificApp(context.getContentResolver(), "org.adaway", repo.getId(), AppMetadataTable.Cols.ALL);
        assertAdAwayMetadata(adaway, id);
    }

    /** @see ProperMultiRepoUpdaterTest#assert2048Metadata(Repo, String) */
    private void assertAdAwayMetadata(App adaway, @RepoIdentifier String id) {
        assertNotNull(adaway);
        assertEquals(String.format("AdAway", id), adaway.name);
        assertEquals(String.format("<p>AdAway from %s repo.</p>", id), adaway.description);
        assertEquals(String.format("Block advertisements (%s)", id), adaway.summary);
        assertEquals(String.format("http://sufficientlysecure.org/index.php/adaway?%s", id), adaway.webURL);
        assertEquals(String.format("https://github.com/dschuermann/ad-away?%s", id), adaway.sourceURL);
        assertEquals(String.format("https://github.com/dschuermann/ad-away/issues?%s", id), adaway.trackerURL);
        assertEquals(String.format("https://github.com/dschuermann/ad-away/raw/HEAD/CHANGELOG?%s", id), adaway.changelogURL);
        assertEquals(String.format("http://sufficientlysecure.org/index.php/adaway?%s", id), adaway.donateURL);
        assertEquals(String.format("369138", id), adaway.flattrID);
    }

    private void assertAdbMetadata(Repo repo, @RepoIdentifier String id) {
        App adb = AppProvider.Helper.findSpecificApp(context.getContentResolver(), "siir.es.adbWireless", repo.getId(), AppMetadataTable.Cols.ALL);
        assertAdbMetadata(adb, id);
    }

    /** @see ProperMultiRepoUpdaterTest#assert2048Metadata(Repo, String) */
    private void assertAdbMetadata(App adb, @RepoIdentifier String id) {
        assertNotNull(adb);
        assertEquals("adbWireless", adb.name);
        assertEquals(String.format("<p>adbWireless from %s repo.</p>", id), adb.description);
        assertEquals(String.format("Wireless adb (%s)", id), adb.summary);
        assertEquals(String.format("https://adbwireless.example.com?%s", id), adb.webURL);
        assertEquals(String.format("https://adbwireless.example.com/source?%s", id), adb.sourceURL);
        assertEquals(String.format("https://adbwireless.example.com/issues?%s", id), adb.trackerURL);
    }

    private void assertCalendarMetadata(Repo repo, @RepoIdentifier String id) {
        App calendar = AppProvider.Helper.findSpecificApp(context.getContentResolver(), "org.dgtale.icsimport", repo.getId(), AppMetadataTable.Cols.ALL);
        assertCalendarMetadata(calendar, id);
    }

    /** @see ProperMultiRepoUpdaterTest#assert2048Metadata(Repo, String) */
    private void assertCalendarMetadata(App calendar, @RepoIdentifier String id) {
        assertNotNull(calendar);
        assertEquals("Add to calendar", calendar.name);
        assertEquals(String.format("<p>Add to calendar from %s repo.</p>", id), calendar.description);
        assertEquals(String.format("Import .ics files into calendar (%s)", id), calendar.summary);
        assertEquals(String.format("https://github.com/danielegobbetti/ICSImport/blob/HEAD/README.md?%s", id), calendar.webURL);
        assertEquals(String.format("https://github.com/danielegobbetti/ICSImport?%s", id), calendar.sourceURL);
        assertEquals(String.format("https://github.com/danielegobbetti/ICSImport/issues?%s", id), calendar.trackerURL);
        assertEquals("2225390", calendar.flattrID);
    }

    private void assertMainArchiveRepoMetadata() {
        assertMainArchiveRepoMetadata(RepoProvider.Helper.all(context));
    }

    /**
     * + AdAway (org.adaway)
     * - Version 2.9.2 (51)
     * - Version 2.9.1 (50)
     * - Version 2.9 (49)
     * - Version 2.8.1 (48)
     * - Version 2.8 (47)
     * - Version 2.7 (46)
     * - Version 2.6 (45)
     * - Version 2.3 (42)
     * - Version 2.1 (40)
     * - Version 1.37 (38)
     * - Version 1.36 (37)
     * - Version 1.35 (36)
     * - Version 1.34 (35)
     */
    private void assertMainArchiveRepoMetadata(List<Repo> allRepos) {
        Repo repo = findRepo(REPO_ARCHIVE, allRepos);

        List<Apk> apks = ApkProvider.Helper.findByRepo(context, repo, Schema.ApkTable.Cols.ALL);
        assertEquals("Apks for main archive repo", 13, apks.size());
        assertApksExist(apks, "org.adaway", new int[]{35, 36, 37, 38, 40, 42, 45, 46, 47, 48, 49, 50, 51});

        assertAdAwayMetadata(repo, "Normal");
    }

    private void assertConflictingRepo() {
        assertConflictingRepo(RepoProvider.Helper.all(context));
    }

    /**
     * + AdAway (org.adaway)
     * - Version 3.0.1 (53) *
     * - Version 3.0 (52) *
     * - Version 2.9.2 (51) *
     * - Version 2.2.1 (50) *
     * + Add to calendar (org.dgtale.icsimport)
     * - Version 1.2 (3)
     * - Version 1.1 (2)
     */
    private void assertConflictingRepo(List<Repo> allRepos) {
        Repo repo = findRepo(REPO_CONFLICTING, allRepos);

        List<Apk> apks = ApkProvider.Helper.findByRepo(context, repo, Schema.ApkTable.Cols.ALL);
        assertEquals("Apks for conflicting repo", 6, apks.size());
        assertApksExist(apks, "org.adaway", new int[]{50, 51, 52, 53});
        assertApksExist(apks, "org.dgtale.icsimport", new int[]{2, 3});

        assertAdAwayMetadata(repo, "Conflicting");
        assertCalendarMetadata(repo, "Conflicting");
    }

}
