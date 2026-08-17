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
package org.apache.gravitino.iceberg.service.authorization.opa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestOPAIcebergAuthorizer {

  private static final String ALLOW_RULE_PATH = "/v1/data/iceberg/rest/allow";
  private static final String CREDENTIAL_RULE_PATH = "/v1/data/iceberg/rest/credential_privilege";
  private static final String ENTITLEMENT_RULE_PATH =
      "/v1/data/iceberg/entitlement/row_filter";

  private static HttpServer mockOpaServer;
  private static String mockOpaUrl;
  private static final AtomicInteger queryCount = new AtomicInteger(0);
  private static final AtomicReference<String> lastQueriedPath = new AtomicReference<>();
  private static final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private static volatile String nextOperationResponse = "{\"result\":true}";
  private static volatile String nextCredentialResponse = "{\"result\":null}";
  private static volatile String nextEntitlementResponse = "{\"result\":null}";

  @BeforeAll
  static void startMockServer() throws IOException {
    mockOpaServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockOpaUrl = "http://localhost:" + mockOpaServer.getAddress().getPort();

    mockOpaServer.createContext(
        "/v1/data/",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            lastQueriedPath.set(path);
            queryCount.incrementAndGet();
            lastRequestBody.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String resp;
            if (ALLOW_RULE_PATH.equals(path)) {
              resp = nextOperationResponse;
            } else if (CREDENTIAL_RULE_PATH.equals(path)) {
              resp = nextCredentialResponse;
            } else if (ENTITLEMENT_RULE_PATH.equals(path)) {
              resp = nextEntitlementResponse;
            } else {
              byte[] notFound = "{}".getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(404, notFound.length);
              try (OutputStream os = exchange.getResponseBody()) {
                os.write(notFound);
              }
              return;
            }
            byte[] respBytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(respBytes);
            }
          }
        });
    mockOpaServer.start();
  }

  @AfterAll
  static void stopMockServer() {
    mockOpaServer.stop(0);
  }

  @Test
  void testCheckOperationAllow() {
    queryCount.set(0);
    nextOperationResponse = "{\"result\":true}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertTrue(authorizer.checkOperation("alice", IcebergOperation.LOAD_TABLE, resource));
    assertEquals(ALLOW_RULE_PATH, lastQueriedPath.get());
  }

  @Test
  void testCheckOperationDeny() {
    queryCount.set(0);
    nextOperationResponse = "{\"result\":false}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertFalse(authorizer.checkOperation("bob", IcebergOperation.LOAD_TABLE, resource));
    assertEquals(ALLOW_RULE_PATH, lastQueriedPath.get());
  }

  @Test
  void testCheckOperationDeniesOnInvalidResponse() {
    queryCount.set(0);
    nextOperationResponse = "{}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertFalse(authorizer.checkOperation("charlie", IcebergOperation.CREATE_TABLE, resource));
  }

  @Test
  void testCheckOperationCachesResults() {
    queryCount.set(0);
    nextOperationResponse = "{\"result\":true}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 3600, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");

    authorizer.checkOperation("alice", IcebergOperation.LOAD_TABLE, resource);
    int callsAfterFirst = queryCount.get();
    authorizer.checkOperation("alice", IcebergOperation.LOAD_TABLE, resource);
    int callsAfterSecond = queryCount.get();

    assertEquals(callsAfterFirst, callsAfterSecond, "Second call should hit cache");
  }

  @Test
  void testCheckCredentialWrite() {
    queryCount.set(0);
    nextCredentialResponse = "{\"result\":\"write\"}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertEquals(
        CredentialPrivilege.WRITE, authorizer.checkCredential("alice", resource));
    assertEquals(CREDENTIAL_RULE_PATH, lastQueriedPath.get());
  }

  @Test
  void testCheckCredentialRead() {
    queryCount.set(0);
    nextCredentialResponse = "{\"result\":\"read\"}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertEquals(
        CredentialPrivilege.READ, authorizer.checkCredential("bob", resource));
    assertEquals(CREDENTIAL_RULE_PATH, lastQueriedPath.get());
  }

  @Test
  void testCheckCredentialNullOnNoResult() {
    queryCount.set(0);
    nextCredentialResponse = "{\"result\":null}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertNull(authorizer.checkCredential("charlie", resource));
  }

  @Test
  void testCheckCredentialNullOnMissingResult() {
    queryCount.set(0);
    nextCredentialResponse = "{}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertNull(authorizer.checkCredential("dave", resource));
  }

  @Test
  void testCheckOperationReturnsFalseOnUnreachableServer() {
    // Point to an unreachable port
    OPAIcebergAuthorizer authorizer =
        new OPAIcebergAuthorizer("http://localhost:1", 0, 100);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertFalse(authorizer.checkOperation("alice", IcebergOperation.LOAD_TABLE, resource));
  }

  @Test
  void testGetRowFilterReturnsFilter() {
    queryCount.set(0);
    nextEntitlementResponse = "{\"result\":\"region = 'US'\"}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000, true);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertEquals("region = 'US'", authorizer.getRowFilter("alice", resource));
    assertEquals(ENTITLEMENT_RULE_PATH, lastQueriedPath.get());

    // request body carries user and resource coordinates
    JsonNode body;
    try {
      body = new ObjectMapper().readTree(lastRequestBody.get());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    assertEquals("alice", body.get("input").get("user").asText());
    assertEquals("table", body.get("input").get("resource").get("type").asText());
    assertEquals("cat", body.get("input").get("resource").get("catalog").asText());
    assertEquals("db", body.get("input").get("resource").get("schema").asText());
    assertEquals("t1", body.get("input").get("resource").get("name").asText());
  }

  @Test
  void testGetRowFilterNullWhenNoResult() {
    nextEntitlementResponse = "{\"result\":null}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000, true);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertNull(authorizer.getRowFilter("bob", resource));
  }

  @Test
  void testGetRowFilterNullOnMissingResult() {
    nextEntitlementResponse = "{}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000, true);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertNull(authorizer.getRowFilter("carol", resource));
  }

  @Test
  void testGetRowFilterNullOnNonStringResult() {
    nextEntitlementResponse = "{\"result\":42}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000, true);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertNull(authorizer.getRowFilter("carol", resource));
  }

  @Test
  void testGetRowFilterDisabledSendsNoRequest() {
    queryCount.set(0);
    nextEntitlementResponse = "{\"result\":\"region = 'US'\"}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000, false);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertNull(authorizer.getRowFilter("alice", resource));
    assertEquals(0, queryCount.get(), "entitlement-disabled authorizer must not query OPA");
  }
}
