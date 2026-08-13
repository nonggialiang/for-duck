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

import java.util.Map;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ServerAuthenticator startup path: it builds authenticators via the factory and
 * initializes each of them, failing fast when required OAuth settings are missing or when kerberos
 * is configured.
 */
class TestServerAuthenticator {

  @Test
  void initializesSimpleAuthenticatorByDefault() {
    ServerAuthenticator serverAuthenticator = new ServerAuthenticator(new IcebergConfig());
    assertEquals(1, serverAuthenticator.authenticators().size());
    assertTrue(serverAuthenticator.authenticators().get(0) instanceof SimpleAuthenticator);
  }

  @Test
  void oauthWithMissingSignKeyFailsFastAtStartup() {
    IcebergConfig config =
        new IcebergConfig(
            Map.of(
                IcebergConfig.AUTHENTICATORS.getKey(), "oauth",
                OAuthConfig.SERVICE_AUDIENCE.getKey(), OAuthTestKeys.AUDIENCE));
    assertThrows(Exception.class, () -> new ServerAuthenticator(config));
  }

  @Test
  void oauthWithRealKeyInitializes() {
    IcebergConfig config =
        new IcebergConfig(
            Map.of(
                IcebergConfig.AUTHENTICATORS.getKey(), "oauth",
                OAuthConfig.SERVICE_AUDIENCE.getKey(), OAuthTestKeys.AUDIENCE,
                OAuthConfig.DEFAULT_SIGN_KEY.getKey(),
                OAuthTestKeys.configWith(java.util.Collections.emptyMap())
                    .get(OAuthConfig.DEFAULT_SIGN_KEY.getKey())));
    ServerAuthenticator serverAuthenticator = new ServerAuthenticator(config);
    assertEquals(1, serverAuthenticator.authenticators().size());
    assertTrue(serverAuthenticator.authenticators().get(0) instanceof OAuth2TokenAuthenticator);
  }

  @Test
  void kerberosAliasFailsFastAtStartup() {
    IcebergConfig config =
        new IcebergConfig(Map.of(IcebergConfig.AUTHENTICATORS.getKey(), "kerberos"));
    assertThrows(IllegalArgumentException.class, () -> new ServerAuthenticator(config));
  }
}
