/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.gravitino.iceberg.service.spring.IcebergBeanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

public class IcebergRESTServer {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergRESTServer.class);

  public static final String CONF_FILE = "gravitino-iceberg-rest-server.conf";

  /**
   * Loads the configuration file from the given path and applies all entries as system properties
   * so that Spring Boot can pick them up.
   */
  private static void loadPropertiesAsSystemProperties(String confPath) {
    File confFile = new File(confPath, CONF_FILE);
    if (confFile.exists()) {
      try (InputStream input = new FileInputStream(confFile)) {
        Properties props = new Properties();
        props.load(input);
        props.forEach(
            (k, v) -> System.setProperty(String.valueOf(k), String.valueOf(v)));
        LOG.info("Loaded configuration from {}", confFile.getAbsolutePath());
      } catch (IOException e) {
        LOG.error("Failed to load configuration from {}", confFile.getAbsolutePath(), e);
        throw new RuntimeException("Failed to load configuration", e);
      }
    } else {
      LOG.warn("Configuration file not found: {}, using defaults", confFile.getAbsolutePath());
    }
  }

  public static void main(String[] args) {
    LOG.info("Starting Iceberg REST Server");
    String confPath =
        System.getenv("GRAVITINO_HOME") != null
            ? System.getenv("GRAVITINO_HOME") + "/conf"
            : ".";
    if (args.length > 0) {
      confPath = args[0];
    }

    loadPropertiesAsSystemProperties(confPath);

    SpringApplication.run(IcebergBeanConfig.class, args);
  }
}
