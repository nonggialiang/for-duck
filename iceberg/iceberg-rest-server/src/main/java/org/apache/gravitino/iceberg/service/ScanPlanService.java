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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.cache.ScanPlanCache;
import org.apache.gravitino.iceberg.service.cache.ScanPlanCacheKey;
import org.apache.gravitino.utils.ClassUtils;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.IncrementalAppendScan;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Scan;
import org.apache.iceberg.ScanTaskParser;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.rest.PlanStatus;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles server-side Iceberg scan planning, including cache management. Extracted from {@link
 * CatalogWrapperForREST} to keep each service focused on a single responsibility.
 */
public class ScanPlanService {

  private static final Logger LOG = LoggerFactory.getLogger(ScanPlanService.class);

  private final org.apache.iceberg.catalog.Catalog catalog;
  private final ScanPlanCache scanPlanCache;

  public ScanPlanService(org.apache.iceberg.catalog.Catalog catalog, IcebergConfig config) {
    this.catalog = catalog;
    this.scanPlanCache = loadScanPlanCache(config);
  }

  /**
   * Plan table scan and return scan tasks.
   *
   * <p>This method performs synchronous scan planning (COMPLETED status) where tasks are returned
   * immediately as serialized JSON strings.
   *
   * @param tableIdentifier The table identifier.
   * @param scanRequest The scan request parameters including filters, projections, snapshot-id,
   *     etc.
   * @return PlanTableScanResponse with status=COMPLETED and serialized planTasks.
   */
  public PlanTableScanResponse planTableScan(
      TableIdentifier tableIdentifier, PlanTableScanRequest scanRequest) {
    LOG.debug(
        "Planning scan for table: {}, snapshotId: {}, startSnapshotId: {}, endSnapshotId: {}, "
            + "select: {}, caseSensitive: {}",
        tableIdentifier,
        scanRequest.snapshotId(),
        scanRequest.startSnapshotId(),
        scanRequest.endSnapshotId(),
        scanRequest.select(),
        scanRequest.caseSensitive());

    try {
      Table table = catalog.loadTable(tableIdentifier);
      Optional<PlanTableScanResponse> cachedResponse =
          scanPlanCache.get(ScanPlanCacheKey.create(tableIdentifier, table, scanRequest));
      if (cachedResponse.isPresent()) {
        LOG.info("Using cached scan plan for table: {}", tableIdentifier);
        return cachedResponse.get();
      }

      List<String> planTasks = new ArrayList<>();
      Map<Integer, PartitionSpec> specsById = new HashMap<>();
      List<DeleteFile> deleteFiles = new ArrayList<>();

      try (CloseableIterable<FileScanTask> fileScanTasks =
          createFilePlanScanTasks(table, tableIdentifier, scanRequest)) {
        for (FileScanTask fileScanTask : fileScanTasks) {
          try {
            String taskString = ScanTaskParser.toJson(fileScanTask);
            planTasks.add(taskString);

            int specId = fileScanTask.spec().specId();
            if (!specsById.containsKey(specId)) {
              specsById.put(specId, fileScanTask.spec());
            }

            if (!fileScanTask.deletes().isEmpty()) {
              deleteFiles.addAll(fileScanTask.deletes());
            }
          } catch (Exception e) {
            throw new RuntimeException(
                String.format(
                    "Failed to serialize scan task for table: %s. Error: %s",
                    tableIdentifier, e.getMessage()),
                e);
          }
        }
      } catch (IOException e) {
        LOG.error("Failed to close scan task iterator for table: {}", tableIdentifier, e);
        throw new RuntimeException("Failed to plan scan tasks: " + e.getMessage(), e);
      }

      List<DeleteFile> uniqueDeleteFiles =
          deleteFiles.stream().distinct().collect(Collectors.toList());

      if (planTasks.isEmpty()) {
        LOG.info(
            "Scan planning returned no tasks for table: {}. Table may be empty or fully filtered.",
            tableIdentifier);
      }

      PlanTableScanResponse.Builder responseBuilder =
          PlanTableScanResponse.builder()
              .withPlanStatus(PlanStatus.COMPLETED)
              .withPlanTasks(planTasks)
              .withSpecsById(specsById);

      if (!uniqueDeleteFiles.isEmpty()) {
        responseBuilder.withDeleteFiles(uniqueDeleteFiles);
        LOG.debug(
            "Included {} delete files in scan plan for table: {}",
            uniqueDeleteFiles.size(),
            tableIdentifier);
      }

      PlanTableScanResponse response = responseBuilder.build();
      scanPlanCache.put(ScanPlanCacheKey.create(tableIdentifier, table, scanRequest), response);
      return response;

    } catch (IllegalArgumentException e) {
      LOG.error("Invalid scan request for table {}: {}", tableIdentifier, e.getMessage());
      throw new IllegalArgumentException("Invalid scan parameters: " + e.getMessage(), e);
    } catch (org.apache.iceberg.exceptions.NoSuchTableException e) {
      LOG.error("Table not found during scan planning: {}", tableIdentifier);
      throw e;
    } catch (Exception e) {
      LOG.error("Unexpected error during scan planning for table: {}", tableIdentifier, e);
      throw new RuntimeException(
          "Scan planning failed for table " + tableIdentifier + ": " + e.getMessage(), e);
    }
  }

