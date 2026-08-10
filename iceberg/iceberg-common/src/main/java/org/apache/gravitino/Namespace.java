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
package org.apache.gravitino;

import com.google.common.base.Joiner;
import java.util.Arrays;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.exceptions.IllegalNamespaceException;

public class Namespace {

  private static final Namespace EMPTY = new Namespace(new String[0]);
  private static final Joiner DOT = Joiner.on('.');

  private final String[] levels;

  public static Namespace empty() {
    return EMPTY;
  }

  public static Namespace of(String... levels) {
    check(levels != null, "Cannot create a namespace with null levels");
    if (levels.length == 0) {
      return empty();
    }
    for (String level : levels) {
      check(level != null && !level.isEmpty(), "Cannot create a namespace with null or empty level");
    }
    return new Namespace(levels);
  }

  public static Namespace fromString(String namespace) {
    if (namespace == null || StringUtils.isBlank(namespace)) {
      return empty();
    }
    return Namespace.of(namespace.split("\\."));
  }

  private Namespace(String[] levels) {
    this.levels = Arrays.copyOf(levels, levels.length);
  }

  public String[] levels() {
    return Arrays.copyOf(levels, levels.length);
  }

  public String level(int pos) {
    check(pos >= 0 && pos < levels.length, "Invalid level position");
    return levels[pos];
  }

  public int length() {
    return levels.length;
  }

  public boolean isEmpty() {
    return levels.length == 0;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Namespace)) return false;
    Namespace otherNamespace = (Namespace) other;
    return Arrays.equals(levels, otherNamespace.levels);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(levels);
  }

  @Override
  public String toString() {
    return DOT.join(levels);
  }

  public static void check(boolean expression, String message, Object... args) {
    if (!expression) {
      throw new IllegalNamespaceException(message, args);
    }
  }
}
