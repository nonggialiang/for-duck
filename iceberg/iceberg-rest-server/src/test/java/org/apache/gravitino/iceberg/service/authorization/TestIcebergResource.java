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
package org.apache.gravitino.iceberg.service.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.gravitino.iceberg.service.authorization.IcebergResource.ResourceType;
import org.junit.jupiter.api.Test;

class TestIcebergResource {

  @Test
  void testOfCatalog() {
    IcebergResource resource = IcebergResource.ofCatalog("my_catalog");
    assertEquals(ResourceType.CATALOG, resource.getType());
    assertEquals("my_catalog", resource.getCatalogName());
    assertNull(resource.getSchemaName());
    assertNull(resource.getResourceName());
    assertEquals("my_catalog", resource.toPath());
    assertEquals("CATALOG:my_catalog", resource.toString());
  }

  @Test
  void testOfSchema() {
    IcebergResource resource = IcebergResource.ofSchema("cat", "schema1");
    assertEquals(ResourceType.SCHEMA, resource.getType());
    assertEquals("cat", resource.getCatalogName());
    assertEquals("schema1", resource.getSchemaName());
    assertNull(resource.getResourceName());
    assertEquals("cat.schema1", resource.toPath());
    assertEquals("SCHEMA:cat.schema1", resource.toString());
  }

  @Test
  void testOfTable() {
    IcebergResource resource = IcebergResource.ofTable("cat", "schema1", "tbl");
    assertEquals(ResourceType.TABLE, resource.getType());
    assertEquals("cat", resource.getCatalogName());
    assertEquals("schema1", resource.getSchemaName());
    assertEquals("tbl", resource.getResourceName());
    assertEquals("cat.schema1.tbl", resource.toPath());
    assertEquals("TABLE:cat.schema1.tbl", resource.toString());
  }

  @Test
  void testOfView() {
    IcebergResource resource = IcebergResource.ofView("cat", "schema1", "vw");
    assertEquals(ResourceType.VIEW, resource.getType());
    assertEquals("cat", resource.getCatalogName());
    assertEquals("schema1", resource.getSchemaName());
    assertEquals("vw", resource.getResourceName());
    assertEquals("cat.schema1.vw", resource.toPath());
    assertEquals("VIEW:cat.schema1.vw", resource.toString());
  }
}
