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

import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateViewRequest;
import org.apache.iceberg.rest.requests.ImmutableCreateViewRequest;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.iceberg.types.Types.StringType;
import org.apache.iceberg.view.ImmutableSQLViewRepresentation;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.ViewRepresentation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

public class TestSpringIcebergViewOperations extends IcebergNamespaceTestBase {

  private static final Schema VIEW_SCHEMA =
      new Schema(NestedField.of(1, false, "view_col", StringType.get()));

  private static final Namespace NAMESPACE = Namespace.of("view_test_ns");

  @BeforeEach
  public void setUpNamespace() throws Exception {
    dropAllExistingNamespace();
    verifyCreateNamespaceSucc(NAMESPACE.toString());
  }

  @AfterEach
  public void cleanUp() throws Exception {
    dropAllExistingNamespace();
  }

  private LoadViewResponse createView(String name) throws Exception {
    ViewRepresentation sqlRepresentation =
        ImmutableSQLViewRepresentation.builder()
            .dialect("spark")
            .sql("SELECT 1 AS view_col")
            .build();
    org.apache.iceberg.view.ViewVersion viewVersion =
        ImmutableViewVersion.builder()
            .versionId(1)
            .timestampMillis(System.currentTimeMillis())
            .schemaId(0)
            .defaultNamespace(NAMESPACE)
            .addRepresentations(sqlRepresentation)
            .build();
    CreateViewRequest createViewRequest =
        ImmutableCreateViewRequest.builder()
            .name(name)
            .schema(VIEW_SCHEMA)
            .viewVersion(viewVersion)
            .location("/mock/view/" + name)
            .build();
    MvcResult result =
        doPost(getViewPath(NAMESPACE, java.util.Optional.empty()), createViewRequest)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    return objectMapper.readValue(
        result.getResponse().getContentAsString(), LoadViewResponse.class);
  }

  @Test
  void testCreateAndLoadView() throws Exception {
    createView("view1");
    MvcResult result =
        doGet(getViewPath(NAMESPACE, java.util.Optional.of("view1")))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    LoadViewResponse response =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), LoadViewResponse.class);
    org.junit.jupiter.api.Assertions.assertNotNull(response.metadata());
  }

  @Test
  void testViewExists() throws Exception {
    createView("exists_view");
    doHead(getViewPath(NAMESPACE, java.util.Optional.of("exists_view")))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
    doHead(getViewPath(NAMESPACE, java.util.Optional.of("not_exist_view")))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void testDropView() throws Exception {
    createView("drop_view");
    doDelete(getViewPath(NAMESPACE, java.util.Optional.of("drop_view")))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
    doHead(getViewPath(NAMESPACE, java.util.Optional.of("drop_view")))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void testListView() throws Exception {
    createView("list_view1");
    createView("list_view2");
    doGet(getViewPath(NAMESPACE, java.util.Optional.empty()))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }
}
