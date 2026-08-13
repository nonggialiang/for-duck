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

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared RSA key pair + JWT builder + OAuth config builder for tests that exercise the OAuth
 * authenticator / StaticSignKeyValidator with real RSA-signed tokens.
 */
public final class OAuthTestKeys {

  public static final String AUDIENCE = "iceberg-rest";

  private static final PrivateKey PRIVATE_KEY;
  private static final String PUBLIC_KEY_BASE64;

  static {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      PRIVATE_KEY = keyPair.getPrivate();
      PUBLIC_KEY_BASE64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  public static PrivateKey privateKey() {
    return PRIVATE_KEY;
  }

  /** Builds an OAuth config map with the given overrides applied on top of the defaults. */
  public static Map<String, String> configWith(Map<String, String> overrides) {
    Map<String, String> props = new HashMap<>();
    props.put(OAuthConfig.SERVICE_AUDIENCE.getKey(), AUDIENCE);
    props.put(OAuthConfig.DEFAULT_SIGN_KEY.getKey(), PUBLIC_KEY_BASE64);
    props.put(OAuthConfig.SIGNATURE_ALGORITHM_TYPE.getKey(), SignatureAlgorithm.RS256.name());
    props.put(OAuthConfig.PRINCIPAL_FIELDS.getKey(), "sub");
    props.putAll(overrides);
    return props;
  }

  public static String jwt(String subject, String audience) {
    return Jwts.builder()
        .setSubject(subject)
        .setAudience(audience)
        .signWith(PRIVATE_KEY, SignatureAlgorithm.RS256)
        .compact();
  }

  public static String jwtWithClaim(
      String subject, String audience, String claimName, Object claimValue) {
    return Jwts.builder()
        .setSubject(subject)
        .setAudience(audience)
        .claim(claimName, claimValue)
        .signWith(PRIVATE_KEY, SignatureAlgorithm.RS256)
        .compact();
  }

  public static String expiredJwt(String subject, String audience) {
    return Jwts.builder()
        .setSubject(subject)
        .setAudience(audience)
        .setExpiration(new Date(System.currentTimeMillis() - 60_000L))
        .signWith(PRIVATE_KEY, SignatureAlgorithm.RS256)
        .compact();
  }

  private OAuthTestKeys() {}
}
