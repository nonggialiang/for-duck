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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.iceberg.types.Types.StringType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

public class TestSpringIcebergTableOperations extends IcebergNamespaceTestBase {

  private static final Schema TABLE_SCHEMA =
      new Schema(NestedField.of(1, false, "foo_string", StringType.get()));

  private static final Namespace NAMESPACE = Namespace.of("table_test_ns");

  @BeforeEach
  public void setUpNamespace() throws Exception {
    dropAllExistingNamespace();
    verifyCreateNamespaceSucc(NAMESPACE.toString());
  }

  @AfterEach
  public void cleanUp() throws Exception {
    dropAllExistingNamespace();
  }

  private LoadTableResponse createTable(String name) throws Exception {
    CreateTableRequest createTableRequest =
        CreateTableRequest.builder().withName(name).withSchema(TABLE_SCHEMA).build();
    MvcResult result =
        doPost(getTablePath(NAMESPACE, Optional.empty()), createTableRequest)
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    return objectMapper.readValue(
        result.getResponse().getContentAsString(), LoadTableResponse.class);
  }

  @Test
  void testCreateAndLoadTable() throws Exception {
    createTable("table1");
    MvcResult result =
        doGet(getTablePath(NAMESPACE, Optional.of("table1")))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    LoadTableResponse response =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), LoadTableResponse.class);
    org.junit.jupiter.api.Assertions.assertNotNull(response.tableMetadata());
  }

  @Test
  void testListTables() throws Exception {
    createTable("list_table1");
    createTable("list_table2");
    MvcResult result =
        doGet(getTablePath(NAMESPACE, Optional.empty()))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    ListTablesResponse response =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), ListTablesResponse.class);
    List<String> tableNames =
        response.identifiers().stream()
            .map(TableIdentifier::name)
            .sorted()
            .collect(Collectors.toList());
    org.junit.jupiter.api.Assertions.assertEquals(Arrays.asList("list_table1", "list_table2"), tableNames);
  }

  @Test
  void testTableExists() throws Exception {
    createTable("exists_table1");
    doHead(getTablePath(NAMESPACE, Optional.of("exists_table1")))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
    doHead(getTablePath(NAMESPACE, Optional.of("not_exist_table")))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void testDropTable() throws Exception {
    createTable("drop_table1");
    doDelete(getTablePath(NAMESPACE, Optional.of("drop_table1")))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
    doHead(getTablePath(NAMESPACE, Optional.of("drop_table1")))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  @Test
  void testRenameTable() throws Exception {
    createTable("rename_source");
    RenameTableRequest renameRequest =
        RenameTableRequest.builder()
            .withSource(TableIdentifier.of(NAMESPACE, "rename_source"))
            .withDestination(TableIdentifier.of(NAMESPACE, "rename_dest"))
            .build();
    doPost(getRenameTablePath(), renameRequest)
        .andExpect(MockMvcResultMatchers.status().isNoContent());
    doHead(getTablePath(NAMESPACE, Optional.of("rename_dest")))
        .andExpect(MockMvcResultMatchers.status().isNoContent());
    doHead(getTablePath(NAMESPACE, Optional.of("rename_source")))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
