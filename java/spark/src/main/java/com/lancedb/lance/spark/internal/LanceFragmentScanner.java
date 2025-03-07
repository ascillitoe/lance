/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lancedb.lance.spark.internal;

import com.lancedb.lance.Dataset;
import com.lancedb.lance.Fragment;
import com.lancedb.lance.ReadOptions;
import com.lancedb.lance.ipc.LanceScanner;
import com.lancedb.lance.ipc.ScanOptions;
import com.lancedb.lance.spark.LanceConfig;
import com.lancedb.lance.spark.LanceConstant;
import com.lancedb.lance.spark.SparkOptions;
import com.lancedb.lance.spark.read.LanceInputPartition;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class LanceFragmentScanner implements AutoCloseable {
  private Dataset dataset;
  private Fragment fragment;
  private LanceScanner scanner;

  private LanceFragmentScanner(Dataset dataset, Fragment fragment, LanceScanner scanner) {
    this.dataset = dataset;
    this.fragment = fragment;
    this.scanner = scanner;
  }

  public static LanceFragmentScanner create(
      int fragmentId, LanceInputPartition inputPartition, BufferAllocator allocator) {
    Dataset dataset = null;
    Fragment fragment = null;
    LanceScanner scanner = null;
    try {
      LanceConfig config = inputPartition.getConfig();
      ReadOptions options = SparkOptions.genReadOptionFromConfig(config);
      dataset = Dataset.open(allocator, config.getDatasetUri(), options);
      fragment =
          dataset.getFragments().stream()
              .filter(f -> f.getId() == fragmentId)
              .findAny()
              .orElseThrow(() -> new RuntimeException("no fragment found for " + fragmentId));
      ScanOptions.Builder scanOptions = new ScanOptions.Builder();
      scanOptions.columns(getColumnNames(inputPartition.getSchema()));
      if (inputPartition.getWhereCondition().isPresent()) {
        scanOptions.filter(inputPartition.getWhereCondition().get());
      }
      scanOptions.batchSize(SparkOptions.getBatchSize(config));
      scanOptions.withRowId(getWithRowId(inputPartition.getSchema()));
      scanOptions.withRowAddress(getWithRowAddress(inputPartition.getSchema()));
      if (inputPartition.getLimit().isPresent()) {
        scanOptions.limit(inputPartition.getLimit().get());
      }
      if (inputPartition.getOffset().isPresent()) {
        scanOptions.offset(inputPartition.getOffset().get());
      }
      if (inputPartition.getTopNSortOrders().isPresent()) {
        scanOptions.setColumnOrderings(inputPartition.getTopNSortOrders().get());
      }
      scanner = fragment.newScan(scanOptions.build());
    } catch (Throwable t) {
      if (scanner != null) {
        try {
          scanner.close();
        } catch (Throwable it) {
          t.addSuppressed(it);
        }
      }
      if (dataset != null) {
        try {
          dataset.close();
        } catch (Throwable it) {
          t.addSuppressed(it);
        }
      }
      throw t;
    }
    return new LanceFragmentScanner(dataset, fragment, scanner);
  }

  /** @return the arrow reader. The caller is responsible for closing the reader */
  public ArrowReader getArrowReader() {
    return scanner.scanBatches();
  }

  @Override
  public void close() throws IOException {
    if (scanner != null) {
      try {
        scanner.close();
      } catch (Exception e) {
        throw new IOException(e);
      }
    }
    if (dataset != null) {
      dataset.close();
    }
  }

  private static List<String> getColumnNames(StructType schema) {
    return Arrays.stream(schema.fields())
        .map(StructField::name)
        .filter(
            name -> !name.equals(LanceConstant.ROW_ID) && !name.equals(LanceConstant.ROW_ADDRESS))
        .collect(Collectors.toList());
  }

  private static boolean getWithRowId(StructType schema) {
    return Arrays.stream(schema.fields())
        .map(StructField::name)
        .anyMatch(name -> name.equals(LanceConstant.ROW_ID));
  }

  private static boolean getWithRowAddress(StructType schema) {
    return Arrays.stream(schema.fields())
        .map(StructField::name)
        .anyMatch(name -> name.equals(LanceConstant.ROW_ADDRESS));
  }
}
