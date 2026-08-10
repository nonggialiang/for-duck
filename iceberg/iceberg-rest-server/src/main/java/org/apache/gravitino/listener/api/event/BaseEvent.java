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
package org.apache.gravitino.listener.api.event;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.DeveloperApi;

@DeveloperApi
public abstract class BaseEvent {
  private final String user;
  @Nullable private final NameIdentifier identifier;
  private final long eventTime;

  protected BaseEvent(String user, NameIdentifier identifier) {
    this.user = user;
    this.identifier = identifier;
    this.eventTime = System.currentTimeMillis();
  }

  public String user() {
    return user;
  }

  @Nullable
  public NameIdentifier identifier() {
    return identifier;
  }

  public long eventTime() {
    return eventTime;
  }

  public OperationType operationType() {
    return OperationType.UNKNOWN;
  }

  public String remoteAddress() {
    return "unknown";
  }

  public EventSource eventSource() {
    return EventSource.GRAVITINO_SERVER;
  }

  public OperationStatus operationStatus() {
    return OperationStatus.UNKNOWN;
  }

  public Map<String, String> customInfo() {
    return ImmutableMap.of();
  }
}