  private CloseableIterable<FileScanTask> createFilePlanScanTasks(
      Table table, TableIdentifier tableIdentifier, PlanTableScanRequest scanRequest) {
    Long startSnapshotId = scanRequest.startSnapshotId();
    Long endSnapshotId = scanRequest.endSnapshotId();
    if (startSnapshotId != null && endSnapshotId != null) {
      LOG.debug(
          "Using IncrementalAppendScan for table: {}, from snapshot: {} to snapshot: {}",
          tableIdentifier,
          startSnapshotId,
          endSnapshotId);
      IncrementalAppendScan incrementalScan =
          table
              .newIncrementalAppendScan()
              .fromSnapshotInclusive(startSnapshotId)
              .toSnapshot(endSnapshotId);
      incrementalScan = applyScanRequest(incrementalScan, scanRequest);
      return incrementalScan.planFiles();
    } else {
      TableScan tableScan = table.newScan();
      if (scanRequest.snapshotId() != null) {
        tableScan = tableScan.useSnapshot(scanRequest.snapshotId());
        LOG.debug("Applied snapshot filter: snapshot-id={}", scanRequest.snapshotId());
      }
      tableScan = applyScanRequest(tableScan, scanRequest);
      return tableScan.planFiles();
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends Scan> T applyScanRequest(T scan, PlanTableScanRequest scanRequest) {
    scan = (T) scan.caseSensitive(scanRequest.caseSensitive());
    LOG.debug("Applied case-sensitive: {}", scanRequest.caseSensitive());
    scan = applyScanFilter(scan, scanRequest);
    scan = applyScanSelect(scan, scanRequest);
    scan = applyScanStatsFields(scan, scanRequest);
    return scan;
  }

  @SuppressWarnings("unchecked")
  private <T extends Scan> T applyScanFilter(T scan, PlanTableScanRequest scanRequest) {
    if (scanRequest.filter() != null) {
      try {
        scan = (T) scan.filter(scanRequest.filter());
        LOG.debug("Applied filter expression: {}", scanRequest.filter());
      } catch (Exception e) {
        LOG.error("Failed to apply filter expression: {}", e.getMessage(), e);
        throw new IllegalArgumentException("Invalid filter expression: " + e.getMessage(), e);
      }
    }
    return scan;
  }

  @SuppressWarnings("unchecked")
  private <T extends Scan> T applyScanSelect(T scan, PlanTableScanRequest scanRequest) {
    if (scanRequest.select() != null && !scanRequest.select().isEmpty()) {
      try {
        scan = (T) scan.select(scanRequest.select());
        LOG.debug("Applied column projection: {}", scanRequest.select());
      } catch (Exception e) {
        LOG.error("Failed to apply column projection: {}", e.getMessage(), e);
        throw new IllegalArgumentException("Invalid column selection: " + e.getMessage(), e);
      }
    }
    return scan;
  }

  @SuppressWarnings("unchecked")
  private <T extends Scan> T applyScanStatsFields(T scan, PlanTableScanRequest scanRequest) {
    if (scanRequest.statsFields() != null && !scanRequest.statsFields().isEmpty()) {
      try {
        scan = (T) scan.includeColumnStats(scanRequest.statsFields());
        LOG.debug("Applied statistics fields: {}", scanRequest.statsFields());
      } catch (Exception e) {
        LOG.error("Failed to apply statistics fields: {}", e.getMessage(), e);
        throw new IllegalArgumentException("Invalid statistics fields: " + e.getMessage(), e);
      }
    }
    return scan;
  }

  private ScanPlanCache loadScanPlanCache(IcebergConfig config) {
    String impl = config.get(IcebergConfig.SCAN_PLAN_CACHE_IMPL);
    if (StringUtils.isBlank(impl)) {
      return ScanPlanCache.DUMMY;
    }
    ScanPlanCache cache =
        ClassUtils.loadAndGetInstance(impl, Thread.currentThread().getContextClassLoader());
    int capacity = config.get(IcebergConfig.SCAN_PLAN_CACHE_CAPACITY);
    int expireMinutes = config.get(IcebergConfig.SCAN_PLAN_CACHE_EXPIRE_MINUTES);
    cache.initialize(capacity, expireMinutes);
    LOG.info(
        "Load scan plan cache for catalog: {}, impl: {}, capacity: {}, expire minutes: {}",
        catalog.name(),
        impl,
        capacity,
        expireMinutes);
    return cache;
  }

  public void close() throws Exception {
    if (scanPlanCache != null) {
      scanPlanCache.close();
    }
  }
}
