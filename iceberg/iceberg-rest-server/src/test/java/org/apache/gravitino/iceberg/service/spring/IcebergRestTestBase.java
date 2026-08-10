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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import org.apache.gravitino.iceberg.service.IcebergObjectMapper;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.rest.RESTUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Base class for Iceberg REST tests using Spring MockMvc. */
@SpringBootTest(classes = IcebergTestApp.class)
@AutoConfigureMockMvc
public abstract class IcebergRestTestBase {

  @Autowired protected MockMvc mockMvc;

  protected final ObjectMapper objectMapper = IcebergObjectMapper.getInstance();

  protected String urlPathPrefix = "";

  @BeforeEach
  public void setUpUrlPrefix() {
    urlPathPrefix = "";
  }

  // --- HTTP helper methods ---

  protected ResultActions doGet(String path) throws Exception {
    return doGet(path, Optional.empty());
  }

  protected ResultActions doGet(String path, Optional<Map<String, String>> queryParams)
      throws Exception {
    path = maybeInjectPrefix(path);
    MockHttpServletRequestBuilder builder = get(path).accept(MediaType.APPLICATION_JSON);
    if (queryParams.isPresent()) {
      for (Entry<String, String> e : queryParams.get().entrySet()) {
        builder.param(e.getKey(), e.getValue());
      }
    }
    return mockMvc.perform(builder);
  }

  protected ResultActions doHead(String path) throws Exception {
    path = maybeInjectPrefix(path);
    return mockMvc.perform(
        head(path).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON));
  }

  protected ResultActions doDelete(String path) throws Exception {
    path = maybeInjectPrefix(path);
    return mockMvc.perform(
        delete(path).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON));
  }

  protected ResultActions doPost(String path, Object body) throws Exception {
    path = maybeInjectPrefix(path);
    String json = objectMapper.writeValueAsString(body);
    return mockMvc.perform(
        post(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(json));
  }

  // --- Path builders ---

  protected String getTablePath(Namespace ns, Optional<String> name) {
    return Joiner.on("/")
        .skipNulls()
        .join(IcebergTestApp.NAMESPACE_PATH + "/" + RESTUtil.encodeNamespace(ns) + "/tables",
            name.orElse(null));
  }

  protected String getViewPath(Namespace ns, Optional<String> name) {
    return Joiner.on("/")
        .skipNulls()
        .join(IcebergTestApp.NAMESPACE_PATH + "/" + RESTUtil.encodeNamespace(ns) + "/views",
            name.orElse(null));
  }

  protected String getReportMetricsPath(String name, Namespace ns) {
    return Joiner.on("/")
        .skipNulls()
        .join(IcebergTestApp.NAMESPACE_PATH + "/" + RESTUtil.encodeNamespace(ns) + "/tables",
            name, "metrics");
  }

  protected String getNamespacePath(Optional<Namespace> namespace, Optional<String> extraPath) {
    return Joiner.on("/")
        .skipNulls()
        .join(IcebergTestApp.NAMESPACE_PATH,
            namespace.map(RESTUtil::encodeNamespace).orElse(null),
            extraPath.orElse(null));
  }

  protected String getUpdateNamespacePath(Namespace namespace) {
    return getNamespacePath(Optional.of(namespace), Optional.of("properties"));
  }

  protected String getConfigPath() {
    return IcebergTestApp.CONFIG_PATH;
  }

  protected String getRenameTablePath() {
    return IcebergTestApp.RENAME_TABLE_PATH;
  }

  protected String getRenameViewPath() {
    return IcebergTestApp.RENAME_VIEW_PATH;
  }

  // --- Prefix injection ---

  private String maybeInjectPrefix(String path) {
    if (urlPathPrefix == null || urlPathPrefix.isEmpty()) {
      return path;
    }
    String[] items = path.split("/");
    String[] newItems = new String[items.length + 1];
    newItems[0] = items[0]; // empty string before first /
    newItems[1] = urlPathPrefix;
    System.arraycopy(items, 1, newItems, 2, items.length - 1);
    return Joiner.on("/").join(newItems);
  }

  protected void setUrlPathWithPrefix(String prefix) {
    this.urlPathPrefix = prefix;
  }
}
