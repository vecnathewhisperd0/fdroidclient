/*
 * Copyright (C) 2021  Hans-Christoph Steiner <hans@eds.org>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.fdroid.fdroid.work;

import android.app.Instrumentation;
import android.content.Context;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

/**
 * This gathers all the information needed for the Popularity Contest, and
 * submits it using the Clean Insights SDK.  This should <b>never</b> include
 * any Personally Identifing Information (PII) like telephone numbers, IP
 * Addresses, MAC, SSID, IMSI, IMEI, user accounts, etc.
 */
public class PopularityContestWorkerTest {

    private Context context;

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Rule
    public WorkManagerTestRule workManagerTestRule = new WorkManagerTestRule();

    @Before
    public void setUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
    }

    @Test
    public void testWorkRequest() throws ExecutionException, InterruptedException {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PopularityContestWorker.class).build();
        workManagerTestRule.workManager.enqueue(request).getResult();
        ListenableFuture<WorkInfo> workInfo = workManagerTestRule.workManager.getWorkInfoById(request.getId());
        assertEquals(WorkInfo.State.ENQUEUED, workInfo.get().getState());
    }

    /*
    @Test
    public void testGenerateReport() {
        PopularityContestWorker.generateReport(context.getApplicationContext());
    }
  */
}
