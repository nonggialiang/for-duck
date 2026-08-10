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

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Customises the embedded Tomcat connector so that encoded solidus ({@code %2F}) is passed through
 * without special handling. This preserves the Jersey {@code @Encoded} behaviour where path
 * parameters are received URL-decoded by the framework but {@code %2F} sequences are left intact
 * for manual decoding via {@code RESTUtil.decodeString}.
 */
@Configuration
public class IcebergTomcatConfig
    implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  @Override
  public void customize(TomcatServletWebServerFactory factory) {
    factory.addConnectorCustomizers(
        new TomcatConnectorCustomizer() {
          @Override
          public void customize(Connector connector) {
            connector.setProperty("encodedSolidusHandling", "passthrough");
          }
        });
  }
}
