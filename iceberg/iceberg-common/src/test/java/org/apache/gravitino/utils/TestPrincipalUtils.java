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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.Principal;
import java.security.PrivilegedExceptionAction;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.gravitino.DelegatedUserPrincipal;
import org.apache.gravitino.UserPrincipal;
import org.junit.jupiter.api.Test;

/** Verifies requester / effective / delegated principal propagation through {@link PrincipalUtils}. */
class TestPrincipalUtils {

  @Test
  void getCurrentUserNameIsRequesterWhenNoDelegation() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    PrincipalUtils.doAs(
        new UserPrincipal("alice"),
        (PrivilegedExceptionAction<Void>) () -> {
          seen.set(PrincipalUtils.getCurrentUserName());
          return null;
        });
    assertEquals("alice", seen.get());
  }

  @Test
  void getCurrentUserNameIsDelegatedWhenPresent() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    PrincipalUtils.doAs(
        new UserPrincipal("alice"),
        new DelegatedUserPrincipal("bob"),
        (PrivilegedExceptionAction<Void>) () -> {
          seen.set(PrincipalUtils.getCurrentUserName());
          return null;
        });
    assertEquals("bob", seen.get());
  }

  @Test
  void getRequesterUserNameAlwaysReturnsRequester() throws Exception {
    AtomicReference<String> effective = new AtomicReference<>();
    AtomicReference<String> requester = new AtomicReference<>();
    PrincipalUtils.doAs(
        new UserPrincipal("alice"),
        new DelegatedUserPrincipal("bob"),
        (PrivilegedExceptionAction<Void>) () -> {
          effective.set(PrincipalUtils.getEffectiveUserName());
          requester.set(PrincipalUtils.getCurrentRequesterUserName());
          return null;
        });
    assertEquals("bob", effective.get());
    assertEquals("alice", requester.get());
  }

  @Test
  void getDelegatedPrincipalIsEmptyWithoutDelegation() throws Exception {
    AtomicReference<Optional<Principal>> seen = new AtomicReference<>();
    PrincipalUtils.doAs(
        new UserPrincipal("alice"),
        (PrivilegedExceptionAction<Void>) () -> {
          seen.set(PrincipalUtils.getDelegatedPrincipal());
          return null;
        });
    assertTrue(seen.get().isEmpty());
  }

  @Test
  void getDelegatedPrincipalReturnsDelegatedWhenPresent() throws Exception {
    AtomicReference<Optional<Principal>> seen = new AtomicReference<>();
    PrincipalUtils.doAs(
        new UserPrincipal("alice"),
        new DelegatedUserPrincipal("bob"),
        (PrivilegedExceptionAction<Void>) () -> {
          seen.set(PrincipalUtils.getDelegatedPrincipal());
          return null;
        });
    assertTrue(seen.get().isPresent());
    assertEquals("bob", seen.get().get().getName());
    assertTrue(seen.get().get() instanceof DelegatedUserPrincipal);
  }

  @Test
  void anonymousFallbackWhenNoSubject() {
    assertEquals(PrincipalUtils.ANONYMOUS_USER, PrincipalUtils.getCurrentUserName());
    assertEquals(PrincipalUtils.ANONYMOUS_USER, PrincipalUtils.getCurrentRequesterUserName());
  }

  @Test
  void doAsWithNullRequesterUsesAnonymous() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    PrincipalUtils.doAs(
        null,
        (PrivilegedExceptionAction<Void>) () -> {
          seen.set(PrincipalUtils.getCurrentRequesterUserName());
          return null;
        });
    assertEquals(PrincipalUtils.ANONYMOUS_USER, seen.get());
  }
}
