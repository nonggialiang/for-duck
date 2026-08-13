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
package org.apache.gravitino.iceberg.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.StaticIcebergConfigProvider;
import org.apache.gravitino.iceberg.service.spring.IcebergBeanConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies that config prefix stripping works correctly: properties using the {@code
 * gravitino.iceberg-rest.} prefix should be readable after the prefix is removed.
 */
class TestConfigPrefixResolution {

  @Test
  void testIcebergConfigReadsFlatCatalogBackend() {
    Map<String, String> props = new HashMap<>();
    props.put("catalog-backend", "jdbc");
    IcebergConfig config = new IcebergConfig(props);
    assertEquals("jdbc", config.get(IcebergConfig.CATALOG_BACKEND));
  }

  @Test
  void testIcebergConfigReadsPrefixedCatalogBackend() {
    // Manual prefix-stripping path (system-property style).
    Map<String, String> props = new HashMap<>();
    props.put(IcebergConfig.ICEBERG_CONFIG_PREFIX + "catalog-backend", "jdbc");

    // Environment-binder path (application.properties style).
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("gravitino.iceberg-rest.catalog.iceberg_catalog.catalog-backend", "jdbc");
    environment.setProperty(
        "gravitino.iceberg-rest.catalog.iceberg_catalog.uri",
        "jdbc:postgresql://localhost/test");
    environment.setProperty("gravitino.iceberg-rest.extension-packages", "a,b,c,d");
    environment.setProperty("server.port", "9001");

    Properties properties = new IcebergBeanConfig().icebergProperties(environment);

    Map<String, String> stripped = new HashMap<>();
    props.forEach(
        (k, v) -> {
          if (k.startsWith(IcebergConfig.ICEBERG_CONFIG_PREFIX)) {
            stripped.put(k.substring(IcebergConfig.ICEBERG_CONFIG_PREFIX.length()), v);
          } else {
            stripped.put(k, v);
          }
        });
    IcebergConfig config = new IcebergConfig(stripped);
    assertEquals("jdbc", config.get(IcebergConfig.CATALOG_BACKEND));

    assertEquals(
        "jdbc:postgresql://localhost/test",
        properties.getProperty("catalog.iceberg_catalog.uri"));
    assertEquals("a,b,c,d", properties.getProperty("extension-packages"));
    assertFalse(properties.containsKey("server.port"));

    IcebergConfigProvider provider = new StaticIcebergConfigProvider();
    Map<String, String> configMap = new HashMap<>();
    properties.forEach((k, v) -> configMap.put(String.valueOf(k), String.valueOf(v)));
    provider.initialize(configMap);
    IcebergConfig catalogConfig = provider.getIcebergCatalogConfig("iceberg_catalog").orElseThrow();
    assertEquals("jdbc", catalogConfig.get(IcebergConfig.CATALOG_BACKEND));
    assertEquals(
        "jdbc:postgresql://localhost/test", catalogConfig.get(IcebergConfig.CATALOG_URI));
  }

  @Test
  void testDefaultCatalogBackendWhenNoConfigSet() {
    IcebergConfig config = new IcebergConfig(new HashMap<>());
    assertEquals("memory", config.get(IcebergConfig.CATALOG_BACKEND));
  }
}
