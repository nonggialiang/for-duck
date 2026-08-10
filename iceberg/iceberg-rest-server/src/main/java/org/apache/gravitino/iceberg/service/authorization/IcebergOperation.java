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
package org.apache.gravitino.iceberg.service.authorization;

public enum IcebergOperation {
  CREATE_TABLE,
  LOAD_TABLE,
  LOAD_TABLE_CREDENTIAL,
  DROP_TABLE,
  RENAME_TABLE,
  UPDATE_TABLE,
  TABLE_EXISTS,
  LIST_TABLE,
  PLAN_TABLE_SCAN,
  REGISTER_TABLE,
  CREATE_NAMESPACE,
  LOAD_NAMESPACE,
  DROP_NAMESPACE,
  UPDATE_NAMESPACE,
  LIST_NAMESPACE,
  NAMESPACE_EXISTS,
  CREATE_VIEW,
  LOAD_VIEW,
  DROP_VIEW,
  REPLACE_VIEW,
  RENAME_VIEW,
  VIEW_EXISTS,
  LIST_VIEW,
}
