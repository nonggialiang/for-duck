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
package org.apache.gravitino.iceberg.service.spring;

import com.google.common.collect.Maps;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.credential.CredentialConstants;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.allowall.AllowAllAuthorizer;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationExecutor;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationExecutor;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewOperationExecutor;
import org.apache.gravitino.iceberg.service.extension.DummyCredentialProvider;
import org.apache.gravitino.iceberg.service.metrics.IcebergMetricsManager;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.StaticIcebergConfigProvider;
import org.apache.gravitino.iceberg.service.rest.IcebergCatalogWrapperManagerForTest;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTUtil;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;

/**
 * Test Spring Boot application that wires test-specific beans for the Iceberg REST server. Replaces
 * the old {@code IcebergRestTestUtil} + JerseyTest pattern. Excludes both the production {@code
 * IcebergBeanConfig} and {@code IcebergRestServerApplication} to avoid conflicts. This class is
 * explicitly referenced in {@code @SpringBootTest(classes = IcebergTestApp.class)} so Spring does
 * not search for other @SpringBootConfiguration classes.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = "org.apache.gravitino.iceberg",
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = {
                IcebergBeanConfig.class,
                IcebergRestServerApplication.class,
                IcebergBeanConfig.ServerContextInitializer.class
            }))
@TestConfiguration
public class IcebergTestApp {

  public static final String PREFIX = "prefix_gravitino";
  public static final Namespace TEST_NAMESPACE_NAME = Namespace.of("gravitino-test");
  public static final Namespace TEST_NESTED_NAMESPACE_NAME =
      Namespace.of("gravitino-test-2", "nested");
  public static final String VIEW_PATH =
      "/v1/namespaces/" + RESTUtil.encodeNamespace(TEST_NAMESPACE_NAME) + "/views";
  public static final String NAMESPACE_PATH = "/v1/namespaces";
  public static final String CONFIG_PATH = "/v1/config";
  public static final String RENAME_TABLE_PATH = "/v1/tables/rename";
  public static final String RENAME_VIEW_PATH = "/v1/views/rename";

  @Bean
  public IcebergConfig icebergTestConfig() {
    return new IcebergConfig();
  }

  @Bean
  @Primary
  public com.fasterxml.jackson.databind.ObjectMapper icebergObjectMapper() {
    return org.apache.gravitino.iceberg.service.IcebergObjectMapper.getInstance();
  }

  @Bean
  @Primary
  public IcebergConfigProvider icebergTestConfigProvider() {
    Map<String, String> catalogConf = Maps.newHashMap();
    String catalogConfigPrefix = "catalog." + PREFIX;
    catalogConf.put(
        IcebergConstants.ICEBERG_REST_CATALOG_CONFIG_PROVIDER,
        StaticIcebergConfigProvider.class.getName());
    catalogConf.put(String.format("%s.catalog-backend-name", catalogConfigPrefix), PREFIX);
    catalogConf.put(
        CredentialConstants.CREDENTIAL_PROVIDERS, DummyCredentialProvider.DUMMY_CREDENTIAL_TYPE);
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.IO_IMPL),
        "org.apache.iceberg.aws.s3.S3FileIO");
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.ICEBERG_S3_ENDPOINT),
        "https://s3-endpoint.example.com");
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.AWS_S3_REGION), "us-west-2");
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.ICEBERG_S3_PATH_STYLE_ACCESS),
        "true");

    IcebergConfigProvider provider = new StaticIcebergConfigProvider();
    provider.initialize(catalogConf);
    return provider;
  }

  @Bean
  @Primary
  public IcebergCatalogWrapperManager icebergTestCatalogWrapperManager(
      IcebergConfigProvider icebergTestConfigProvider) {
    Map<String, String> catalogConf = Maps.newHashMap();
    String catalogConfigPrefix = "catalog." + PREFIX;
    catalogConf.put(
        IcebergConstants.ICEBERG_REST_CATALOG_CONFIG_PROVIDER,
        StaticIcebergConfigProvider.class.getName());
    catalogConf.put(String.format("%s.catalog-backend-name", catalogConfigPrefix), PREFIX);
    catalogConf.put(
        CredentialConstants.CREDENTIAL_PROVIDERS, DummyCredentialProvider.DUMMY_CREDENTIAL_TYPE);
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.IO_IMPL),
        "org.apache.iceberg.aws.s3.S3FileIO");
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.ICEBERG_S3_ENDPOINT),
        "https://s3-endpoint.example.com");
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.AWS_S3_REGION), "us-west-2");
    catalogConf.put(
        String.format("%s.%s", catalogConfigPrefix, IcebergConstants.ICEBERG_S3_PATH_STYLE_ACCESS),
        "true");
    return new IcebergCatalogWrapperManagerForTest(
        catalogConf, icebergTestConfigProvider, false);
  }

  @Bean
  @Primary
  public IcebergMetricsManager icebergTestMetricsManager(IcebergConfig icebergTestConfig) {
    return new IcebergMetricsManager(icebergTestConfig);
  }

  @Bean
  public EventBus eventBus() {
    return new EventBus(java.util.Collections.emptyList());
  }

  @Bean
  @Primary
  public IcebergTableOperationDispatcher icebergTestTableDispatcher(
      IcebergCatalogWrapperManager icebergTestCatalogWrapperManager, EventBus eventBus) {
    IcebergTableOperationExecutor executor =
        new IcebergTableOperationExecutor(icebergTestCatalogWrapperManager);
    return new IcebergTableEventDispatcher(executor, eventBus);
  }

  @Bean
  @Primary
  public IcebergViewOperationDispatcher icebergTestViewDispatcher(
      IcebergCatalogWrapperManager icebergTestCatalogWrapperManager, EventBus eventBus) {
    IcebergViewOperationExecutor executor =
        new IcebergViewOperationExecutor(icebergTestCatalogWrapperManager);
    return new IcebergViewEventDispatcher(executor, eventBus);
  }

  @Bean
  @Primary
  public IcebergNamespaceOperationDispatcher icebergTestNamespaceDispatcher(
      IcebergCatalogWrapperManager icebergTestCatalogWrapperManager, EventBus eventBus) {
    IcebergNamespaceOperationExecutor executor =
        new IcebergNamespaceOperationExecutor(icebergTestCatalogWrapperManager);
    return new IcebergNamespaceEventDispatcher(executor, eventBus);
  }

  /**
   * Separate component that initialises {@link ServerContext} after the context is fully refreshed.
   * Lives outside {@link IcebergTestApp} to avoid the circular reference that occurs when a {@code
   * @Configuration} class both defines {@code @Bean} methods and injects the beans they produce.
   */
  @org.springframework.stereotype.Component
  static class TestServerContextInitializer {

    private final IcebergConfigProvider configProvider;
    private final IcebergCatalogWrapperManager catalogWrapperManager;

    TestServerContextInitializer(
        IcebergConfigProvider configProvider,
        IcebergCatalogWrapperManager catalogWrapperManager) {
      this.configProvider = configProvider;
      this.catalogWrapperManager = catalogWrapperManager;
    }

    @PostConstruct
    public void init() {
      ServerContext.reset();
      ServerContext.initialize(
          new AllowAllAuthorizer(),
          catalogWrapperManager,
          configProvider.getDefaultCatalogName());
    }
  }
}
