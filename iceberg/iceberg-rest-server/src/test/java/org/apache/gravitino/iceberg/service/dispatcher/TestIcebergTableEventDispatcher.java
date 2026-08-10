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
package org.apache.gravitino.iceberg.service.dispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.allowall.AllowAllAuthorizer;
import org.apache.gravitino.iceberg.service.rest.DummyEventListener;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.event.IcebergCreateTableEvent;
import org.apache.gravitino.listener.api.event.IcebergCreateTableFailureEvent;
import org.apache.gravitino.listener.api.event.IcebergCreateTablePreEvent;
import org.apache.gravitino.listener.api.event.IcebergDropTableEvent;
import org.apache.gravitino.listener.api.event.IcebergDropTablePreEvent;
import org.apache.gravitino.listener.api.event.IcebergLoadTableEvent;
import org.apache.gravitino.listener.api.event.IcebergLoadTablePreEvent;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestIcebergTableEventDispatcher {

  private static final String CATALOG = "test_catalog";
  private static final Namespace NAMESPACE = Namespace.of("db1");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "col", Types.StringType.get()));

  private IcebergTableOperationExecutor mockExecutor;
  private DummyEventListener listener;
  private IcebergTableEventDispatcher dispatcher;
  private IcebergRequestContext context;

  private LoadTableResponse createLoadTableResponse() {
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            SCHEMA, PartitionSpec.unpartitioned(), "/tmp/test", new HashMap<>());
    return LoadTableResponse.builder().withTableMetadata(metadata).build();
  }

  @BeforeAll
  static void initServerContext() {
    ServerContext.reset();
    ServerContext.initialize(new AllowAllAuthorizer(), null, CATALOG);
  }

  @AfterAll
  static void tearDown() {
    ServerContext.reset();
  }

  @BeforeEach
  void setUp() {
    mockExecutor = mock(IcebergTableOperationExecutor.class);
    listener = new DummyEventListener();
    EventBus eventBus = new EventBus(List.of(listener));
    dispatcher = new IcebergTableEventDispatcher(mockExecutor, eventBus);
    context = mock(IcebergRequestContext.class);
    when(context.catalogName()).thenReturn(CATALOG);
    when(context.userName()).thenReturn("test_user");
  }

  @Test
  void testCreateTableDispatchesPreAndPostEvents() {
    CreateTableRequest request =
        CreateTableRequest.builder().withName("t1").withSchema(SCHEMA).build();
    LoadTableResponse response = createLoadTableResponse();
    when(mockExecutor.createTable(eq(context), eq(NAMESPACE), any(CreateTableRequest.class)))
        .thenReturn(response);

    LoadTableResponse result = dispatcher.createTable(context, NAMESPACE, request);

    assertEquals(response, result);
    assertTrue(listener.popPreEvent() instanceof IcebergCreateTablePreEvent);
    assertTrue(listener.popPostEvent() instanceof IcebergCreateTableEvent);
  }

  @Test
  void testCreateTableFailureDispatchesFailureEventAndRethrows() {
    CreateTableRequest request =
        CreateTableRequest.builder().withName("t1").withSchema(SCHEMA).build();
    RuntimeException expectedException = new RuntimeException("create failed");
    when(mockExecutor.createTable(eq(context), eq(NAMESPACE), any(CreateTableRequest.class)))
        .thenThrow(expectedException);

    RuntimeException thrown =
        assertThrows(RuntimeException.class, () -> dispatcher.createTable(context, NAMESPACE, request));

    assertEquals("create failed", thrown.getMessage());
    assertTrue(listener.popPreEvent() instanceof IcebergCreateTablePreEvent);
    assertTrue(listener.popPostEvent() instanceof IcebergCreateTableFailureEvent);
  }

  @Test
  void testLoadTableDispatchesEvents() {
    TableIdentifier tableId = TableIdentifier.of(NAMESPACE, "t1");
    LoadTableResponse response = createLoadTableResponse();
    when(mockExecutor.loadTable(context, tableId)).thenReturn(response);

    LoadTableResponse result = dispatcher.loadTable(context, tableId);

    assertEquals(response, result);
    assertTrue(listener.popPreEvent() instanceof IcebergLoadTablePreEvent);
    assertTrue(listener.popPostEvent() instanceof IcebergLoadTableEvent);
  }

  @Test
  void testDropTableDispatchesEvents() {
    TableIdentifier tableId = TableIdentifier.of(NAMESPACE, "t1");

    dispatcher.dropTable(context, tableId, false);

    assertTrue(listener.popPreEvent() instanceof IcebergDropTablePreEvent);
    assertTrue(listener.popPostEvent() instanceof IcebergDropTableEvent);
  }

  @Test
  void testListTableAppliesFilterAndDispatchesEvents() {
    TableIdentifier t1 = TableIdentifier.of(NAMESPACE, "t1");
    ListTablesResponse response = ListTablesResponse.builder().add(t1).build();
    when(mockExecutor.listTable(context, NAMESPACE)).thenReturn(response);

    ListTablesResponse result = dispatcher.listTable(context, NAMESPACE);

    assertEquals(1, result.identifiers().size());
  }
}
