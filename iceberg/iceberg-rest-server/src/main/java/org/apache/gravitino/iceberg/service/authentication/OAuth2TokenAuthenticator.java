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

import com.google.common.base.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 2.0 authenticator supporting pluggable {@link OAuthTokenValidator}s.
 */
public class OAuth2TokenAuthenticator implements Authenticator {
  private static final Logger LOG = LoggerFactory.getLogger(OAuth2TokenAuthenticator.class);

  private String serviceAudience;
  private OAuthTokenValidator tokenValidator;

  @Override
  public boolean isDataFromToken() {
    return true;
  }

  @Override
  public void initialize(Config config) throws RuntimeException {
    this.serviceAudience = config.get(OAuthConfig.SERVICE_AUDIENCE);
    Preconditions.checkArgument(
        StringUtils.isNotBlank(serviceAudience),
        "Service audience cannot be blank for OAuth authentication");
    this.tokenValidator = OAuthTokenValidatorFactory.createValidator(config);
  }

  @Override
  public Principal authenticateToken(byte[] tokenData) {
    if (tokenData == null) {
      LOG.warn("Empty token authorization header");
      throw new UnauthorizedException("Empty token authorization header");
    }
    String authData = new String(tokenData, StandardCharsets.UTF_8);
    if (StringUtils.isBlank(authData)
        || !authData.startsWith(AuthConstants.AUTHORIZATION_BEARER_HEADER)) {
      LOG.warn("Invalid token authorization header format");
      throw new UnauthorizedException("Invalid token authorization header");
    }
    String token = authData.substring(AuthConstants.AUTHORIZATION_BEARER_HEADER.length());
    if (StringUtils.isBlank(token)) {
      throw new UnauthorizedException("Blank token found");
    }
    try {
      return tokenValidator.validateToken(token, serviceAudience);
    } catch (UnauthorizedException e) {
      throw e;
    } catch (Exception e) {
      LOG.error("JWT parse error: {}", e.getMessage(), e);
      throw new UnauthorizedException(e, "JWT parse error");
    }
  }

  @Override
  public boolean supportsToken(byte[] tokenData) {
    return tokenData != null
        && new String(tokenData, StandardCharsets.UTF_8)
            .startsWith(AuthConstants.AUTHORIZATION_BEARER_HEADER);
  }
}
