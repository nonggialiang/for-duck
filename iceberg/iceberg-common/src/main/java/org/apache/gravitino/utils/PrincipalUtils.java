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
package org.apache.gravitino.utils;

import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import javax.security.auth.Subject;

public class PrincipalUtils {

  public static final String ANONYMOUS_USER = "anonymous";

  @SuppressWarnings("removal")
  public static Principal getCurrentPrincipal() {
    java.security.AccessControlContext context = java.security.AccessController.getContext();
    Subject subject = Subject.getSubject(context);
    if (subject == null || subject.getPrincipals().isEmpty()) {
      return () -> ANONYMOUS_USER;
    }
    return subject.getPrincipals().iterator().next();
  }

  public static String getCurrentUserName() {
    return getCurrentPrincipal().getName();
  }

  public static <T> T doAs(Principal principal, PrivilegedExceptionAction<T> action) {
    try {
      return Subject.doAs(
          new Subject(true, java.util.Collections.singleton(principal), java.util.Collections.emptySet(),
              java.util.Collections.emptySet()),
          action);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("removal")
  public static <T> T doAs(Principal principal, PrivilegedAction<T> action) {
    return Subject.doAs(
        new Subject(true, java.util.Collections.singleton(principal), java.util.Collections.emptySet(),
            java.util.Collections.emptySet()),
        action);
  }

  private PrincipalUtils() {}
}
