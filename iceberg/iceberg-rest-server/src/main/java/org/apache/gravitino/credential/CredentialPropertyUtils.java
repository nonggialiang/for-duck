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
package org.apache.gravitino.credential;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.credential.s3.AwsIrsaCredential;
import org.apache.gravitino.credential.s3.S3SecretKeyCredential;
import org.apache.gravitino.credential.s3.S3TokenCredential;

public class CredentialPropertyUtils {

  @VisibleForTesting static final String ICEBERG_S3_ACCESS_KEY_ID = "s3.access-key-id";
  @VisibleForTesting static final String ICEBERG_S3_SECRET_ACCESS_KEY = "s3.secret-access-key";
  @VisibleForTesting static final String ICEBERG_S3_TOKEN = "s3.session-token";

  private static Map<String, String> icebergCredentialPropertyMap =
      ImmutableMap.<String, String>builder()
          .put(S3SecretKeyCredential.GRAVITINO_S3_STATIC_ACCESS_KEY_ID, ICEBERG_S3_ACCESS_KEY_ID)
          .put(
              S3SecretKeyCredential.GRAVITINO_S3_STATIC_SECRET_ACCESS_KEY,
              ICEBERG_S3_SECRET_ACCESS_KEY)
          .put(S3TokenCredential.GRAVITINO_S3_TOKEN, ICEBERG_S3_TOKEN)
          .put(AwsIrsaCredential.ACCESS_KEY_ID, ICEBERG_S3_ACCESS_KEY_ID)
          .put(AwsIrsaCredential.SECRET_ACCESS_KEY, ICEBERG_S3_SECRET_ACCESS_KEY)
          .put(AwsIrsaCredential.SESSION_TOKEN, ICEBERG_S3_TOKEN)
          .build();

  public static Map<String, String> toIcebergProperties(Credential credential) {
    if (credential instanceof S3TokenCredential
        || credential instanceof S3SecretKeyCredential
        || credential instanceof AwsIrsaCredential) {
      return transformProperties(credential.credentialInfo(), icebergCredentialPropertyMap);
    }
    return credential.toProperties();
  }

  private static Map<String, String> transformProperties(
      Map<String, String> originProperties, Map<String, String> transformMap) {
    HashMap<String, String> properties = new HashMap<>();
    originProperties.forEach(
        (k, v) -> {
          if (transformMap.containsKey(k)) {
            properties.put(transformMap.get(k), v);
          }
        });
    return properties;
  }
}
