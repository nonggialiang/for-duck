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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.iceberg.service.entitlement.EntitlementSupport;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.ServiceFailureException;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestIcebergViewOperationExecutor {

  private IcebergViewOperationExecutor executor;
  private IcebergCatalogWrapperManager mockWrapperManager;
  private CatalogWrapperForREST mockCatalogWrapper;
  private IcebergRequestContext mockContext;

  @BeforeEach
  public void setUp() {
    mockWrapperManager = mock(IcebergCatalogWrapperManager.class);
    mockCatalogWrapper = mock(CatalogWrapperForREST.class);
    executor = new IcebergViewOperationExecutor(mockWrapperManager);

    mockContext = mock(IcebergRequestContext.class);
    when(mockContext.catalogName()).thenReturn("test_catalog");
    when(mockWrapperManager.getCatalogWrapper("test_catalog")).thenReturn(mockCatalogWrapper);
  }

  @AfterEach
  public void tearDown() {
    ServerContext.reset();
  }

  /** alice=row-filter only, bob=write only, dave=both, carol=neither. */
  private static class StubAuthorizer implements IcebergAuthorizer {
    @Override
    public boolean checkOperation(
        String userName, IcebergOperation op, IcebergResource resource) {
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
    public void registerOwner(String catalog, String namespace, String resource, String owner) {}

    @Override
    public void removeOwner(String catalog, String namespace, String resource) {}
  }

  private void initServerContext() {
    ServerContext.reset();
    ServerContext.initialize(new StubAuthorizer(), null, "test_catalog");
  }

  private LoadTableResponse newTableResponse() {
    TableMetadata tableMetadata =
        TableMetadata.newTableMetadata(
            new Schema(
                Arrays.asList(
                    Types.NestedField.required(1, "id", Types.LongType.get()),
                    Types.NestedField.optional(2, "region", Types.StringType.get()))),
            PartitionSpec.unpartitioned(),
            "s3://bucket/warehouse/ns/t1",
            ImmutableMap.of());
    return LoadTableResponse.builder().withTableMetadata(tableMetadata).build();
  }

  @Test
  public void testCreateView() {
    Namespace namespace = Namespace.of("test_ns");
    CreateViewRequest mockRequest = mock(CreateViewRequest.class);
    LoadViewResponse mockResponse = mock(LoadViewResponse.class);
    when(mockCatalogWrapper.createView(namespace, mockRequest)).thenReturn(mockResponse);

    LoadViewResponse result = executor.createView(mockContext, namespace, mockRequest);

    Assertions.assertEquals(mockResponse, result);
    verify(mockCatalogWrapper).createView(namespace, mockRequest);
  }

  @Test
  public void testLoadView() {
    TableIdentifier viewId = TableIdentifier.of("test_ns", "test_view");
    LoadViewResponse mockResponse = mock(LoadViewResponse.class);
    when(mockCatalogWrapper.loadView(viewId)).thenReturn(mockResponse);

    LoadViewResponse result = executor.loadView(mockContext, viewId);

    Assertions.assertEquals(mockResponse, result);
    verify(mockCatalogWrapper).loadView(viewId);
  }

  @Test
  public void testDropView() {
    TableIdentifier viewId = TableIdentifier.of("test_ns", "test_view");

    executor.dropView(mockContext, viewId);

    verify(mockCatalogWrapper).dropView(viewId);
  }

  @Test
  public void testListView() {
    Namespace namespace = Namespace.of("test_ns");
    ListTablesResponse mockResponse = mock(ListTablesResponse.class);
    when(mockCatalogWrapper.listView(namespace)).thenReturn(mockResponse);

    ListTablesResponse result = executor.listView(mockContext, namespace);

    Assertions.assertEquals(mockResponse, result);
    verify(mockCatalogWrapper).listView(namespace);
  }

  @Test
  public void testViewExists() {
    TableIdentifier viewId = TableIdentifier.of("test_ns", "test_view");
    when(mockCatalogWrapper.viewExists(viewId)).thenReturn(true);

    boolean result = executor.viewExists(mockContext, viewId);

    Assertions.assertTrue(result);
    verify(mockCatalogWrapper).viewExists(viewId);
  }

  @Test
  public void testViewDoesNotExist() {
    TableIdentifier viewId = TableIdentifier.of("test_ns", "non_existent_view");
    when(mockCatalogWrapper.viewExists(viewId)).thenReturn(false);

    boolean result = executor.viewExists(mockContext, viewId);

    Assertions.assertFalse(result);
    verify(mockCatalogWrapper).viewExists(viewId);
  }

  @Test
  public void testLoadViewThrowsException() {
    TableIdentifier viewId = TableIdentifier.of("test_ns", "test_view");
    RuntimeException exception = new RuntimeException("View not found");
    when(mockCatalogWrapper.loadView(viewId)).thenThrow(exception);

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class, () -> executor.loadView(mockContext, viewId));

    Assertions.assertEquals(exception, thrown);
    verify(mockCatalogWrapper).loadView(viewId);
  }

  @Test
  public void testCreateViewThrowsException() {
    Namespace namespace = Namespace.of("test_ns");
    CreateViewRequest mockRequest = mock(CreateViewRequest.class);
    RuntimeException exception = new RuntimeException("Failed to create view");
    when(mockCatalogWrapper.createView(namespace, mockRequest)).thenThrow(exception);

    RuntimeException thrown =
        Assertions.assertThrows(
            RuntimeException.class, () -> executor.createView(mockContext, namespace, mockRequest));

    Assertions.assertEquals(exception, thrown);
    verify(mockCatalogWrapper).createView(namespace, mockRequest);
  }

  @Test
  public void testReplaceView() {
    TableIdentifier viewId = TableIdentifier.of("test_ns", "test_view");
    UpdateTableRequest mockRequest = mock(UpdateTableRequest.class);
    LoadViewResponse mockResponse = mock(LoadViewResponse.class);
    when(mockCatalogWrapper.updateView(viewId, mockRequest)).thenReturn(mockResponse);

    LoadViewResponse result = executor.replaceView(mockContext, viewId, mockRequest);

    Assertions.assertEquals(mockResponse, result);
    verify(mockCatalogWrapper).updateView(viewId, mockRequest);
  }

  @Test
  public void testRenameView() {
    RenameTableRequest mockRequest = mock(RenameTableRequest.class);

    executor.renameView(mockContext, mockRequest);

    verify(mockCatalogWrapper).renameView(mockRequest);
  }

  @Test
  public void testLoadViewSynthesizesEntitlementViewForEntitledUser() {
    initServerContext();
    when(mockContext.userName()).thenReturn("alice");
    TableIdentifier viewId = TableIdentifier.of("test_ns", "t1");
    when(mockCatalogWrapper.loadView(viewId))
        .thenThrow(new NoSuchViewException("View does not exist"));
    when(mockCatalogWrapper.loadTable(viewId)).thenReturn(newTableResponse());

    LoadViewResponse response = executor.loadView(mockContext, viewId);

    SQLViewRepresentation sql =
        (SQLViewRepresentation) response.metadata().currentVersion().representations().get(0);
    assertEquals("spark", sql.dialect());
    assertEquals(
        "SELECT * FROM `test_catalog`.`test_ns`.`t1@entitlement` WHERE region = 'US'", sql.sql());
    assertEquals(
        "true",
        response.metadata().properties().get(EntitlementSupport.ENTITLEMENT_VIEW_PROPERTY));
    verify(mockCatalogWrapper).loadTable(viewId);
  }

  @Test
  public void testLoadViewSynthesizesWhenCatalogDoesNotSupportViews() {
    initServerContext();
    when(mockContext.userName()).thenReturn("alice");
    TableIdentifier viewId = TableIdentifier.of("test_ns", "t1");
    when(mockCatalogWrapper.loadView(viewId))
        .thenThrow(new UnsupportedOperationException("catalog does not support view"));
    when(mockCatalogWrapper.loadTable(viewId)).thenReturn(newTableResponse());

    LoadViewResponse response = executor.loadView(mockContext, viewId);

    SQLViewRepresentation sql =
        (SQLViewRepresentation) response.metadata().currentVersion().representations().get(0);
    assertTrue(sql.sql().contains("t1@entitlement"));
  }

  @Test
  public void testLoadViewRethrowsWhenNoEntitlement() {
    initServerContext();
    when(mockContext.userName()).thenReturn("carol");
    TableIdentifier viewId = TableIdentifier.of("test_ns", "missing_view");
    NoSuchViewException original = new NoSuchViewException("View does not exist");
    when(mockCatalogWrapper.loadView(viewId)).thenThrow(original);

    assertThrows(NoSuchViewException.class, () -> executor.loadView(mockContext, viewId));
  }

  @Test
  public void testLoadViewThrowsOnConflict() {
    initServerContext();
    when(mockContext.userName()).thenReturn("dave");
    TableIdentifier viewId = TableIdentifier.of("test_ns", "t1");
    when(mockCatalogWrapper.loadView(viewId))
        .thenThrow(new NoSuchViewException("View does not exist"));

    ServiceFailureException thrown =
        assertThrows(ServiceFailureException.class, () -> executor.loadView(mockContext, viewId));
    assertTrue(thrown.getMessage().contains("conflicts with the write privilege"));
  }

  @Test
  public void testLoadViewRealViewWinsOverEntitlement() {
    initServerContext();
    when(mockContext.userName()).thenReturn("alice");
    TableIdentifier viewId = TableIdentifier.of("test_ns", "real_view");
    LoadViewResponse mockResponse = mock(LoadViewResponse.class);
    when(mockCatalogWrapper.loadView(viewId)).thenReturn(mockResponse);

    Assertions.assertEquals(mockResponse, executor.loadView(mockContext, viewId));
  }
}
