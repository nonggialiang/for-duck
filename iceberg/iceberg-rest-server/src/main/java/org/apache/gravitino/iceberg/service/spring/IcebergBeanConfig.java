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
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.gravitino.iceberg.service.ServerContext;
import org.apache.gravitino.iceberg.service.EntitlementFilter;
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
import org.apache.gravitino.iceberg.service.entitlement.EntitlementSupport;
import org.apache.gravitino.iceberg.service.metrics.IcebergMetricsManager;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.iceberg.service.provider.StaticIcebergConfigProvider;
import org.apache.gravitino.iceberg.service.authentication.ServerAuthenticator;
import org.apache.gravitino.iceberg.service.IcebergAuthenticationFilter;
import org.apache.gravitino.listener.EventBus;
import org.apache.gravitino.listener.EventListenerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

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
   * Loads Iceberg properties from the Spring Boot {@link Environment} and strips the {@code
   * gravitino.iceberg-rest.} prefix so that {@link IcebergConfig} entries can be read correctly.
   * Nested maps and lists from the relaxed binder are flattened into dotted keys.
   */
  @Bean
  @Primary
  public Properties icebergProperties(Environment environment) {
    Binder binder = Binder.get(environment);
    Map<String, Object> bound =
        binder
            .bind("gravitino.iceberg-rest", Bindable.mapOf(String.class, Object.class))
            .orElseGet(Collections::emptyMap);
    Properties result = new Properties();
    flattenProperties("", bound, result);
    return result;
  }

  /**
   * Request logging filter; the Iceberg authentication filter is registered ahead of this one so
   * that authentication runs first.
   */
  @Bean
  public CommonsRequestLoggingFilter requestLoggingFilter() {
    CommonsRequestLoggingFilter filter =
        new CommonsRequestLoggingFilter() {
          @Override
          protected boolean shouldLog(HttpServletRequest request) {
            return LOG.isInfoEnabled();
          }

          @Override
          protected void beforeRequest(HttpServletRequest request, String message) {
            LOG.info(message + ", headers=" + headersToString(request));
          }
        };
    filter.setIncludeClientInfo(true);
    filter.setIncludeQueryString(true);
    filter.setIncludePayload(true);
    filter.setIncludeHeaders(false);
    filter.setMaxPayloadLength(10000);
    filter.setAfterMessagePrefix("After request [");
    filter.setBeforeMessagePrefix("Before request [");
    filter.setBeforeMessageSuffix("]");
    filter.setAfterMessageSuffix("]");
    return filter;
  }

  /** Builds and initializes the configured authenticators from {@link IcebergConfig}. */
  @Bean
  public ServerAuthenticator serverAuthenticator(IcebergConfig icebergConfig) {
    return new ServerAuthenticator(icebergConfig);
  }

  /**
   * Registers the {@link IcebergAuthenticationFilter} ahead of the {@link CommonsRequestLoggingFilter}
   * so that authentication and {@code X-Iceberg-Access-Delegator} capture run before request logging,
   * controllers, request-context creation, and authorization AOP.
   */
  @Bean
  public FilterRegistrationBean<IcebergAuthenticationFilter> icebergAuthenticationFilter(
      ServerAuthenticator serverAuthenticator) {
    IcebergAuthenticationFilter filter =
        new IcebergAuthenticationFilter(serverAuthenticator.authenticators());
    FilterRegistrationBean<IcebergAuthenticationFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/v1/*");
    registration.setOrder(Integer.MIN_VALUE + 100);
    registration.setName("icebergAuthenticationFilter");
    return registration;
  }

  /**
   * Registers the {@link EntitlementFilter} right after the authentication filter so the current
   * user is already available, and configures the entitlement view SQL dialect from {@link
   * IcebergConfig#ENTITLEMENT_DIALECT}.
   */
  @Bean
  public FilterRegistrationBean<EntitlementFilter> icebergEntitlementFilter(
      IcebergConfig icebergConfig) {
    EntitlementSupport.configureDialect(icebergConfig.get(IcebergConfig.ENTITLEMENT_DIALECT));
    FilterRegistrationBean<EntitlementFilter> registration =
        new FilterRegistrationBean<>(new EntitlementFilter());
    registration.addUrlPatterns("/v1/*");
    registration.setOrder(Integer.MIN_VALUE + 110);
    registration.setName("icebergEntitlementFilter");
    return registration;
  }

  private static String headersToString(HttpServletRequest request) {
    Enumeration<String> headerNames = request.getHeaderNames();
    if (headerNames == null) {
      return "{}";
    }
    Map<String, List<String>> headers = new HashMap<>();
    while (headerNames.hasMoreElements()) {
      String headerName = headerNames.nextElement();
      headers.put(headerName, Collections.list(request.getHeaders(headerName)));
    }
    return headers.toString();
  }

  @SuppressWarnings("unchecked")
  private static void flattenProperties(String prefix, Map<String, Object> source, Properties target) {
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      Object value = entry.getValue();
      if (value instanceof Map) {
        flattenProperties(key, (Map<String, Object>) value, target);
      } else if (value instanceof List<?>) {
        target.setProperty(
            key,
            ((List<?>) value).stream()
                .map(Object::toString)
                .reduce((a, b) -> a + "," + b)
                .orElse(""));
      } else if (value != null) {
        target.setProperty(key, String.valueOf(value));
      }
    }
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
      boolean entitlementEnabled = icebergConfig.get(IcebergConfig.ENTITLEMENT_ENABLED);
      LOG.info(
          "Using OPAIcebergAuthorizer with URL: {}, cacheTtl: {}s, timeout: {}ms, "
              + "entitlementEnabled: {}",
          opaUrl,
          cacheTtl,
          timeout,
          entitlementEnabled);
      return new OPAIcebergAuthorizer(opaUrl, cacheTtl, timeout, entitlementEnabled);
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
