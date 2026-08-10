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
package org.apache.gravitino.iceberg.common.utils;

import com.google.common.annotations.VisibleForTesting;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergCatalogBackend;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.exceptions.ConnectionFailedException;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.jdbc.JdbcCatalog;
import org.apache.iceberg.jdbc.JdbcCatalogWithMetadataLocationSupport;
import org.apache.iceberg.jdbc.UncheckedSQLException;
import org.apache.iceberg.memory.MemoryCatalogWithMetadataLocationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergCatalogUtil {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergCatalogUtil.class);

  private static InMemoryCatalog loadMemoryCatalog(IcebergConfig icebergConfig) {
    String icebergCatalogName = icebergConfig.getCatalogBackendName();
    InMemoryCatalog memoryCatalog = new MemoryCatalogWithMetadataLocationSupport();
    Map<String, String> resultProperties = icebergConfig.getIcebergCatalogProperties();
    if (!resultProperties.containsKey(IcebergConstants.WAREHOUSE)) {
      resultProperties.put(IcebergConstants.WAREHOUSE, "/tmp");
    }
    memoryCatalog.initialize(icebergCatalogName, resultProperties);
    return memoryCatalog;
  }

  @SuppressWarnings("FormatStringAnnotation")
  private static JdbcCatalog loadJdbcCatalog(IcebergConfig icebergConfig) {
    String driverClassName = icebergConfig.getJdbcDriver();
    String icebergCatalogName = icebergConfig.getCatalogBackendName();

    Map<String, String> properties = icebergConfig.getIcebergCatalogProperties();
    try {
      Class.forName(driverClassName);
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException("Couldn't load jdbc driver " + driverClassName);
    }
    JdbcCatalog jdbcCatalog =
        new JdbcCatalogWithMetadataLocationSupport(
            icebergConfig.get(IcebergConfig.JDBC_INIT_TABLES));

    properties.putIfAbsent(IcebergConstants.ICEBERG_JDBC_SCHEMA_VERSION, "V1");

    Configuration hdfsConfiguration = new Configuration();
    properties.forEach(hdfsConfiguration::set);
    jdbcCatalog.setConf(hdfsConfiguration);
    try {
      jdbcCatalog.initialize(icebergCatalogName, properties);
    } catch (UncheckedSQLException e) {
      if (e.getCause() instanceof SQLException
          && e.getCause().getMessage().contains("Access denied")) {
        throw new ConnectionFailedException(e, e.getMessage());
      }
      throw e;
    }
    return jdbcCatalog;
  }

  @VisibleForTesting
  static Catalog loadCatalogBackend(String catalogType) {
    return loadCatalogBackend(
        IcebergCatalogBackend.valueOf(catalogType.toUpperCase(Locale.ROOT)),
        new IcebergConfig(Collections.emptyMap()));
  }

  public static Catalog loadCatalogBackend(
      IcebergCatalogBackend catalogBackend, IcebergConfig icebergConfig) {
    LOG.info("Load catalog backend of {}", catalogBackend);
    switch (catalogBackend) {
      case MEMORY:
        return loadMemoryCatalog(icebergConfig);
      case JDBC:
        return loadJdbcCatalog(icebergConfig);
      default:
        throw new UnsupportedOperationException(
            catalogBackend + " catalog is not supported, supported: [memory, jdbc]");
    }
  }

  private IcebergCatalogUtil() {}
}
