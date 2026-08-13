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
package org.apache.gravitino.auth;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.gravitino.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Regex-based {@link PrincipalMapper} that extracts a username using a regex pattern with a
 * capturing group.
 *
 * <p>Thread-safe: {@link Pattern#matcher(CharSequence)} returns a thread-local {@link Matcher}.
 */
public class RegexPrincipalMapper implements PrincipalMapper {

  private static final Logger LOG = LoggerFactory.getLogger(RegexPrincipalMapper.class);

  private final Pattern pattern;

  public RegexPrincipalMapper(String patternStr) {
    if (patternStr == null || patternStr.isEmpty()) {
      throw new IllegalArgumentException("Pattern string cannot be null or empty");
    }
    this.pattern = Pattern.compile(patternStr);
    LOG.info("Initialized RegexPrincipalMapper with pattern: {}", patternStr);
  }

  @Override
  public Principal map(String principal) {
    if (principal == null) {
      return null;
    }
    try {
      Matcher matcher = pattern.matcher(principal);
      if (matcher.find() && matcher.groupCount() >= 1) {
        String extracted = matcher.group(1);
        String username = (extracted != null && !extracted.isEmpty()) ? extracted : principal;
        return new UserPrincipal(username);
      }
      return new UserPrincipal(principal);
    } catch (Exception e) {
      String message =
          String.format(
              "Error applying regex pattern '%s' to principal '%s'", pattern.pattern(), principal);
      LOG.error("{}: {}", message, e.getMessage());
      throw new IllegalArgumentException(message, e);
    }
  }

  public String getPatternString() {
    return pattern.pattern();
  }
}
