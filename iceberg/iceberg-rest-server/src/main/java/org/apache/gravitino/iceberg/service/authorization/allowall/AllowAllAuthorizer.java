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
package org.apache.gravitino.iceberg.service.authorization.allowall;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;

import org.apache.gravitino.credential.CredentialPrivilege;

public class AllowAllAuthorizer implements IcebergAuthorizer {

  public static final String NAME = "allow-all";

  @Override
  public boolean checkOperation(
      String userName, IcebergOperation op, IcebergResource resource) {
    return true;
  }

  @Override
  public CredentialPrivilege checkCredential(String userName, IcebergResource resource) {
    return CredentialPrivilege.WRITE;
  }

  @Override
  public void registerOwner(String catalog, String namespace, String resource, String owner) {}

  @Override
  public void removeOwner(String catalog, String namespace, String resource) {}
}
