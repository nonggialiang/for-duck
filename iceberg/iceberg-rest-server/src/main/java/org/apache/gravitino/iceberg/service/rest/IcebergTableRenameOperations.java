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

import jakarta.servlet.http.HttpServletRequest;
import org.apache.gravitino.Entity;
import org.apache.gravitino.iceberg.service.HttpResponseBuilder;
import org.apache.gravitino.iceberg.service.PrefixResolver;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.annotation.AuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationMetadata.RequestType;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationOperation;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationDispatcher;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
    path = {"/v1/tables/rename", "/v1/{prefix}/tables/rename"},
    produces = MediaType.APPLICATION_JSON_VALUE)
public class IcebergTableRenameOperations {
  private static final Logger LOG = LoggerFactory.getLogger(IcebergTableRenameOperations.class);

  private final IcebergTableOperationDispatcher tableOperationDispatcher;

  public IcebergTableRenameOperations(IcebergTableOperationDispatcher tableOperationDispatcher) {
    this.tableOperationDispatcher = tableOperationDispatcher;
  }

  @PostMapping
  @IcebergAuthorizationOperation(IcebergOperation.RENAME_TABLE)
  public ResponseEntity<Object> renameTable(
      @AuthorizationMetadata(type = Entity.EntityType.CATALOG)
          @PathVariable(value = "prefix", required = false)
          String prefix,
      @IcebergAuthorizationMetadata(type = RequestType.RENAME_TABLE)
          @RequestBody
          RenameTableRequest renameTableRequest,
      HttpServletRequest request) {
    String catalogName = PrefixResolver.getCatalogName(prefix);
    LOG.info(
        "Rename Iceberg tables, catalog: {}, source: {}, destination: {}.",
        catalogName,
        renameTableRequest.source(),
        renameTableRequest.destination());
    IcebergRequestContext context = new IcebergRequestContext(request, catalogName);
    tableOperationDispatcher.renameTable(context, renameTableRequest);
    return HttpResponseBuilder.noContentEntity();
  }
}
