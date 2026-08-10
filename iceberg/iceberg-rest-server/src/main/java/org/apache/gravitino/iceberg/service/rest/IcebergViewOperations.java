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
import jakarta.servlet.http.HttpServletRequest;
import org.apache.gravitino.Entity;
import org.apache.gravitino.Entity.EntityType;
import org.apache.gravitino.iceberg.service.HttpResponseBuilder;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.iceberg.service.PrefixResolver;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.annotation.AuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationOperation;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewOperationDispatcher;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    path = {
      "/v1/namespaces/{namespace}/views",
      "/v1/{prefix}/namespaces/{namespace}/views"
    },
    produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergViewOperations {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergViewOperations.class);

  private final ObjectMapper icebergObjectMapper;
  private final IcebergViewOperationDispatcher viewOperationDispatcher;

  public IcebergViewOperations(IcebergViewOperationDispatcher viewOperationDispatcher) {
    this.viewOperationDispatcher = viewOperationDispatcher;
    this.icebergObjectMapper = IcebergObjectMapper.getInstance();
  }

  @GetMapping
  @IcebergAuthorizationOperation(IcebergOperation.LIST_VIEW)
  public ResponseEntity<Object> listView(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info("List Iceberg views, catalog: {}, namespace: {}", catalogName, icebergNS);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    ListTablesResponse listTablesResponse = viewOperationDispatcher.listView(context, icebergNS);
    return HttpResponseBuilder.okEntity(listTablesResponse);
  }

  @PostMapping
  @IcebergAuthorizationOperation(IcebergOperation.CREATE_VIEW)
  public ResponseEntity<Object> createView(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @RequestBody CreateViewRequest createViewRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info(
        "Create Iceberg view, catalog: {}, namespace: {}, createViewRequest: {}",
        catalogName,
        icebergNS,
        createViewRequest);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    LoadViewResponse loadViewResponse =
        viewOperationDispatcher.createView(context, icebergNS, createViewRequest);
    return HttpResponseBuilder.okEntity(loadViewResponse);
  }

  @GetMapping("{view}")
  @IcebergAuthorizationOperation(IcebergOperation.LOAD_VIEW)
  public ResponseEntity<Object> loadView(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = EntityType.VIEW) @PathVariable("view") String view,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String viewName = RESTUtil.decodeString(view);
    LOG.info(
        "Load Iceberg view, catalog: {}, namespace: {}, view: {}",
        catalogName,
        icebergNS,
        viewName);
    TableIdentifier viewIdentifier = TableIdentifier.of(icebergNS, viewName);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    LoadViewResponse loadViewResponse =
        viewOperationDispatcher.loadView(context, viewIdentifier);
    return HttpResponseBuilder.okEntity(loadViewResponse);
  }

  @PostMapping("{view}")
  @IcebergAuthorizationOperation(IcebergOperation.REPLACE_VIEW)
  public ResponseEntity<Object> replaceView(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = EntityType.VIEW) @PathVariable("view") String view,
      @RequestBody UpdateTableRequest replaceViewRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String viewName = RESTUtil.decodeString(view);
    LOG.info(
        "Replace Iceberg view, catalog: {}, namespace: {}, view: {}, replaceViewRequest: {}",
        catalogName,
        icebergNS,
        viewName,
        serializeReplaceViewRequest(replaceViewRequest));
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    TableIdentifier viewIdentifier = TableIdentifier.of(icebergNS, viewName);
    LoadViewResponse loadViewResponse =
        viewOperationDispatcher.replaceView(context, viewIdentifier, replaceViewRequest);
    return HttpResponseBuilder.okEntity(loadViewResponse);
  }

  @DeleteMapping("{view}")
  @IcebergAuthorizationOperation(IcebergOperation.DROP_VIEW)
  public ResponseEntity<Object> dropView(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = EntityType.VIEW) @PathVariable("view") String view,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String viewName = RESTUtil.decodeString(view);
    LOG.info(
        "Drop Iceberg view, catalog: {}, namespace: {}, view: {}",
        catalogName,
        icebergNS,
        viewName);
    TableIdentifier viewIdentifier = TableIdentifier.of(icebergNS, viewName);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    viewOperationDispatcher.dropView(context, viewIdentifier);
    return HttpResponseBuilder.noContentEntity();
  }

  @RequestMapping(value = "{view}", method = RequestMethod.HEAD)
  @IcebergAuthorizationOperation(IcebergOperation.VIEW_EXISTS)
  public ResponseEntity<Object> viewExists(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = EntityType.SCHEMA) @PathVariable("namespace") String namespace,
      @AuthorizationMetadata(type = EntityType.VIEW) @PathVariable("view") String view,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    String viewName = RESTUtil.decodeString(view);
    LOG.info(
        "Check Iceberg view exists, catalog: {}, namespace: {}, view: {}",
        catalogName,
        icebergNS,
        viewName);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    TableIdentifier viewIdentifier = TableIdentifier.of(icebergNS, viewName);
    boolean exists = viewOperationDispatcher.viewExists(context, viewIdentifier);
    if (exists) {
      return HttpResponseBuilder.noContentEntity();
    } else {
      return HttpResponseBuilder.notExistsEntity();
    }
  }

  private String serializeReplaceViewRequest(UpdateTableRequest replaceViewRequest) {
    try {
      return icebergObjectMapper.writeValueAsString(replaceViewRequest);
    } catch (JsonProcessingException e) {
      LOG.warn("Serialize update view request failed", e);
      return replaceViewRequest.toString();
    }
  }
}
