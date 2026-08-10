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
package org.apache.gravitino.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.iceberg.service.extension.DummyCredentialProvider;
import org.junit.jupiter.api.Test;

class TestCatalogCredentialManager {

  @Test
  void testNoProvidersThrows() {
    CatalogCredentialManager manager = new CatalogCredentialManager("cat", new HashMap<>());
    assertThrows(IllegalArgumentException.class, () -> manager.getCredential(null));
    manager.close();
  }

  @Test
  void testSingleProviderReturnsCredential() {
    Map<String, String> props = new HashMap<>();
    props.put(
        CredentialConstants.CREDENTIAL_PROVIDERS,
        DummyCredentialProvider.DUMMY_CREDENTIAL_TYPE);
    CatalogCredentialManager manager = new CatalogCredentialManager("cat", props);

    Credential cred = manager.getCredential(null);
    assertNotNull(cred);
    assertEquals(DummyCredentialProvider.DUMMY_CREDENTIAL_TYPE, cred.credentialType());
    manager.close();
  }

  @Test
  void testGetCredentialByType() {
    Map<String, String> props = new HashMap<>();
    props.put(
        CredentialConstants.CREDENTIAL_PROVIDERS,
        DummyCredentialProvider.DUMMY_CREDENTIAL_TYPE);
    CatalogCredentialManager manager = new CatalogCredentialManager("cat", props);

    Credential cred =
        manager.getCredential(DummyCredentialProvider.DUMMY_CREDENTIAL_TYPE, null);
    assertNotNull(cred);
    manager.close();
  }

  @Test
  void testGetCredentialByUnknownTypeThrows() {
    Map<String, String> props = new HashMap<>();
    props.put(
        CredentialConstants.CREDENTIAL_PROVIDERS,
        DummyCredentialProvider.DUMMY_CREDENTIAL_TYPE);
    CatalogCredentialManager manager = new CatalogCredentialManager("cat", props);

    assertThrows(IllegalStateException.class, () -> manager.getCredential("unknown-type", null));
    manager.close();
  }
}
