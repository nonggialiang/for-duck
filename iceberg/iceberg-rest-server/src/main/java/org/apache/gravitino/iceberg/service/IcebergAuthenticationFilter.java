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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Enumeration;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.DelegatedUserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.iceberg.service.authentication.Authenticator;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring servlet filter that authenticates every Iceberg REST request, captures the optional
 * {@code X-Iceberg-Access-Delegator} header, and runs the downstream request inside a JAAS {@link
 * javax.security.auth.Subject} carrying both the authenticated requester and the delegated user.
 *
 * <p>Authentication failures are rendered as Iceberg {@link ErrorResponse} JSON bodies (rather than
 * the servlet container's default HTML error pages) so that Iceberg REST clients such as the Java
 * {@code RESTCatalog} can parse them.
 */
public class IcebergAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergAuthenticationFilter.class);
  private static final ObjectMapper MAPPER = IcebergObjectMapper.getInstance();

  private final List<Authenticator> authenticators;

  public IcebergAuthenticationFilter(List<Authenticator> authenticators) {
    this.authenticators = authenticators;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      Principal requester = authenticate(request);
      Principal delegatedUser = extractDelegatedUser(request);

      request.setAttribute(AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME, requester);
      PrincipalUtils.doAs(
          requester,
          delegatedUser,
          () -> {
            filterChain.doFilter(request, response);
            return null;
          });
    } catch (UnauthorizedException ue) {
      if (!ue.getChallenges().isEmpty()) {
        for (String challenge : ue.getChallenges()) {
          response.setHeader(AuthConstants.HTTP_CHALLENGE_HEADER, challenge);
        }
      }
      sendErrorResponse(response, ue);
    } catch (Exception e) {
      sendErrorResponse(response, e);
    }
  }

  private Principal authenticate(HttpServletRequest request) {
    Enumeration<String> headerData = request.getHeaders(AuthConstants.HTTP_HEADER_AUTHORIZATION);
    byte[] authData = null;
    if (headerData.hasMoreElements()) {
      authData = headerData.nextElement().getBytes(StandardCharsets.UTF_8);
    }

    for (Authenticator authenticator : authenticators) {
      if (authenticator.supportsToken(authData) && authenticator.isDataFromToken()) {
        Principal principal = authenticator.authenticateToken(authData);
        if (principal != null) {
          return principal;
        }
      }
    }
    throw new UnauthorizedException("The provided credentials did not support");
  }

  private static Principal extractDelegatedUser(HttpServletRequest request) {
    String delegator = request.getHeader(AuthConstants.ICEBERG_ACCESS_DELEGATOR_HEADER);
    if (StringUtils.isBlank(delegator)) {
      return null;
    }
    return new DelegatedUserPrincipal(delegator);
  }

  /**
   * Renders an Iceberg {@link ErrorResponse} JSON body for an authentication failure. Reuses the
   * Iceberg exception mapping so clients receive spec-compliant error types and codes.
   */
  protected void sendErrorResponse(HttpServletResponse response, Exception exception)
      throws IOException {
    Exception icebergException = IcebergExceptionMapper.convertToIcebergException(exception);
    int status = IcebergExceptionMapper.getErrorCode(icebergException);
    String message = icebergException.getMessage();
    if (StringUtils.isBlank(message)) {
      HttpStatus resolved = HttpStatus.resolve(status);
      message = resolved != null ? resolved.getReasonPhrase() : "Error";
    }
    String type = icebergException.getClass().getSimpleName();
    ErrorResponse errorResponse = HttpResponseBuilder.errorResponse(status, type, message);

    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try {
      MAPPER.writeValue(response.getWriter(), errorResponse);
    } catch (IOException ioe) {
      LOG.warn("Failed to write authentication error response: {}", ioe.getMessage());
    }
  }
}
