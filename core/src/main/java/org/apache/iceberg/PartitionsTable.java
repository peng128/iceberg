/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import java.util.Map;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Projections;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.StructLikeWrapper;

/**
 * A {@link Table} implementation that exposes a table's partitions as rows.
 */
public class PartitionsTable extends BaseMetadataTable {

  private final Schema schema;
  static final boolean PLAN_SCANS_WITH_WORKER_POOL =
      SystemProperties.getBoolean(SystemProperties.SCAN_THREAD_POOL_ENABLED, true);

  PartitionsTable(TableOperations ops, Table table) {
    this(ops, table, table.name() + ".partitions");
  }

  PartitionsTable(TableOperations ops, Table table, String name) {
    super(ops, table, name);

    this.schema = new Schema(
        Types.NestedField.required(1, "partition", Partitioning.partitionType(table)),
        Types.NestedField.required(2, "record_count", Types.LongType.get()),
        Types.NestedField.required(3, "file_count", Types.IntegerType.get()),
        Types.NestedField.required(4, "spec_id", Types.IntegerType.get())
    );
  }

  @Override
  public TableScan newScan() {
    return new PartitionsScan(operations(), table());
  }

  @Override
  public Schema schema() {
    if (table().spec().fields().size() < 1) {
      return schema.select("record_count", "file_count");
    }
    return schema;
  }

  @Override
  MetadataTableType metadataTableType() {
    return MetadataTableType.PARTITIONS;
  }

  private DataTask task(StaticTableScan scan) {
    TableOperations ops = operations();
    Iterable<Partition> partitions = partitions(scan);
    if (table().spec().fields().size() < 1) {
      // the table is unpartitioned, partitions contains only the root partition
      return StaticDataTask.of(
          io().newInputFile(ops.current().metadataFileLocation()),
          schema(), scan.schema(), partitions,
          root -> StaticDataTask.Row.of(root.recordCount, root.fileCount)
      );
    } else {
      return StaticDataTask.of(
          io().newInputFile(ops.current().metadataFileLocation()),
          schema(), scan.schema(), partitions,
          PartitionsTable::convertPartition
      );
    }
  }

  private static StaticDataTask.Row convertPartition(Partition partition) {
    return StaticDataTask.Row.of(partition.key, partition.recordCount, partition.fileCount, partition.specId);
  }

  private static Iterable<Partition> partitions(StaticTableScan scan) {
    CloseableIterable<FileScanTask> tasks = planFiles(scan);

    PartitionMap partitions = new PartitionMap(scan.table().spec().partitionType());
    for (FileScanTask task : tasks) {
      partitions.get(task.file().partition()).update(task.file());
    }
    return partitions.all();
  }

  @VisibleForTesting
  static CloseableIterable<FileScanTask> planFiles(StaticTableScan scan) {
    Table table = scan.table();
    Snapshot snapshot = table.snapshot(scan.snapshot().snapshotId());
    boolean caseSensitive = scan.isCaseSensitive();

    // use an inclusive projection to remove the partition name prefix and filter out any non-partition expressions
    Expression partitionFilter = Projections
        .inclusive(transformSpec(scan.schema(), table.spec()), caseSensitive)
        .project(scan.filter());

    ManifestGroup manifestGroup = new ManifestGroup(table.io(), snapshot.dataManifests(), snapshot.deleteManifests())
        .caseSensitive(caseSensitive)
        .filterPartitions(partitionFilter)
        .select(scan.colStats() ? DataTableScan.SCAN_WITH_STATS_COLUMNS : DataTableScan.SCAN_COLUMNS)
        .specsById(scan.table().specs())
        .ignoreDeleted();

    if (scan.shouldIgnoreResiduals()) {
      manifestGroup = manifestGroup.ignoreResiduals();
    }

    if (scan.snapshot().dataManifests().size() > 1 &&
        (PLAN_SCANS_WITH_WORKER_POOL || scan.context().planWithCustomizedExecutor())) {
      manifestGroup = manifestGroup.planWith(scan.context().planExecutor());
    }

    return manifestGroup.planFiles();
  }

  private class PartitionsScan extends StaticTableScan {
    PartitionsScan(TableOperations ops, Table table) {
      super(ops, table, PartitionsTable.this.schema(), MetadataTableType.PARTITIONS, PartitionsTable.this::task);
    }
  }

  static class PartitionMap {
    private final Map<StructLikeWrapper, Partition> partitions = Maps.newHashMap();
    private final Types.StructType type;
    private final StructLikeWrapper reused;

    PartitionMap(Types.StructType type) {
      this.type = type;
      this.reused = StructLikeWrapper.forType(type);
    }

    Partition get(StructLike key) {
      Partition partition = partitions.get(reused.set(key));
      if (partition == null) {
        partition = new Partition(key);
        partitions.put(StructLikeWrapper.forType(type).set(key), partition);
      }
      return partition;
    }

    Iterable<Partition> all() {
      return partitions.values();
    }
  }

  static class Partition {
    private final StructLike key;
    private long recordCount;
    private int fileCount;
    private int specId;

    Partition(StructLike key) {
      this.key = key;
      this.recordCount = 0;
      this.fileCount = 0;
      this.specId = 0;
    }

    void update(DataFile file) {
      this.recordCount += file.recordCount();
      this.fileCount += 1;
      this.specId = file.specId();
    }
  }
}
