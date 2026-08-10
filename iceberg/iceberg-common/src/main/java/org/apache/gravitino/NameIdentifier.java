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

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import java.util.Arrays;
import org.apache.gravitino.exceptions.IllegalNameIdentifierException;

public class NameIdentifier {

  private static final Splitter DOT = Splitter.on('.');

  private final Namespace namespace;
  private final String name;

  public static NameIdentifier of(String... names) {
    check(names != null, "Cannot create a NameIdentifier with null names");
    check(names.length > 0, "Cannot create a NameIdentifier with no names");
    return new NameIdentifier(
        Namespace.of(Arrays.copyOf(names, names.length - 1)), names[names.length - 1]);
  }

  public static NameIdentifier of(Namespace namespace, String name) {
    return new NameIdentifier(namespace, name);
  }

  public static NameIdentifier parse(String identifier) {
    check(identifier != null && !identifier.isEmpty(), "Cannot parse a null or empty identifier");
    Iterable<String> parts = DOT.split(identifier);
    return NameIdentifier.of(Iterables.toArray(parts, String.class));
  }

  private NameIdentifier(Namespace namespace, String name) {
    check(namespace != null, "Cannot create a NameIdentifier with null namespace");
    check(name != null && !name.isEmpty(), "Cannot create a NameIdentifier with null or empty name");
    this.namespace = namespace;
    this.name = name;
  }

  public boolean hasNamespace() {
    return !namespace.isEmpty();
  }

  public Namespace namespace() {
    return namespace;
  }

  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof NameIdentifier)) return false;
    NameIdentifier otherNameIdentifier = (NameIdentifier) other;
    return namespace.equals(otherNameIdentifier.namespace) && name.equals(otherNameIdentifier.name);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(new int[] {namespace.hashCode(), name.hashCode()});
  }

  @Override
  public String toString() {
    if (hasNamespace()) {
      return namespace.toString() + "." + name;
    } else {
      return name;
    }
  }

  public static void check(boolean condition, String message, Object... args) {
    if (!condition) {
      throw new IllegalNameIdentifierException(message, args);
    }
  }
}
