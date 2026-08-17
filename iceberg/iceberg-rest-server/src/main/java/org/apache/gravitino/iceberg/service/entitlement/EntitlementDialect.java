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

package org.apache.gravitino.iceberg.service.entitlement;

/**
 * Generates the SELECT statement of a synthesized entitlement view for a specific query engine
 * dialect. Implementations are registered in {@link EntitlementSupport#configureDialect(String)}
 * and selected through {@code authorization.entitlement.dialect}.
 */
public interface EntitlementDialect {

  /** Returns the unique dialect name used in configuration and view representations. */
  String name();

  /**
   * Builds the SQL of the synthesized entitlement view.
   *
   * @param catalog the catalog name of the underlying table
   * @param schema the schema (namespace) of the underlying table
   * @param suffixedTable the table name already suffixed with {@code @entitlement}
   * @param rowFilter the row-filter SQL expression to apply
   * @return the view SQL referencing the suffixed table and filtering rows
   */
  String buildSelectSql(String catalog, String schema, String suffixedTable, String rowFilter);
}
