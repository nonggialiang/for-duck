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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;

/**
 * Holds shared server-wide components (authorizer, catalog wrapper manager, default catalog name)
 * that were previously exposed as static fields on {@code RESTService}. Centralizing them here
 * makes the dependency explicit and avoids scattering static mutable state across the codebase.
 */
public class ServerContext {

  private static volatile ServerContext instance;

  private final IcebergAuthorizer authorizer;
  private final IcebergCatalogWrapperManager catalogWrapperManager;
  private final String defaultCatalogName;

  private ServerContext(
      IcebergAuthorizer authorizer,
      IcebergCatalogWrapperManager catalogWrapperManager,
      String defaultCatalogName) {
    this.authorizer = authorizer;
    this.catalogWrapperManager = catalogWrapperManager;
    this.defaultCatalogName = defaultCatalogName;
  }

  /**
   * Initializes the single {@code ServerContext} instance. Must be called once during server
   * startup before any request is served.
   *
   * @throws IllegalStateException if already initialized.
   */
  public static void initialize(
      IcebergAuthorizer authorizer,
      IcebergCatalogWrapperManager catalogWrapperManager,
      String defaultCatalogName) {
    Preconditions.checkState(instance == null, "ServerContext is already initialized");
    instance = new ServerContext(authorizer, catalogWrapperManager, defaultCatalogName);
  }

  /** Returns the single {@code ServerContext} instance. */
  public static ServerContext getInstance() {
    Preconditions.checkState(instance != null, "ServerContext has not been initialized");
    return instance;
  }

  /** Resets the singleton instance. Intended for use in tests only. */
  @VisibleForTesting
  public static void reset() {
    instance = null;
  }

  public IcebergAuthorizer getAuthorizer() {
    return authorizer;
  }

  public IcebergCatalogWrapperManager getCatalogWrapperManager() {
    return catalogWrapperManager;
  }

  public String getDefaultCatalogName() {
    return defaultCatalogName;
  }
}
