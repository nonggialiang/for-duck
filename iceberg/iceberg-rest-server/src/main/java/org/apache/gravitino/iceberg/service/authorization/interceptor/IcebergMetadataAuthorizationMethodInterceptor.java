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
package org.apache.gravitino.iceberg.service.authorization.interceptor;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.PrefixResolver;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.iceberg.service.authorization.annotation.AuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationOperation;
import com.google.common.annotations.VisibleForTesting;
import org.apache.gravitino.iceberg.service.authorization.handler.LoadTableAuthzHandler;
import org.apache.gravitino.iceberg.service.authorization.handler.RenameTableAuthzHandler;
import org.apache.gravitino.iceberg.service.authorization.handler.RenameViewAuthzHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.rest.RESTUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("FormatStringAnnotation")
public class IcebergMetadataAuthorizationMethodInterceptor {
  private static final Logger LOG =
      LoggerFactory.getLogger(IcebergMetadataAuthorizationMethodInterceptor.class);

  public interface AuthorizationHandler {
    void process(Map<Entity.EntityType, NameIdentifier> nameIdentifierMap);
    boolean authorizationCompleted();
  }

  private static final Map<IcebergAuthorizationMetadata.RequestType,
      BiFunction<Parameter[], Object[], AuthorizationHandler>> HANDLER_FACTORIES =
      Map.of(
          IcebergAuthorizationMetadata.RequestType.LOAD_TABLE, LoadTableAuthzHandler::new,
          IcebergAuthorizationMetadata.RequestType.RENAME_TABLE, RenameTableAuthzHandler::new,
          IcebergAuthorizationMetadata.RequestType.RENAME_VIEW, RenameViewAuthzHandler::new);

  /**
   * Performs the authorization check for the given method and arguments. If the check fails, a
   * {@link ForbiddenException} is thrown (to be handled by the global exception handler).
   *
   * @param method the controller method (must be annotated with {@link IcebergAuthorizationOperation})
   * @param args the method arguments
   */
  public void authorize(Method method, Object[] args) {
    IcebergAuthorizationOperation opAnnotation = method.getAnnotation(IcebergAuthorizationOperation.class);
    if (opAnnotation == null) {
      return;
    }
    Parameter[] parameters = method.getParameters();
    Map<Entity.EntityType, NameIdentifier> nameIdentifierMap =
        extractNameIdentifierFromParameters(parameters, args);

    Optional<AuthorizationHandler> handler = createAuthorizationHandler(parameters, args);
    boolean authzCompleted = false;
    if (handler.isPresent()) {
      handler.get().process(nameIdentifierMap);
      authzCompleted = handler.get().authorizationCompleted();
    }

    if (!authzCompleted) {
      IcebergAuthorizer authorizer = ServerContext.getInstance().getAuthorizer();
      String userName = PrincipalUtils.getCurrentUserName();
      IcebergResource resource = buildResource(nameIdentifierMap);
      if (!authorizer.checkOperation(userName, opAnnotation.value(), resource)) {
        String msg = String.format(
            "User '%s' is not authorized to perform '%s' on resource: %s",
            userName, opAnnotation.value(), resource);
        LOG.info(msg);
        throw new ForbiddenException(msg);
      }
    }

    LOG.debug("Authorization: operation={}, method={}", opAnnotation.value(), method.getName());
  }

  @VisibleForTesting
  Map<Entity.EntityType, NameIdentifier> extractNameIdentifierFromParameters(
      Parameter[] parameters, Object[] args) {
    Map<Entity.EntityType, NameIdentifier> nameIdentifierMap = new HashMap<>();
    String catalog = null;
    String schema = null;
    Namespace rawNamespace = null;
    for (int i = 0; i < parameters.length; i++) {
      Parameter parameter = parameters[i];
      AuthorizationMetadata authorizeResource =
          parameter.getAnnotation(AuthorizationMetadata.class);
      if (authorizeResource == null) continue;
      Entity.EntityType type = authorizeResource.type();
      String value = args[i] == null ? null : String.valueOf(args[i]);
      switch (type) {
        case CATALOG:
          catalog = PrefixResolver.getCatalogName(value);
          nameIdentifierMap.put(Entity.EntityType.CATALOG, NameIdentifier.of(catalog));
          break;
        case SCHEMA:
          rawNamespace = RESTUtil.decodeNamespace(value);
          schema = rawNamespace.level(rawNamespace.length() - 1);
          nameIdentifierMap.put(Entity.EntityType.SCHEMA, NameIdentifier.of(catalog, schema));
          break;
        case TABLE:
          nameIdentifierMap.put(EntityType.TABLE,
              NameIdentifier.of(catalog, schema, RESTUtil.decodeString(value)));
          break;
        case VIEW:
          String decodedViewName = RESTUtil.decodeString(value);
          nameIdentifierMap.put(EntityType.VIEW,
              NameIdentifier.of(catalog, schema, decodedViewName));
          nameIdentifierMap.put(EntityType.TABLE,
              NameIdentifier.of(catalog, schema, decodedViewName));
          break;
        default:
          break;
      }
    }
    return nameIdentifierMap;
  }

  private Optional<AuthorizationHandler> createAuthorizationHandler(
      Parameter[] parameters, Object[] args) {
    for (Parameter parameter : parameters) {
      IcebergAuthorizationMetadata icebergMetadata =
          parameter.getAnnotation(IcebergAuthorizationMetadata.class);
      if (icebergMetadata != null) {
        BiFunction<Parameter[], Object[], AuthorizationHandler> factory =
            HANDLER_FACTORIES.get(icebergMetadata.type());
        if (factory != null) {
          return Optional.of(factory.apply(parameters, args));
        }
      }
    }
    return Optional.empty();
  }

  @VisibleForTesting
  boolean isExceptionPropagate(Exception e) {
    return e.getClass().getName().startsWith("org.apache.iceberg.exceptions");
  }

  private static IcebergResource buildResource(
      Map<Entity.EntityType, NameIdentifier> nameIdentifierMap) {
    NameIdentifier catalog = nameIdentifierMap.get(Entity.EntityType.CATALOG);
    NameIdentifier schema = nameIdentifierMap.get(Entity.EntityType.SCHEMA);
    NameIdentifier table = nameIdentifierMap.get(Entity.EntityType.TABLE);
    NameIdentifier view = nameIdentifierMap.get(Entity.EntityType.VIEW);

    String catalogName = catalog != null ? catalog.name() : "";
    String schemaName = schema != null ? schema.name() : null;
    if (table != null) {
      return IcebergResource.ofTable(catalogName, schemaName != null ? schemaName : "", table.name());
    } else if (view != null) {
      return IcebergResource.ofView(catalogName, schemaName != null ? schemaName : "", view.name());
    } else if (schema != null) {
      return IcebergResource.ofSchema(catalogName, schemaName);
    } else {
      return IcebergResource.ofCatalog(catalogName);
    }
  }
}
