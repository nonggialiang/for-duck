/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.gravitino.iceberg.service.authorization;

import org.apache.gravitino.credential.CredentialPrivilege;

public interface IcebergAuthorizer {

  /**
   * Checks whether the user is authorized to perform an operation on a resource.
   *
   * @param userName the authenticated user name
   * @param op the operation being attempted
   * @param resource the target resource
   * @return true if authorized, false otherwise
   */
  boolean checkOperation(String userName, IcebergOperation op, IcebergResource resource);

  /**
   * Determines the credential privilege level for a user on a resource.
   *
   * @param userName the authenticated user name
   * @param resource the target resource
   * @return the credential privilege level, or null if no access
   */
  CredentialPrivilege checkCredential(String userName, IcebergResource resource);

  /**
   * Registers ownership of a resource when it is created.
   *
   * @param catalog the catalog name
   * @param namespace the namespace (schema)
   * @param resource the resource name
   * @param owner the owner user name
   */
  void registerOwner(String catalog, String namespace, String resource, String owner);

  /**
   * Removes ownership of a resource when it is dropped.
   *
   * @param catalog the catalog name
   * @param namespace the namespace (schema)
   * @param resource the resource name
   */
  void removeOwner(String catalog, String namespace, String resource);
}
