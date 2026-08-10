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

import java.util.ArrayList;
import java.util.List;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.ListTablesResponse;

/**
 * Filters list responses from catalog operations to remove entries the current user is not
 * authorized to access. Centralizes authorization filtering so that REST operation classes and
 * dispatchers share a single, consistent implementation.
 */
public final class ListResponseFilter {

  private ListResponseFilter() {}

  /**
   * Filters a {@link ListTablesResponse}, keeping only tables the current user is authorized to
   * load.
   */
  public static ListTablesResponse filterTables(
      ListTablesResponse response, String catalogName, IcebergAuthorizer authorizer) {
    String userName = PrincipalUtils.getCurrentUserName();
    List<TableIdentifier> filtered = new ArrayList<>();
    for (TableIdentifier ident : response.identifiers()) {
      String schemaName = ident.namespace().length() > 0 ? ident.namespace().level(0) : "";
      IcebergResource resource = IcebergResource.ofTable(catalogName, schemaName, ident.name());
      if (authorizer.checkOperation(userName, IcebergOperation.LOAD_TABLE, resource)) {
        filtered.add(ident);
      }
    }
    return ListTablesResponse.builder()
        .addAll(filtered)
        .nextPageToken(response.nextPageToken())
        .build();
  }

  /**
   * Filters a {@link ListTablesResponse} representing views, keeping only views the current user is
   * authorized to load.
   */
  public static ListTablesResponse filterViews(
      ListTablesResponse response, String catalogName, IcebergAuthorizer authorizer) {
    String userName = PrincipalUtils.getCurrentUserName();
    List<TableIdentifier> filtered = new ArrayList<>();
    for (TableIdentifier ident : response.identifiers()) {
      String schemaName = ident.namespace().length() > 0 ? ident.namespace().level(0) : "";
      IcebergResource resource = IcebergResource.ofView(catalogName, schemaName, ident.name());
      if (authorizer.checkOperation(userName, IcebergOperation.LOAD_VIEW, resource)) {
        filtered.add(ident);
      }
    }
    return ListTablesResponse.builder()
        .addAll(filtered)
        .nextPageToken(response.nextPageToken())
        .build();
  }

  /**
   * Filters a {@link ListNamespacesResponse}, keeping only namespaces the current user is authorized
   * to load.
   */
  public static ListNamespacesResponse filterNamespaces(
      ListNamespacesResponse response, String catalogName, IcebergAuthorizer authorizer) {
    String userName = PrincipalUtils.getCurrentUserName();
    List<Namespace> filtered = new ArrayList<>();
    for (Namespace namespace : response.namespaces()) {
      String schemaName = namespace.isEmpty() ? "" : namespace.level(0);
      IcebergResource resource = IcebergResource.ofSchema(catalogName, schemaName);
      if (authorizer.checkOperation(userName, IcebergOperation.LOAD_NAMESPACE, resource)) {
        filtered.add(namespace);
      }
    }
    return ListNamespacesResponse.builder().addAll(filtered).build();
  }
}
