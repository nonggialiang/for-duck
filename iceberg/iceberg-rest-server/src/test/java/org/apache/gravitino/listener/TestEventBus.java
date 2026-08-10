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
package org.apache.gravitino.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.apache.gravitino.listener.api.event.BaseEvent;
import org.apache.gravitino.listener.api.event.Event;
import org.apache.gravitino.listener.api.event.PreEvent;
import org.apache.gravitino.listener.api.event.SupportsChangingPreEvent;
import org.junit.jupiter.api.Test;

class TestEventBus {

  // ---- Simple test event subclasses ----

  private static class TestPostEvent extends Event {
    TestPostEvent() {
      super("user", NameIdentifier.of("catalog"));
    }
  }

  private static class TestPreEvent extends PreEvent {
    TestPreEvent() {
      super("user", NameIdentifier.of("catalog"));
    }
  }

  private static class TestChangeablePreEvent extends PreEvent
      implements SupportsChangingPreEvent {
    TestChangeablePreEvent() {
      super("user", NameIdentifier.of("catalog"));
    }
  }

  // ---- Tests ----

  @Test
  void testDispatchPostEventCallsOnPostEvent() {
    EventListenerPlugin listener = mock(EventListenerPlugin.class);
    EventBus bus = new EventBus(List.of(listener));

    bus.dispatchEvent(new TestPostEvent());

    verify(listener, times(1)).onPostEvent(any(Event.class));
  }

  @Test
  void testDispatchPostEventReturnsEmpty() {
    EventListenerPlugin listener = mock(EventListenerPlugin.class);
    EventBus bus = new EventBus(List.of(listener));

    Optional<BaseEvent> result = bus.dispatchEvent(new TestPostEvent());
    assertTrue(result.isEmpty());
  }

  @Test
  void testDispatchPreEventCallsOnPreEvent() {
    EventListenerPlugin listener = mock(EventListenerPlugin.class);
    EventBus bus = new EventBus(List.of(listener));

    bus.dispatchEvent(new TestPreEvent());

    verify(listener, times(1)).onPreEvent(any(PreEvent.class));
  }

  @Test
  void testDispatchChangeablePreEventTransformsAndReturnsTransformed() {
    EventListenerPlugin listener = mock(EventListenerPlugin.class);
    TestChangeablePreEvent original = new TestChangeablePreEvent();
    TestChangeablePreEvent transformed = new TestChangeablePreEvent();
    when(listener.transformPreEvent(original)).thenReturn(transformed);

    EventBus bus = new EventBus(List.of(listener));
    Optional<BaseEvent> result = bus.dispatchEvent(original);

    assertTrue(result.isPresent());
    assertEquals(transformed, result.get());
    verify(listener).transformPreEvent(original);
    verify(listener).onPreEvent(transformed);
  }

  @Test
  void testDispatchChangeablePreEventWithNoListenersReturnsOriginal() {
    EventBus bus = new EventBus(Collections.emptyList());
    TestChangeablePreEvent event = new TestChangeablePreEvent();
    Optional<BaseEvent> result = bus.dispatchEvent(event);
    // With no listeners, transformPreEvent returns the original unchanged,
    // but since it's a SupportsChangingPreEvent, the result is still present.
    assertTrue(result.isPresent());
    assertEquals(event, result.get());
  }

  @Test
  void testDispatchMultipleListenersAllCalled() {
    EventListenerPlugin l1 = mock(EventListenerPlugin.class);
    EventListenerPlugin l2 = mock(EventListenerPlugin.class);
    EventBus bus = new EventBus(List.of(l1, l2));

    bus.dispatchEvent(new TestPostEvent());

    verify(l1).onPostEvent(any());
    verify(l2).onPostEvent(any());
  }

  @Test
  void testIsHighWatermarkNoAsyncListeners() {
    EventBus bus = new EventBus(Collections.emptyList());
    assertEquals(false, bus.isHighWatermark());
  }

  @Test
  void testGetEventListeners() {
    EventListenerPlugin l1 = mock(EventListenerPlugin.class);
    EventBus bus = new EventBus(List.of(l1));
    assertEquals(1, bus.getEventListeners().size());
  }
}
