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

import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.HttpResponseBuilder;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.iceberg.rest.Endpoint;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergConfigOperations {

  private final IcebergCatalogWrapperManager catalogWrapperManager;

  // TODO: Iceberg 1.10.1's Endpoint.V1_SUBMIT_TABLE_SCAN_PLAN uses a broken path that is missing
  // the namespaces/{namespace} segment (fixed in apache/iceberg#14120, targeting 1.11.x).
  // We override it here with the correct namespace-scoped path until we upgrade.
  private static final Endpoint V1_SUBMIT_TABLE_SCAN_PLAN =
      Endpoint.create("POST", "/v1/{prefix}/namespaces/{namespace}/tables/{table}/plan");

  private static final List<Endpoint> DEFAULT_ENDPOINTS =
      ImmutableList.<Endpoint>builder()
          .add(Endpoint.V1_LIST_NAMESPACES)
          .add(Endpoint.V1_LOAD_NAMESPACE)
          .add(Endpoint.V1_CREATE_NAMESPACE)
          .add(Endpoint.V1_UPDATE_NAMESPACE)
          .add(Endpoint.V1_DELETE_NAMESPACE)
          .add(Endpoint.V1_NAMESPACE_EXISTS)
          .add(Endpoint.V1_LIST_TABLES)
          .add(Endpoint.V1_LOAD_TABLE)
          .add(Endpoint.V1_CREATE_TABLE)
          .add(Endpoint.V1_UPDATE_TABLE)
          .add(Endpoint.V1_DELETE_TABLE)
          .add(Endpoint.V1_RENAME_TABLE)
          .add(Endpoint.V1_TABLE_EXISTS)
          .add(Endpoint.V1_REGISTER_TABLE)
          .add(Endpoint.V1_REPORT_METRICS)
          .add(Endpoint.V1_TABLE_CREDENTIALS)
          .add(V1_SUBMIT_TABLE_SCAN_PLAN)
          .build();

  private static final List<Endpoint> DEFAULT_VIEW_ENDPOINTS =
      ImmutableList.<Endpoint>builder()
          .add(Endpoint.V1_LIST_VIEWS)
          .add(Endpoint.V1_LOAD_VIEW)
          .add(Endpoint.V1_CREATE_VIEW)
          .add(Endpoint.V1_UPDATE_VIEW)
          .add(Endpoint.V1_DELETE_VIEW)
          .add(Endpoint.V1_RENAME_VIEW)
          .add(Endpoint.V1_VIEW_EXISTS)
          .build();

  public IcebergConfigOperations(IcebergCatalogWrapperManager catalogWrapperManager) {
    this.catalogWrapperManager = catalogWrapperManager;
  }

  @GetMapping
  public ResponseEntity<Object> getConfig(
      @RequestParam(name = "warehouse", defaultValue = "") String warehouse) {
    String catalogName = getCatalogName(warehouse);
    boolean supportsView = supportsViewOperations(catalogName);
    ConfigResponse.Builder builder = ConfigResponse.builder();
    builder.withDefaults(getDefaultConfig(catalogName)).withEndpoints(getEndpoints(supportsView));
    if (StringUtils.isNotBlank(warehouse)) {
      builder.withDefault("prefix", warehouse);
    }
    return HttpResponseBuilder.okEntity(builder.build());
  }

  private List<Endpoint> getEndpoints(boolean supportsViewOperations) {
    if (!supportsViewOperations) {
      return DEFAULT_ENDPOINTS;
    }
    return Stream.concat(DEFAULT_ENDPOINTS.stream(), DEFAULT_VIEW_ENDPOINTS.stream())
        .collect(Collectors.toList());
  }

  private Map<String, String> getCatalogConfig(String catalogName) {
    Map<String, String> configs = new HashMap<>();
    CatalogWrapperForREST catalogWrapper = getCatalogWrapper(catalogName);
    configs.putAll(catalogWrapper.getCatalogConfigToClient());
    return configs;
  }

  private String getCatalogName(String warehouse) {
    if (StringUtils.isBlank(warehouse)) {
      return IcebergConstants.ICEBERG_REST_DEFAULT_CATALOG;
    } else {
      return warehouse;
    }
  }

  private boolean supportsViewOperations(String catalogName) {
    CatalogWrapperForREST catalogWrapperForREST = getCatalogWrapper(catalogName);
    return catalogWrapperForREST.supportsViewOperations();
  }

  private CatalogWrapperForREST getCatalogWrapper(String catalogName) {
    return catalogWrapperManager.getCatalogWrapper(catalogName);
  }

  protected Map<String, String> getDefaultConfig(String catalogName) {
    return getCatalogConfig(catalogName);
  }
}
