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
package com.sc.ssdr.kyuubi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.kyuubi.config.KyuubiConf;
import org.apache.kyuubi.plugin.SessionConfAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DelegatorInjectConfAdvisor implements SessionConfAdvisor {
  private static final Logger LOG = LoggerFactory.getLogger(DelegatorInjectConfAdvisor.class);
  private static final Pattern CATALOG_LIST = Pattern.compile("^[^,]+(\\s*,\\s*[^,]+)*$");
  private static final String DELEGATOR_INJECT_CATALOGS =
      "kyuubi.session.delegator.inject.catalogs";

  @Override
  public Map<String, String> getConfOverlay(String user, Map<String, String> sessionConf) {
    String catalogs = getDelegatorInjectCatalogs();
    if (catalogs == null || catalogs.isBlank()) {
      return Collections.emptyMap();
    }
    if (!CATALOG_LIST.matcher(catalogs).matches()) {
      LOG.warn("Invalid {} value: {}", DELEGATOR_INJECT_CATALOGS, catalogs);
      return Collections.emptyMap();
    }
    Map<String, String> overlay = new HashMap<>();
    for (String rawCatalog : catalogs.split(",")) {
      String catalog = rawCatalog.trim();
      overlay.put(
          "spark.sql.catalog." + catalog + ".header.X-Iceberg-Access-Delegator",
          user);
    }
    return overlay;
  }

  String getDelegatorInjectCatalogs() {
    scala.Option<String> catalogs =
        new KyuubiConf(true).loadFileDefaults().getOption(DELEGATOR_INJECT_CATALOGS);
    return catalogs.isDefined() ? catalogs.get() : null;
  }
}
