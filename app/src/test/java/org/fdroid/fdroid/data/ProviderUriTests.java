package org.belos.belmarket.data;

import org.belos.belmarket.BuildConfig;
import org.belos.belmarket.TestUtils;
import org.belos.belmarket.data.Schema.InstalledAppTable;
import org.belos.belmarket.mock.MockApk;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.ArrayList;
import java.util.List;

import static org.belos.belmarket.Assert.assertInvalidUri;
import static org.belos.belmarket.Assert.assertValidUri;

// TODO: Use sdk=24 when Robolectric supports this
@Config(constants = BuildConfig.class, sdk = 23)
@RunWith(RobolectricGradleTestRunner.class)
public class ProviderUriTests {

    private ShadowContentResolver resolver;

    @Before
    public void setup() {
        resolver = Shadows.shadowOf(RuntimeEnvironment.application.getContentResolver());
    }

    @After
    public void teardown() {
        FDroidProvider.clearDbHelperSingleton();
    }

    @Test
    public void invalidInstalledAppProviderUris() {
        ShadowContentResolver.registerProvider(InstalledAppProvider.getAuthority(), new InstalledAppProvider());
        assertInvalidUri(resolver, InstalledAppProvider.getAuthority());
        assertInvalidUri(resolver, "blah");
    }

    @Test
    public void validInstalledAppProviderUris() {
        ShadowContentResolver.registerProvider(InstalledAppProvider.getAuthority(), new InstalledAppProvider());
        String[] projection = new String[] {InstalledAppTable.Cols._ID};
        assertValidUri(resolver, InstalledAppProvider.getContentUri(), projection);
        assertValidUri(resolver, InstalledAppProvider.getAppUri("org.example.app"), projection);
        assertValidUri(resolver, InstalledAppProvider.getSearchUri("blah"), projection);
        assertValidUri(resolver, InstalledAppProvider.getSearchUri("\"blah\""), projection);
        assertValidUri(resolver, InstalledAppProvider.getSearchUri("blah & sneh"), projection);
        assertValidUri(resolver, InstalledAppProvider.getSearchUri("http://blah.example.com?sneh=\"sneh\""), projection);
    }

    @Test
    public void invalidRepoProviderUris() {
        ShadowContentResolver.registerProvider(RepoProvider.getAuthority(), new RepoProvider());
        assertInvalidUri(resolver, RepoProvider.getAuthority());
        assertInvalidUri(resolver, "blah");
    }

    @Test
    public void validRepoProviderUris() {
        ShadowContentResolver.registerProvider(RepoProvider.getAuthority(), new RepoProvider());
        String[] projection = new String[] {Schema.RepoTable.Cols._ID};
        assertValidUri(resolver, RepoProvider.getContentUri(), projection);
        assertValidUri(resolver, RepoProvider.getContentUri(10000L), projection);
        assertValidUri(resolver, RepoProvider.allExceptSwapUri(), projection);
    }

    @Test
    public void invalidAppProviderUris() {
        ShadowContentResolver.registerProvider(AppProvider.getAuthority(), new AppProvider());
        assertInvalidUri(resolver, AppProvider.getAuthority());
        assertInvalidUri(resolver, "blah");
    }

    @Test
    public void validAppProviderUris() {
        ShadowContentResolver.registerProvider(AppProvider.getAuthority(), new AppProvider());
        String[] projection = new String[] {Schema.AppMetadataTable.Cols._ID};
        assertValidUri(resolver, AppProvider.getContentUri(), "content://org.belos.belmarket.data.AppProvider", projection);
        assertValidUri(resolver, AppProvider.getSearchUri("'searching!'"), "content://org.belos.belmarket.data.AppProvider/search/'searching!'", projection);
        assertValidUri(resolver, AppProvider.getSearchUri("/"), "content://org.belos.belmarket.data.AppProvider/search/%2F", projection);
        assertValidUri(resolver, AppProvider.getSearchUri(""), "content://org.belos.belmarket.data.AppProvider", projection);
        assertValidUri(resolver, AppProvider.getSearchUri(null), "content://org.belos.belmarket.data.AppProvider", projection);
        assertValidUri(resolver, AppProvider.getInstalledUri(), "content://org.belos.belmarket.data.AppProvider/installed", projection);
        assertValidUri(resolver, AppProvider.getCanUpdateUri(), "content://org.belos.belmarket.data.AppProvider/canUpdate", projection);

        App app = new App();
        app.repoId = 1;
        app.packageName = "org.belos.belmarket";

        assertValidUri(resolver, AppProvider.getSpecificAppUri(app.packageName, app.repoId), "content://org.belos.belmarket.data.AppProvider/app/1/org.belos.belmarket", projection);
    }

