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
package org.apache.gravitino.iceberg.service.authorization.handler;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.iceberg.service.authorization.annotation.AuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata;

import java.lang.reflect.Parameter;
import java.util.Map;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.ServerContext;

import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata.RequestType;
import org.apache.gravitino.iceberg.service.authorization.interceptor.IcebergMetadataAuthorizationMethodInterceptor.AuthorizationHandler;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.MetadataTableType;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.rest.RESTUtil;

public class LoadTableAuthzHandler implements AuthorizationHandler {
  private final Parameter[] parameters;
  private final Object[] args;

  public LoadTableAuthzHandler(Parameter[] parameters, Object[] args) {
    this.parameters = parameters;
    this.args = args;
  }

  @Override
  public void process(Map<EntityType, NameIdentifier> nameIdentifierMap) {
    String tableName = null;
    Namespace namespace = null;

    for (int i = 0; i < parameters.length; i++) {
      Parameter parameter = parameters[i];
      IcebergAuthorizationMetadata icebergMetadata =
          parameter.getAnnotation(IcebergAuthorizationMetadata.class);
      if (icebergMetadata != null && icebergMetadata.type() == RequestType.LOAD_TABLE) {
        tableName = RESTUtil.decodeString(String.valueOf(args[i]));
      }
      AuthorizationMetadata authMetadata = parameter.getAnnotation(AuthorizationMetadata.class);
      if (authMetadata != null && authMetadata.type() == EntityType.SCHEMA) {
        namespace = RESTUtil.decodeNamespace(String.valueOf(args[i]));
      }
    }

    if (tableName == null || namespace == null) {
      throw new NoSuchTableException("Table not found - missing table name or namespace");
    }

    if (isMetadataTable(tableName, namespace)) {
      throw new NoSuchTableException("Table %s not found", tableName);
    }

    NameIdentifier catalogId = nameIdentifierMap.get(EntityType.CATALOG);
    NameIdentifier schemaId = nameIdentifierMap.get(EntityType.SCHEMA);

    if (catalogId == null || schemaId == null) {
      throw new NoSuchTableException("Missing catalog or schema context for table authorization");
    }

    String catalog = catalogId.name();
    String schema = schemaId.name();

    IcebergCatalogWrapperManager wrapperManager =
        ServerContext.getInstance().getCatalogWrapperManager();
    IcebergCatalogWrapper catalogWrapper = wrapperManager.getCatalogWrapper(catalog);
    TableIdentifier tableIdentifier = TableIdentifier.of(namespace, tableName);

    if (catalogWrapper.supportsViewOperations() && catalogWrapper.viewExists(tableIdentifier)) {
      throw new NoSuchTableException("Table %s not found", tableName);
    }

    nameIdentifierMap.put(
        EntityType.TABLE, NameIdentifier.of(catalog, schema, tableName));

    IcebergAuthorizer authorizer = ServerContext.getInstance().getAuthorizer();
    String userName = PrincipalUtils.getCurrentUserName();
    IcebergResource resource = IcebergResource.ofTable(catalog, schema, tableName);
    if (!authorizer.checkOperation(userName, IcebergOperation.LOAD_TABLE, resource)) {
      throw new ForbiddenException(
          "User '%s' is not authorized to load table '%s'", userName, tableName);
    }
  }

  @Override
  public boolean authorizationCompleted() {
    return true;
  }

  private boolean isMetadataTable(String tableName, Namespace namespace) {
    MetadataTableType metadataTableType = MetadataTableType.from(tableName);
    if (metadataTableType == null) return false;
    return namespace.levels().length > 1;
  }
}
