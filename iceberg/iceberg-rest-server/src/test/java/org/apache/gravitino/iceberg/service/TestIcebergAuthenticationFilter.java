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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.iceberg.service.authentication.SimpleAuthenticator;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifies the IcebergAuthenticationFilter end-to-end with the SimpleAuthenticator. */
class TestIcebergAuthenticationFilter {

  private static final ObjectMapper MAPPER = IcebergObjectMapper.getInstance();

  private IcebergAuthenticationFilter filterWithSimple() {
    return new IcebergAuthenticationFilter(java.util.List.of(new SimpleAuthenticator()));
  }

  private MockHttpServletRequest request(String basicUser) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI("/v1/config");
    if (basicUser != null) {
      String cred = basicUser + ":x";
      request.addHeader(
          AuthConstants.HTTP_HEADER_AUTHORIZATION,
          AuthConstants.AUTHORIZATION_BASIC_HEADER
              + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8)));
    }
    return request;
  }

  @Test
  void missingCredentialAuthenticatesAnonymousWithSimple() throws Exception {
    // SimpleAuthenticator treats missing credentials as the anonymous user (Gravitino semantics),
    // so the chain runs with status 200.
    IcebergAuthenticationFilter filter = filterWithSimple();
    MockHttpServletRequest request = request(null);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
  }

  @Test
  void authenticatorWithNoSupportProducesIcebergJson401() throws Exception {
    IcebergAuthenticationFilter filter =
        new IcebergAuthenticationFilter(
            java.util.List.of(
                new org.apache.gravitino.iceberg.service.authentication.OAuth2TokenAuthenticator()));
    MockHttpServletRequest request = request(null);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertEquals(401, response.getStatus());
    assertTrue(
        response.getContentType().contains("application/json"),
        "expected JSON content type, got: " + response.getContentType());
    ErrorResponse error = MAPPER.readValue(response.getContentAsByteArray(), ErrorResponse.class);
    assertEquals(401, error.code());
    assertTrue(error.message().toLowerCase().contains("credential"));
  }

  @Test
  void validBasicAuthEstablishesRequester() throws Exception {
    IcebergAuthenticationFilter filter = filterWithSimple();
    MockHttpServletRequest request = request("alice");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seen = new AtomicReference<>();
    FilterChain chain =
        (req, res) -> {
          seen.set(PrincipalUtils.getCurrentRequesterUserName());
        };

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertEquals("alice", seen.get());
    assertEquals("alice", ((Principal) request.getAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME)).getName());
  }

  @Test
  void delegatorHeaderSwitchesEffectiveUser() throws Exception {
    IcebergAuthenticationFilter filter = filterWithSimple();
    MockHttpServletRequest request = request("alice");
    request.addHeader(AuthConstants.ICEBERG_ACCESS_DELEGATOR_HEADER, "bob");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> effective = new AtomicReference<>();
    AtomicReference<String> requester = new AtomicReference<>();
    FilterChain chain =
        (req, res) -> {
          effective.set(PrincipalUtils.getCurrentUserName());
          requester.set(PrincipalUtils.getCurrentRequesterUserName());
        };

    filter.doFilter(request, response, chain);

    assertEquals(200, response.getStatus());
    assertEquals("bob", effective.get(), "effective user should be the delegated user");
    assertEquals("alice", requester.get(), "requester identity must be preserved");
  }

  @Test
  void noDelegatorKeepsRequesterAsEffective() throws Exception {
    IcebergAuthenticationFilter filter = filterWithSimple();
    MockHttpServletRequest request = request("alice");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> effective = new AtomicReference<>();
    FilterChain chain = (req, res) -> effective.set(PrincipalUtils.getCurrentUserName());

    filter.doFilter(request, response, chain);

    assertEquals("alice", effective.get());
  }

  @Test
  void principalDoAsWrapsChainEvenForAnonymous() throws Exception {
    IcebergAuthenticationFilter filter = filterWithSimple();
    MockHttpServletRequest request = request(null);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> requester = new AtomicReference<>();
    FilterChain chain = (req, res) -> requester.set(PrincipalUtils.getCurrentRequesterUserName());

    filter.doFilter(request, response, chain);

    assertEquals(AuthConstants.ANONYMOUS_USER, requester.get());
  }

  @SuppressWarnings("unused")
  private static void useUserPrincipal(UserPrincipal p) {
    // sanity that UserPrincipal is on the classpath for the test imports
  }
}
