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
package org.apache.gravitino.iceberg.service;

import java.util.Map;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.LoadCredentialsResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;

/**
 * Thin delegation layer that combines {@link ScanPlanService} and {@link CredentialVendingService}
 * on top of the base {@link IcebergCatalogWrapper}. Each concern is isolated in its dedicated
 * service; this class wires them together and delegates the catalog-level operations.
 */
public class CatalogWrapperForREST extends IcebergCatalogWrapper {

  private final CredentialVendingService credentialVendingService;
  private final ScanPlanService scanPlanService;

  public CatalogWrapperForREST(String catalogName, IcebergConfig config) {
    super(config);
    this.credentialVendingService = new CredentialVendingService(catalogName, config);
    this.scanPlanService = new ScanPlanService(catalog, config);
  }

  @Override
  public void close() throws Exception {
    try {
      credentialVendingService.close();
      scanPlanService.close();
    } finally {
      super.close();
    }
  }

  public LoadTableResponse createTable(
      Namespace namespace, CreateTableRequest request, boolean requestCredential) {
    LoadTableResponse loadTableResponse = super.createTable(namespace, request);
    if (credentialVendingService.shouldGenerateCredential(loadTableResponse, requestCredential)) {
      return credentialVendingService.injectCredential(
          TableIdentifier.of(namespace, request.name()),
          loadTableResponse,
          CredentialPrivilege.WRITE);
    }
    return loadTableResponse;
  }

  public LoadTableResponse loadTable(
      TableIdentifier identifier, boolean requestCredential, CredentialPrivilege privilege) {
    LoadTableResponse loadTableResponse = super.loadTable(identifier);
    if (credentialVendingService.shouldGenerateCredential(loadTableResponse, requestCredential)) {
      return credentialVendingService.injectCredential(identifier, loadTableResponse, privilege);
    }
    return loadTableResponse;
  }

  public LoadCredentialsResponse getTableCredentials(
      TableIdentifier identifier, CredentialPrivilege privilege) {
    return credentialVendingService.getTableCredentials(identifier, privilege, this);
  }

  public PlanTableScanResponse planTableScan(
      TableIdentifier tableIdentifier, PlanTableScanRequest scanRequest) {
    return scanPlanService.planTableScan(tableIdentifier, scanRequest);
  }

  public Map<String, String> getCatalogConfigToClient() {
    return credentialVendingService.getCatalogConfigToClient();
  }
}