    @Test
    public void validTempAppProviderUris() {
        ShadowContentResolver.registerProvider(TempAppProvider.getAuthority(), new TempAppProvider());
        String[] projection = new String[]{Schema.AppMetadataTable.Cols._ID};

        // Required so that the `assertValidUri` calls below will indeed have a real temp_fdroid_app
        // table to query.
        TempAppProvider.Helper.init(TestUtils.createContextWithContentResolver(resolver));

        List<String> packageNames = new ArrayList<>(2);
        packageNames.add("org.belos.belmarket");
        packageNames.add("com.example.com");

        assertValidUri(resolver, TempAppProvider.getAppsUri(packageNames, 1), "content://org.belos.belmarket.data.TempAppProvider/apps/1/org.belos.belmarket%2Ccom.example.com", projection);
        assertValidUri(resolver, TempAppProvider.getContentUri(), "content://org.belos.belmarket.data.TempAppProvider", projection);
    }

    @Test
    public void invalidApkProviderUris() {
        ShadowContentResolver.registerProvider(ApkProvider.getAuthority(), new ApkProvider());
        assertInvalidUri(resolver, ApkProvider.getAuthority());
        assertInvalidUri(resolver, "blah");
    }

    @Test
    public void validApkProviderUris() {
        ShadowContentResolver.registerProvider(ApkProvider.getAuthority(), new ApkProvider());
        String[] projection = new String[] {Schema.ApkTable.Cols._ID};

        List<Apk> apks = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            apks.add(new MockApk("com.example." + i, i));
        }

        assertValidUri(resolver, ApkProvider.getContentUri(), "content://org.belos.belmarket.data.ApkProvider", projection);
        assertValidUri(resolver, ApkProvider.getAppUri("org.belos.belmarket"), "content://org.belos.belmarket.data.ApkProvider/app/org.belos.belmarket", projection);
        assertValidUri(resolver, ApkProvider.getApkFromAnyRepoUri(new MockApk("org.belos.belmarket", 100)), "content://org.belos.belmarket.data.ApkProvider/apk-any-repo/100/org.belos.belmarket", projection);
        assertValidUri(resolver, ApkProvider.getContentUri(apks), projection);
        assertValidUri(resolver, ApkProvider.getApkFromAnyRepoUri("org.belos.belmarket", 100), "content://org.belos.belmarket.data.ApkProvider/apk-any-repo/100/org.belos.belmarket", projection);
        assertValidUri(resolver, ApkProvider.getRepoUri(1000), "content://org.belos.belmarket.data.ApkProvider/repo/1000", projection);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidApkUrisWithTooManyApks() {
        String[] projection = Schema.ApkTable.Cols.ALL;

        List<Apk> manyApks = new ArrayList<>(ApkProvider.MAX_APKS_TO_QUERY - 5);
        for (int i = 0; i < ApkProvider.MAX_APKS_TO_QUERY - 1; i++) {
            manyApks.add(new MockApk("com.example." + i, i));
        }
        assertValidUri(resolver, ApkProvider.getContentUri(manyApks), projection);

        manyApks.add(new MockApk("org.belos.belmarket.1", 1));
        manyApks.add(new MockApk("org.belos.belmarket.2", 2));

        // Technically, it is a valid URI, because it doesn't
        // throw an UnsupportedOperationException. However it
        // is still not okay (we run out of bindable parameters
        // in the sqlite query.
        assertValidUri(resolver, ApkProvider.getContentUri(manyApks), projection);
    }

}
