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
package org.apache.gravitino.iceberg.service.authorization.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.annotation.AuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.interceptor.IcebergMetadataAuthorizationMethodInterceptor.AuthorizationHandler;
import org.apache.gravitino.iceberg.service.authorization.allowall.AllowAllAuthorizer;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests for {@link RenameTableAuthzHandler} and {@link RenameViewAuthzHandler}. */
class TestRenameAuthzHandlers {

  // ---- Test method signatures for reflection ----

  public void renameTableOp(
      @AuthorizationMetadata(type = EntityType.CATALOG) String prefix,
      @IcebergAuthorizationMetadata(type = IcebergAuthorizationMetadata.RequestType.RENAME_TABLE)
          RenameTableRequest request) {}

  public void renameViewOp(
      @AuthorizationMetadata(type = EntityType.CATALOG) String prefix,
      @IcebergAuthorizationMetadata(type = IcebergAuthorizationMetadata.RequestType.RENAME_VIEW)
          RenameTableRequest request) {}

  private static Method getRenameTableMethod() throws NoSuchMethodException {
    return TestRenameAuthzHandlers.class.getMethod(
        "renameTableOp", String.class, RenameTableRequest.class);
  }

  private static Method getRenameViewMethod() throws NoSuchMethodException {
    return TestRenameAuthzHandlers.class.getMethod(
        "renameViewOp", String.class, RenameTableRequest.class);
  }

  private static Map<EntityType, NameIdentifier> baseIdentifierMap(String catalog) {
    Map<EntityType, NameIdentifier> map = new HashMap<>();
    map.put(EntityType.CATALOG, NameIdentifier.of(catalog));
    return map;
  }

  // ---- RenameTableAuthzHandler tests ----

  @Test
  void testRenameTableSameSchemaNoAuthzCheck() throws Exception {
    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    ServerContext.reset();
    ServerContext.initialize(authorizer, null, "cat");

    Method method = getRenameTableMethod();
    Parameter[] params = method.getParameters();
    RenameTableRequest request =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(Namespace.of("db1"), "t1"))
            .withDestination(TableIdentifier.of(Namespace.of("db1"), "t2"))
            .build();
    Object[] args = new Object[] {"cat/", request};

    AuthorizationHandler handler = new RenameTableAuthzHandler(params, args);
    Map<EntityType, NameIdentifier> map = baseIdentifierMap("cat");
    handler.process(map);

    assertFalse(handler.authorizationCompleted());
    assertEquals("db1", map.get(EntityType.SCHEMA).name());
    assertEquals("t1", map.get(EntityType.TABLE).name());
  }

  @Test
  void testRenameTableCrossSchemaAuthorized() throws Exception {
    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(anyString(), any(), any())).thenReturn(true);
    ServerContext.reset();
    ServerContext.initialize(authorizer, null, "cat");

    Method method = getRenameTableMethod();
    Parameter[] params = method.getParameters();
    RenameTableRequest request =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(Namespace.of("db1"), "t1"))
            .withDestination(TableIdentifier.of(Namespace.of("db2"), "t1"))
            .build();
    Object[] args = new Object[] {"cat/", request};

    AuthorizationHandler handler = new RenameTableAuthzHandler(params, args);
    handler.process(baseIdentifierMap("cat"));

    assertTrue(handler.authorizationCompleted());
  }

  @Test
  void testRenameTableCrossSchemaDenied() throws Exception {
    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(anyString(), any(), any())).thenReturn(false);
    ServerContext.reset();
    ServerContext.initialize(authorizer, null, "cat");

    Method method = getRenameTableMethod();
    Parameter[] params = method.getParameters();
    RenameTableRequest request =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(Namespace.of("db1"), "t1"))
            .withDestination(TableIdentifier.of(Namespace.of("db2"), "t1"))
            .build();
    Object[] args = new Object[] {"cat/", request};

    AuthorizationHandler handler = new RenameTableAuthzHandler(params, args);
    assertThrows(ForbiddenException.class, () -> handler.process(baseIdentifierMap("cat")));
  }

  @Test
  void testRenameTableMissingCatalog() throws Exception {
    ServerContext.reset();
    ServerContext.initialize(new AllowAllAuthorizer(), null, "cat");

    Method method = getRenameTableMethod();
    Parameter[] params = method.getParameters();
    RenameTableRequest request =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(Namespace.of("db1"), "t1"))
            .withDestination(TableIdentifier.of(Namespace.of("db1"), "t2"))
            .build();
    Object[] args = new Object[] {"cat/", request};

    AuthorizationHandler handler = new RenameTableAuthzHandler(params, args);
    assertThrows(
        ForbiddenException.class, () -> handler.process(new HashMap<>()));
  }

  // ---- RenameViewAuthzHandler tests ----

  @Test
  void testRenameViewSameSchemaNoAuthzCheck() throws Exception {
    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    ServerContext.reset();
    ServerContext.initialize(authorizer, null, "cat");

    Method method = getRenameViewMethod();
    Parameter[] params = method.getParameters();
    RenameTableRequest request =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(Namespace.of("db1"), "v1"))
            .withDestination(TableIdentifier.of(Namespace.of("db1"), "v2"))
            .build();
    Object[] args = new Object[] {"cat/", request};

    AuthorizationHandler handler = new RenameViewAuthzHandler(params, args);
    Map<EntityType, NameIdentifier> map = baseIdentifierMap("cat");
    handler.process(map);

    assertFalse(handler.authorizationCompleted());
    assertEquals("v1", map.get(EntityType.VIEW).name());
  }

  @Test
  void testRenameViewCrossSchemaAuthorized() throws Exception {
    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(anyString(), any(), any())).thenReturn(true);
    ServerContext.reset();
    ServerContext.initialize(authorizer, null, "cat");

    Method method = getRenameViewMethod();
    Parameter[] params = method.getParameters();
    RenameTableRequest request =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(Namespace.of("db1"), "v1"))
            .withDestination(TableIdentifier.of(Namespace.of("db2"), "v1"))
            .build();
    Object[] args = new Object[] {"cat/", request};

    AuthorizationHandler handler = new RenameViewAuthzHandler(params, args);
    handler.process(baseIdentifierMap("cat"));

    assertTrue(handler.authorizationCompleted());
  }

  @Test
  void testRenameViewCrossSchemaDenied() throws Exception {
    IcebergAuthorizer authorizer = mock(IcebergAuthorizer.class);
    when(authorizer.checkOperation(anyString(), any(), any())).thenReturn(false);
    ServerContext.reset();
    ServerContext.initialize(authorizer, null, "cat");

    Method method = getRenameViewMethod();
    Parameter[] params = method.getParameters();
    RenameTableRequest request =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(Namespace.of("db1"), "v1"))
            .withDestination(TableIdentifier.of(Namespace.of("db2"), "v1"))
            .build();
    Object[] args = new Object[] {"cat/", request};

    AuthorizationHandler handler = new RenameViewAuthzHandler(params, args);
    assertThrows(ForbiddenException.class, () -> handler.process(baseIdentifierMap("cat")));
  }

  @AfterAll
  static void tearDown() {
    ServerContext.reset();
  }
}
