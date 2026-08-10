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
import java.util.Collections;
import org.apache.iceberg.catalog.Namespace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestSpringIcebergNamespaceOperations extends IcebergNamespaceTestBase {

  @BeforeEach
  public void cleanBefore() throws Exception {
    dropAllExistingNamespace();
  }

  @AfterEach
  public void cleanUp() throws Exception {
    dropAllExistingNamespace();
  }

  @Test
  void testCreateNamespace() throws Exception {
    verifyCreateNamespaceSucc("create_foo1");
    verifyCreateNamespaceFail(409, Namespace.of("create_foo1"));
    verifyCreateNamespaceSucc("create_foo2", "create_foo3");
  }

  @Test
  void testLoadNamespace() throws Exception {
    verifyCreateNamespaceSucc("load_foo1");
    verifyLoadNamespaceSucc(Namespace.of("load_foo1"));
    verifyLoadNamespaceFail(404, Namespace.of("load_foo_not_exist"));
  }

  @Test
  void testNamespaceExists() throws Exception {
    verifyCreateNamespaceSucc("exists_foo1");
    verifyNamespaceExistsStatusCode(204, Namespace.of("exists_foo1"));
    verifyNamespaceExistsStatusCode(404, Namespace.of("exists_foo_not_exist"));
  }

  @Test
  void testDropNamespace() throws Exception {
    verifyCreateNamespaceSucc("drop_foo1");
    verifyDropNamespaceSucc(Namespace.of("drop_foo1"));
    verifyDropNamespaceFail(404, Namespace.of("drop_foo_not_exist"));
  }

  @Test
  void testListNamespace() throws Exception {
    verifyCreateNamespaceSucc("list_foo1");
    verifyCreateNamespaceSucc("list_foo2");
    verifyListNamespaceSucc(
        java.util.Optional.empty(),
        Arrays.asList("list_foo1", "list_foo2"));
  }

  @Test
  void testUpdateNamespace() throws Exception {
    verifyCreateNamespaceSucc("update_foo1");
    verifyUpdateNamespaceSucc(Namespace.of("update_foo1"));
  }

  @Test
  void testRegisterTable() throws Exception {
    verifyCreateNamespaceSucc("register_foo1");
    verifyRegisterTableSucc("register_table1", Namespace.of("register_foo1"));
    verifyRegisterTableFail(409, "fail_table", Namespace.of("register_foo1"));
  }
}
