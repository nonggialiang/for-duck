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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TestOPAIcebergAuthorizer {

  private static HttpServer mockOpaServer;
  private static String mockOpaUrl;
  private static final AtomicInteger queryCount = new AtomicInteger(0);
  private static volatile String nextResponse = "{\"result\":true}";

  @BeforeAll
  static void startMockServer() throws IOException {
    mockOpaServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockOpaUrl = "http://localhost:" + mockOpaServer.getAddress().getPort();

    mockOpaServer.createContext(
        "/v1/data/",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            queryCount.incrementAndGet();
            byte[] resp = nextResponse.getBytes();
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
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
    nextResponse = "{\"result\":true}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertTrue(authorizer.checkOperation("alice", IcebergOperation.LOAD_TABLE, resource));
  }

  @Test
  void testCheckOperationDeny() {
    queryCount.set(0);
    nextResponse = "{\"result\":false}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertFalse(authorizer.checkOperation("bob", IcebergOperation.LOAD_TABLE, resource));
  }

  @Test
  void testCheckOperationDeniesOnInvalidResponse() {
    queryCount.set(0);
    nextResponse = "{}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertFalse(authorizer.checkOperation("charlie", IcebergOperation.CREATE_TABLE, resource));
  }

  @Test
  void testCheckOperationCachesResults() {
    queryCount.set(0);
    nextResponse = "{\"result\":true}";
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
    nextResponse = "{\"result\":{\"credential_privilege\":\"write\"}}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertEquals(
        CredentialPrivilege.WRITE, authorizer.checkCredential("alice", resource));
  }

  @Test
  void testCheckCredentialRead() {
    queryCount.set(0);
    nextResponse = "{\"result\":{\"credential_privilege\":\"read\"}}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertEquals(
        CredentialPrivilege.READ, authorizer.checkCredential("bob", resource));
  }

  @Test
  void testCheckCredentialNullOnNoResult() {
    queryCount.set(0);
    nextResponse = "{\"result\":null}";
    OPAIcebergAuthorizer authorizer = new OPAIcebergAuthorizer(mockOpaUrl, 0, 5000);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertNull(authorizer.checkCredential("charlie", resource));
  }

  @Test
  void testCheckOperationReturnsFalseOnUnreachableServer() {
    // Point to an unreachable port
    OPAIcebergAuthorizer authorizer =
        new OPAIcebergAuthorizer("http://localhost:1", 0, 100);
    IcebergResource resource = IcebergResource.ofTable("cat", "db", "t1");
    assertFalse(authorizer.checkOperation("alice", IcebergOperation.LOAD_TABLE, resource));
  }
}
