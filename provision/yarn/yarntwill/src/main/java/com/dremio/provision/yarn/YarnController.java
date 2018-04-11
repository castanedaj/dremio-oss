/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.provision.yarn;

import static com.dremio.provision.yarn.DacDaemonYarnApplication.KEYTAB_FILE_NAME;
import static com.dremio.provision.yarn.DacDaemonYarnApplication.MAX_APP_RESTART_RETRIES;
import static com.dremio.provision.yarn.DacDaemonYarnApplication.YARN_BUNDLED_JAR_NAME;
import static com.dremio.provision.yarn.DacDaemonYarnApplication.YARN_CLUSTER_ID;
import static com.dremio.provision.yarn.DacDaemonYarnApplication.YARN_CPU;
import static com.dremio.provision.yarn.DacDaemonYarnApplication.YARN_MEMORY_OFF_HEAP;
import static com.dremio.provision.yarn.DacDaemonYarnApplication.YARN_RUNNABLE_NAME;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.twill.api.ClassAcceptor;
import org.apache.twill.api.TwillController;
import org.apache.twill.api.TwillPreparer;
import org.apache.twill.api.TwillRunnerService;
import org.apache.twill.api.logging.LogEntry;
import org.apache.twill.ext.BundledJarRunner;
import org.apache.twill.yarn.YarnTwillRunnerService;
import org.slf4j.Logger;

import com.dremio.config.DremioConfig;
import com.dremio.provision.ClusterId;
import com.dremio.provision.Property;
import com.dremio.provision.PropertyType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;


/**
 * Class that allows to control Dremio YARN deployment
 * It is a singleton to start only single controller per process and only if needed
 */
public class YarnController {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(YarnController.class);

  @VisibleForTesting
  final ConcurrentMap<ClusterId,TwillRunnerService> twillRunners = Maps.newConcurrentMap();

  @VisibleForTesting
  final DremioConfig dremioConfig;

  public YarnController() {
    dremioConfig = DremioConfig.create();
  }

  public TwillRunnerService getTwillService(ClusterId key) {
    return twillRunners.get(key);
  }

  public void invalidateTwillService(final ClusterId key) {
    TwillRunnerService service = twillRunners.remove(key);
    if (service != null) {
      service.stop();
    }
  }

  public TwillRunnerService startTwillRunner(YarnConfiguration yarnConfiguration) {
    String zkStr = dremioConfig.getString(DremioConfig.ZOOKEEPER_QUORUM);
    String clusterId = yarnConfiguration.get(YARN_CLUSTER_ID);
    Preconditions.checkNotNull(clusterId, "Cluster ID can not be null");
    TwillRunnerService twillRunner = new YarnTwillRunnerService(yarnConfiguration, zkStr);
    TwillRunnerService previousOne = twillRunners.putIfAbsent(new ClusterId(clusterId), twillRunner);
    if (previousOne == null) {
      // start one we are planning to add - if it is already in collection it should be started
      twillRunner.start();
      return twillRunner;
    }
    return previousOne;
  }

  public TwillController startCluster(YarnConfiguration yarnConfiguration, List<Property> propertyList) {
    TwillController tmpController = createPreparer(yarnConfiguration, propertyList).start();
    return tmpController;
  }

