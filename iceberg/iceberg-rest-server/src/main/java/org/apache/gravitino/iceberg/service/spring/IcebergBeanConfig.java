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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.allowall.AllowAllAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.opa.OPAIcebergAuthorizer;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationExecutor;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationExecutor;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewEventDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewOperationDispatcher;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergViewOperationExecutor;
import org.apache.gravitino.iceberg.service.metrics.IcebergMetricsManager;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.StaticIcebergConfigProvider;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Central Spring configuration that replaces the HK2 {@code AbstractBinder} previously used in
 * {@code RESTService}. It wires all server-level beans (config, dispatchers, authorizer, metrics,
 * etc.) and initialises {@link ServerContext} in {@link #initializeServerContext()}.
 *
 * <p>Because {@link IcebergRestServerApplication} is intentionally free of {@code
 * @SpringBootApplication} to avoid clashing with the test application {@code IcebergTestApp} during
 * Spring Boot test context initialization, this class carries {@link EnableAutoConfiguration} and
 * {@link ComponentScan} so that {@code SpringApplication.run(IcebergBeanConfig.class, args)} boots
 * the embedded web container and discovers all {@code @RestController} classes under {@code
 * org.apache.gravitino.iceberg}.
 */
@Configuration
@EnableAutoConfiguration
@ComponentScan(basePackages = "org.apache.gravitino.iceberg")
public class IcebergBeanConfig {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergBeanConfig.class);

  /**
   * Loads properties from system properties, stripping the {@code gravitino.iceberg-rest.} prefix
   * so that both {@link IcebergConfig} entries and Spring Boot {@code server.*} entries can be read
   * correctly.
   */
  @Bean
  @Primary
  public Properties icebergProperties() {
    Properties sysProps = System.getProperties();
    String prefix = IcebergConfig.ICEBERG_CONFIG_PREFIX;
    Properties result = new Properties();
    for (String name : sysProps.stringPropertyNames()) {
      String value = sysProps.getProperty(name);
      if (name.startsWith(prefix)) {
        result.setProperty(name.substring(prefix.length()), value);
      } else {
        result.setProperty(name, value);
      }
    }
    return result;
  }

  @Bean
  public IcebergConfig icebergConfig(Properties icebergProperties) {
    Map<String, String> stripped = new HashMap<>();
    icebergProperties.forEach(
        (k, v) -> stripped.put(String.valueOf(k), String.valueOf(v)));
    return new IcebergConfig(stripped);
  }

  @Bean
  public IcebergConfigProvider icebergConfigProvider(IcebergConfig icebergConfig) {
    Map<String, String> configProperties = icebergConfig.getAllConfig();
    String providerName = icebergConfig.get(IcebergConfig.ICEBERG_REST_CATALOG_CONFIG_PROVIDER);
    IcebergConfigProvider provider;
    if (IcebergConstants.STATIC_ICEBERG_CATALOG_CONFIG_PROVIDER_NAME.equals(providerName)) {
      provider = new StaticIcebergConfigProvider();
    } else {
      LOG.info("Load Iceberg catalog provider by class name: {}.", providerName);
      try {
        Class<?> providerClz = Class.forName(providerName);
        provider = (IcebergConfigProvider) providerClz.getDeclaredConstructor().newInstance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    provider.initialize(configProperties);
    return provider;
  }

  @Bean
  public EventListenerManager eventListenerManager(IcebergConfig icebergConfig) {
    Map<String, String> configProperties = icebergConfig.getAllConfig();
    EventListenerManager manager = new EventListenerManager();
    manager.init(configProperties);
    manager.start();
    return manager;
  }

  @Bean
  public EventBus eventBus(EventListenerManager eventListenerManager) {
    return eventListenerManager.createEventBus();
  }

  @Bean
  public IcebergCatalogWrapperManager icebergCatalogWrapperManager(
      IcebergConfig icebergConfig, IcebergConfigProvider icebergConfigProvider) {
    Map<String, String> configProperties = icebergConfig.getAllConfig();
    IcebergCatalogWrapperManager manager =
        new IcebergCatalogWrapperManager(configProperties, icebergConfigProvider, false);
    return manager;
  }

  @Bean
  public IcebergMetricsManager icebergMetricsManager(IcebergConfig icebergConfig) {
    IcebergMetricsManager manager = new IcebergMetricsManager(icebergConfig);
    manager.start();
    return manager;
  }

  @Bean
  public IcebergAuthorizer icebergAuthorizer(IcebergConfig icebergConfig) {
    String authorizerType = icebergConfig.get(IcebergConfig.AUTHORIZER_TYPE);
    if (AllowAllAuthorizer.NAME.equalsIgnoreCase(authorizerType)) {
      LOG.info("Using AllowAllAuthorizer");
      return new AllowAllAuthorizer();
    } else if (OPAIcebergAuthorizer.NAME.equalsIgnoreCase(authorizerType)) {
      String opaUrl = icebergConfig.get(IcebergConfig.OPA_URL);
      long cacheTtl = icebergConfig.get(IcebergConfig.OPA_CACHE_TTL_SECONDS);
      long timeout = icebergConfig.get(IcebergConfig.OPA_TIMEOUT_MS);
      LOG.info(
          "Using OPAIcebergAuthorizer with URL: {}, cacheTtl: {}s, timeout: {}ms",
          opaUrl,
          cacheTtl,
          timeout);
      return new OPAIcebergAuthorizer(opaUrl, cacheTtl, timeout);
    } else {
      LOG.warn(
          "Unknown authorizer type '{}', falling back to AllowAllAuthorizer", authorizerType);
      return new AllowAllAuthorizer();
    }
  }

  @Bean
  public IcebergTableOperationExecutor icebergTableOperationExecutor(
      IcebergCatalogWrapperManager icebergCatalogWrapperManager) {
    return new IcebergTableOperationExecutor(icebergCatalogWrapperManager);
  }

  @Bean
  @Primary
  public IcebergTableOperationDispatcher icebergTableOperationDispatcher(
      IcebergTableOperationExecutor executor, EventBus eventBus) {
    return new IcebergTableEventDispatcher(executor, eventBus);
  }

  @Bean
  public IcebergViewOperationExecutor icebergViewOperationExecutor(
      IcebergCatalogWrapperManager icebergCatalogWrapperManager) {
    return new IcebergViewOperationExecutor(icebergCatalogWrapperManager);
  }

  @Bean
  @Primary
  public IcebergViewOperationDispatcher icebergViewOperationDispatcher(
      IcebergViewOperationExecutor executor, EventBus eventBus) {
    return new IcebergViewEventDispatcher(executor, eventBus);
  }

  @Bean
  public IcebergNamespaceOperationExecutor icebergNamespaceOperationExecutor(
      IcebergCatalogWrapperManager icebergCatalogWrapperManager) {
    return new IcebergNamespaceOperationExecutor(icebergCatalogWrapperManager);
  }

  @Bean
  @Primary
  public IcebergNamespaceOperationDispatcher icebergNamespaceOperationDispatcher(
      IcebergNamespaceOperationExecutor executor, EventBus eventBus) {
    return new IcebergNamespaceEventDispatcher(executor, eventBus);
  }

  @Bean
  @Primary
  public ObjectMapper icebergObjectMapper() {
    return IcebergObjectMapper.getInstance();
  }

  @Bean
  public List<String> restApiExtensionPackages(IcebergConfig icebergConfig) {
    List<String> packages = Lists.newArrayList("org.apache.gravitino.iceberg.service.rest");
    packages.addAll(icebergConfig.get(IcebergConfig.REST_API_EXTENSION_PACKAGES));
    return packages;
  }

  /**
   * Separate component that initialises {@link ServerContext} after its constructor dependencies
   * (produced by {@link IcebergBeanConfig}'s {@code @Bean} methods) are available. Extracting this
   * avoids the fragile ordering between a {@code @Configuration} class's {@code @PostConstruct} and
   * the invocation of its own {@code @Bean} factory methods.
   */
  @org.springframework.stereotype.Component
  static class ServerContextInitializer {

    private final IcebergAuthorizer authorizer;
    private final IcebergCatalogWrapperManager catalogWrapperManager;
    private final IcebergConfigProvider configProvider;
    private final IcebergMetricsManager metricsManager;
    private final EventListenerManager eventListenerManager;

    ServerContextInitializer(
        IcebergAuthorizer authorizer,
        IcebergCatalogWrapperManager catalogWrapperManager,
        IcebergConfigProvider configProvider,
        IcebergMetricsManager metricsManager,
        EventListenerManager eventListenerManager) {
      this.authorizer = authorizer;
      this.catalogWrapperManager = catalogWrapperManager;
      this.configProvider = configProvider;
      this.metricsManager = metricsManager;
      this.eventListenerManager = eventListenerManager;
    }

    @PostConstruct
    public void init() {
      ServerContext.reset();
      ServerContext.initialize(
          authorizer, catalogWrapperManager, configProvider.getDefaultCatalogName());
      LOG.info("ServerContext initialised");
    }

    @PreDestroy
    public void shutdown() throws Exception {
      metricsManager.close();
      eventListenerManager.stop();
      catalogWrapperManager.close();
      configProvider.close();
    }
  }
}
