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

import com.google.common.collect.ImmutableSet;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;
import java.util.Optional;
import java.util.Set;
import javax.security.auth.Subject;
import org.apache.gravitino.DelegatedUserPrincipal;
import org.apache.gravitino.UserPrincipal;

/**
 * Principal propagation helpers built on top of the JAAS {@link Subject}.
 *
 * <p>The Subject may carry two identities:
 *
 * <ul>
 *   <li>the <em>authenticated requester</em> (always a {@link UserPrincipal}), and
 *   <li>an optional <em>delegated user</em> (a {@link DelegatedUserPrincipal}) sourced from the
 *       {@code X-Iceberg-Access-Delegator} header.
 * </ul>
 *
 * <p>{@link #getCurrentUserName()} remains delegator-aware for backward compatibility with
 * authorization-time callers: it returns the delegated user when present, otherwise the requester.
 * Explicit accessors {@link #getRequesterPrincipal()} / {@link #getCurrentRequesterUserName()} and
 * {@link #getEffectivePrincipal()} / {@link #getEffectiveUserName()} / {@link #getDelegatedPrincipal()}
 * let call sites be intentional about which identity they need.
 */
public class PrincipalUtils {

  public static final String ANONYMOUS_USER = "anonymous";

  private static final Principal ANONYMOUS_PRINCIPAL = new UserPrincipal(ANONYMOUS_USER);

  /** Returns the effective principal: the delegated user when present, otherwise the requester. */
  @SuppressWarnings("removal")
  public static Principal getCurrentPrincipal() {
    Subject subject = Subject.getSubject(java.security.AccessController.getContext());
    return effectivePrincipal(subject);
  }

  /**
   * Returns the effective user name (delegator-aware). This is the identity used for authorization
   * decisions.
   */
  public static String getCurrentUserName() {
    return getCurrentPrincipal().getName();
  }

  /**
   * Returns the authenticated requester principal, regardless of whether a delegated user is
   * present.
   */
  @SuppressWarnings("removal")
  public static Principal getRequesterPrincipal() {
    Subject subject = Subject.getSubject(java.security.AccessController.getContext());
    return requesterPrincipal(subject);
  }

  /** Returns the authenticated requester user name. */
  public static String getCurrentRequesterUserName() {
    return getRequesterPrincipal().getName();
  }

  /**
   * Returns the effective principal explicitly (delegated user when present, otherwise requester).
   */
  public static Principal getEffectivePrincipal() {
    return getCurrentPrincipal();
  }

  /** Returns the effective user name explicitly. */
  public static String getEffectiveUserName() {
    return getEffectivePrincipal().getName();
  }

  /**
   * Returns the delegated user principal when one is present in the current subject, otherwise
   * empty.
   */
  @SuppressWarnings("removal")
  public static Optional<Principal> getDelegatedPrincipal() {
    Subject subject = Subject.getSubject(java.security.AccessController.getContext());
    if (subject == null) {
      return Optional.empty();
    }
    Set<DelegatedUserPrincipal> delegated = subject.getPrincipals(DelegatedUserPrincipal.class);
    if (delegated.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(delegated.iterator().next());
  }

  /** Returns the delegated user name when present, otherwise empty. */
  public static Optional<String> getDelegatedUserName() {
    return getDelegatedPrincipal().map(Principal::getName);
  }

  /** Runs the action as the given requester only (no delegation). */
  public static <T> T doAs(Principal principal, PrivilegedExceptionAction<T> action) {
    return doAs(principal, null, action);
  }

  /**
   * Runs the action carrying both the authenticated requester and (optionally) the delegated user.
   */
  @SuppressWarnings("removal")
  public static <T> T doAs(
      Principal requester, Principal delegatedUser, PrivilegedExceptionAction<T> action) {
    Principal requesterPrincipal = requester != null ? requester : ANONYMOUS_PRINCIPAL;
    java.util.Set<Principal> principals =
        delegatedUser != null
            ? ImmutableSet.of(requesterPrincipal, delegatedUser)
            : ImmutableSet.of(requesterPrincipal);
    Subject subject =
        new Subject(
            true, principals, java.util.Collections.emptySet(), java.util.Collections.emptySet());
    try {
      return Subject.doAs(subject, action);
    } catch (java.security.PrivilegedActionException pae) {
      Throwable cause = pae.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new RuntimeException(cause);
    }
  }

  /** Runs the action as the given requester only (no delegation), returning a value. */
  @SuppressWarnings("removal")
  public static <T> T doAs(Principal principal, PrivilegedAction<T> action) {
    Principal requesterPrincipal = principal != null ? principal : ANONYMOUS_PRINCIPAL;
    Subject subject =
        new Subject(
            true,
            ImmutableSet.of(requesterPrincipal),
            java.util.Collections.emptySet(),
            java.util.Collections.emptySet());
    return Subject.doAs(subject, action);
  }

  private static Principal effectivePrincipal(Subject subject) {
    if (subject == null) {
      return ANONYMOUS_PRINCIPAL;
    }
    Set<DelegatedUserPrincipal> delegated = subject.getPrincipals(DelegatedUserPrincipal.class);
    if (!delegated.isEmpty()) {
      return delegated.iterator().next();
    }
    return requesterPrincipal(subject);
  }

  private static Principal requesterPrincipal(Subject subject) {
    if (subject == null) {
      return ANONYMOUS_PRINCIPAL;
    }
    Set<UserPrincipal> requesters = subject.getPrincipals(UserPrincipal.class);
    if (!requesters.isEmpty()) {
      return requesters.iterator().next();
    }
    // Fall back to the first principal of any type for compatibility with code paths that
    // built Subjects directly with a non-UserPrincipal.
    if (!subject.getPrincipals().isEmpty()) {
      return subject.getPrincipals().iterator().next();
    }
    return ANONYMOUS_PRINCIPAL;
  }

  private PrincipalUtils() {}
}
