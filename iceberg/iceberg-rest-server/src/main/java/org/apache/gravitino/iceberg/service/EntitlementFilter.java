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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.iceberg.service.entitlement.EntitlementSupport;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single decision point on the request path for entitlement row-level filtering. It must run after
 * {@link IcebergAuthenticationFilter} so {@link PrincipalUtils#getCurrentUserName()} resolves.
 *
 * <ul>
 *   <li>Requests for {@code /tables/{t}@entitlement} (raw or {@code %40} encoded): the suffix is
 *       validated against the user's entitlement and stripped before the request continues, so the
 *       underlying table metadata is served as-is. Users without the entitlement are left
 *       untouched and naturally receive a 404, which prevents suffix-based bypasses.
 *   <li>Plain GET/HEAD loads of {@code /tables/{t}}: entitled users are short-circuited with a 404
 *       that mimics a missing table, forcing engines to fall back to {@code /views/{t}} where the
 *       filtered view is synthesized.
 *   <li>Users whose row filter conflicts with their write privilege get an explicit 500 error.
 * </ul>
 */
public class EntitlementFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(EntitlementFilter.class);
  private static final ObjectMapper MAPPER = IcebergObjectMapper.getInstance();

  private static final String V1_PREFIX = "/v1";
  private static final String TABLES_MARKER = "/tables/";
  private static final String NAMESPACES_MARKER = "/namespaces/";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = pathWithinContext(request);
    int tablesIdx = path.indexOf(TABLES_MARKER);
    if (!path.startsWith(V1_PREFIX) || tablesIdx < 0) {
      filterChain.doFilter(request, response);
      return;
    }

    String beforeTables = path.substring(0, tablesIdx);
    String afterTables = path.substring(tablesIdx + TABLES_MARKER.length());
    int slash = afterTables.indexOf('/');
    String tableSegment = slash < 0 ? afterTables : afterTables.substring(0, slash);
    boolean exactTableLoad = slash < 0;
    if (tableSegment.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    String suffix = EntitlementSupport.matchedSuffix(tableSegment);
    if (suffix != null) {
      TableTarget target = resolveTableTarget(beforeTables, tableSegment, suffix.length());
      if (target == null) {
        filterChain.doFilter(request, response);
        return;
      }
      String userName = PrincipalUtils.getCurrentUserName();
      switch (EntitlementSupport.resolveMode(userName, target.resource)) {
        case ENTITLED:
          // Strip the suffix and let the request continue to the real table. Because the
          // disguise-404 branch below only fires on suffix-less GET/HEAD requests, the rewritten
          // request can never be re-disguised: no loop is possible.
          filterChain.doFilter(stripSuffix(request, suffix, path, tableSegment), response);
          return;
        case CONFLICT:
          sendErrorResponse(
              response,
              HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "ServiceFailureException",
              EntitlementSupport.conflictMessage(userName, target.resource));
          return;
        default:
          // No entitlement: leave the request untouched; the table with the suffix does not
          // exist downstream and the client receives a natural 404.
          filterChain.doFilter(request, response);
          return;
      }
    }

    if (exactTableLoad && isGetOrHead(request)) {
      TableTarget target = resolveTableTarget(beforeTables, tableSegment, 0);
      if (target == null) {
        filterChain.doFilter(request, response);
        return;
      }
      String userName = PrincipalUtils.getCurrentUserName();
      switch (EntitlementSupport.resolveMode(userName, target.resource)) {
        case ENTITLED:
          // Disguise the table as non-existent so engines fall back to loading the view,
          // mirroring the JSON shape produced by IcebergExceptionMapper for NoSuchTableException.
          sendErrorResponse(
              response,
              HttpServletResponse.SC_NOT_FOUND,
              "NoSuchTableException",
              String.format(
                  "Table does not exist: %s.%s.%s",
                  target.catalog, target.schema, target.tableName));
          return;
        case CONFLICT:
          sendErrorResponse(
              response,
              HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
              "ServiceFailureException",
              EntitlementSupport.conflictMessage(userName, target.resource));
          return;
        default:
          filterChain.doFilter(request, response);
          return;
      }
    }

    filterChain.doFilter(request, response);
  }

  /** Returns the request URI relative to the context path. */
  private static String pathWithinContext(HttpServletRequest request) {
    String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
    String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
    if (!contextPath.isEmpty() && uri.startsWith(contextPath)) {
      return uri.substring(contextPath.length());
    }
    return uri;
  }

  private static boolean isGetOrHead(HttpServletRequest request) {
    String method = request.getMethod();
    return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
  }

  /**
   * Parses catalog/schema/table from the pre-{@code /tables/} part of the path plus the raw table
   * segment. Returns null when the path cannot be parsed as a table reference.
   */
  private TableTarget resolveTableTarget(
      String beforeTables, String tableSegment, int strippedSuffixLength) {
    try {
      int nsIdx = beforeTables.indexOf(NAMESPACES_MARKER);
      if (nsIdx < 0) {
        return null;
      }
      String namespacePart = beforeTables.substring(nsIdx + NAMESPACES_MARKER.length());
      if (namespacePart.isEmpty()) {
        return null;
      }
      String prefix = "";
      if (nsIdx > V1_PREFIX.length() + 1) {
        prefix = beforeTables.substring(V1_PREFIX.length() + 1, nsIdx);
      }
      String catalog = PrefixResolver.getCatalogName(prefix);
      Namespace namespace = RESTUtil.decodeNamespace(namespacePart);
      String schema = namespace.level(namespace.levels().length - 1);
      String rawName = tableSegment.substring(0, tableSegment.length() - strippedSuffixLength);
      String tableName = RESTUtil.decodeString(rawName);
      return new TableTarget(catalog, schema, tableName);
    } catch (Exception e) {
      LOG.debug("Skipping entitlement handling for unparseable table path: {}", beforeTables, e);
      return null;
    }
  }

  /** Wraps the request so the entitlement suffix is removed from its URI and URL. */
  private HttpServletRequest stripSuffix(
      HttpServletRequest request, String rawSuffix, String path, String tableSegment) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
    int segmentStart = contextPath.length() + path.indexOf(TABLES_MARKER) + TABLES_MARKER.length();
    int suffixStart = segmentStart + tableSegment.length() - rawSuffix.length();
    String newUri = uri.substring(0, suffixStart) + uri.substring(suffixStart + rawSuffix.length());
    return new HttpServletRequestWrapper(request) {
      @Override
      public String getRequestURI() {
        return newUri;
      }

      @Override
      public StringBuffer getRequestURL() {
        StringBuffer original = super.getRequestURL();
        String url = original.toString();
        int cut = url.lastIndexOf(rawSuffix);
        if (cut < 0) {
          return original;
        }
        return new StringBuffer(url.substring(0, cut) + url.substring(cut + rawSuffix.length()));
      }
    };
  }

  private void sendErrorResponse(
      HttpServletResponse response, int status, String type, String message) throws IOException {
    ErrorResponse errorResponse = HttpResponseBuilder.errorResponse(status, type, message);
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try {
      MAPPER.writeValue(response.getWriter(), errorResponse);
    } catch (IOException e) {
      LOG.warn("Failed to write entitlement error response: {}", e.getMessage());
    }
  }

  private static final class TableTarget {
    private final String catalog;
    private final String schema;
    private final String tableName;
    private final IcebergResource resource;

    private TableTarget(String catalog, String schema, String tableName) {
      this.catalog = catalog;
      this.schema = schema;
      this.tableName = tableName;
      this.resource = IcebergResource.ofTable(catalog, schema, tableName);
    }
  }
}