  protected TwillPreparer createPreparer(YarnConfiguration yarnConfiguration, List<Property> propertyList) {
    BundledJarRunner.Arguments discoveryArgs = new BundledJarRunner.Arguments.Builder()
      .setJarFileName(YARN_BUNDLED_JAR_NAME)
      .setLibFolder("/lib")
      .setMainClassName("com.dremio.dac.daemon.DremioDaemon")
      .setMainArgs(new String[] {})
      .createArguments();

    DacDaemonYarnApplication dacDaemonApp = new DacDaemonYarnApplication(dremioConfig, yarnConfiguration,
      new DacDaemonYarnApplication.Environment());
    File jarFile = new File(dacDaemonApp.getYarnBundledJarName());

    Preconditions.checkState(jarFile.exists());
    Preconditions.checkState(jarFile.canRead());

    TwillRunnerService twillRunner = startTwillRunner(yarnConfiguration);

    Map<String, String> envVars = Maps.newHashMap();
    envVars.put("MALLOC_ARENA_MAX", "4");
    envVars.put("MALLOC_MMAP_THRESHOLD_", "131072");
    envVars.put("MALLOC_TRIM_THRESHOLD_", "131072");
    envVars.put("MALLOC_TOP_PAD_", "131072");
    envVars.put("MALLOC_MMAP_MAX_", "65536");

    // maprfs specific env vars to enable read ahead throttling
    envVars.put("MAPR_IMPALA_RA_THROTTLE", Optional.fromNullable(System.getenv("MAPR_IMPALA_RA_THROTTLE")).or("true"));
    envVars.put("MAPR_MAX_RA_STREAMS", Optional.fromNullable(System.getenv("MAPR_MAX_RA_STREAMS")).or("200"));

    try {
      String userName = UserGroupInformation.getCurrentUser().getUserName();
      envVars.put("HADOOP_USER_NAME", userName);
    } catch (IOException e) {
      logger.error("Exception while trying to fill out HADOOP_USER_NAME with current user", e);
    }

    for (Property prop : propertyList) {
      // add if it is env var
      if (PropertyType.ENV_VAR.equals(prop.getType())) {
        envVars.put(prop.getKey(), prop.getValue());
      }
    }
    String[] yarnClasspath = yarnConfiguration.getStrings(YarnConfiguration.YARN_APPLICATION_CLASSPATH,
      YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH);
    final TwillPreparer preparer = twillRunner.prepare(dacDaemonApp)
      .addLogHandler(new YarnTwillLogHandler())
      .withApplicationClassPaths(yarnClasspath)
      .withBundlerClassAcceptor(new HadoopClassExcluder())
      .setLogLevels(ImmutableMap.of(Logger.ROOT_LOGGER_NAME, yarnContainerLogLevel()))
      .withEnv(envVars)
      .withMaxRetries(YARN_RUNNABLE_NAME, MAX_APP_RESTART_RETRIES)
      .withArguments(YARN_RUNNABLE_NAME, discoveryArgs.toArray());

    for (String classpathJar : dacDaemonApp.getJarNames()) {
      preparer.withClassPaths(classpathJar);
    }

    preparer.addJVMOptions(prepareCommandOptions(yarnConfiguration, propertyList));

    String queue = yarnConfiguration.get(DacDaemonYarnApplication.YARN_QUEUE_NAME);
    if (queue != null) {
      preparer.setSchedulerQueue(queue);
    }

    if (dremioConfig.getBoolean(DremioConfig.DEBUG_YARN_ENABLED)) {
      preparer.enableDebugging(true, YARN_RUNNABLE_NAME);
    }

    return preparer;
  }

