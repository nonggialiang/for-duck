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

import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.interceptor.IcebergMetadataAuthorizationMethodInterceptor.AuthorizationHandler;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.rest.requests.RenameTableRequest;

@SuppressWarnings("FormatStringAnnotation")
public class RenameTableAuthzHandler implements AuthorizationHandler {
  private final Parameter[] parameters;
  private final Object[] args;
  private boolean crossNamespaceRename = false;

  public RenameTableAuthzHandler(Parameter[] parameters, Object[] args) {
    this.parameters = parameters;
    this.args = args;
  }

  @Override
  public void process(Map<EntityType, NameIdentifier> nameIdentifierMap) {
    RenameTableRequest renameTableRequest = null;
    for (int i = 0; i < parameters.length; i++) {
      IcebergAuthorizationMetadata metadata =
          parameters[i].getAnnotation(IcebergAuthorizationMetadata.class);
      if (metadata != null
          && metadata.type() == IcebergAuthorizationMetadata.RequestType.RENAME_TABLE) {
        renameTableRequest = (RenameTableRequest) args[i];
        break;
      }
    }

    if (renameTableRequest == null) {
      throw new ForbiddenException("RenameTableRequest not found in parameters");
    }

    NameIdentifier catalogIdent = nameIdentifierMap.get(EntityType.CATALOG);
    if (catalogIdent == null) {
      throw new ForbiddenException("Missing catalog context for authorization");
    }

    String catalog = catalogIdent.name();
    String sourceSchema = renameTableRequest.source().namespace().level(0);
    String sourceTable = renameTableRequest.source().name();

    nameIdentifierMap.put(
        EntityType.SCHEMA, NameIdentifier.of(catalog, sourceSchema));
    nameIdentifierMap.put(
        EntityType.TABLE, NameIdentifier.of(catalog, sourceSchema, sourceTable));

    String destSchema = renameTableRequest.destination().namespace().level(0);
    if (!sourceSchema.equals(destSchema)) {
      crossNamespaceRename = true;
      IcebergAuthorizer authorizer = ServerContext.getInstance().getAuthorizer();
      String userName = PrincipalUtils.getCurrentUserName();
      IcebergResource sourceRes = IcebergResource.ofTable(catalog, sourceSchema, sourceTable);
      IcebergResource destRes = IcebergResource.ofTable(catalog, destSchema, null);
      if (!authorizer.checkOperation(userName, IcebergOperation.RENAME_TABLE, sourceRes)
          || !authorizer.checkOperation(userName, IcebergOperation.CREATE_TABLE, destRes)) {
        throw new ForbiddenException(
            "User '%s' is not authorized to rename table '%s' to schema '%s'",
            userName, sourceTable, destSchema);
      }
    }
  }

  @Override
  public boolean authorizationCompleted() {
    return crossNamespaceRename;
  }
}
