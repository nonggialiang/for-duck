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
package org.apache.gravitino;

import com.google.common.base.Preconditions;
import java.security.Principal;

/**
 * A {@link Principal} representing the delegated access user extracted from the {@code
 * X-Iceberg-Access-Delegator} header. It is carried alongside the authenticated requester in the
 * JAAS {@link javax.security.auth.Subject} so that authorization can prefer the delegated identity
 * while auditing keeps the requester.
 */
public final class DelegatedUserPrincipal implements Principal {

  private final String username;

  public DelegatedUserPrincipal(final String username) {
    Preconditions.checkArgument(username != null, "DelegatedUserPrincipal must have the username");
    this.username = username;
  }

  @Override
  public String getName() {
    return username;
  }

  @Override
  public int hashCode() {
    return username.hashCode();
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o instanceof DelegatedUserPrincipal) {
      return this.username.equals(((DelegatedUserPrincipal) o).username);
    }
    return false;
  }

  @Override
  public String toString() {
    return "[delegated-principal: " + this.username + "]";
  }
}
