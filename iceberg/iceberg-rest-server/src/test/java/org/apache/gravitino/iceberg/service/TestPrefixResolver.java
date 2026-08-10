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

import org.apache.gravitino.iceberg.service.authorization.allowall.AllowAllAuthorizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestPrefixResolver {

  private static final String DEFAULT_CATALOG = "default_catalog";

  @BeforeAll
  static void init() {
    ServerContext.reset();
    ServerContext.initialize(new AllowAllAuthorizer(), null, DEFAULT_CATALOG);
  }

  @AfterAll
  static void tearDown() {
    ServerContext.reset();
  }

  @Test
  void testGetCatalogNameFromPrefix() {
    // In Spring MVC, the prefix path variable does NOT include a trailing slash
    assertEquals("my_catalog", PrefixResolver.getCatalogName("my_catalog"));
  }

  @Test
  void testGetCatalogNameFromEmptyPrefix() {
    assertEquals(DEFAULT_CATALOG, PrefixResolver.getCatalogName(""));
  }

  @Test
  void testGetCatalogNameFromNullPrefix() {
    assertEquals(DEFAULT_CATALOG, PrefixResolver.getCatalogName(null));
  }

  @Test
  void testGetCatalogNameFromBlankPrefix() {
    assertEquals(DEFAULT_CATALOG, PrefixResolver.getCatalogName("   "));
  }
}
