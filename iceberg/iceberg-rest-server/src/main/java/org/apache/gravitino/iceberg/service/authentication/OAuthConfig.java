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
package org.apache.gravitino.iceberg.service.authentication;

import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Arrays;
import org.apache.gravitino.config.ConfigBuilder;
import org.apache.gravitino.config.ConfigConstants;
import org.apache.gravitino.config.ConfigEntry;

/**
 * OAuth configuration entries for the Iceberg REST server.
 *
 * <p>Keys are <em>internal</em> (the {@code gravitino.iceberg-rest.} external prefix is stripped by
 * {@code IcebergBeanConfig} before being loaded into {@link org.apache.gravitino.iceberg.common
 * .IcebergConfig}). External key example: {@code
 * gravitino.iceberg-rest.auth.oauth.serviceAudience}.
 */
public interface OAuthConfig {

  String OAUTH_CONFIG_PREFIX = "auth.oauth.";

  ConfigEntry<String> SERVICE_AUDIENCE =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "serviceAudience")
          .doc("The audience name when the Iceberg REST server uses OAuth as the authenticator")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .createWithDefault("GravitinoIcebergServer");

  ConfigEntry<Long> ALLOW_SKEW_SECONDS =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "allowSkewSecs")
          .doc("The JWT allowed clock skew seconds when OAuth is the authenticator")
          .version(ConfigConstants.VERSION_1_2_0)
          .longConf()
          .createWithDefault(0L);

  ConfigEntry<String> DEFAULT_SIGN_KEY =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "defaultSignKey")
          .doc("The signing key of JWT when OAuth is the authenticator")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .create();

  ConfigEntry<String> SIGNATURE_ALGORITHM_TYPE =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "signAlgorithmType")
          .doc("The signature algorithm when OAuth is the authenticator")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .createWithDefault(SignatureAlgorithm.RS256.name());

  ConfigEntry<String> DEFAULT_SERVER_URI =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "serverUri")
          .doc(
              "The URI of the default OAuth server. Required when using StaticSignKeyValidator; not "
                  + "required for JWKS-based validators")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .create();

  ConfigEntry<String> DEFAULT_TOKEN_PATH =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "tokenPath")
          .doc(
              "The token path of the default OAuth server. Required when using StaticSignKeyValidator")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .create();

  ConfigEntry<String> AUTHORITY =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "authority")
          .doc("OAuth authority / expected issuer URL used for JWT issuer validation")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .create();

  ConfigEntry<String> JWKS_URI =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "jwksUri")
          .doc("JWKS URI used for server-side OAuth token validation")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .create();

  ConfigEntry<java.util.List<String>> PRINCIPAL_FIELDS =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "principalFields")
          .doc(
              "JWT claim field(s) to use as principal identity. Comma-separated for ordered "
                  + "fallback (e.g. 'preferred_username,email,sub')")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .toSequence()
          .createWithDefault(Arrays.asList("sub"));

  ConfigEntry<String> TOKEN_VALIDATOR_CLASS =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "tokenValidatorClass")
          .doc("Fully qualified class name of the OAuth token validator implementation")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .createWithDefault(
              "org.apache.gravitino.iceberg.service.authentication.StaticSignKeyValidator");

  ConfigEntry<String> PRINCIPAL_MAPPER =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "principalMapper")
          .doc(
              "Principal mapper for OAuth/JWT principals. Built-in: 'regex'. "
                  + "Also accepts a fully qualified class name implementing PrincipalMapper.")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .createWithDefault("regex");

  ConfigEntry<String> PRINCIPAL_MAPPER_REGEX_PATTERN =
      new ConfigBuilder(OAUTH_CONFIG_PREFIX + "principalMapper.regex.pattern")
          .doc(
              "Regex pattern (with a capturing group) to extract the username from the OAuth "
                  + "principal field. Only used when principalMapper is 'regex'.")
          .version(ConfigConstants.VERSION_1_2_0)
          .stringConf()
          .createWithDefault("^(.*)$");
}
