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

import com.google.common.collect.ImmutableMap;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.responses.ImmutableLoadViewResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.view.ImmutableSQLViewRepresentation;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;

/**
 * Central decision hub for entitlement row-level filtering. Entitlement redirects read-only users
 * that have a configured row filter away from the raw table and serves them a synthesized filtered
 * view instead; all request-flow decisions funnel through {@link #resolveMode(String,
 * IcebergResource)}.
 */
public final class EntitlementSupport {

  public static final String SUFFIX = "@entitlement";
  public static final String ENCODED_SUFFIX = "%40entitlement";

  public static final String ENTITLEMENT_VIEW_PROPERTY = "gravitino.entitlement-view";

  private static final String DEFAULT_DIALECT_NAME = SparkEntitlementDialect.NAME;
  private static final Map<String, EntitlementDialect> DIALECTS =
      ImmutableMap.of(SparkEntitlementDialect.NAME, new SparkEntitlementDialect());

  private static volatile EntitlementDialect dialect = DIALECTS.get(DEFAULT_DIALECT_NAME);

  private EntitlementSupport() {}

  /** How a user's request to a table resource must be handled. */
  public enum Mode {
    /** No entitlement applies; the request follows the normal authorization path. */
    NORMAL,
    /** The user has a row filter and no write privilege; serve the filtered view instead. */
    ENTITLED,
    /** The user has both a row filter and a write privilege; reject with an explicit error. */
    CONFLICT
  }

  /** Selects the view SQL dialect at server startup; unknown names fail fast. */
  public static void configureDialect(String dialectName) {
    EntitlementDialect configured =
        DIALECTS.get(dialectName == null ? null : dialectName.toLowerCase(Locale.ROOT));
    if (configured == null) {
      throw new IllegalArgumentException(
          String.format("Unknown entitlement dialect: '%s'", dialectName));
    }
    dialect = configured;
  }

  public static EntitlementDialect getDialect() {
    return dialect;
  }

  /** Returns whether the raw (possibly percent-encoded) path segment carries the suffix. */
  public static boolean hasSuffix(String rawSegment) {
    return matchedSuffix(rawSegment) != null;
  }

  /**
   * Returns the raw suffix ("@entitlement" or "%40entitlement", case as written) that terminates
   * the segment, or null when absent. Matching is case-insensitive.
   */
  @Nullable
  public static String matchedSuffix(String rawSegment) {
    if (rawSegment == null || rawSegment.isEmpty()) {
      return null;
    }
    String lower = rawSegment.toLowerCase(Locale.ROOT);
    if (lower.endsWith(SUFFIX)) {
      return rawSegment.substring(rawSegment.length() - SUFFIX.length());
    }
    if (lower.endsWith(ENCODED_SUFFIX)) {
      return rawSegment.substring(rawSegment.length() - ENCODED_SUFFIX.length());
    }
    return null;
  }

  /** Strips the suffix from the raw segment, preserving any other percent-encoding as written. */
  public static String stripSuffix(String rawSegment) {
    String suffix = matchedSuffix(rawSegment);
    if (suffix == null) {
      return rawSegment;
    }
    return rawSegment.substring(0, rawSegment.length() - suffix.length());
  }

  /**
   * Resolves the entitlement mode for a user on a table resource: NORMAL when no row filter
   * applies, CONFLICT when both a row filter and the UPDATE_TABLE privilege exist, ENTITLED
   * otherwise (row filter without write privilege).
   */
  public static Mode resolveMode(String userName, IcebergResource resource) {
    IcebergAuthorizer authorizer = getAuthorizer();
    if (authorizer == null) {
      return Mode.NORMAL;
    }
    String rowFilter = authorizer.getRowFilter(userName, resource);
    if (rowFilter == null) {
      return Mode.NORMAL;
    }
    return authorizer.checkOperation(userName, IcebergOperation.UPDATE_TABLE, resource)
        ? Mode.CONFLICT
        : Mode.ENTITLED;
  }

  /** Returns the row-filter SQL for the user on the resource, null-safe on ServerContext. */
  @Nullable
  public static String getRowFilter(String userName, IcebergResource resource) {
    IcebergAuthorizer authorizer = getAuthorizer();
    return authorizer == null ? null : authorizer.getRowFilter(userName, resource);
  }

  /** Shared error message for users whose row filter conflicts with their write privilege. */
  public static String conflictMessage(String userName, IcebergResource resource) {
    return String.format(
        "Entitlement row filter conflicts with the write privilege of user '%s' on table '%s'; "
            + "ask an administrator to remove one of them",
        userName,
        resource.toPath());
  }

  /**
   * Builds a synthesized view response that selects from the suffixed physical table with the
   * user's row filter applied. The view UUID is deterministic per (user, table) so repeated loads
   * return a stable identifier.
   */
  public static LoadViewResponse buildLoadViewResponse(
      String catalog,
      String schema,
      String table,
      TableMetadata tableMetadata,
      String rowFilter,
      String userName) {
    EntitlementDialect currentDialect = dialect;
    String sql = currentDialect.buildSelectSql(catalog, schema, table + SUFFIX, rowFilter);
    SQLViewRepresentation sqlRepresentation =
        ImmutableSQLViewRepresentation.builder()
            .dialect(currentDialect.name())
            .sql(sql)
            .build();
    ViewVersion viewVersion =
        ImmutableViewVersion.builder()
            .versionId(1)
            .timestampMillis(System.currentTimeMillis())
            .schemaId(tableMetadata.currentSchemaId())
            .defaultNamespace(Namespace.of(schema))
            .defaultCatalog(catalog)
            .addRepresentations(sqlRepresentation)
            .build();
    ViewMetadata viewMetadata =
        ViewMetadata.builder()
            .assignUUID(deterministicViewUuid(userName, catalog, schema, table))
            .setLocation(tableMetadata.location() + "-entitlement-view")
            .setCurrentVersion(viewVersion, tableMetadata.schema())
            .setProperties(ImmutableMap.of(ENTITLEMENT_VIEW_PROPERTY, "true"))
            .build();
    // The view has no physical metadata file; report a deterministic virtual location derived
    // from the view location and UUID so clients can cache the response as usual.
    String metadataLocation =
        String.format(
            "%s/metadata/%s-%s.metadata.json",
            viewMetadata.location(), viewMetadata.currentVersionId(), viewMetadata.uuid());
    return ImmutableLoadViewResponse.builder()
        .metadata(viewMetadata)
        .metadataLocation(metadataLocation)
        .build();
  }

  private static String deterministicViewUuid(
      String userName, String catalog, String schema, String table) {
    String key = String.join("/", userName, catalog, schema, table);
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
  }

  @Nullable
  private static IcebergAuthorizer getAuthorizer() {
    try {
      return ServerContext.getInstance().getAuthorizer();
    } catch (IllegalStateException e) {
      return null;
    }
  }
}
