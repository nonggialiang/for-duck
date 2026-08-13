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

import java.util.List;
import org.apache.gravitino.Config;

/**
 * Holds the initialized {@link Authenticator}s for the Iceberg REST server.
 *
 * <p>Unlike the Gravitino singleton, this is a plain, Spring-managed object instantiated by {@code
 * IcebergBeanConfig} so that authenticator lifecycle follows the Spring context.
 */
public class ServerAuthenticator {

  private final List<Authenticator> authenticators;

  public ServerAuthenticator(Config config) {
    this.authenticators = AuthenticatorFactory.createAuthenticators(config);
    for (Authenticator authenticator : authenticators) {
      authenticator.initialize(config);
    }
  }

  public List<Authenticator> authenticators() {
    return authenticators;
  }
}
