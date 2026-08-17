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

package org.apache.gravitino.iceberg.service.spring;

import java.security.PrivilegedExceptionAction;
import java.util.Optional;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.service.authorization.IcebergAuthorizer;
import org.apache.gravitino.iceberg.service.authorization.IcebergOperation;
import org.apache.gravitino.iceberg.service.authorization.IcebergResource;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.LoadViewResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * End-to-end MockMvc test of the entitlement dynamic-view flow: alice (read-only with row filter)
 * sees a 404 on the raw table and a synthesized filtered view; bob (write privilege) reads the raw
 * table; dave (both) gets an explicit conflict error; the suffixed {@code t1@entitlement} request
 * of an entitled user returns the real table without loops.
 */
public class TestSpringIcebergEntitlementViews extends IcebergNamespaceTestBase {

  private static final Schema TABLE_SCHEMA =
      new Schema(
          java.util.Arrays.asList(
              Types.NestedField.required(1, "id", Types.LongType.get()),
              Types.NestedField.optional(2, "region", Types.StringType.get())));

  private static final Namespace NAMESPACE = Namespace.of("entitlement_test_ns");

  /**
   * alice=row-filter only, bob=write only, dave=both, everyone else (incl. carol)=neither. The
   * UPDATE_TABLE check doubles as the write-privilege signal.
   */
  @TestConfiguration
  static class EntitlementTestConfig {

    @Bean
    @Primary
    IcebergAuthorizer stubEntitlementAuthorizer() {
      return new StubEntitlementAuthorizer();
    }
  }

  static class StubEntitlementAuthorizer implements IcebergAuthorizer {
    @Override
    public boolean checkOperation(
        String userName, IcebergOperation op, IcebergResource resource) {
      if (op == IcebergOperation.UPDATE_TABLE) {
        return "bob".equals(userName) || "dave".equals(userName);
      }
      return true;
    }

    @Override
    public String getRowFilter(String userName, IcebergResource resource) {
      return ("alice".equals(userName) || "dave".equals(userName)) ? "region = 'US'" : null;
    }

    @Override
    public CredentialPrivilege checkCredential(String userName, IcebergResource resource) {
      return null;
    }

    @Override
    public void registerOwner(
        String catalog, String namespace, String resource, String owner) {}

    @Override
    public void removeOwner(String catalog, String namespace, String resource) {}
  }

  @BeforeEach
  public void setUpNamespace() throws Exception {
    dropAllExistingNamespace();
    verifyCreateNamespaceSucc(NAMESPACE.toString());
    asUser(
        "carol",
        () -> {
          CreateTableRequest createTableRequest =
              CreateTableRequest.builder().withName("t1").withSchema(TABLE_SCHEMA).build();
          return doPost(getTablePath(NAMESPACE, Optional.empty()), createTableRequest)
              .andExpect(MockMvcResultMatchers.status().isOk())
              .andReturn();
        });
  }

  @AfterEach
  public void cleanUp() throws Exception {
    dropAllExistingNamespace();
  }

  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private <T> T asUser(String user, ThrowingSupplier<T> action) throws Exception {
    return PrincipalUtils.doAs(
        new UserPrincipal(user), (PrivilegedExceptionAction<T>) () -> action.get());
  }

  @Test
  void testEntitledUserGetsDisguised404AndSynthesizedView() throws Exception {
    // raw table load is disguised as a missing table
    asUser(
        "alice",
        () ->
            doGet(getTablePath(NAMESPACE, Optional.of("t1")))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(
                    result ->
                        Assertions.assertTrue(
                            result.getResponse()
                                .getContentAsString()
                                .contains("NoSuchTableException")))
                .andReturn());

    // view load synthesizes the entitlement view
    MvcResult viewResult =
        asUser(
            "alice",
            () ->
                doGet(getViewPath(NAMESPACE, Optional.of("t1")))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andReturn());
    LoadViewResponse viewResponse =
        objectMapper.readValue(
            viewResult.getResponse().getContentAsString(), LoadViewResponse.class);
    Assertions.assertEquals(1, viewResponse.metadata().currentVersion().representations().size());
    Assertions.assertTrue(
        viewResponse
            .metadata()
            .currentVersion()
            .representations()
            .get(0)
            .toString()
            .contains("t1@entitlement"),
        "synthesized view must reference the suffixed table");
    Assertions.assertTrue(
        viewResponse
            .metadata()
            .currentVersion()
            .representations()
            .get(0)
            .toString()
            .contains("region = 'US'"));
    Assertions.assertEquals(
        "true",
        viewResponse.metadata().properties().get("gravitino.entitlement-view"));

    // the suffixed table request returns the real table: no loop
    MvcResult tableResult =
        asUser(
            "alice",
            () ->
                doGet(getTablePath(NAMESPACE, Optional.of("t1@entitlement")))
                    .andExpect(MockMvcResultMatchers.status().isOk())
                    .andReturn());
    LoadTableResponse suffixedResponse =
        objectMapper.readValue(
            tableResult.getResponse().getContentAsString(), LoadTableResponse.class);
    Assertions.assertNotNull(suffixedResponse.tableMetadata());
  }

  @Test
  void testEntitledEncodedSuffixAlsoServesRealTable() throws Exception {
    // the URI is passed pre-built: MockMvc's templated get(String) would re-encode %40
    asUser(
        "alice",
        () ->
            mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    java.net.URI.create(getTablePath(NAMESPACE, Optional.of("t1%40entitlement"))))
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn());
  }

  @Test
  void testUserWithoutEntitlementCannotUseSuffix() throws Exception {
    asUser(
        "carol",
        () ->
            doGet(getTablePath(NAMESPACE, Optional.of("t1@entitlement")))
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andReturn());
  }

  @Test
  void testWriteUserReadsRawTable() throws Exception {
    asUser(
        "bob",
        () ->
            doGet(getTablePath(NAMESPACE, Optional.of("t1")))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn());
  }

  @Test
  void testConflictUserGetsExplicitError() throws Exception {
    asUser(
        "dave",
        () ->
            doGet(getTablePath(NAMESPACE, Optional.of("t1")))
                .andExpect(MockMvcResultMatchers.status().isInternalServerError())
                .andExpect(
                    result ->
                        Assertions.assertTrue(
                            result.getResponse()
                                .getContentAsString()
                                .contains("ServiceFailureException")))
                .andReturn());
  }
}
