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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.credential.CatalogCredentialManager;
import org.apache.gravitino.credential.Credential;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.credential.CredentialPropertyUtils;
import org.apache.gravitino.credential.PathBasedCredentialContext;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.gravitino.utils.MapUtils;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.ServiceUnavailableException;
import org.apache.iceberg.rest.responses.ImmutableLoadCredentialsResponse;
import org.apache.iceberg.rest.responses.LoadCredentialsResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles credential generation and injection for Iceberg REST table operations. Extracted from
 * {@link CatalogWrapperForREST} to isolate credential-vending concerns.
 */
public class CredentialVendingService {

  private static final Logger LOG = LoggerFactory.getLogger(CredentialVendingService.class);

  private static final Set<String> CATALOG_PROPERTIES_TO_CLIENT_KEYS =
      ImmutableSet.of(
          IcebergConstants.IO_IMPL,
          IcebergConstants.AWS_S3_REGION,
          IcebergConstants.ICEBERG_S3_ENDPOINT,
          IcebergConstants.ICEBERG_S3_PATH_STYLE_ACCESS);

  @SuppressWarnings("deprecation")
  private static final Map<String, String> DEPRECATED_PROPERTIES =
      ImmutableMap.of(
          CredentialConstants.CREDENTIAL_PROVIDER_TYPE,
          CredentialConstants.CREDENTIAL_PROVIDERS);

  private final CatalogCredentialManager catalogCredentialManager;
  private final Map<String, String> catalogConfigToClients;

  public CredentialVendingService(String catalogName, IcebergConfig config) {
    this.catalogConfigToClients =
        MapUtils.getFilteredMap(
            config.getIcebergCatalogProperties(),
            key -> CATALOG_PROPERTIES_TO_CLIENT_KEYS.contains(key));
    Map<String, String> catalogProperties =
        checkForCompatibility(config.getAllConfig(), DEPRECATED_PROPERTIES);
    this.catalogCredentialManager = new CatalogCredentialManager(catalogName, catalogProperties);
  }

  public LoadTableResponse injectCredential(
      TableIdentifier tableIdentifier,
      LoadTableResponse loadTableResponse,
      CredentialPrivilege privilege) {
    final Credential credential = getCredential(loadTableResponse, privilege);
    LOG.info(
        "Generate credential: {} for Iceberg table: {}",
        credential.credentialType(),
        tableIdentifier);
    Map<String, String> credentialConfig = CredentialPropertyUtils.toIcebergProperties(credential);
    return LoadTableResponse.builder()
        .withTableMetadata(loadTableResponse.tableMetadata())
        .addAllConfig(loadTableResponse.config())
        .addAllConfig(getCatalogConfigToClient())
        .addAllConfig(credentialConfig)
        .build();
  }

  public LoadCredentialsResponse getTableCredentials(
      TableIdentifier identifier, CredentialPrivilege privilege, IcebergCatalogWrapper catalogWrapper) {
    try {
      LoadTableResponse loadTableResponse = catalogWrapper.loadTable(identifier);
      Credential credential = getCredential(loadTableResponse, privilege);
      org.apache.iceberg.rest.credentials.Credential icebergCredential =
          new org.apache.iceberg.rest.credentials.Credential() {
            @Override
            public String prefix() {
              return "";
            }

            @Override
            public Map<String, String> config() {
              return CredentialPropertyUtils.toIcebergProperties(credential);
            }

            @Override
            public void validate() {}
          };
      return ImmutableLoadCredentialsResponse.builder().addCredentials(icebergCredential).build();
    } catch (ServiceUnavailableException e) {
      LOG.warn("Service unavailable when loading table credentials for table: {}", identifier, e);
      return ImmutableLoadCredentialsResponse.builder().build();
    }
  }

  public boolean shouldGenerateCredential(
      LoadTableResponse loadTableResponse, boolean requestCredential) {
    if (!requestCredential) {
      return false;
    }
    validateCredentialLocation(loadTableResponse.tableMetadata().location());
    return !isLocalOrHdfsTable(loadTableResponse.tableMetadata());
  }

  public Map<String, String> getCatalogConfigToClient() {
    return catalogConfigToClients;
  }

  private Credential getCredential(
      LoadTableResponse loadTableResponse, CredentialPrivilege privilege) {
    TableMetadata tableMetadata = loadTableResponse.tableMetadata();
    String[] path =
        Stream.of(
                tableMetadata.location(),
                tableMetadata.property(TableProperties.WRITE_DATA_LOCATION, ""),
                tableMetadata.property(TableProperties.WRITE_METADATA_LOCATION, ""))
            .filter(StringUtils::isNotBlank)
            .toArray(String[]::new);

    PathBasedCredentialContext context =
        privilege == CredentialPrivilege.WRITE
            ? new PathBasedCredentialContext(
                PrincipalUtils.getCurrentUserName(),
                ImmutableSet.copyOf(path),
                Collections.emptySet())
            : new PathBasedCredentialContext(
                PrincipalUtils.getCurrentUserName(),
                Collections.emptySet(),
                ImmutableSet.copyOf(path));
    Credential credential = catalogCredentialManager.getCredential(context);
    if (credential == null) {
      throw new ServiceUnavailableException("Couldn't generate credential, %s", context);
    }
    return credential;
  }

  private boolean isLocalOrHdfsTable(TableMetadata tableMetadata) {
    return isLocalOrHdfsLocation(tableMetadata.location());
  }

  @VisibleForTesting
  static void validateCredentialLocation(String location) {
    if (StringUtils.isBlank(location)) {
      throw new IllegalArgumentException(
          "Table location cannot be null or blank when requesting credentials");
    }
  }

  @VisibleForTesting
  static boolean isLocalOrHdfsLocation(String location) {
    if (StringUtils.isBlank(location)) {
      return false;
    }
    URI uri;
    try {
      uri = URI.create(location);
    } catch (IllegalArgumentException e) {
      return false;
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      return true;
    }
    return "file".equalsIgnoreCase(scheme) || "hdfs".equalsIgnoreCase(scheme);
  }

  @VisibleForTesting
  static Map<String, String> checkForCompatibility(
      Map<String, String> properties, Map<String, String> deprecatedProperties) {
    Map<String, String> newProperties = new HashMap<>(properties);
    deprecatedProperties.forEach(
        (deprecatedProperty, newProperty) ->
            replaceDeprecatedProperties(newProperties, deprecatedProperty, newProperty));
    return newProperties;
  }

  private static void replaceDeprecatedProperties(
      Map<String, String> properties, String deprecatedProperty, String newProperty) {
    String deprecatedValue = properties.get(deprecatedProperty);
    String newValue = properties.get(newProperty);
    if (StringUtils.isNotBlank(deprecatedValue) && StringUtils.isNotBlank(newValue)) {
      throw new IllegalArgumentException(
          String.format("Should not set both %s and %s", deprecatedProperty, newProperty));
    }
    if (StringUtils.isNotBlank(deprecatedValue)) {
      LOG.warn("{} is deprecated, please use {} instead.", deprecatedProperty, newProperty);
      properties.remove(deprecatedProperty);
      properties.put(newProperty, deprecatedValue);
    }
  }

  public void close() throws Exception {
    if (catalogCredentialManager != null) {
      catalogCredentialManager.close();
    }
  }
}
