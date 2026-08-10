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

import org.apache.commons.lang3.StringUtils;

/** Resolves the Iceberg REST catalog name from the URL path prefix. */
public final class PrefixResolver {

  private PrefixResolver() {}

  /**
   * Extracts the catalog name from the prefix path segment. In Spring MVC the path variable does
   * not include a trailing slash, so the value is used as-is. If the prefix is blank, falls back to
   * the server-wide default catalog name.
   */
  public static String getCatalogName(String rawPrefix) {
    if (StringUtils.isBlank(rawPrefix)) {
      return ServerContext.getInstance().getDefaultCatalogName();
    }
    return rawPrefix;
  }
}
