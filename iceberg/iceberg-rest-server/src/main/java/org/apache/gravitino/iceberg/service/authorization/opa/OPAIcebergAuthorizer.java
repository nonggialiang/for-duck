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
package org.apache.gravitino.iceberg.service.authorization.opa;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OPAIcebergAuthorizer implements IcebergAuthorizer {

  private static final Logger LOG = LoggerFactory.getLogger(OPAIcebergAuthorizer.class);

  public static final String NAME = "opa";
  public static final String OPA_PACKAGE = "iceberg.rest";
  public static final String OPA_DATA_OWNERS_PATH = "/v1/data/iceberg/rest/owners";

  private static final long DEFAULT_CACHE_TTL_SECONDS = 30;
  private static final long DEFAULT_TIMEOUT_MS = 2000;

  private final String opaUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Cache<CacheKey, Boolean> decisionCache;
  private final Cache<CacheKey, CredentialPrivilege> credentialCache;
  private final long timeoutMs;

  public OPAIcebergAuthorizer(String opaUrl, long cacheTtlSeconds, long timeoutMs) {
    this.opaUrl = opaUrl.endsWith("/") ? opaUrl.substring(0, opaUrl.length() - 1) : opaUrl;
    this.timeoutMs = timeoutMs;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build();
    this.objectMapper = new ObjectMapper();
    this.decisionCache = Caffeine.newBuilder()
        .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
        .maximumSize(10_000)
        .build();
    this.credentialCache = Caffeine.newBuilder()
        .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
        .maximumSize(10_000)
        .build();
  }

  public OPAIcebergAuthorizer(String opaUrl) {
    this(opaUrl, DEFAULT_CACHE_TTL_SECONDS, DEFAULT_TIMEOUT_MS);
  }

  @Override
  public boolean checkOperation(
      String userName, IcebergOperation op, IcebergResource resource) {
    CacheKey key = new CacheKey("op", userName, op.name(), resource);
    return decisionCache.get(key, k -> doCheckOperation(userName, op, resource));
  }

  @Override
  public CredentialPrivilege checkCredential(String userName, IcebergResource resource) {
    CacheKey key = new CacheKey("cred", userName, null, resource);
    return credentialCache.get(key, k -> doCheckCredential(userName, resource));
  }

  @Override
  public void registerOwner(String catalog, String namespace, String resource, String owner) {
    String path =
        String.format("%s/%s/%s/%s", catalog, namespace, resource, owner);
    putOpaData(OPA_DATA_OWNERS_PATH + "/" + path, "{}");
  }

  @Override
  public void removeOwner(String catalog, String namespace, String resource) {
    String path = String.format("%s/%s/%s", catalog, namespace, resource);
    deleteOpaData(OPA_DATA_OWNERS_PATH + "/" + path);
  }

  private boolean doCheckOperation(
      String userName, IcebergOperation op, IcebergResource resource) {
    try {
      ObjectNode input = buildInput(userName, op, resource);
      ObjectNode request = objectMapper.createObjectNode();
      request.set("input", input);

      HttpResponse<String> response = sendOpaQuery(request);
      if (response.statusCode() == 200) {
        JsonNode result = objectMapper.readTree(response.body());
        if (result.has("result") && result.get("result").isBoolean()) {
          return result.get("result").asBoolean();
        }
      }
      LOG.warn("OPA returned unexpected response for operation check: status={}", response.statusCode());
      return false;
    } catch (Exception e) {
      LOG.error("OPA operation check failed, denying access", e);
      return false;
    }
  }

  private CredentialPrivilege doCheckCredential(String userName, IcebergResource resource) {
    try {
      ObjectNode input = buildInput(userName, null, resource);
      ObjectNode request = objectMapper.createObjectNode();
      request.set("input", input);

      HttpResponse<String> response = sendOpaQuery(request);
      if (response.statusCode() == 200) {
        JsonNode result = objectMapper.readTree(response.body());
        JsonNode credResult = result.get("result");
        if (credResult != null) {
          String privilege = credResult.get("credential_privilege").asText();
          if ("write".equalsIgnoreCase(privilege)) {
            return CredentialPrivilege.WRITE;
          } else if ("read".equalsIgnoreCase(privilege)) {
            return CredentialPrivilege.READ;
          }
        }
      }
      return null;
    } catch (Exception e) {
      LOG.error("OPA credential check failed", e);
      return null;
    }
  }

  private ObjectNode buildInput(
      String userName, IcebergOperation op, IcebergResource resource) {
    ObjectNode input = objectMapper.createObjectNode();
    input.put("user", userName);
    if (op != null) {
      input.put("action", op.name().toLowerCase());
    }
    ObjectNode res = objectMapper.createObjectNode();
    res.put("type", resource.getType().name().toLowerCase());
    res.put("catalog", resource.getCatalogName());
    if (resource.getSchemaName() != null) {
      res.put("schema", resource.getSchemaName());
    }
    if (resource.getResourceName() != null) {
      res.put("name", resource.getResourceName());
    }
    input.set("resource", res);
    return input;
  }

  private HttpResponse<String> sendOpaQuery(ObjectNode body) throws Exception {
    String url = opaUrl + "/v1/data/" + OPA_PACKAGE;
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
        .timeout(Duration.ofMillis(timeoutMs))
        .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void putOpaData(String path, String data) {
    try {
      String url = opaUrl + path;
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Content-Type", "application/json")
          .PUT(HttpRequest.BodyPublishers.ofString(data))
          .timeout(Duration.ofMillis(timeoutMs))
          .build();
      httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      LOG.warn("Failed to register owner in OPA: path={}", path, e);
    }
  }

  private void deleteOpaData(String path) {
    try {
      String url = opaUrl + path;
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .DELETE()
          .timeout(Duration.ofMillis(timeoutMs))
          .build();
      httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      LOG.warn("Failed to remove owner from OPA: path={}", path, e);
    }
  }

  private static class CacheKey {
    private final String type;
    private final String user;
    private final String action;
    private final IcebergResource resource;

    CacheKey(String type, String user, String action, IcebergResource resource) {
      this.type = type;
      this.user = user;
      this.action = action;
      this.resource = resource;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CacheKey)) return false;
      CacheKey that = (CacheKey) o;
      return Objects.equals(type, that.type)
          && Objects.equals(user, that.user)
          && Objects.equals(action, that.action)
          && Objects.equals(resource, that.resource);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, user, action, resource);
    }
  }
}
