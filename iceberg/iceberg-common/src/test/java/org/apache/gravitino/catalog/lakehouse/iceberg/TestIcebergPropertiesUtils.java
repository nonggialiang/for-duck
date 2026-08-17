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
package org.apache.gravitino.catalog.lakehouse.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestIcebergPropertiesUtils {

  @Test
  void testToIcebergCatalogPropertiesMapsKnownKeys() {
    Map<String, String> gravitinoProps = new HashMap<>();
    gravitinoProps.put(IcebergConstants.CATALOG_BACKEND, "jdbc");
    gravitinoProps.put(IcebergConstants.GRAVITINO_JDBC_USER, "admin");
    gravitinoProps.put(IcebergConstants.GRAVITINO_JDBC_PSWD, "secret");
    gravitinoProps.put(IcebergConstants.URI, "jdbc:sqlite::memory:");
    gravitinoProps.put(IcebergConstants.WAREHOUSE, "/tmp/warehouse");

    Map<String, String> icebergProps =
        IcebergPropertiesUtils.toIcebergCatalogProperties(gravitinoProps);

    assertEquals("jdbc", icebergProps.get(IcebergConstants.CATALOG_BACKEND));
    assertEquals("admin", icebergProps.get(IcebergConstants.ICEBERG_JDBC_USER));
    assertEquals("secret", icebergProps.get(IcebergConstants.ICEBERG_JDBC_PSWD));
    assertEquals("jdbc:sqlite::memory:", icebergProps.get(IcebergConstants.URI));
    assertEquals("/tmp/warehouse", icebergProps.get(IcebergConstants.WAREHOUSE));
  }

  @Test
  void testToIcebergCatalogPropertiesIgnoresUnknownKeys() {
    Map<String, String> props = new HashMap<>();
    props.put(IcebergConstants.CATALOG_BACKEND, "memory");
    props.put("unknown-key", "value");
    props.put("gravitino.bypass.custom", "custom");

    Map<String, String> result = IcebergPropertiesUtils.toIcebergCatalogProperties(props);

    assertTrue(result.containsKey(IcebergConstants.CATALOG_BACKEND));
    assertFalse(result.containsKey("unknown-key"));
    assertFalse(result.containsKey("gravitino.bypass.custom"));
  }

  @Test
  void testToIcebergCatalogPropertiesEmptyInput() {
    Map<String, String> result =
        IcebergPropertiesUtils.toIcebergCatalogProperties(new HashMap<>());
    assertTrue(result.isEmpty());
  }

  @Test
  void testGetCatalogBackendNameExplicit() {
    Map<String, String> props = new HashMap<>();
    props.put(IcebergConstants.CATALOG_BACKEND_NAME, "my_catalog");
    assertEquals("my_catalog", IcebergPropertiesUtils.getCatalogBackendName(props));
  }

  @Test
  void testGetCatalogBackendNameFromBackendLowercased() {
    Map<String, String> props = new HashMap<>();
    props.put(IcebergConstants.CATALOG_BACKEND, "JDBC");
    assertEquals("jdbc", IcebergPropertiesUtils.getCatalogBackendName(props));
  }

  @Test
  void testGetCatalogBackendNameDefaultsToMemory() {
    Map<String, String> props = new HashMap<>();
    assertEquals("memory", IcebergPropertiesUtils.getCatalogBackendName(props));
  }

  @Test
  void testGetCatalogBackendNameBackendNameTakesPrecedence() {
    Map<String, String> props = new HashMap<>();
    props.put(IcebergConstants.CATALOG_BACKEND_NAME, "explicit_name");
    props.put(IcebergConstants.CATALOG_BACKEND, "memory");
    assertEquals("explicit_name", IcebergPropertiesUtils.getCatalogBackendName(props));
  }

  @Test
  void testGetCatalogBackendNameEmptyProps() {
    assertEquals("memory", IcebergPropertiesUtils.getCatalogBackendName(new HashMap<>()));
  }
}
