/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.test.functional;

import static org.apache.accumulo.fate.util.UtilWaitThread.sleepUninterruptibly;
import static org.junit.Assert.fail;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.cli.BatchWriterOpts;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.clientImpl.Credentials;
import org.apache.accumulo.core.clientImpl.MasterClient;
import org.apache.accumulo.core.clientImpl.thrift.ThriftNotActiveServiceException;
import org.apache.accumulo.core.conf.ConfigurationTypeHelper;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.master.thrift.MasterClientService;
import org.apache.accumulo.core.master.thrift.MasterMonitorInfo;
import org.apache.accumulo.core.master.thrift.TableInfo;
import org.apache.accumulo.core.master.thrift.TabletServerStatus;
import org.apache.accumulo.core.trace.Tracer;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.accumulo.miniclusterImpl.MiniAccumuloConfigImpl;
import org.apache.accumulo.test.TestIngest;
import org.apache.hadoop.conf.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DynamicThreadPoolsIT extends AccumuloClusterHarness {

  @Override
  public void configureMiniCluster(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.setNumTservers(1);
    Map<String,String> siteConfig = cfg.getSiteConfig();
    siteConfig.put(Property.TSERV_MAJC_DELAY.getKey(), "100ms");
    cfg.setSiteConfig(siteConfig);
  }

  @Override
  protected int defaultTimeoutSeconds() {
    return 4 * 60;
  }

  private String majcDelay;

  @Before
  public void updateMajcDelay() throws Exception {
    try (AccumuloClient c = createAccumuloClient()) {
      majcDelay = c.instanceOperations().getSystemConfiguration()
          .get(Property.TSERV_MAJC_DELAY.getKey());
      c.instanceOperations().setProperty(Property.TSERV_MAJC_DELAY.getKey(), "100ms");
      if (getClusterType() == ClusterType.STANDALONE) {
        Thread.sleep(ConfigurationTypeHelper.getTimeInMillis(majcDelay));
      }
    }
  }

  @After
  public void resetMajcDelay() throws Exception {
    try (AccumuloClient c = createAccumuloClient()) {
      c.instanceOperations().setProperty(Property.TSERV_MAJC_DELAY.getKey(), majcDelay);
    }
  }

  @Test
  public void test() throws Exception {
    final String[] tables = getUniqueNames(15);
    String firstTable = tables[0];
    try (AccumuloClient c = createAccumuloClient()) {
      c.instanceOperations().setProperty(Property.TSERV_MAJC_MAXCONCURRENT.getKey(), "5");
      TestIngest.Opts opts = new TestIngest.Opts();
      opts.rows = 500 * 1000;
      opts.createTable = true;
      opts.setTableName(firstTable);
      opts.setClientProperties(getClientProperties());
      TestIngest.ingest(c, opts, new BatchWriterOpts());
      c.tableOperations().flush(firstTable, null, null, true);
      for (int i = 1; i < tables.length; i++)
        c.tableOperations().clone(firstTable, tables[i], true, null, null);
      sleepUninterruptibly(11, TimeUnit.SECONDS); // time between checks of the thread pool sizes
      Credentials creds = new Credentials(getAdminPrincipal(), getAdminToken());
      for (int i = 1; i < tables.length; i++)
        c.tableOperations().compact(tables[i], null, null, true, false);
      for (int i = 0; i < 30; i++) {
        int count = 0;
        MasterClientService.Iface client = null;
        MasterMonitorInfo stats = null;
        while (true) {
          try {
            client = MasterClient.getConnectionWithRetry(getClientContext());
            stats = client.getMasterStats(Tracer.traceInfo(), creds.toThrift(c.getInstanceID()));
            break;
          } catch (ThriftNotActiveServiceException e) {
            // Let it loop, fetching a new location
            sleepUninterruptibly(100, TimeUnit.MILLISECONDS);
          } finally {
            if (client != null)
              MasterClient.close(client);
          }
        }
        for (TabletServerStatus server : stats.tServerInfo) {
          for (TableInfo table : server.tableMap.values()) {
            count += table.majors.running;
          }
        }
        System.out.println("count " + count);
        if (count > 3)
          return;
        sleepUninterruptibly(500, TimeUnit.MILLISECONDS);
      }
      fail("Could not observe higher number of threads after changing the config");
    }
  }
}
