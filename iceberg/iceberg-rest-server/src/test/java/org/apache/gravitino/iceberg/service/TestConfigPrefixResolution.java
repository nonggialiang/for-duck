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

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.junit.jupiter.api.Test;

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
    Map<String, String> props = new HashMap<>();
    props.put(IcebergConfig.ICEBERG_CONFIG_PREFIX + "catalog-backend", "jdbc");

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
  }

  @Test
  void testDefaultCatalogBackendWhenNoConfigSet() {
    IcebergConfig config = new IcebergConfig(new HashMap<>());
    assertEquals("memory", config.get(IcebergConfig.CATALOG_BACKEND));
  }
}
