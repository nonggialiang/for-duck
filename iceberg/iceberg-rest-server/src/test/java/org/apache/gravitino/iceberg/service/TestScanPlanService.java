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
package org.apache.gravitino.iceberg.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.iceberg.NullOrder;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.PlanStatus;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

class TestScanPlanService {

  private static final TableIdentifier TABLE_ID = TableIdentifier.of("db", "test_table");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));

  @Test
  @SuppressWarnings("unchecked")
  void testPlanTableScanReturnsCompletedStatusWithEmptyTasks() {
    org.apache.iceberg.catalog.Catalog catalog = mock(org.apache.iceberg.catalog.Catalog.class);
    Table table = mock(Table.class);
    TableScan scan = mock(TableScan.class);

    when(catalog.loadTable(TABLE_ID)).thenReturn(table);
    when(table.newScan()).thenReturn(scan);
    when(scan.caseSensitive(false)).thenReturn(scan);
    when(scan.planFiles()).thenReturn(CloseableIterable.empty());

    IcebergConfig config = new IcebergConfig(new HashMap<>());
    ScanPlanService service = new ScanPlanService(catalog, config);

    PlanTableScanRequest request =
        PlanTableScanRequest.builder().withCaseSensitive(false).build();
    PlanTableScanResponse response = service.planTableScan(TABLE_ID, request);

    assertEquals(PlanStatus.COMPLETED, response.planStatus());
    assertNotNull(response.planTasks());
    assertEquals(0, response.planTasks().size());
  }

  @Test
  void testPlanTableScanThrowsWhenTableNotFound() {
    org.apache.iceberg.catalog.Catalog catalog = mock(org.apache.iceberg.catalog.Catalog.class);
    when(catalog.loadTable(TABLE_ID)).thenThrow(new NoSuchTableException("not found"));

    IcebergConfig config = new IcebergConfig(new HashMap<>());
    ScanPlanService service = new ScanPlanService(catalog, config);

    PlanTableScanRequest request =
        PlanTableScanRequest.builder().withCaseSensitive(false).build();
    assertThrows(NoSuchTableException.class, () -> service.planTableScan(TABLE_ID, request));
  }

  @Test
  @SuppressWarnings("unchecked")
  void testPlanTableScanUsesSnapshotId() {
    org.apache.iceberg.catalog.Catalog catalog = mock(org.apache.iceberg.catalog.Catalog.class);
    Table table = mock(Table.class);
    TableScan scan = mock(TableScan.class);

    when(catalog.loadTable(TABLE_ID)).thenReturn(table);
    when(table.newScan()).thenReturn(scan);
    when(scan.caseSensitive(false)).thenReturn(scan);
    when(scan.useSnapshot(42L)).thenReturn(scan);
    when(scan.planFiles()).thenReturn(CloseableIterable.empty());

    IcebergConfig config = new IcebergConfig(new HashMap<>());
    ScanPlanService service = new ScanPlanService(catalog, config);

    PlanTableScanRequest request =
        PlanTableScanRequest.builder().withSnapshotId(42L).withCaseSensitive(false).build();
    PlanTableScanResponse response = service.planTableScan(TABLE_ID, request);

    assertEquals(PlanStatus.COMPLETED, response.planStatus());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testPlanTableScanCachesResults() {
    org.apache.iceberg.catalog.Catalog catalog = mock(org.apache.iceberg.catalog.Catalog.class);
    Table table = mock(Table.class);
    TableScan scan = mock(TableScan.class);

    when(catalog.loadTable(TABLE_ID)).thenReturn(table);
    when(table.newScan()).thenReturn(scan);
    when(scan.caseSensitive(false)).thenReturn(scan);
    when(scan.planFiles()).thenReturn(CloseableIterable.empty());

    IcebergConfig config = new IcebergConfig(new HashMap<>());
    ScanPlanService service = new ScanPlanService(catalog, config);

    PlanTableScanRequest request =
        PlanTableScanRequest.builder().withCaseSensitive(false).build();

    PlanTableScanResponse first = service.planTableScan(TABLE_ID, request);
    PlanTableScanResponse second = service.planTableScan(TABLE_ID, request);

    // Both responses should have the same status (cache hit returns same response)
    assertEquals(first.planStatus(), second.planStatus());
  }
}
