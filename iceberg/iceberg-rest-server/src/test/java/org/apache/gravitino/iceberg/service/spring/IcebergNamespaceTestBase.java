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
package org.apache.gravitino.iceberg.service.spring;

import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTUtil;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.responses.CreateNamespaceResponse;
import org.apache.iceberg.rest.responses.GetNamespaceResponse;
import org.apache.iceberg.rest.responses.ListNamespacesResponse;
import org.apache.iceberg.rest.responses.UpdateNamespacePropertiesResponse;
import org.junit.jupiter.api.Assertions;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/** Provides MockMvc-based verify helpers for namespace operations. */
public abstract class IcebergNamespaceTestBase extends IcebergRestTestBase {

  private final Map<String, String> properties = ImmutableMap.of("a", "b");
  private final Map<String, String> updatedProperties = ImmutableMap.of("b", "c");

  protected void verifyCreateNamespaceSucc(String... name) throws Exception {
    verifyCreateNamespaceSucc(Namespace.of(name));
  }

  protected void verifyCreateNamespaceSucc(Namespace name) throws Exception {
    CreateNamespaceRequest request =
        CreateNamespaceRequest.builder().withNamespace(name).setProperties(properties).build();
    MvcResult result =
        doPost(getNamespacePath(Optional.empty(), Optional.empty()), request)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    CreateNamespaceResponse namespaceResponse =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), CreateNamespaceResponse.class);
    Assertions.assertTrue(namespaceResponse.namespace().equals(name));
    Assertions.assertEquals(namespaceResponse.properties(), properties);
  }

  protected void verifyCreateNamespaceFail(int statusCode, Namespace name) throws Exception {
    CreateNamespaceRequest request =
        CreateNamespaceRequest.builder().withNamespace(name).setProperties(properties).build();
    doPost(getNamespacePath(Optional.empty(), Optional.empty()), request)
        .andExpect(MockMvcResultMatchers.status().is(statusCode));
  }

  protected void verifyLoadNamespaceSucc(Namespace name) throws Exception {
    MvcResult result =
        doGet(getNamespacePath(Optional.of(name), Optional.empty()))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    GetNamespaceResponse r =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), GetNamespaceResponse.class);
    Assertions.assertEquals(name, r.namespace());
    Assertions.assertEquals(properties, r.properties());
  }

  protected void verifyLoadNamespaceFail(int status, Namespace name) throws Exception {
    doGet(getNamespacePath(Optional.of(name), Optional.empty()))
        .andExpect(MockMvcResultMatchers.status().is(status));
  }

  protected void verifyDropNamespaceSucc(Namespace name) throws Exception {
    doDelete(getNamespacePath(Optional.of(name), Optional.empty()))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
  }

  protected void verifyDropNamespaceFail(int status, Namespace name) throws Exception {
    doDelete(getNamespacePath(Optional.of(name), Optional.empty()))
        .andExpect(MockMvcResultMatchers.status().is(status));
  }

  protected void verifyNamespaceExistsStatusCode(int status, Namespace name) throws Exception {
    doHead(getNamespacePath(Optional.of(name), Optional.empty()))
        .andExpect(MockMvcResultMatchers.status().is(status));
  }

  protected void verifyListNamespaceSucc(Optional<Namespace> parent, List<String> schemas)
      throws Exception {
    Optional<Map<String, String>> queryParam =
        parent.isPresent()
            ? Optional.of(ImmutableMap.of("parent", RESTUtil.encodeNamespace(parent.get())))
            : Optional.empty();
    MvcResult result =
        doGet(getNamespacePath(Optional.empty(), Optional.empty()), queryParam)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    ListNamespacesResponse r =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), ListNamespacesResponse.class);
    List<String> ns =
        r.namespaces().stream().map(Namespace::toString).collect(Collectors.toList());
    Assertions.assertEquals(schemas, ns);
  }

  protected void verifyListNamespaceFail(Optional<Namespace> parent, int status) throws Exception {
    Optional<Map<String, String>> queryParam =
        parent.isPresent()
            ? Optional.of(ImmutableMap.of("parent", RESTUtil.encodeNamespace(parent.get())))
            : Optional.empty();
    doGet(getNamespacePath(Optional.empty(), Optional.empty()), queryParam)
        .andExpect(MockMvcResultMatchers.status().is(status));
  }

  protected void verifyUpdateNamespaceSucc(Namespace name) throws Exception {
    UpdateNamespacePropertiesRequest request =
        UpdateNamespacePropertiesRequest.builder()
            .removeAll(Arrays.asList("a", "a1"))
            .updateAll(updatedProperties)
            .build();
    MvcResult result =
        doPost(getUpdateNamespacePath(name), request)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    UpdateNamespacePropertiesResponse r =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), UpdateNamespacePropertiesResponse.class);
    Assertions.assertEquals(Arrays.asList("a"), r.removed());
    Assertions.assertEquals(Arrays.asList("a1"), r.missing());
    Assertions.assertEquals(Arrays.asList("b"), r.updated());
  }

  protected void verifyUpdateNamespaceFail(int status, Namespace name) throws Exception {
    UpdateNamespacePropertiesRequest request =
        UpdateNamespacePropertiesRequest.builder()
            .removeAll(Arrays.asList("a", "a1"))
            .updateAll(updatedProperties)
            .build();
    doPost(getUpdateNamespacePath(name), request)
        .andExpect(MockMvcResultMatchers.status().is(status));
  }

  protected void verifyRegisterTableSucc(String tableName, Namespace ns) throws Exception {
    RegisterTableRequest request =
        ImmutableRegisterTableRequest.builder().name(tableName).metadataLocation("mock").build();
    doPost(getNamespacePath(Optional.of(ns), Optional.of("register")), request)
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  protected void verifyRegisterTableFail(int statusCode, String tableName, Namespace ns)
      throws Exception {
    RegisterTableRequest request =
        ImmutableRegisterTableRequest.builder().name(tableName).metadataLocation("mock").build();
    doPost(getNamespacePath(Optional.of(ns), Optional.of("register")), request)
        .andExpect(MockMvcResultMatchers.status().is(statusCode));
  }

  protected void dropAllExistingNamespace() throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
      MvcResult result =
          doGet(getNamespacePath(Optional.empty(), Optional.empty()))
              .andExpect(MockMvcResultMatchers.status().isOk())
              .andReturn();
      ListNamespacesResponse r =
          objectMapper.readValue(
              result.getResponse().getContentAsString(), ListNamespacesResponse.class);
      if (r.namespaces().isEmpty()) {
        return;
      }
      // Sort by depth descending so child namespaces are dropped before their parents.
      List<Namespace> sorted =
          r.namespaces().stream()
              .sorted((a, b) -> Integer.compare(b.length(), a.length()))
              .collect(Collectors.toList());
      for (Namespace ns : sorted) {
        dropAllTablesInNamespace(ns);
        dropAllViewsInNamespace(ns);
        try {
          doDelete(getNamespacePath(Optional.of(ns), Optional.empty()))
              .andExpect(MockMvcResultMatchers.status().isNoContent());
        } catch (AssertionError | Exception ignored) {
          // Namespace may have children; will retry in the next iteration.
        }
      }
    }
  }

  private void dropAllTablesInNamespace(Namespace ns) throws Exception {
    MvcResult tableResult;
    try {
      tableResult = doGet(getTablePath(ns, Optional.empty())).andReturn();
    } catch (Exception e) {
      return;
    }
    if (tableResult.getResponse().getStatus() != 200) {
      return;
    }
    org.apache.iceberg.rest.responses.ListTablesResponse tableResp =
        objectMapper.readValue(
            tableResult.getResponse().getContentAsString(),
            org.apache.iceberg.rest.responses.ListTablesResponse.class);
    for (org.apache.iceberg.catalog.TableIdentifier ti : tableResp.identifiers()) {
      doDelete(getTablePath(ns, Optional.of(ti.name())));
    }
  }

  private void dropAllViewsInNamespace(Namespace ns) throws Exception {
    MvcResult viewResult;
    try {
      viewResult = doGet(getViewPath(ns, Optional.empty())).andReturn();
    } catch (Exception e) {
      return; // catalog may not support views
    }
    int status = viewResult.getResponse().getStatus();
    if (status != 200) {
      return;
    }
    org.apache.iceberg.rest.responses.ListTablesResponse viewResp =
        objectMapper.readValue(
            viewResult.getResponse().getContentAsString(),
            org.apache.iceberg.rest.responses.ListTablesResponse.class);
    for (org.apache.iceberg.catalog.TableIdentifier ti : viewResp.identifiers()) {
      doDelete(getViewPath(ns, Optional.of(ti.name())));
    }
  }
}
