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

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Verifies OAuth2TokenAuthenticator + StaticSignKeyValidator with real RSA-signed JWTs. */
class TestOAuth2TokenAuthenticator {

  private static final String AUDIENCE = "iceberg-rest";
  private static PrivateKey privateKey;
  private static String publicKeyBase64;

  @BeforeAll
  static void generateKeys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    privateKey = keyPair.getPrivate();
    publicKeyBase64 =
        Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
  }

  private static IcebergConfig oauthConfig() {
    Map<String, String> props = new HashMap<>();
    props.put(OAuthConfig.SERVICE_AUDIENCE.getKey(), AUDIENCE);
    props.put(OAuthConfig.DEFAULT_SIGN_KEY.getKey(), publicKeyBase64);
    props.put(OAuthConfig.SIGNATURE_ALGORITHM_TYPE.getKey(), SignatureAlgorithm.RS256.name());
    props.put(OAuthConfig.DEFAULT_SERVER_URI.getKey(), "http://oauth.example.com");
    props.put(OAuthConfig.DEFAULT_TOKEN_PATH.getKey(), "/token");
    props.put(OAuthConfig.PRINCIPAL_FIELDS.getKey(), "sub");
    return new IcebergConfig(props);
  }

  private static String jwt(String subject, String audience) {
    return Jwts.builder()
        .setSubject(subject)
        .setAudience(audience)
        .signWith(privateKey, SignatureAlgorithm.RS256)
        .compact();
  }

  @Test
  void validatesValidBearerToken() {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    authenticator.initialize(oauthConfig());
    String token = jwt("alice", AUDIENCE);
    byte[] header = (AuthConstants.AUTHORIZATION_BEARER_HEADER + token).getBytes(StandardCharsets.UTF_8);

    Object principal = authenticator.authenticateToken(header);
    assertEquals("alice", ((UserPrincipal) principal).getName());
  }

  @Test
  void rejectsInvalidBearerHeader() {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    authenticator.initialize(oauthConfig());
    assertThrows(
        UnauthorizedException.class,
        () -> authenticator.authenticateToken("Basic abc".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejectsWrongAudience() {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    authenticator.initialize(oauthConfig());
    String token = jwt("alice", "wrong-audience");
    byte[] header = (AuthConstants.AUTHORIZATION_BEARER_HEADER + token).getBytes(StandardCharsets.UTF_8);
    assertThrows(UnauthorizedException.class, () -> authenticator.authenticateToken(header));
  }

  @Test
  void rejectsGarbledToken() {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    authenticator.initialize(oauthConfig());
    byte[] header =
        (AuthConstants.AUTHORIZATION_BEARER_HEADER + "not.a.jwt").getBytes(StandardCharsets.UTF_8);
    assertThrows(UnauthorizedException.class, () -> authenticator.authenticateToken(header));
  }

  @Test
  void supportsOnlyBearerHeader() {
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    authenticator.initialize(oauthConfig());
    assertEquals(false, authenticator.supportsToken(null));
    assertEquals(
        true,
        authenticator.supportsToken(
            (AuthConstants.AUTHORIZATION_BEARER_HEADER + "x").getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void blankServiceAudienceFailsFast() {
    Map<String, String> props = new HashMap<>(oauthConfig().getAllConfig());
    props.put(OAuthConfig.SERVICE_AUDIENCE.getKey(), "");
    OAuth2TokenAuthenticator authenticator = new OAuth2TokenAuthenticator();
    assertThrows(IllegalArgumentException.class, () -> authenticator.initialize(new IcebergConfig(props)));
  }
}
