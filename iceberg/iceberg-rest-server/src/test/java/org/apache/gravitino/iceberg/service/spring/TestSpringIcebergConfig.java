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
import java.util.Map;
import java.util.Optional;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

public class TestSpringIcebergConfig extends IcebergRestTestBase {

  @Test
  public void testConfig() throws Exception {
    MvcResult result =
        doGet(getConfigPath()).andExpect(MockMvcResultMatchers.status().isOk()).andReturn();
    ConfigResponse response =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), ConfigResponse.class);
    Assertions.assertEquals(0, response.defaults().size());
    Assertions.assertEquals(0, response.overrides().size());
  }

  @Test
  public void testConfigWithEmptyWarehouse() throws Exception {
    Map<String, String> queryParams = ImmutableMap.of("warehouse", "");
    MvcResult result =
        doGet(getConfigPath(), Optional.of(queryParams))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    ConfigResponse response =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), ConfigResponse.class);
    Assertions.assertEquals(0, response.defaults().size());
    Assertions.assertEquals(0, response.overrides().size());
  }

  @Test
  public void testConfigWithValidWarehouse() throws Exception {
    String warehouseName = IcebergTestApp.PREFIX;
    Map<String, String> queryParams = ImmutableMap.of("warehouse", warehouseName);
    MvcResult result =
        doGet(getConfigPath(), Optional.of(queryParams))
            .andExpect(MockMvcResultMatchers.status().isOk())
            .andReturn();
    ConfigResponse response =
        objectMapper.readValue(
            result.getResponse().getContentAsString(), ConfigResponse.class);
    Map<String, String> expectedConfig =
        ImmutableMap.of(
            "prefix",
            warehouseName,
            IcebergConstants.IO_IMPL,
            "org.apache.iceberg.aws.s3.S3FileIO",
            IcebergConstants.ICEBERG_S3_ENDPOINT,
            "https://s3-endpoint.example.com",
            IcebergConstants.AWS_S3_REGION,
            "us-west-2",
            IcebergConstants.ICEBERG_S3_PATH_STYLE_ACCESS,
            "true");
    Assertions.assertEquals(expectedConfig, response.defaults());
    Assertions.assertEquals(0, response.overrides().size());
  }

  @Test
  public void testConfigWithNonExistentWarehouse() throws Exception {
    Map<String, String> queryParams = ImmutableMap.of("warehouse", "invalid-catalog");
    doGet(getConfigPath(), Optional.of(queryParams))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }
}
