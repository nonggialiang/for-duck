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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.junit.jupiter.api.Test;

/** Verifies AuthenticatorFactory alias resolution and kerberos fail-fast rejection. */
class TestAuthenticatorFactory {

  private static IcebergConfig configWithAuthenticators(String... names) {
    return new IcebergConfig(
        Map.of(IcebergConfig.AUTHENTICATORS.getKey(), String.join(",", names)));
  }

  @Test
  void simpleAliasResolves() {
    List<Authenticator> authenticators =
        AuthenticatorFactory.createAuthenticators(configWithAuthenticators("simple"));
    assertEquals(1, authenticators.size());
    assertTrue(authenticators.get(0) instanceof SimpleAuthenticator);
  }

  @Test
  void oauthAliasResolves() {
    // createAuthenticators only instantiates; it does not initialize. Initialization requires a
    // full OAuth config which is exercised in TestServerAuthenticator / TestOAuth2TokenAuthenticator.
    IcebergConfig cfg =
        new IcebergConfig(
            Map.of(
                IcebergConfig.AUTHENTICATORS.getKey(), "oauth",
                OAuthConfig.SERVICE_AUDIENCE.getKey(), "x",
                OAuthConfig.DEFAULT_SIGN_KEY.getKey(),
                java.util.Base64.getEncoder().encodeToString(new byte[16])));
    List<Authenticator> authenticators = AuthenticatorFactory.createAuthenticators(cfg);
    assertEquals(1, authenticators.size());
    assertTrue(authenticators.get(0) instanceof OAuth2TokenAuthenticator);
  }

  @Test
  void multipleAuthenticatorsResolveInOrder() {
    List<Authenticator> authenticators =
        AuthenticatorFactory.createAuthenticators(configWithAuthenticators("simple", "oauth"));
    assertEquals(2, authenticators.size());
    assertTrue(authenticators.get(0) instanceof SimpleAuthenticator);
    assertTrue(authenticators.get(1) instanceof OAuth2TokenAuthenticator);
  }

  @Test
  void kerberosAliasIsRejectedAtStartup() {
    IcebergConfig cfg = configWithAuthenticators("kerberos");
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> AuthenticatorFactory.createAuthenticators(cfg));
    assertTrue(ex.getMessage().toLowerCase().contains("kerberos"));
  }

  @Test
  void kerberosMixedWithOtherAliasesIsAlsoRejected() {
    IcebergConfig cfg = configWithAuthenticators("simple", "kerberos");
    assertThrows(IllegalArgumentException.class, () -> AuthenticatorFactory.createAuthenticators(cfg));
  }

  @Test
  void customClassNameIsLoaded() {
    List<Authenticator> authenticators =
        AuthenticatorFactory.createAuthenticators(
            configWithAuthenticators(SimpleAuthenticator.class.getName()));
    assertEquals(1, authenticators.size());
    assertTrue(authenticators.get(0) instanceof SimpleAuthenticator);
  }
}
