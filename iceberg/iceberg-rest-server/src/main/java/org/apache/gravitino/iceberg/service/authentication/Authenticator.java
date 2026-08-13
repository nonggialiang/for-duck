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

import java.security.Principal;
import org.apache.gravitino.Config;

/** Authentication mechanism for HTTP/HTTPS requests to the Iceberg REST server. */
public interface Authenticator {

  /** Whether the data used to authenticate is derived from the request token. */
  default boolean isDataFromToken() {
    return false;
  }

  /**
   * Authenticates using the token data.
   *
   * @param tokenData the data used for authentication
   * @return the authenticated user principal
   */
  default Principal authenticateToken(byte[] tokenData) {
    throw new UnsupportedOperationException(
        "Authenticator doesn't support to authenticate the data from the token");
  }

  /**
   * Initializes the authenticator.
   *
   * @param config the config for the authenticator
   * @throws RuntimeException if initialization fails
   */
  void initialize(Config config) throws RuntimeException;

  /**
   * Determines if the provided token data is supported by this authenticator.
   *
   * @param tokenData the byte array containing the token data
   * @return true if the token data is supported and can be authenticated
   */
  default boolean supportsToken(byte[] tokenData) {
    return false;
  }
}
