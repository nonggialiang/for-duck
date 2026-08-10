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
package org.apache.gravitino.storage;

public class S3Properties {
  public static final String GRAVITINO_S3_ENDPOINT = "s3-endpoint";
  public static final String GRAVITINO_S3_ACCESS_KEY_ID = "s3-access-key-id";
  public static final String GRAVITINO_S3_SECRET_ACCESS_KEY = "s3-secret-access-key";
  public static final String GRAVITINO_S3_REGION = "s3-region";
  public static final String GRAVITINO_S3_ROLE_ARN = "s3-role-arn";
  public static final String GRAVITINO_S3_STS_ENDPOINT = "s3-token-service-endpoint";
  public static final String GRAVITINO_S3_EXTERNAL_ID = "s3-external-id";
  public static final String GRAVITINO_S3_CREDS_PROVIDER = "s3-creds-provider";
  public static final String GRAVITINO_S3_PATH_STYLE_ACCESS = "s3-path-style-access";

  private S3Properties() {}
}
