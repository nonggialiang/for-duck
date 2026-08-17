/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.gravitino.iceberg.service.entitlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestEntitlementSupport {

  private static final Schema TABLE_SCHEMA =
      new Schema(
          Arrays.asList(
              Types.NestedField.required(1, "id", Types.LongType.get()),
              Types.NestedField.optional(2, "region", Types.StringType.get())));

  private static final IcebergResource RESOURCE = IcebergResource.ofTable("cat", "db", "t1");

  /** alice=row-filter only, bob=write only, dave=both, carol=neither. */
  private static class StubAuthorizer implements IcebergAuthorizer {
    private final AtomicReference<IcebergOperation> lastCheckedOperation =
        new AtomicReference<>();

    @Override
    public boolean checkOperation(
        String userName, IcebergOperation op, IcebergResource resource) {
      lastCheckedOperation.set(op);
      if (op == IcebergOperation.UPDATE_TABLE) {
        return "bob".equals(userName) || "dave".equals(userName);
      }
      return true;
    }

    @Override
    public String getRowFilter(String userName, IcebergResource resource) {
      return ("alice".equals(userName) || "dave".equals(userName)) ? "region = 'US'" : null;
    }

    @Override
    public CredentialPrivilege checkCredential(String userName, IcebergResource resource) {
      return null;
    }

    @Override
    public void registerOwner(
        String catalog, String namespace, String resource, String owner) {}

    @Override
    public void removeOwner(String catalog, String namespace, String resource) {}
  }

  private StubAuthorizer stubAuthorizer;

  @BeforeEach
  public void setUp() {
    stubAuthorizer = new StubAuthorizer();
    ServerContext.reset();
    ServerContext.initialize(stubAuthorizer, null, "cat");
  }

  @AfterEach
  public void tearDown() {
    ServerContext.reset();
    EntitlementSupport.configureDialect("spark");
  }

  @Test
  public void testResolveModeFourQuadrants() {
    // no filter, no write -> NORMAL
    assertEquals(EntitlementSupport.Mode.NORMAL, EntitlementSupport.resolveMode("carol", RESOURCE));
    // no filter, write -> NORMAL
    assertEquals(EntitlementSupport.Mode.NORMAL, EntitlementSupport.resolveMode("bob", RESOURCE));
    // filter, no write -> ENTITLED
    assertEquals(
        EntitlementSupport.Mode.ENTITLED, EntitlementSupport.resolveMode("alice", RESOURCE));
    // filter + write -> CONFLICT
    assertEquals(
        EntitlementSupport.Mode.CONFLICT, EntitlementSupport.resolveMode("dave", RESOURCE));
    // write signal must be probed with UPDATE_TABLE
    assertEquals(IcebergOperation.UPDATE_TABLE, stubAuthorizer.lastCheckedOperation.get());
  }

  @Test
  public void testResolveModeNormalWhenServerContextUnset() {
    ServerContext.reset();
    assertEquals(EntitlementSupport.Mode.NORMAL, EntitlementSupport.resolveMode("alice", RESOURCE));
    assertNull(EntitlementSupport.getRowFilter("alice", RESOURCE));
  }

  @Test
  public void testGetRowFilter() {
    assertEquals("region = 'US'", EntitlementSupport.getRowFilter("alice", RESOURCE));
    assertNull(EntitlementSupport.getRowFilter("carol", RESOURCE));
  }

  @Test
  public void testHasSuffixAndStripSuffix() {
    assertTrue(EntitlementSupport.hasSuffix("t1@entitlement"));
    assertTrue(EntitlementSupport.hasSuffix("t1%40entitlement"));
    assertTrue(EntitlementSupport.hasSuffix("T1@ENTITLEMENT"));
    assertTrue(EntitlementSupport.hasSuffix("t1%40Entitlement"));
    assertFalse(EntitlementSupport.hasSuffix("t1"));
    assertFalse(EntitlementSupport.hasSuffix("entitlement"));
    assertFalse(EntitlementSupport.hasSuffix(""));
    assertFalse(EntitlementSupport.hasSuffix(null));

    assertEquals("t1", EntitlementSupport.stripSuffix("t1@entitlement"));
    assertEquals("t1", EntitlementSupport.stripSuffix("t1%40entitlement"));
    assertEquals("T1", EntitlementSupport.stripSuffix("T1@ENTITLEMENT"));
    assertEquals("t1", EntitlementSupport.stripSuffix("t1"));
    // percent-encoding is preserved by design: the caller decodes afterwards
    assertEquals("my%20table", EntitlementSupport.stripSuffix("my%20table@entitlement"));
    assertEquals("@entitlement", EntitlementSupport.stripSuffix("@entitlement@entitlement"));
  }

  @Test
  public void testMatchedSuffixPreservesRawForm() {
    assertEquals("@entitlement", EntitlementSupport.matchedSuffix("t1@entitlement"));
    assertEquals("%40entitlement", EntitlementSupport.matchedSuffix("t1%40entitlement"));
    assertEquals("@ENTITLEMENT", EntitlementSupport.matchedSuffix("t1@ENTITLEMENT"));
    assertNull(EntitlementSupport.matchedSuffix("t1"));
  }

  @Test
  public void testSparkDialectSql() {
    SparkEntitlementDialect dialect = new SparkEntitlementDialect();
    assertEquals("spark", dialect.name());
    assertEquals(
        "SELECT * FROM `cat`.`db`.`t1@entitlement` WHERE region = 'US'",
        dialect.buildSelectSql("cat", "db", "t1@entitlement", "region = 'US'"));
    // embedded backticks are escaped by doubling
    assertEquals(
        "SELECT * FROM `cat`.`d``b`.`t1@entitlement` WHERE true",
        dialect.buildSelectSql("cat", "d`b", "t1@entitlement", "true"));
  }

  @Test
  public void testConfigureDialect() {
    EntitlementSupport.configureDialect("spark");
    assertEquals("spark", EntitlementSupport.getDialect().name());
    EntitlementSupport.configureDialect("SPARK");
    assertEquals("spark", EntitlementSupport.getDialect().name());
    assertThrows(
        IllegalArgumentException.class, () -> EntitlementSupport.configureDialect("trino"));
    assertThrows(IllegalArgumentException.class, () -> EntitlementSupport.configureDialect(null));
  }

  @Test
  public void testBuildLoadViewResponse() {
    TableMetadata tableMetadata =
        TableMetadata.newTableMetadata(
            TABLE_SCHEMA, PartitionSpec.unpartitioned(), "s3://bucket/warehouse/db/t1",
            ImmutableMap.of());
    LoadViewResponse response =
        EntitlementSupport.buildLoadViewResponse(
            "cat", "db", "t1", tableMetadata, "region = 'US'", "alice");

    SQLViewRepresentation sql =
        (SQLViewRepresentation)
            response.metadata().currentVersion().representations().get(0);
    assertEquals("spark", sql.dialect());
    assertEquals(
        "SELECT * FROM `cat`.`db`.`t1@entitlement` WHERE region = 'US'", sql.sql());
    assertEquals("s3://bucket/warehouse/db/t1-entitlement-view", response.metadata().location());
    assertEquals(
        "true",
        response.metadata().properties().get(EntitlementSupport.ENTITLEMENT_VIEW_PROPERTY));
    assertEquals(1, response.metadata().currentVersion().versionId());
    assertEquals("cat", response.metadata().currentVersion().defaultCatalog());
    assertEquals(
        org.apache.iceberg.catalog.Namespace.of("db"),
        response.metadata().currentVersion().defaultNamespace());
    // schema of the view is the schema of the underlying table
    assertEquals(tableMetadata.currentSchemaId(), response.metadata().currentSchemaId());
    assertEquals(tableMetadata.schema().asStruct(), response.metadata().schema().asStruct());
  }

  @Test
  public void testViewUuidIsDeterministic() {
    TableMetadata tableMetadata =
        TableMetadata.newTableMetadata(
            TABLE_SCHEMA, PartitionSpec.unpartitioned(), "s3://bucket/warehouse/db/t1",
            ImmutableMap.of());
    LoadViewResponse first =
        EntitlementSupport.buildLoadViewResponse(
            "cat", "db", "t1", tableMetadata, "region = 'US'", "alice");
    LoadViewResponse second =
        EntitlementSupport.buildLoadViewResponse(
            "cat", "db", "t1", tableMetadata, "region = 'US'", "alice");
    LoadViewResponse otherUser =
        EntitlementSupport.buildLoadViewResponse(
            "cat", "db", "t1", tableMetadata, "region = 'US'", "carol");

    assertEquals(first.metadata().uuid(), second.metadata().uuid());
    assertNotEquals(first.metadata().uuid(), otherUser.metadata().uuid());
  }

  @Test
  public void testConflictMessage() {
    assertEquals(
        "Entitlement row filter conflicts with the write privilege of user 'dave' on table "
            + "'cat.db.t1'; ask an administrator to remove one of them",
        EntitlementSupport.conflictMessage("dave", RESOURCE));
  }
}
