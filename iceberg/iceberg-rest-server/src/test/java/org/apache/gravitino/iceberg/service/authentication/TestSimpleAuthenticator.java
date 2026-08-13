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
package org.apache.gravitino.iceberg.service.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.junit.jupiter.api.Test;

class TestSimpleAuthenticator {

  private static byte[] basicHeader(String user, String password) {
    String cred = user + ":" + password;
    return (AuthConstants.AUTHORIZATION_BASIC_HEADER
            + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8)))
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void supportsNullAndBasicHeader() {
    SimpleAuthenticator authenticator = new SimpleAuthenticator();
    authenticator.initialize(new IcebergConfig());
    assertTrue(authenticator.supportsToken(null));
    assertTrue(authenticator.supportsToken(basicHeader("u", "p")));
  }

  @Test
  void parsesBasicHeaderUser() {
    SimpleAuthenticator authenticator = new SimpleAuthenticator();
    authenticator.initialize(new IcebergConfig());
    Principal principal = authenticator.authenticateToken(basicHeader("alice", "secret"));
    assertTrue(principal instanceof UserPrincipal);
    assertEquals("alice", ((UserPrincipal) principal).getName());
  }

  @Test
  void fallsBackToAnonymousWhenNoCredential() {
    SimpleAuthenticator authenticator = new SimpleAuthenticator();
    authenticator.initialize(new IcebergConfig());
    Principal principal = authenticator.authenticateToken(null);
    assertEquals(AuthConstants.ANONYMOUS_USER, principal.getName());
  }

  @Test
  void fallsBackToAnonymousForNonBasicHeader() {
    SimpleAuthenticator authenticator = new SimpleAuthenticator();
    authenticator.initialize(new IcebergConfig());
    Principal principal =
        authenticator.authenticateToken("Bearer abc".getBytes(StandardCharsets.UTF_8));
    assertEquals(AuthConstants.ANONYMOUS_USER, principal.getName());
  }

  @Test
  void fallsBackToAnonymousForMalformedBase64() {
    SimpleAuthenticator authenticator = new SimpleAuthenticator();
    authenticator.initialize(new IcebergConfig());
    Principal principal =
        authenticator.authenticateToken(
            (AuthConstants.AUTHORIZATION_BASIC_HEADER + "???not-base64!!!")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(AuthConstants.ANONYMOUS_USER, principal.getName());
  }
}
