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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.URL;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.auth.PrincipalMapper;
import org.apache.gravitino.auth.PrincipalMapperFactory;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic JWKS-based OAuth token validator. Uses the Nimbus JOSE+JWT library to validate JWT tokens
 * from any OAuth provider that exposes a JWKS endpoint.
 */
public class JwksTokenValidator implements OAuthTokenValidator {
  private static final Logger LOG = LoggerFactory.getLogger(JwksTokenValidator.class);

  private String jwksUri;
  private String expectedIssuer;
  private List<String> principalFields;
  private long allowSkewSeconds;
  private PrincipalMapper principalMapper;

  @Override
  public void initialize(Config config) {
    this.jwksUri = config.get(OAuthConfig.JWKS_URI);
    this.expectedIssuer = config.get(OAuthConfig.AUTHORITY);
    this.principalFields = config.get(OAuthConfig.PRINCIPAL_FIELDS);
    this.allowSkewSeconds = config.get(OAuthConfig.ALLOW_SKEW_SECONDS);
    String mapperType = config.get(OAuthConfig.PRINCIPAL_MAPPER);
    String regexPattern = config.get(OAuthConfig.PRINCIPAL_MAPPER_REGEX_PATTERN);
    this.principalMapper = PrincipalMapperFactory.create(mapperType, regexPattern);

    LOG.info("Initializing JWKS token validator");
    PreconditionsOrThrow(jwksUri);
    try {
      new URL(jwksUri);
    } catch (Exception e) {
      LOG.error("Invalid JWKS URI format: {}", jwksUri);
      throw new IllegalArgumentException("Invalid JWKS URI format: " + jwksUri, e);
    }
  }

  private static void PreconditionsOrThrow(String jwksUri) {
    if (StringUtils.isBlank(jwksUri)) {
      throw new IllegalArgumentException(
          "JWKS URI must be configured when using JWKS-based OAuth providers");
    }
  }

  @Override
  public Principal validateToken(String token, String serviceAudience) {
    if (token == null || token.trim().isEmpty()) {
      throw new UnauthorizedException("Token cannot be null or empty");
    }
    if (serviceAudience == null || serviceAudience.trim().isEmpty()) {
      throw new UnauthorizedException("Service audience cannot be null or empty");
    }
    try {
      SignedJWT signedJWT = SignedJWT.parse(token);
      JWKSource<SecurityContext> jwkSource = createJwkSource();
      JWSAlgorithm algorithm = JWSAlgorithm.parse(signedJWT.getHeader().getAlgorithm().getName());
      JWSKeySelector<SecurityContext> keySelector =
          new JWSVerificationKeySelector<>(algorithm, jwkSource);

      DefaultJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
      jwtProcessor.setJWSKeySelector(keySelector);

      Set<String> acceptedAudiences =
          StringUtils.isNotBlank(serviceAudience)
              ? Collections.singleton(serviceAudience)
              : null;
      JWTClaimsSet.Builder exactMatchBuilder = new JWTClaimsSet.Builder();
      if (StringUtils.isNotBlank(expectedIssuer)) {
        exactMatchBuilder.issuer(expectedIssuer);
      }
      JWTClaimsSet exactMatchClaims = exactMatchBuilder.build();
      DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier =
          new DefaultJWTClaimsVerifier<>(acceptedAudiences, exactMatchClaims, null, null);
      claimsVerifier.setMaxClockSkew((int) allowSkewSeconds);
      jwtProcessor.setJWTClaimsSetVerifier(claimsVerifier);

      JWTClaimsSet validatedClaims = jwtProcessor.process(signedJWT, null);
      String principal = extractPrincipal(validatedClaims);
      if (principal == null) {
        throw new UnauthorizedException("No valid principal found in token");
      }
      return principalMapper.map(principal);
    } catch (Exception e) {
      LOG.error("JWKS JWT validation error: {}", e.getMessage());
      throw new UnauthorizedException(e, "JWKS JWT validation error");
    }
  }

  private JWKSource<SecurityContext> createJwkSource() throws Exception {
    try {
      return JWKSourceBuilder.create(new URL(jwksUri)).build();
    } catch (Exception e) {
      LOG.error("Failed to create JWKS source from URI: {}", jwksUri, e);
      throw new Exception("Failed to create JWKS source: " + e.getMessage(), e);
    }
  }

  private String extractPrincipal(JWTClaimsSet validatedClaims) {
    if (principalFields != null && !principalFields.isEmpty()) {
      for (String field : principalFields) {
        if (StringUtils.isNotBlank(field)) {
          Object principal = validatedClaims.getClaim(field);
          if (principal != null) {
            return principal.toString();
          }
        }
      }
    }
    return null;
  }
}
