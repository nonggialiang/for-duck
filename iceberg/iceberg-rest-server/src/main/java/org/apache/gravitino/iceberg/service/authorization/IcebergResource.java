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
package org.apache.gravitino.iceberg.service.authorization;

public class IcebergResource {
  public enum ResourceType { CATALOG, SCHEMA, TABLE, VIEW }

  private final ResourceType type;
  private final String catalogName;
  private final String schemaName;
  private final String resourceName;

  private IcebergResource(
      ResourceType type, String catalogName, String schemaName, String resourceName) {
    this.type = type;
    this.catalogName = catalogName;
    this.schemaName = schemaName;
    this.resourceName = resourceName;
  }

  public static IcebergResource ofCatalog(String catalogName) {
    return new IcebergResource(ResourceType.CATALOG, catalogName, null, null);
  }

  public static IcebergResource ofSchema(String catalogName, String schemaName) {
    return new IcebergResource(ResourceType.SCHEMA, catalogName, schemaName, null);
  }

  public static IcebergResource ofTable(String catalogName, String schemaName, String tableName) {
    return new IcebergResource(ResourceType.TABLE, catalogName, schemaName, tableName);
  }

  public static IcebergResource ofView(String catalogName, String schemaName, String viewName) {
    return new IcebergResource(ResourceType.VIEW, catalogName, schemaName, viewName);
  }

  public ResourceType getType() { return type; }
  public String getCatalogName() { return catalogName; }
  public String getSchemaName() { return schemaName; }
  public String getResourceName() { return resourceName; }

  public String toPath() {
    StringBuilder sb = new StringBuilder();
    sb.append(catalogName);
    if (schemaName != null) {
      sb.append(".").append(schemaName);
      if (resourceName != null) {
        sb.append(".").append(resourceName);
      }
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return String.format("%s:%s", type, toPath());
  }
}
