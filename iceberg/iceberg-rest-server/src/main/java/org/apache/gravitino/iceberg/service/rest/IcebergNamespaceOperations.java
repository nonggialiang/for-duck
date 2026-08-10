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
import org.apache.gravitino.iceberg.service.HttpResponseBuilder;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.iceberg.service.PrefixResolver;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.annotation.AuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationOperation;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationDispatcher;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    path = {"/v1/namespaces", "/v1/{prefix}/namespaces"},
    produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergNamespaceOperations {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergNamespaceOperations.class);

  private final ObjectMapper icebergObjectMapper;
  private final IcebergNamespaceOperationDispatcher namespaceOperationDispatcher;

  public IcebergNamespaceOperations(
      IcebergNamespaceOperationDispatcher namespaceOperationDispatcher) {
    this.namespaceOperationDispatcher = namespaceOperationDispatcher;
    this.icebergObjectMapper = IcebergObjectMapper.getInstance();
  }

  @GetMapping
  @IcebergAuthorizationOperation(IcebergOperation.LIST_NAMESPACE)
  public ResponseEntity<Object> listNamespaces(
      @RequestParam(name = "parent", defaultValue = "") String parent,
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace parentNamespace =
        parent.isEmpty() ? Namespace.empty() : RESTUtil.decodeNamespace(parent);
    LOG.info(
        "List Iceberg namespaces, catalog: {}, parentNamespace: {}", catalogName, parentNamespace);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    ListNamespacesResponse response =
        namespaceOperationDispatcher.listNamespaces(context, parentNamespace);
    return HttpResponseBuilder.okEntity(response);
  }

  @GetMapping("{namespace}")
  @IcebergAuthorizationOperation(IcebergOperation.LOAD_NAMESPACE)
  public ResponseEntity<Object> loadNamespace(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) @PathVariable("namespace")
          String namespace,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info("Load Iceberg namespace, catalog: {}, namespace: {}", catalogName, icebergNS);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    GetNamespaceResponse getNamespaceResponse =
        namespaceOperationDispatcher.loadNamespace(context, icebergNS);
    return HttpResponseBuilder.okEntity(getNamespaceResponse);
  }

  @RequestMapping(value = "{namespace}", method = RequestMethod.HEAD)
  @IcebergAuthorizationOperation(IcebergOperation.NAMESPACE_EXISTS)
  public ResponseEntity<Object> namespaceExists(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) @PathVariable("namespace")
          String namespace,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info("Check Iceberg namespace exists, catalog: {}, namespace: {}", catalogName, icebergNS);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    boolean exists = namespaceOperationDispatcher.namespaceExists(context, icebergNS);
    if (exists) {
      return HttpResponseBuilder.noContentEntity();
    } else {
      return HttpResponseBuilder.notExistsEntity();
    }
  }

  @DeleteMapping("{namespace}")
  @IcebergAuthorizationOperation(IcebergOperation.DROP_NAMESPACE)
  public ResponseEntity<Object> dropNamespace(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) @PathVariable("namespace")
          String namespace,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info("Drop Iceberg namespace, catalog: {}, namespace: {}", catalogName, icebergNS);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    namespaceOperationDispatcher.dropNamespace(context, icebergNS);
    return HttpResponseBuilder.noContentEntity();
  }

  @PostMapping
  @IcebergAuthorizationOperation(IcebergOperation.CREATE_NAMESPACE)
  public ResponseEntity<Object> createNamespace(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @RequestBody CreateNamespaceRequest createNamespaceRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    LOG.info(
        "Create Iceberg namespace, catalog: {}, createNamespaceRequest: {}",
        catalogName,
        createNamespaceRequest);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    CreateNamespaceResponse createNamespaceResponse =
        namespaceOperationDispatcher.createNamespace(context, createNamespaceRequest);
    return HttpResponseBuilder.okEntity(createNamespaceResponse);
  }

  @PostMapping("{namespace}/properties")
  @IcebergAuthorizationOperation(IcebergOperation.UPDATE_NAMESPACE)
  public ResponseEntity<Object> updateNamespace(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) @PathVariable("namespace")
          String namespace,
      @RequestBody UpdateNamespacePropertiesRequest updateNamespacePropertiesRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info(
        "Update Iceberg namespace, catalog: {}, namespace: {}, updateNamespacePropertiesRequest: {}",
        catalogName,
        icebergNS,
        serializeUpdateNamespacePropertiesRequest(updateNamespacePropertiesRequest));
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    UpdateNamespacePropertiesResponse updateNamespacePropertiesResponse =
        namespaceOperationDispatcher.updateNamespace(
            context, icebergNS, updateNamespacePropertiesRequest);
    return HttpResponseBuilder.okEntity(updateNamespacePropertiesResponse);
  }

  @PostMapping("{namespace}/register")
  @IcebergAuthorizationOperation(IcebergOperation.REGISTER_TABLE)
  public ResponseEntity<Object> registerTable(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @AuthorizationMetadata(type = Entity.EntityType.SCHEMA) @PathVariable("namespace")
          String namespace,
      @RequestBody RegisterTableRequest registerTableRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    Namespace icebergNS = RESTUtil.decodeNamespace(namespace);
    LOG.info(
        "Register Iceberg table, catalog: {}, namespace: {}, registerTableRequest: {}",
        catalogName,
        icebergNS,
        registerTableRequest);
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    LoadTableResponse loadTableResponse =
        namespaceOperationDispatcher.registerTable(context, icebergNS, registerTableRequest);
    return HttpResponseBuilder.okEntity(loadTableResponse);
  }

  private String serializeUpdateNamespacePropertiesRequest(
      UpdateNamespacePropertiesRequest updateNamespacePropertiesRequest) {
    try {
      return icebergObjectMapper.writeValueAsString(updateNamespacePropertiesRequest);
    } catch (JsonProcessingException e) {
      LOG.warn("Serialize update namespace properties failed", e);
      return updateNamespacePropertiesRequest.toString();
    }
  }
}
