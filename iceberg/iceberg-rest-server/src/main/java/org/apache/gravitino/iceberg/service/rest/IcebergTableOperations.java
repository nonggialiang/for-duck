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
package org.apache.gravitino.iceberg.service.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.iceberg.service.HttpResponseBuilder;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.iceberg.service.PrefixResolver;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.annotation.AuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata.RequestType;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationOperation;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationDispatcher;
import org.apache.gravitino.iceberg.service.metrics.IcebergMetricsManager;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.requests.ReportMetricsRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadCredentialsResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    path = {
      "/v1/namespaces/{namespace}/tables",
      "/v1/{prefix}/namespaces/{namespace}/tables"
    },
    produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergTableOperations {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergTableOperations.class);

  @VisibleForTesting
  public static final String X_ICEBERG_ACCESS_DELEGATION = "X-Iceberg-Access-Delegation";

  private final IcebergMetricsManager icebergMetricsManager;
  private final ObjectMapper icebergObjectMapper;
  private final IcebergTableOperationDispatcher tableOperationDispatcher;

  public IcebergTableOperations(
      IcebergMetricsManager icebergMetricsManager,
      IcebergTableOperationDispatcher tableOperationDispatcher) {
    this.icebergMetricsManager = icebergMetricsManager;
    this.tableOperationDispatcher = tableOperationDispatcher;
    this.icebergObjectMapper = IcebergObjectMapper.getInstance();
  }

  @GetMapping
  @IcebergAuthorizationOperation(IcebergOperation.LIST_TABLE)
  public ResponseEntity<Object> listTable(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info("List Iceberg tables, catalog: {}, namespace: {}", catalogName, icebergNS);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    ListTablesResponse listTablesResponse = tableOperationDispatcher.listTable(context, icebergNS);
    return HttpResponseBuilder.okEntity(listTablesResponse);
  }

  @PostMapping
  @IcebergAuthorizationOperation(IcebergOperation.CREATE_TABLE)
  public ResponseEntity<Object> createTable(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @RequestBody CreateTableRequest createTableRequest,
      @RequestHeader(value = X_ICEBERG_ACCESS_DELEGATION, required = false)
          String accessDelegation,
      HttpServletRequest request) {
    boolean isCredentialVending = isCredentialVending(accessDelegation);
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info(
        "Create Iceberg table, catalog: {}, namespace: {}, create table request: {}, "
            + "accessDelegation: {}, isCredentialVending: {}",
        catalogName,
        icebergNS,
        createTableRequest,
        accessDelegation,
        isCredentialVending);
    IcebergRequestContext context =
        new IcebergRequestContext(request, catalogName, isCredentialVending);
    LoadTableResponse loadTableResponse =
        tableOperationDispatcher.createTable(context, icebergNS, createTableRequest);
    return HttpResponseBuilder.okEntity(loadTableResponse);
  }

  @PostMapping("{table}")
  @IcebergAuthorizationOperation(IcebergOperation.UPDATE_TABLE)
  public ResponseEntity<Object> updateTable(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = Entity.EntityType.TABLE) @PathVariable("table") String table,
      @RequestBody UpdateTableRequest updateTableRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String tableName = RESTUtil.decodeString(table);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Update Iceberg table, catalog: {}, namespace: {}, table: {}, updateTableRequest: {}",
          catalogName,
          icebergNS,
          table,
          serializeUpdateTableRequest(updateTableRequest));
    }
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    TableIdentifier tableIdentifier = TableIdentifier.of(icebergNS, tableName);
    LoadTableResponse loadTableResponse =
        tableOperationDispatcher.updateTable(context, tableIdentifier, updateTableRequest);
    return HttpResponseBuilder.okEntity(loadTableResponse);
  }

  @DeleteMapping("{table}")
  @IcebergAuthorizationOperation(IcebergOperation.DROP_TABLE)
  public ResponseEntity<Object> dropTable(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = Entity.EntityType.TABLE) @PathVariable("table") String table,
      @RequestParam(name = "purgeRequested", defaultValue = "false") boolean purgeRequested,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String tableName = RESTUtil.decodeString(table);
    LOG.info(
        "Drop Iceberg table, catalog: {}, namespace: {}, table: {}, purgeRequested: {}",
        catalogName,
        icebergNS,
        tableName,
        purgeRequested);
    TableIdentifier tableIdentifier = TableIdentifier.of(icebergNS, tableName);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    tableOperationDispatcher.dropTable(context, tableIdentifier, purgeRequested);
    return HttpResponseBuilder.noContentEntity();
  }

  @GetMapping("{table}")
  @IcebergAuthorizationOperation(IcebergOperation.LOAD_TABLE)
  public ResponseEntity<Object> loadTable(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @IcebergAuthorizationMetadata(type = RequestType.LOAD_TABLE)
          @PathVariable("table")
          String table,
      @RequestParam(name = "snapshots", defaultValue = "all") String snapshots,
      @RequestHeader(value = X_ICEBERG_ACCESS_DELEGATION, required = false)
          String accessDelegation,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String tableName = RESTUtil.decodeString(table);
    boolean isCredentialVending = isCredentialVending(accessDelegation);
    LOG.info(
        "Load Iceberg table, catalog: {}, namespace: {}, table: {}, access delegation: {}, "
            + "credential vending: {}",
        catalogName,
        icebergNS,
        tableName,
        accessDelegation,
        isCredentialVending);
    // todo support snapshots
    TableIdentifier tableIdentifier = TableIdentifier.of(icebergNS, tableName);
    IcebergRequestContext context =
        new IcebergRequestContext(request, catalogName, isCredentialVending);
    LoadTableResponse loadTableResponse =
        tableOperationDispatcher.loadTable(context, tableIdentifier);
    return HttpResponseBuilder.okEntity(loadTableResponse);
  }

  @RequestMapping(value = "{table}", method = RequestMethod.HEAD)
  @IcebergAuthorizationOperation(IcebergOperation.TABLE_EXISTS)
  public ResponseEntity<Object> tableExists(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = Entity.EntityType.TABLE) @PathVariable("table") String table,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String tableName = RESTUtil.decodeString(table);
    LOG.info(
        "Check Iceberg table exists, catalog: {}, namespace: {}, table: {}",
        catalogName,
        icebergNS,
        tableName);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    TableIdentifier tableIdentifier = TableIdentifier.of(icebergNS, tableName);
    boolean exists = tableOperationDispatcher.tableExists(context, tableIdentifier);
    if (exists) {
      return HttpResponseBuilder.noContentEntity();
    } else {
      return HttpResponseBuilder.notExistsEntity();
    }
  }

  @PostMapping("{table}/metrics")
  public ResponseEntity<Object> reportTableMetrics(
      @PathVariable(value = "prefix", required = false) String prefix,
      @PathVariable("namespace") String namespace,
      @PathVariable("table") String table,
      @RequestBody ReportMetricsRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String tableName = RESTUtil.decodeString(table);
    LOG.info(
        "Report Iceberg table metrics, catalog: {}, namespace: {}, table: {}",
        catalogName,
        icebergNS,
        tableName);
    boolean accepted = icebergMetricsManager.recordMetric(catalogName, icebergNS, request.report());
    if (accepted) {
      return HttpResponseBuilder.noContentEntity();
    } else {
      throw new RuntimeException("Metrics service unavailable: queue full or service closed");
    }
  }

  @GetMapping("{table}/credentials")
  @IcebergAuthorizationOperation(IcebergOperation.LOAD_TABLE_CREDENTIAL)
  public ResponseEntity<Object> getTableCredentials(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = Entity.EntityType.TABLE) @PathVariable("table") String table,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String tableName = RESTUtil.decodeString(table);
    LOG.info(
        "Get Iceberg table credentials, catalog: {}, namespace: {}, table: {}",
        catalogName,
        icebergNS,
        tableName);
    TableIdentifier tableIdentifier = TableIdentifier.of(icebergNS, tableName);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    LoadCredentialsResponse credentialsResponse =
        tableOperationDispatcher.getTableCredentials(context, tableIdentifier);
    return HttpResponseBuilder.okEntity(credentialsResponse);
  }

  @PostMapping("{table}/plan")
  @IcebergAuthorizationOperation(IcebergOperation.PLAN_TABLE_SCAN)
  public ResponseEntity<Object> planTableScan(
      @AuthorizationMetadata(type = EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = EntityType.TABLE) @PathVariable("table") String table,
      @RequestBody PlanTableScanRequest scanRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String tableName = RESTUtil.decodeString(table);
    LOG.info(
        "Plan table scan, catalog: {}, namespace: {}, table: {}",
        catalogName,
        icebergNS,
        tableName);
    TableIdentifier tableIdentifier = TableIdentifier.of(icebergNS, tableName);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    PlanTableScanResponse scanResponse =
        tableOperationDispatcher.planTableScan(context, tableIdentifier, scanRequest);
    return HttpResponseBuilder.okEntity(scanResponse);
  }

  private String serializeUpdateTableRequest(UpdateTableRequest updateTableRequest) {
    try {
      return icebergObjectMapper.writeValueAsString(updateTableRequest);
    } catch (JsonProcessingException e) {
      LOG.warn("Serialize update table request failed", e);
      return updateTableRequest.toString();
    }
  }

  private boolean isCredentialVending(String accessDelegation) {
    if (StringUtils.isBlank(accessDelegation)) {
      return false;
    }
    if ("vended-credentials".equalsIgnoreCase(accessDelegation)) {
      return true;
    }
    if ("remote-signing".equalsIgnoreCase(accessDelegation)) {
      throw new UnsupportedOperationException(
          "Gravitino IcebergRESTServer doesn't support remote signing");
    } else {
      throw new IllegalArgumentException(
          X_ICEBERG_ACCESS_DELEGATION
              + ": "
              + accessDelegation
              + " is illegal, Iceberg REST spec supports: [vended-credentials,remote-signing], "
              + "Gravitino Iceberg REST server supports: vended-credentials");
    }
  }
}
