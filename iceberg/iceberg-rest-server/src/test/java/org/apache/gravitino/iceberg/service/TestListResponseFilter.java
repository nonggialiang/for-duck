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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.junit.jupiter.api.Test;

class TestListResponseFilter {

  @Test
  void testFilterTablesKeepsAuthorizedTables() {
    TableIdentifier t1 = TableIdentifier.of(Namespace.of("db1"), "table1");
    TableIdentifier t2 = TableIdentifier.of(Namespace.of("db1"), "table2");
    ListTablesResponse response =
        ListTablesResponse.builder().add(t1).add(t2).build();

    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(
            anyString(), eq(IcebergOperation.LOAD_TABLE), any()))
        .thenReturn(true, false);

    ListTablesResponse filtered =
        ListResponseFilter.filterTables(response, "cat", authorizer);

    assertEquals(1, filtered.identifiers().size());
    assertEquals("table1", filtered.identifiers().get(0).name());
  }

  @Test
  void testFilterTablesAllAuthorized() {
    TableIdentifier t1 = TableIdentifier.of(Namespace.of("db"), "a");
    TableIdentifier t2 = TableIdentifier.of(Namespace.of("db"), "b");
    ListTablesResponse response =
        ListTablesResponse.builder().add(t1).add(t2).build();

    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(anyString(), any(), any())).thenReturn(true);

    ListTablesResponse filtered =
        ListResponseFilter.filterTables(response, "cat", authorizer);

    assertEquals(2, filtered.identifiers().size());
  }

  @Test
  void testFilterTablesNoneAuthorized() {
    TableIdentifier t1 = TableIdentifier.of(Namespace.of("db"), "a");
    ListTablesResponse response = ListTablesResponse.builder().add(t1).build();

    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(anyString(), any(), any())).thenReturn(false);

    ListTablesResponse filtered =
        ListResponseFilter.filterTables(response, "cat", authorizer);

    assertTrue(filtered.identifiers().isEmpty());
  }

  @Test
  void testFilterViews() {
    TableIdentifier v1 = TableIdentifier.of(Namespace.of("db"), "view1");
    TableIdentifier v2 = TableIdentifier.of(Namespace.of("db"), "view2");
    ListTablesResponse response =
        ListTablesResponse.builder().add(v1).add(v2).build();

    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(
            anyString(), eq(IcebergOperation.LOAD_VIEW), any()))
        .thenReturn(false, true);

    ListTablesResponse filtered =
        ListResponseFilter.filterViews(response, "cat", authorizer);

    assertEquals(1, filtered.identifiers().size());
    assertEquals("view2", filtered.identifiers().get(0).name());
  }

  @Test
  void testFilterNamespaces() {
    Namespace ns1 = Namespace.of("schema1");
    Namespace ns2 = Namespace.of("schema2");
    ListNamespacesResponse response =
        ListNamespacesResponse.builder().add(ns1).add(ns2).build();

    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(
            anyString(), eq(IcebergOperation.LOAD_NAMESPACE), any()))
        .thenReturn(true, false);

    ListNamespacesResponse filtered =
        ListResponseFilter.filterNamespaces(response, "cat", authorizer);

    assertEquals(1, filtered.namespaces().size());
    assertEquals("schema1", filtered.namespaces().get(0).level(0));
  }

  @Test
  void testFilterTablesWithEmptyNamespace() {
    TableIdentifier t = TableIdentifier.of(Namespace.empty(), "rootless_table");
    ListTablesResponse response = ListTablesResponse.builder().add(t).build();

    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(anyString(), any(), any())).thenReturn(true);

    ListTablesResponse filtered =
        ListResponseFilter.filterTables(response, "cat", authorizer);

    assertEquals(1, filtered.identifiers().size());
    assertEquals("rootless_table", filtered.identifiers().get(0).name());
  }
}
