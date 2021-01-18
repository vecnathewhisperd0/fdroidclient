package org.fdroid.fdroid.work;

import android.app.Application;
import android.text.format.DateUtils;
import org.apache.commons.io.FileUtils;
import org.fdroid.fdroid.TestUtils;
import org.fdroid.fdroid.data.FDroidProviderTest;
import org.fdroid.fdroid.installer.InstallHistoryService;
import org.fdroid.fdroid.work.PopularityContestWorker.MatomoEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.text.ParseException;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@Config(application = Application.class)
@RunWith(RobolectricTestRunner.class)
public class PopulationContestWorkerTest extends FDroidProviderTest {

    @Test
    public void testNormalizeTimestampToWeek() {
        long startTime = 1610038865743L;
        long endTime = 1610037631519L;

        long normalizedStart = PopularityContestWorker.toCleanInsightsTimestamp(startTime);
        long normalizedEnd = PopularityContestWorker.toCleanInsightsTimestamp(endTime);
        assertEquals(normalizedStart, normalizedEnd);

        long normalizedRelativeEnd = PopularityContestWorker.toCleanInsightsTimestamp(startTime, endTime);
        assertEquals(1609976365L, normalizedRelativeEnd);
    }

    @Test
    public void testParseInstallHistory() throws IOException {
        FileUtils.copyFile(TestUtils.copyResourceToTempFile("install_history_all"),
                InstallHistoryService.getInstallHistoryFile(context));
        long weekStart = PopularityContestWorker.getReportingWeekStart(1611268892206L + DateUtils.WEEK_IN_MILLIS);
        Collection<? extends MatomoEvent> events = PopularityContestWorker.parseInstallHistoryCsv(context,
                weekStart);
        assertEquals(3, events.size());
        for (MatomoEvent event : events) {
            assertEquals(event.value, "105");
            assertEquals(event.name, "com.termux");
        }

        Collection<? extends MatomoEvent> oneWeekAgo = PopularityContestWorker.parseInstallHistoryCsv(context,
                weekStart - DateUtils.WEEK_IN_MILLIS);
        assertEquals(11, oneWeekAgo.size());

        Collection<? extends MatomoEvent> twoWeeksAgo = PopularityContestWorker.parseInstallHistoryCsv(context,
                weekStart - (2 * DateUtils.WEEK_IN_MILLIS));
        assertEquals(0, twoWeeksAgo.size());

        Collection<? extends MatomoEvent> threeWeeksAgo = PopularityContestWorker.parseInstallHistoryCsv(context,
                weekStart - (3 * DateUtils.WEEK_IN_MILLIS));
        assertEquals(9, threeWeeksAgo.size());
        assertNotEquals(oneWeekAgo, threeWeeksAgo);
    }

    @Test
    public void testGetReportingWeekStart() throws ParseException {
        long now = System.currentTimeMillis();
        long start = PopularityContestWorker.getReportingWeekStart(now);
        assertTrue((now - DateUtils.WEEK_IN_MILLIS) > start);
        assertTrue((now - DateUtils.WEEK_IN_MILLIS) < (start + DateUtils.WEEK_IN_MILLIS));
    }
}
