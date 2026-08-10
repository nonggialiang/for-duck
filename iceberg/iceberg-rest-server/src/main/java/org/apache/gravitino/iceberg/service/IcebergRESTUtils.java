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
package org.apache.gravitino.iceberg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.gravitino.NameIdentifier;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;

/**
 * Miscellaneous helpers for Iceberg REST operations. HTTP response building now lives in {@link
 * HttpResponseBuilder} and prefix resolution lives in {@link PrefixResolver}.
 */
public class IcebergRESTUtils {

  private IcebergRESTUtils() {}

  public static NameIdentifier getGravitinoNameIdentifier(
      String catalogName, TableIdentifier icebergIdentifier) {
    Stream<String> catalogNS =
        Stream.concat(
            Stream.of(catalogName), Arrays.stream(icebergIdentifier.namespace().levels()));
    String[] catalogNSTable =
        Stream.concat(catalogNS, Stream.of(icebergIdentifier.name())).toArray(String[]::new);
    return NameIdentifier.of(catalogNSTable);
  }

  public static NameIdentifier getGravitinoNameIdentifier(
      String catalogName, Namespace namespace) {
    Stream<String> catalogNS =
        Stream.concat(Stream.of(catalogName), Arrays.stream(namespace.levels()));
    return NameIdentifier.of(catalogNS.toArray(String[]::new));
  }

  public static <T> T cloneIcebergRESTObject(Object message, Class<T> className) {
    ObjectMapper icebergObjectMapper = IcebergObjectMapper.getInstance();
    try {
      byte[] values = icebergObjectMapper.writeValueAsBytes(message);
      return icebergObjectMapper.readValue(values, className);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public static Map<String, String> getHttpHeaders(HttpServletRequest httpServletRequest) {
    Map<String, String> headers = new HashMap<>();
    Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
    while (headerNames.hasMoreElements()) {
      String headerName = headerNames.nextElement();
      String headerValue = httpServletRequest.getHeader(headerName);
      if (headerValue != null) {
        headers.put(headerName, headerValue);
      }
    }
    return headers;
  }
}
