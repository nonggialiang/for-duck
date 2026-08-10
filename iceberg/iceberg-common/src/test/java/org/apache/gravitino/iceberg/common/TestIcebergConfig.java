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

package org.apache.gravitino.iceberg.common;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.config.ConfigConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestIcebergConfig {
  @Test
  public void testLoadIcebergConfig() {
    Map<String, String> properties =
        ImmutableMap.of(ConfigConstants.WEBSERVER_HTTP_PORT, "1000");

    IcebergConfig icebergConfig = new IcebergConfig();
    icebergConfig.loadFromMap(properties, k -> k.startsWith("gravitino."));
    // "httpPort" does not start with "gravitino." so it is not loaded; the default port applies.
    Assertions.assertEquals(
        IcebergConfig.DEFAULT_ICEBERG_REST_SERVICE_HTTP_PORT,
        Integer.parseInt(
            icebergConfig.getRawString(
                ConfigConstants.WEBSERVER_HTTP_PORT,
                String.valueOf(IcebergConfig.DEFAULT_ICEBERG_REST_SERVICE_HTTP_PORT))));

    IcebergConfig icebergRESTConfig2 = new IcebergConfig(properties);
    Assertions.assertEquals(
        1000,
        Integer.parseInt(icebergRESTConfig2.getRawString(ConfigConstants.WEBSERVER_HTTP_PORT)));
  }
}
