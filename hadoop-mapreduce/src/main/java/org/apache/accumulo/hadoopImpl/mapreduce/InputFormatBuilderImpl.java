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
package org.apache.accumulo.hadoopImpl.mapreduce;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.sample.SamplerConfiguration;
import org.apache.accumulo.core.clientImpl.ClientInfo;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.hadoop.mapreduce.InputFormatBuilder;
import org.apache.accumulo.hadoopImpl.mapreduce.lib.InputConfigurator;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.Job;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class InputFormatBuilderImpl<T>
    implements InputFormatBuilder, InputFormatBuilder.ClientParams<T>,
    InputFormatBuilder.TableParams<T>, InputFormatBuilder.InputFormatOptions<T> {

  Class<?> callingClass;
  String tableName;
  ClientInfo clientInfo;
  Authorizations scanAuths;

  Optional<String> context = Optional.empty();
  Collection<Range> ranges = Collections.emptyList();
  Collection<IteratorSetting.Column> fetchColumns = Collections.emptyList();
  Map<String,IteratorSetting> iterators = Collections.emptyMap();
  Optional<SamplerConfiguration> samplerConfig = Optional.empty();
  Map<String,String> hints = Collections.emptyMap();
  BuilderBooleans bools = new BuilderBooleans();

  public InputFormatBuilderImpl(Class<?> callingClass) {
    this.callingClass = callingClass;
  }

  @Override
  public InputFormatBuilder.TableParams<T> clientProperties(Properties clientProperties) {
    this.clientInfo = ClientInfo
        .from(Objects.requireNonNull(clientProperties, "clientProperties must not be null"));
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> table(String tableName) {
    this.tableName = Objects.requireNonNull(tableName, "Table name must not be null");
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> auths(Authorizations auths) {
    this.scanAuths = Objects.requireNonNull(auths, "Authorizations must not be null");
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> classLoaderContext(String context) {
    this.context = Optional.of(context);
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> ranges(Collection<Range> ranges) {
    this.ranges = ImmutableList
        .copyOf(Objects.requireNonNull(ranges, "Collection of ranges is null"));
    if (this.ranges.size() == 0)
      throw new IllegalArgumentException("Specified collection of ranges is empty.");
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> fetchColumns(
      Collection<IteratorSetting.Column> fetchColumns) {
    this.fetchColumns = ImmutableList
        .copyOf(Objects.requireNonNull(fetchColumns, "Collection of fetch columns is null"));
    if (this.fetchColumns.size() == 0)
      throw new IllegalArgumentException("Specified collection of fetch columns is empty.");
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> addIterator(IteratorSetting cfg) {
    // store iterators by name to prevent duplicates
    Objects.requireNonNull(cfg, "IteratorSetting must not be null.");
    if (this.iterators.size() == 0)
      this.iterators = new LinkedHashMap<>();
    this.iterators.put(cfg.getName(), cfg);
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> executionHints(Map<String,String> hints) {
    this.hints = ImmutableMap
        .copyOf(Objects.requireNonNull(hints, "Map of execution hints must not be null."));
    if (hints.size() == 0)
      throw new IllegalArgumentException("Specified map of execution hints is empty.");
    return this;
  }

  @Override
  public InputFormatBuilder.InputFormatOptions<T> samplerConfiguration(
      SamplerConfiguration samplerConfig) {
    this.samplerConfig = Optional.of(samplerConfig);
    return this;
  }

  @Override
  public InputFormatOptions<T> autoAdjustRanges(boolean value) {
    bools.autoAdjustRanges = value;
    return this;
  }

  @Override
  public InputFormatOptions<T> scanIsolation(boolean value) {
    bools.scanIsolation = value;
    return this;
  }

  @Override
  public InputFormatOptions<T> localIterators(boolean value) {
    bools.localIters = value;
    return this;
  }

  @Override
  public InputFormatOptions<T> offlineScan(boolean value) {
    bools.offlineScan = value;
    return this;
  }

  @Override
  public InputFormatOptions<T> batchScan(boolean value) {
    bools.batchScan = value;
    if (value)
      bools.autoAdjustRanges = true;
    return this;
  }

  @Override
  public void store(T j) throws AccumuloException, AccumuloSecurityException {
    if (j instanceof Job) {
      store((Job) j);
    } else if (j instanceof JobConf) {
      store((JobConf) j);
    } else {
      throw new IllegalArgumentException("Unexpected type " + j.getClass().getName());
    }
  }

  /**
   * Final builder method for mapreduce configuration
   */
  private void store(Job job) throws AccumuloException, AccumuloSecurityException {
    AbstractInputFormat.setClientInfo(job, clientInfo);
    InputFormatBase.setInputTableName(job, tableName);

    scanAuths = getUserAuths(scanAuths, clientInfo);
    AbstractInputFormat.setScanAuthorizations(job, scanAuths);

    // all optional values
    if (context.isPresent())
      AbstractInputFormat.setClassLoaderContext(job, context.get());
    if (ranges.size() > 0)
      InputFormatBase.setRanges(job, ranges);
    if (iterators.size() > 0)
      InputConfigurator.writeIteratorsToConf(callingClass, job.getConfiguration(),
          iterators.values());
    if (fetchColumns.size() > 0)
      InputConfigurator.fetchColumns(callingClass, job.getConfiguration(), fetchColumns);
    if (samplerConfig.isPresent())
      InputFormatBase.setSamplerConfiguration(job, samplerConfig.get());
    if (hints.size() > 0)
      InputFormatBase.setExecutionHints(job, hints);
    InputFormatBase.setAutoAdjustRanges(job, bools.autoAdjustRanges);
    InputFormatBase.setScanIsolation(job, bools.scanIsolation);
    InputFormatBase.setLocalIterators(job, bools.localIters);
    InputFormatBase.setOfflineTableScan(job, bools.offlineScan);
    InputFormatBase.setBatchScan(job, bools.batchScan);
  }

  /**
   * Final builder method for legacy mapred configuration
   */
  private void store(JobConf jobConf) throws AccumuloException, AccumuloSecurityException {
    org.apache.accumulo.hadoopImpl.mapred.AbstractInputFormat.setClientInfo(jobConf, clientInfo);
    org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setInputTableName(jobConf, tableName);

    scanAuths = getUserAuths(scanAuths, clientInfo);
    org.apache.accumulo.hadoopImpl.mapred.AbstractInputFormat.setScanAuthorizations(jobConf,
        scanAuths);

    // all optional values
    if (context.isPresent())
      org.apache.accumulo.hadoopImpl.mapred.AbstractInputFormat.setClassLoaderContext(jobConf,
          context.get());
    if (ranges.size() > 0)
      org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setRanges(jobConf, ranges);
    if (iterators.size() > 0)
      InputConfigurator.writeIteratorsToConf(callingClass, jobConf, iterators.values());
    if (fetchColumns.size() > 0)
      InputConfigurator.fetchColumns(callingClass, jobConf, fetchColumns);
    if (samplerConfig.isPresent())
      org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setSamplerConfiguration(jobConf,
          samplerConfig.get());
    if (hints.size() > 0)
      org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setExecutionHints(jobConf, hints);
    org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setAutoAdjustRanges(jobConf,
        bools.autoAdjustRanges);
    org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setScanIsolation(jobConf,
        bools.scanIsolation);
    org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setLocalIterators(jobConf,
        bools.localIters);
    org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setOfflineTableScan(jobConf,
        bools.offlineScan);
    org.apache.accumulo.hadoopImpl.mapred.InputFormatBase.setBatchScan(jobConf, bools.batchScan);
  }

  private Authorizations getUserAuths(Authorizations scanAuths, ClientInfo clientInfo)
      throws AccumuloSecurityException, AccumuloException {
    if (scanAuths != null)
      return scanAuths;
    try (AccumuloClient c = Accumulo.newClient().from(clientInfo.getProperties()).build()) {
      return c.securityOperations().getUserAuthorizations(clientInfo.getPrincipal());
    }
  }

  private static class BuilderBooleans {
    boolean autoAdjustRanges = true;
    boolean scanIsolation = false;
    boolean offlineScan = false;
    boolean localIters = false;
    boolean batchScan = false;
  }
}