  @VisibleForTesting
  protected String prepareCommandOptions(YarnConfiguration yarnConfiguration, List<Property> propertyList) {

    String directMemory = yarnConfiguration.get(YARN_MEMORY_OFF_HEAP);

    final String jvmOptions = yarnConfiguration.get(DremioConfig.YARN_JVM_OPTIONS,
      dremioConfig.getString(DremioConfig.YARN_JVM_OPTIONS));

    String zkStr = dremioConfig.getString(DremioConfig.ZOOKEEPER_QUORUM);
    int defaultZkPort = dremioConfig.getInt(DremioConfig.EMBEDDED_MASTER_ZK_ENABLED_PORT_INT);

    if (dremioConfig.getBoolean(DremioConfig.EMBEDDED_MASTER_ZK_ENABLED_BOOL) &&
        zkStr.equals(String.format("localhost:%d", defaultZkPort))) {
      zkStr = String.format("%s:%d", dremioConfig.getThisNode(), defaultZkPort);
    }

    final Map<String,String> basicJVMOptions = Maps.newHashMap();
    final Map<String, String> systemOptions = Maps.newHashMap();

    basicJVMOptions.put(DremioConfig.ZOOKEEPER_QUORUM, zkStr);
    basicJVMOptions.put(DremioConfig.LOCAL_WRITE_PATH_STRING, yarnConfiguration.get(DremioConfig.LOCAL_WRITE_PATH_STRING,
      dremioConfig.getString(DremioConfig.LOCAL_WRITE_PATH_STRING)));
    basicJVMOptions.put(DremioConfig.DIST_WRITE_PATH_STRING, yarnConfiguration.get(DremioConfig.DIST_WRITE_PATH_STRING,
      dremioConfig.getString(DremioConfig.DIST_WRITE_PATH_STRING)));
    basicJVMOptions.put("dremio.classpath.scanning.cache.enabled", "false");
    basicJVMOptions.put("logback.configurationFile", "logback.xml");
    basicJVMOptions.put(DremioConfig.DEBUG_AUTOPORT_BOOL, "true");
    basicJVMOptions.put("services.coordinator.enabled", "false");
    basicJVMOptions.put(DremioConfig.EXECUTOR_CPU, yarnConfiguration.get(YARN_CPU));

    final String kerberosPrincipal = dremioConfig.getString(DremioConfig.KERBEROS_PRINCIPAL);
    if (!Strings.isNullOrEmpty(kerberosPrincipal)) {
      basicJVMOptions.put(DremioConfig.KERBEROS_PRINCIPAL, kerberosPrincipal);
    }
    if (!Strings.isNullOrEmpty(dremioConfig.getString(DremioConfig.KERBEROS_KEYTAB_PATH))) {
      basicJVMOptions.put(DremioConfig.KERBEROS_KEYTAB_PATH, KEYTAB_FILE_NAME);
    }

    systemOptions.put("-XX:MaxDirectMemorySize", directMemory + "m");

    for (Property prop : propertyList) {
      // don't add if it is env var
      if (PropertyType.ENV_VAR.equals(prop.getType())) {
        continue;
      }
      // if prop type is null (old property) and it starts with -X - it is a system prop
      // if prop type is SYSTEM_PROP - it is a system prop
      if ((prop.getType() == null && prop.getKey().startsWith("-X"))
        || PropertyType.SYSTEM_PROP.equals(prop.getType())) {
        systemOptions.put(prop.getKey(), prop.getValue());
      } else {
        basicJVMOptions.put(prop.getKey(), prop.getValue());
      }
    }

    StringBuilder basicJVMOptionsB = new StringBuilder();
    for (Map.Entry<String,String> entry : basicJVMOptions.entrySet()) {
      basicJVMOptionsB.append(" -D");
      basicJVMOptionsB.append(entry.getKey());
      if (!entry.getValue().isEmpty()) {
        basicJVMOptionsB.append("=");
        basicJVMOptionsB.append(entry.getValue());
      }
    }

    for (Map.Entry<String,String> entry : systemOptions.entrySet()) {
      basicJVMOptionsB.append(" ");
      basicJVMOptionsB.append(StringEscapeUtils.escapeJava(entry.getKey()));
      if (!entry.getValue().isEmpty()) {
        basicJVMOptionsB.append("=");
        basicJVMOptionsB.append(StringEscapeUtils.escapeJava(entry.getValue()));
      }
    }
    basicJVMOptionsB.append(" ");
    basicJVMOptionsB.append(jvmOptions);

    return basicJVMOptionsB.toString();
  }


  static class HadoopClassExcluder extends ClassAcceptor {
    @Override
    public boolean accept(String className, URL classUrl, URL classPathUrl) {
      // exclude hadoop and log4j
      if (className.startsWith("org.apache.log4j")) {
        return false;
      }
      if (className.startsWith("org.slf4j.impl")) {
        return false;
      }
      if (className.startsWith("org.apache.hadoop")) {
        return false;
      }
      return true;
    }
  }

  private LogEntry.Level yarnContainerLogLevel() {
      if (logger.isTraceEnabled()) {
        return LogEntry.Level.TRACE;
      }
      if (logger.isDebugEnabled()) {
        return LogEntry.Level.DEBUG;
      }
      if (logger.isInfoEnabled()) {
        return LogEntry.Level.INFO;
      }
      if (logger.isWarnEnabled()) {
        return LogEntry.Level.WARN;
      }
      if (logger.isErrorEnabled()) {
        return LogEntry.Level.ERROR;
      }
      return LogEntry.Level.INFO;
    }
}

