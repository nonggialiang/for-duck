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
package com.sc.ssdr.kyuubi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegatorInjectConfAdvisorTest {
  @Test
  void getConfOverlayBuildsKeyFromSingleCatalog() {
    DelegatorInjectConfAdvisor advisor = new TestDelegatorInjectConfAdvisor("catalog1");
    Map<String, String> overlay = advisor.getConfOverlay("alice", Map.of());
    assertEquals(
        Map.of("spark.sql.catalog.catalog1.header.X-Iceberg-Access-Delegator", "alice"), overlay);
  }

  @Test
  void getConfOverlayBuildsKeysFromCatalogList() {
    DelegatorInjectConfAdvisor advisor = new TestDelegatorInjectConfAdvisor("catalog1,catalog2");
    Map<String, String> overlay = advisor.getConfOverlay("alice", Map.of());
    assertEquals(
        Map.of(
            "spark.sql.catalog.catalog1.header.X-Iceberg-Access-Delegator", "alice",
            "spark.sql.catalog.catalog2.header.X-Iceberg-Access-Delegator", "alice"),
        overlay);
  }

  @Test
  void getConfOverlayReturnsEmptyMapWhenConfigMissing() {
    DelegatorInjectConfAdvisor advisor = new TestDelegatorInjectConfAdvisor(null);
    Map<String, String> overlay = advisor.getConfOverlay("alice", Map.of());
    assertTrue(overlay.isEmpty());
  }

  @Test
  void getConfOverlayReturnsEmptyMapWhenConfigInvalid() {
    DelegatorInjectConfAdvisor advisor = new TestDelegatorInjectConfAdvisor("catalog1,bad.name");
    Map<String, String> overlay = advisor.getConfOverlay("alice", Map.of());
    assertTrue(overlay.isEmpty());
  }

  private static class TestDelegatorInjectConfAdvisor extends DelegatorInjectConfAdvisor {
    private final String catalogs;

    private TestDelegatorInjectConfAdvisor(String catalogs) {
      this.catalogs = catalogs;
    }

    @Override
    String getDelegatorInjectCatalogs() {
      return catalogs;
    }
  }
}
