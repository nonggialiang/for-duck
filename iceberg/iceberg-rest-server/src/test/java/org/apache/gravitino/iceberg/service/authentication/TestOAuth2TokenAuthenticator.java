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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.junit.jupiter.api.Test;

/**
 * Verifies OAuth2TokenAuthenticator + StaticSignKeyValidator with real RSA-signed JWTs, covering
 * fail-fast initialization, token validation, principal-field fallback, and principal-mapper regex.
 */
class TestOAuth2TokenAuthenticator {

  private static byte[] bearer(String token) {
    return (AuthConstants.AUTHORIZATION_BEARER_HEADER + token).getBytes(StandardCharsets.UTF_8);
  }

  private static OAuth2TokenAuthenticator initialized(Map<String, String> overrides) {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    authenticator.initialize(new IcebergConfig(OAuthTestKeys.configWith(overrides)));
    return authenticator;
  }

  // ---- happy path ----

  @Test
  void validatesValidBearerToken() {
    OAuth2TokenAuthenticator authenticator = initialized(java.util.Collections.emptyMap());
    Object principal = authenticator.authenticateToken(bearer(OAuthTestKeys.jwt("alice", OAuthTestKeys.AUDIENCE)));
    assertEquals("alice", ((UserPrincipal) principal).getName());
  }

  @Test
  void supportsOnlyBearerHeader() {
    OAuth2TokenAuthenticator authenticator = initialized(java.util.Collections.emptyMap());
    assertEquals(false, authenticator.supportsToken(null));
    assertEquals(false, authenticator.supportsToken("Basic abc".getBytes(StandardCharsets.UTF_8)));
    assertEquals(true, authenticator.supportsToken(bearer("anything")));
  }

  // ---- token / header rejection ----

  @Test
  void rejectsInvalidBearerHeader() {
    OAuth2TokenAuthenticator authenticator = initialized(java.util.Collections.emptyMap());
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken("Basic abc".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsBlankBearerToken() {
    OAuth2TokenAuthenticator authenticator = initialized(java.util.Collections.emptyMap());
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken(bearer("")));
  }

  @Test
  void rejectsWrongAudience() {
    OAuth2TokenAuthenticator authenticator = initialized(java.util.Collections.emptyMap());
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken(bearer(OAuthTestKeys.jwt("alice", "wrong-audience"))));
  }

  @Test
  void rejectsGarbledToken() {
    OAuth2TokenAuthenticator authenticator = initialized(java.util.Collections.emptyMap());
    assertThrows(UnauthorizedException.class, () -> authenticator.authenticateToken(bearer("not.a.jwt")));
  }

  @Test
  void rejectsExpiredToken() {
    OAuth2TokenAuthenticator authenticator = initialized(java.util.Collections.emptyMap());
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken(bearer(OAuthTestKeys.expiredJwt("alice", OAuthTestKeys.AUDIENCE))));
  }

  @Test
  void rejectsTokenWithoutConfiguredPrincipalField() {
    // Token only has sub, but config asks for preferred_username first; sub is removed from the
    // field list, so no principal can be extracted.
    OAuth2TokenAuthenticator authenticator =
        initialized(Map.of(OAuthConfig.PRINCIPAL_FIELDS.getKey(), "preferred_username"));
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken(bearer(OAuthTestKeys.jwt("alice", OAuthTestKeys.AUDIENCE))));
  }

  // ---- principal field fallback + mapping ----

  @Test
  void firstPrincipalFieldWinsWhenPresent() {
    // Token carries both sub and preferred_username; with preferred_username listed first it wins.
    OAuth2TokenAuthenticator authenticator =
        initialized(Map.of(OAuthConfig.PRINCIPAL_FIELDS.getKey(), "preferred_username,sub"));
    String token =
        OAuthTestKeys.jwtWithClaim("some-sub", OAuthTestKeys.AUDIENCE, "preferred_username", "alice");
    Object principal = authenticator.authenticateToken(bearer(token));
    assertEquals("alice", ((UserPrincipal) principal).getName());
  }

  @Test
  void principalFieldFallsThroughWhenFirstAbsent() {
    // 'missing_claim' is not in the token, so the validator falls through to 'sub'.
    OAuth2TokenAuthenticator authenticator =
        initialized(Map.of(OAuthConfig.PRINCIPAL_FIELDS.getKey(), "missing_claim,sub"));
    Object principal =
        authenticator.authenticateToken(bearer(OAuthTestKeys.jwt("alice", OAuthTestKeys.AUDIENCE)));
    assertEquals("alice", ((UserPrincipal) principal).getName());
  }

  @Test
  void principalMapperRegexIsApplied() {
    // Extract local-part from an email-style subject.
    OAuth2TokenAuthenticator authenticator =
        initialized(
            Map.of(
                OAuthConfig.PRINCIPAL_MAPPER.getKey(), "regex",
                OAuthConfig.PRINCIPAL_MAPPER_REGEX_PATTERN.getKey(), "^([^@]+)@.*$"));
    Object principal =
        authenticator.authenticateToken(bearer(OAuthTestKeys.jwt("alice@example.com", OAuthTestKeys.AUDIENCE)));
    assertEquals("alice", ((UserPrincipal) principal).getName());
  }

  // ---- fail-fast initialization ----

  @Test
  void blankServiceAudienceFailsFast() {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    assertThrows(
        IllegalArgumentException.class,
        () -> authenticator.initialize(new IcebergConfig(OAuthTestKeys.configWith(Map.of(OAuthConfig.SERVICE_AUDIENCE.getKey(), "")))));
  }

  @Test
  void missingDefaultSignKeyFailsFast() {
    Map<String, String> props = OAuthTestKeys.configWith(java.util.Collections.emptyMap());
    props.remove(OAuthConfig.DEFAULT_SIGN_KEY.getKey());
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    // StaticSignKeyValidator.initialize decodes the blank/null key and throws IllegalArgumentException.
    assertThrows(
        Exception.class,
        () -> authenticator.initialize(new IcebergConfig(props)));
  }

  @Test
  void unknownValidatorClassFailsFast() {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            authenticator.initialize(
                new IcebergConfig(
                    OAuthTestKeys.configWith(
                        Map.of(OAuthConfig.TOKEN_VALIDATOR_CLASS.getKey(), "org.does.not.Exist")))));
  }
}
