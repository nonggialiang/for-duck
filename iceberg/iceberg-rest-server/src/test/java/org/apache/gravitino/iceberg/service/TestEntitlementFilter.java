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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.security.PrivilegedExceptionAction;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.utils.PrincipalUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class TestEntitlementFilter {

  private EntitlementFilter filter;

  /** alice=row-filter only, bob=write only, dave=both, carol=neither; records the last resource. */
  private static class RecordingStubAuthorizer implements IcebergAuthorizer {
    private final AtomicReference<IcebergResource> lastResource = new AtomicReference<>();

    @Override
    public boolean checkOperation(
        String userName, IcebergOperation op, IcebergResource resource) {
      lastResource.set(resource);
      if (op == IcebergOperation.UPDATE_TABLE) {
        return "bob".equals(userName) || "dave".equals(userName);
      }
      return true;
    }

    @Override
    public String getRowFilter(String userName, IcebergResource resource) {
      lastResource.set(resource);
      return ("alice".equals(userName) || "dave".equals(userName)) ? "region = 'US'" : null;
    }

    @Override
    public CredentialPrivilege checkCredential(String userName, IcebergResource resource) {
      return null;
    }

    @Override
    public void registerOwner(String catalog, String namespace, String resource, String owner) {}

    @Override
    public void removeOwner(String catalog, String namespace, String resource) {}
  }

  private RecordingStubAuthorizer stubAuthorizer;

  @BeforeEach
  public void setUp() {
    filter = new EntitlementFilter();
    stubAuthorizer = new RecordingStubAuthorizer();
    ServerContext.reset();
    ServerContext.initialize(stubAuthorizer, null, "default_catalog");
  }

  @AfterEach
  public void tearDown() {
    ServerContext.reset();
  }

  private void doFilterAs(String user, String method, String uri) throws Exception {
    PrincipalUtils.doAs(
        new UserPrincipal(user),
        (PrivilegedExceptionAction<Void>)
            () -> {
              MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
              MockHttpServletResponse response = new MockHttpServletResponse();
              MockFilterChain chain = new MockFilterChain();
              filter.doFilter(request, response, chain);
              lastChain.set(chain);
              lastResponse.set(response);
              return null;
            });
  }

  private final AtomicReference<MockFilterChain> lastChain = new AtomicReference<>();
  private final AtomicReference<MockHttpServletResponse> lastResponse = new AtomicReference<>();

  private boolean chainStarted() {
    return lastChain.get().getRequest() != null;
  }

  private HttpServletRequest chainedRequest() {
    return (HttpServletRequest) lastChain.get().getRequest();
  }

  @Test
  public void testEntitledGetTableIsDisguisedAsNotFound() throws Exception {
    doFilterAs("alice", "GET", "/v1/namespaces/db/tables/t1");
    assertFalse(chainStarted(), "chain must not continue for entitled table load");
    assertEquals(404, lastResponse.get().getStatus());
    String body = lastResponse.get().getContentAsString();
    assertTrue(body.contains("NoSuchTableException"), body);
    assertTrue(body.contains("Table does not exist: default_catalog.db.t1"), body);
  }

  @Test
  public void testEntitledHeadTableIsDisguisedAsNotFound() throws Exception {
    doFilterAs("alice", "HEAD", "/v1/namespaces/db/tables/t1");
    assertFalse(chainStarted());
    assertEquals(404, lastResponse.get().getStatus());
  }

  @Test
  public void testEntitledSuffixedTableIsStrippedAndContinues() throws Exception {
    doFilterAs("alice", "GET", "/v1/namespaces/db/tables/t1@entitlement");
    assertTrue(chainStarted(), "suffixed request must continue down the chain");
    assertEquals("/v1/namespaces/db/tables/t1", chainedRequest().getRequestURI());
    assertEquals(200, lastResponse.get().getStatus());
  }

  @Test
  public void testEntitledEncodedSuffixedTableIsStrippedAndContinues() throws Exception {
    doFilterAs("alice", "GET", "/v1/namespaces/db/tables/t1%40entitlement");
    assertTrue(chainStarted());
    assertEquals("/v1/namespaces/db/tables/t1", chainedRequest().getRequestURI());
  }

  @Test
  public void testSuffixedRequestUrlIsRewritten() throws Exception {
    doFilterAs("alice", "GET", "/v1/namespaces/db/tables/t1@entitlement");
    assertEquals(
        "http://localhost/v1/namespaces/db/tables/t1",
        chainedRequest().getRequestURL().toString());
  }

  @Test
  public void testUserWithoutEntitlementIsNotRewritten() throws Exception {
    // without suffix: normal flow
    doFilterAs("carol", "GET", "/v1/namespaces/db/tables/t1");
    assertTrue(chainStarted());
    assertEquals("/v1/namespaces/db/tables/t1", chainedRequest().getRequestURI());
    assertEquals(200, lastResponse.get().getStatus());

    // with suffix: untouched, downstream naturally 404s (anti-bypass)
    doFilterAs("carol", "GET", "/v1/namespaces/db/tables/t1@entitlement");
    assertTrue(chainStarted());
    assertEquals("/v1/namespaces/db/tables/t1@entitlement", chainedRequest().getRequestURI());
    assertEquals(200, lastResponse.get().getStatus());
  }

  @Test
  public void testConflictUserGetsExplicitError() throws Exception {
    doFilterAs("dave", "GET", "/v1/namespaces/db/tables/t1");
    assertFalse(chainStarted());
    assertEquals(500, lastResponse.get().getStatus());
    String body = lastResponse.get().getContentAsString();
    assertTrue(body.contains("ServiceFailureException"), body);
    assertTrue(body.contains("conflicts with the write privilege"), body);

    doFilterAs("dave", "GET", "/v1/namespaces/db/tables/t1@entitlement");
    assertFalse(chainStarted());
    assertEquals(500, lastResponse.get().getStatus());
  }

  @Test
  public void testWriteUserPassesThrough() throws Exception {
    doFilterAs("bob", "GET", "/v1/namespaces/db/tables/t1");
    assertTrue(chainStarted());
    assertEquals("/v1/namespaces/db/tables/t1", chainedRequest().getRequestURI());
    assertEquals(200, lastResponse.get().getStatus());
  }

  @Test
  public void testNonTableAndNonGetRequestsPassThrough() throws Exception {
    doFilterAs("alice", "GET", "/v1/namespaces/db/views/t1");
    assertTrue(chainStarted());
    assertEquals("/v1/namespaces/db/views/t1", chainedRequest().getRequestURI());

    doFilterAs("alice", "GET", "/v1/config");
    assertTrue(chainStarted());

    // POST /tables/t1 is a write path, authorization decides
    doFilterAs("alice", "POST", "/v1/namespaces/db/tables/t1");
    assertTrue(chainStarted());
    assertEquals("/v1/namespaces/db/tables/t1", chainedRequest().getRequestURI());

    // table listing
    doFilterAs("alice", "GET", "/v1/namespaces/db/tables");
    assertTrue(chainStarted());

    // sub-resource of a suffixed table keeps the remainder
    doFilterAs("alice", "GET", "/v1/namespaces/db/tables/t1@entitlement/metrics");
    assertTrue(chainStarted());
    assertEquals("/v1/namespaces/db/tables/t1/metrics", chainedRequest().getRequestURI());
  }

  @Test
  public void testPrefixAndNestedNamespaceParsing() throws Exception {
    doFilterAs("alice", "GET", "/v1/my_catalog/namespaces/db/tables/t1@entitlement");
    assertTrue(chainStarted());
    assertEquals("/v1/my_catalog/namespaces/db/tables/t1", chainedRequest().getRequestURI());
    assertEquals("my_catalog", stubAuthorizer.lastResource.get().getCatalogName());
    assertEquals("db", stubAuthorizer.lastResource.get().getSchemaName());
    assertEquals("t1", stubAuthorizer.lastResource.get().getResourceName());

    // nested namespace: last level is the schema
    doFilterAs("alice", "GET", "/v1/namespaces/parent%1Fchild/tables/t1");
    assertEquals(404, lastResponse.get().getStatus());
    assertEquals("child", stubAuthorizer.lastResource.get().getSchemaName());
  }

  @Test
  public void testServerContextUnsetLeavesRequestUntouched() throws Exception {
    ServerContext.reset();
    doFilterAs("alice", "GET", "/v1/namespaces/db/tables/t1");
    assertTrue(chainStarted());
    assertNull(lastResponse.get().getErrorMessage());
    assertEquals(200, lastResponse.get().getStatus());
  }
}
