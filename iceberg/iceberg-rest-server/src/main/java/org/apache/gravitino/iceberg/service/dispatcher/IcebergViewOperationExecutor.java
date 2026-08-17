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

package org.apache.gravitino.iceberg.service.dispatcher;

import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.iceberg.service.entitlement.EntitlementSupport;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
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

public class IcebergViewOperationExecutor implements IcebergViewOperationDispatcher {

  private IcebergCatalogWrapperManager icebergCatalogWrapperManager;

  public IcebergViewOperationExecutor(IcebergCatalogWrapperManager icebergCatalogWrapperManager) {
    this.icebergCatalogWrapperManager = icebergCatalogWrapperManager;
  }

  @Override
  public LoadViewResponse createView(
      IcebergRequestContext context, Namespace namespace, CreateViewRequest createViewRequest) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .createView(namespace, createViewRequest);
  }

  @Override
  public LoadViewResponse replaceView(
      IcebergRequestContext context,
      TableIdentifier viewIdentifier,
      UpdateTableRequest replaceViewRequest) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .updateView(viewIdentifier, replaceViewRequest);
  }

  @Override
  public void dropView(IcebergRequestContext context, TableIdentifier viewIdentifier) {
    icebergCatalogWrapperManager.getCatalogWrapper(context.catalogName()).dropView(viewIdentifier);
  }

  @Override
  public LoadViewResponse loadView(IcebergRequestContext context, TableIdentifier viewIdentifier) {
    CatalogWrapperForREST catalogWrapper =
        icebergCatalogWrapperManager.getCatalogWrapper(context.catalogName());
    try {
      return catalogWrapper.loadView(viewIdentifier);
    } catch (NoSuchViewException | UnsupportedOperationException e) {
      return loadEntitlementViewIfPresent(context, viewIdentifier, catalogWrapper, e);
    }
  }

  /**
   * Fallback when the view does not exist (or the catalog supports no views at all): entitled
   * users get a synthesized view over the suffixed physical table; conflicting users get an
   * explicit error; everyone else sees the original exception.
   */
  private LoadViewResponse loadEntitlementViewIfPresent(
      IcebergRequestContext context,
      TableIdentifier viewIdentifier,
      CatalogWrapperForREST catalogWrapper,
      RuntimeException original) {
    String user = context.userName();
    IcebergResource resource =
        IcebergResource.ofTable(
            context.catalogName(),
            viewIdentifier.namespace().level(viewIdentifier.namespace().levels().length - 1),
            viewIdentifier.name());
    switch (EntitlementSupport.resolveMode(user, resource)) {
      case ENTITLED:
        String rowFilter = EntitlementSupport.getRowFilter(user, resource);
        LoadTableResponse tableResponse = catalogWrapper.loadTable(viewIdentifier);
        return EntitlementSupport.buildLoadViewResponse(
            context.catalogName(),
            resource.getSchemaName(),
            viewIdentifier.name(),
            tableResponse.tableMetadata(),
            rowFilter,
            user);
      case CONFLICT:
        throw new ServiceFailureException(EntitlementSupport.conflictMessage(user, resource));
      default:
        throw original;
    }
  }

  @Override
  public ListTablesResponse listView(IcebergRequestContext context, Namespace namespace) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .listView(namespace);
  }

  @Override
  public boolean viewExists(IcebergRequestContext context, TableIdentifier viewIdentifier) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .viewExists(viewIdentifier);
  }

  @Override
  public void renameView(IcebergRequestContext context, RenameTableRequest renameViewRequest) {
    icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .renameView(renameViewRequest);
  }
}
