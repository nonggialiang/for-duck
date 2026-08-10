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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.iceberg.service.rest.DummyEventListener;
import org.apache.gravitino.listener.api.EventListenerPlugin;
import org.junit.jupiter.api.Test;

class TestEventListenerManager {

  @Test
  void testInitWithSyncListener() {
    Map<String, String> props = new HashMap<>();
    props.put("names", "test-listener");
    props.put("test-listener.class", DummyEventListener.class.getName());

    EventListenerManager manager = new EventListenerManager();
    manager.init(props);

    EventBus bus = manager.createEventBus();
    assertNotNull(bus);
    assertEquals(1, bus.getEventListeners().size());
    manager.stop();
  }

  @Test
  void testInitWithNoListeners() {
    Map<String, String> props = new HashMap<>();
    props.put("names", "");

    EventListenerManager manager = new EventListenerManager();
    manager.init(props);

    EventBus bus = manager.createEventBus();
    assertNotNull(bus);
    assertTrue(bus.getEventListeners().isEmpty());
  }

  @Test
  void testInitWithMultipleSyncListeners() {
    Map<String, String> props = new HashMap<>();
    props.put("names", "l1,l2");
    props.put("l1.class", DummyEventListener.class.getName());
    props.put("l2.class", DummyEventListener.class.getName());

    EventListenerManager manager = new EventListenerManager();
    manager.init(props);

    EventBus bus = manager.createEventBus();
    assertEquals(2, bus.getEventListeners().size());
    manager.stop();
  }

  @Test
  void testInitMissingClassThrows() {
    Map<String, String> props = new HashMap<>();
    props.put("names", "bad-listener");

    EventListenerManager manager = new EventListenerManager();
    assertThrows(RuntimeException.class, () -> manager.init(props));
  }

  @Test
  void testInitWithInvalidClassThrows() {
    Map<String, String> props = new HashMap<>();
    props.put("names", "bad");
    props.put("bad.class", "com.nonexistent.FakeClass");

    EventListenerManager manager = new EventListenerManager();
    assertThrows(RuntimeException.class, () -> manager.init(props));
  }

  @Test
  void testDuplicateListenerNamesThrows() {
    Map<String, String> props = new HashMap<>();
    props.put("names", "dup,dup");
    props.put("dup.class", DummyEventListener.class.getName());

    EventListenerManager manager = new EventListenerManager();
    assertThrows(RuntimeException.class, () -> manager.init(props));
  }

  @Test
  void testAddEventListenerDynamically() {
    Map<String, String> props = new HashMap<>();
    props.put("names", "");

    EventListenerManager manager = new EventListenerManager();
    manager.init(props);

    manager.addEventListener("dynamic", new DummyEventListener());

    EventBus bus = manager.createEventBus();
    assertEquals(1, bus.getEventListeners().size());
    manager.stop();
  }
}
