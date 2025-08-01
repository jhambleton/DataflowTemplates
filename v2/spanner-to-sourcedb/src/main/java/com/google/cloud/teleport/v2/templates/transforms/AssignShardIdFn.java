/*
 * Copyright (C) 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.templates.transforms;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TimestampBound;
import com.google.cloud.spanner.Value;
import com.google.cloud.teleport.v2.spanner.ddl.Column;
import com.google.cloud.teleport.v2.spanner.ddl.Ddl;
import com.google.cloud.teleport.v2.spanner.ddl.IndexColumn;
import com.google.cloud.teleport.v2.spanner.ddl.Table;
import com.google.cloud.teleport.v2.spanner.migrations.schema.Schema;
import com.google.cloud.teleport.v2.spanner.sourceddl.SourceSchema;
import com.google.cloud.teleport.v2.spanner.type.Type;
import com.google.cloud.teleport.v2.spanner.utils.IShardIdFetcher;
import com.google.cloud.teleport.v2.spanner.utils.ShardIdRequest;
import com.google.cloud.teleport.v2.spanner.utils.ShardIdResponse;
import com.google.cloud.teleport.v2.templates.changestream.DataChangeRecordTypeConvertor;
import com.google.cloud.teleport.v2.templates.changestream.TrimmedShardedDataChangeRecord;
import com.google.cloud.teleport.v2.templates.constants.Constants;
import com.google.cloud.teleport.v2.templates.utils.ShardingLogicImplFetcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.apache.beam.sdk.io.gcp.spanner.SpannerAccessor;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.io.gcp.spanner.changestreams.model.Mod;
import org.apache.beam.sdk.io.gcp.spanner.changestreams.model.ModType;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.jetbrains.annotations.NotNull;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This DoFn assigns the shardId as key to the record. */
public class AssignShardIdFn
    extends DoFn<TrimmedShardedDataChangeRecord, KV<Long, TrimmedShardedDataChangeRecord>> {
  private static final Logger LOG = LoggerFactory.getLogger(AssignShardIdFn.class);

  private static final java.time.Duration LOOKBACK_DURATION_FOR_DELETE =
      java.time.Duration.ofNanos(1000);

  private final SpannerConfig spannerConfig;

  /* SpannerAccessor must be transient so that its value is not serialized at runtime. */
  private transient SpannerAccessor spannerAccessor;

  /* The information schema of the Cloud Spanner database */
  private final Ddl ddl;

  private final SourceSchema sourceSchema;

  private final Schema schema;

  // Jackson Object mapper.
  private transient ObjectMapper mapper;

  private final String shardingMode;

  private final String shardName;

  private final String skipDirName;

  private final String customJarPath;

  private final String shardingCustomClassName;

  private final String shardingCustomParameters;

  private IShardIdFetcher shardIdFetcher;

  private final Long maxConnectionsAcrossAllShards;

  private final String sourceType;

  public AssignShardIdFn(
      SpannerConfig spannerConfig,
      Schema schema,
      Ddl ddl,
      SourceSchema sourceSchema,
      String shardingMode,
      String shardName,
      String skipDirName,
      String customJarPath,
      String shardingCustomClassName,
      String shardingCustomParameters,
      Long maxConnectionsAcrossAllShards,
      String sourceTyoe) {
    this.spannerConfig = spannerConfig;
    this.schema = schema;
    this.ddl = ddl;
    this.sourceSchema = sourceSchema;
    this.shardingMode = shardingMode;
    this.shardName = shardName;
    this.skipDirName = skipDirName;
    this.customJarPath = customJarPath;
    this.shardingCustomClassName = shardingCustomClassName;
    this.shardingCustomParameters = shardingCustomParameters;
    this.maxConnectionsAcrossAllShards = maxConnectionsAcrossAllShards;
    this.sourceType = sourceTyoe;
  }

  // setSpannerAccessor is added to be used by unit tests
  public void setSpannerAccessor(SpannerAccessor spannerAccessor) {
    this.spannerAccessor = spannerAccessor;
  }

  // setMapper is added to be used by unit tests
  public void setMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  // setShardIdFetcher is added to be used by unit tests
  public void setShardIdFetcher(IShardIdFetcher shardIdFetcher) {
    this.shardIdFetcher = shardIdFetcher;
  }

  /** Setup function connects to Cloud Spanner. */
  @Setup
  public void setup() {
    boolean retry = true;
    while (retry) {
      try {
        if (spannerConfig != null) {
          spannerAccessor = SpannerAccessor.getOrCreate(spannerConfig);
        }
        mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        shardIdFetcher =
            ShardingLogicImplFetcher.getShardingLogicImpl(
                customJarPath,
                shardingCustomClassName,
                shardingCustomParameters,
                schema,
                skipDirName);
        retry = false;
      } catch (SpannerException e) {
        LOG.info("Exception in setup of AssignShardIdFn {}", e.getMessage());
        if (e.getMessage().contains("RESOURCE_EXHAUSTED")) {
          try {
            Thread.sleep(10000);
          } catch (java.lang.InterruptedException ex) {
            throw new RuntimeException(ex);
          }
        }
      } catch (Exception e) {
        throw e;
      }
    }
  }

  /** Teardown function disconnects from the Cloud Spanner. */
  @Teardown
  public void teardown() {
    if (spannerConfig != null) {
      spannerAccessor.close();
    }
  }

  /**
   * Assigns shard id to each spanner record. If custom jar path is specified, loads the custom
   * class locally and then fetches the shard id and if not specified relies on default
   * ShardIdFetcherImpl to fetch the shard id.
   */
  @ProcessElement
  public void processElement(ProcessContext c) throws Exception {
    TrimmedShardedDataChangeRecord record = new TrimmedShardedDataChangeRecord(c.element());
    String qualifiedShard = "";
    String tableName = record.getTableName();
    String keysJsonStr = record.getMod().getKeysJson();

    try {
      Map<String, Object> spannerRecord =
          getSpannerRecordMapAndUpdateDeletedValue(record, tableName, keysJsonStr);

      if (shardingMode.equals(Constants.SHARDING_MODE_SINGLE_SHARD)) {
        record.setShard(this.shardName);
        qualifiedShard = this.shardName;
      } else {
        // Skip from processing if table not found at source.
        boolean doesTableExist = doesTableExistAtSource(tableName);
        if (!doesTableExist) {
          LOG.warn(
              "Writing record for table {} to skipped directory name {} since table not present in"
                  + " the session file.",
              tableName,
              skipDirName);
          record.setShard(skipDirName);
          qualifiedShard = skipDirName;
        } else {
          ShardIdRequest shardIdRequest = new ShardIdRequest(tableName, spannerRecord);

          ShardIdResponse shardIdResponse = getShardIdResponse(shardIdRequest);

          qualifiedShard = shardIdResponse.getLogicalShardId();
          if (qualifiedShard == null || qualifiedShard.isEmpty() || qualifiedShard.contains("/")) {
            throw new IllegalArgumentException(
                "Invalid logical shard id value: "
                    + qualifiedShard
                    + " for spanner table: "
                    + record.getTableName());
          }
        }
      }
      record.setShard(qualifiedShard);
      String finalKeyString = tableName + "_" + keysJsonStr + "_" + qualifiedShard;
      Long finalKey =
          finalKeyString.hashCode() % maxConnectionsAcrossAllShards; // The total parallelism is
      // maxConnectionsAcrossAllShards
      c.output(KV.of(finalKey, record));
    } catch (Exception e) {
      StringWriter errors = new StringWriter();
      e.printStackTrace(new PrintWriter(errors));
      LOG.error("Error fetching shard Id column: " + e.getMessage() + ": " + errors.toString(), e);
      // The record has no shard hence will be sent to DLQ in subsequent steps
      String finalKeyString = record.getTableName() + "_" + keysJsonStr + "_" + skipDirName;
      Long finalKey = finalKeyString.hashCode() % maxConnectionsAcrossAllShards;
      c.output(KV.of(finalKey, record));
    }
  }

  @NotNull
  private Map<String, Object> getSpannerRecordMapAndUpdateDeletedValue(
      TrimmedShardedDataChangeRecord record, String tableName, String keysJsonStr)
      throws Exception {
    JsonNode keysJson = mapper.readTree(keysJsonStr);
    String newValueJsonStr = record.getMod().getNewValuesJson();
    JsonNode newValueJson = mapper.readTree(newValueJsonStr);
    Map<String, Object> spannerRecord = new HashMap<>();
    // Query the spanner database in case of a DELETE event
    if (record.getModType() == ModType.DELETE) {
      spannerRecord =
          fetchSpannerRecord(
              tableName,
              record.getCommitTimestamp(),
              record.getServerTransactionId(),
              keysJson,
              record,
              record.getModType());
    } else {
      spannerRecord = getSpannerRecordFromChangeStreamData(tableName, keysJson, newValueJson);
    }
    return spannerRecord;
  }

  private Map<String, Object> fetchSpannerRecord(
      String tableName,
      com.google.cloud.Timestamp commitTimestamp,
      String serverTxnId,
      JsonNode keysJson,
      TrimmedShardedDataChangeRecord record,
      ModType modType)
      throws Exception {

    // Stale read the spanner row for all the columns for timestamp 1 micro second less than the
    // DELETE event
    java.time.Instant commitInstant =
        java.time.Instant.ofEpochSecond(commitTimestamp.getSeconds(), commitTimestamp.getNanos());

    java.time.Instant staleInstant = commitInstant.minus(LOOKBACK_DURATION_FOR_DELETE);

    com.google.cloud.Timestamp staleReadTs =
        com.google.cloud.Timestamp.ofTimeSecondsAndNanos(
            staleInstant.getEpochSecond(), staleInstant.getNano());
    List<String> columns =
        ddl.table(tableName).columns().stream()
            .filter(column -> (!column.isGenerated() || column.isStored()))
            .map(Column::name)
            .collect(Collectors.toList());

    Struct row =
        spannerAccessor
            .getDatabaseClient()
            .singleUse(TimestampBound.ofReadTimestamp(staleReadTs))
            .readRow(tableName, generateKey(tableName, keysJson), columns);
    if (row == null) {
      throw new Exception(
          "stale read on Spanner returned null for table: "
              + tableName
              + ", commitTimestamp: "
              + commitTimestamp
              + " and serverTxnId:"
              + serverTxnId);
    }
    Map<String, Object> rowAsMap = getRowAsMap(row, columns, tableName);
    // TODO find a way to not make a special case from Cassandra.
    if (modType == ModType.DELETE && sourceType != Constants.SOURCE_CASSANDRA) {

      Table table = ddl.table(tableName);
      ImmutableSet<String> keyColumns =
          table.primaryKeys().stream().map(k -> k.name()).collect(ImmutableSet.toImmutableSet());
      Mod newMod;
      ObjectNode newValuesJsonNode =
          (ObjectNode) mapper.readTree(record.getMod().getNewValuesJson());
      rowAsMap.keySet().stream()
          .filter(k -> !keyColumns.contains(k))
          .forEach(colName -> marshalSpannerValues(newValuesJsonNode, tableName, colName, row));
      String newValuesJson = mapper.writeValueAsString(newValuesJsonNode);
      record.setMod(
          new Mod(
              record.getMod().getKeysJson(), record.getMod().getOldValuesJson(), newValuesJson));
    }
    return rowAsMap;
  }

  /*
   * Marshals Spanner's read row values to match CDC stream's representation.
   */
  private void marshalSpannerValues(
      ObjectNode newValuesJsonNode, String tableName, String colName, Struct row) {
    // TODO(b/430495490): Add support for string arrays on Spanner side.
    switch (ddl.table(tableName).column(colName).type().getCode()) {
      case FLOAT64:
        double val = row.getDouble(colName);
        if (Double.isNaN(val) || !Double.isFinite(val)) {
          newValuesJsonNode.put(colName, val);

        } else {
          newValuesJsonNode.put(colName, new BigDecimal(val));
        }
        break;
      case BOOL:
        newValuesJsonNode.put(colName, row.getBoolean(colName));
        break;
      case BYTES:
        // We need to trim the base64 string to remove newlines added at the end.
        // Older version of Base64 lik e(MIME) or Privacy-Enhanced Mail (PEM), often included line
        // breaks.
        // MySql adheres to RFC 4648 (the standard MySQL uses) which states that implementations
        // MUST
        // NOT add line feeds to base-encoded data unless explicitly directed by a referring
        // specification.
        newValuesJsonNode.put(
            colName,
            Base64.getEncoder()
                .encodeToString(row.getValue(colName).getBytes().toByteArray())
                .trim());
        break;
      default:
        newValuesJsonNode.put(colName, row.getValue(colName).toString());
    }
  }

  public Map<String, Object> getRowAsMap(Struct row, List<String> columns, String tableName)
      throws Exception {
    Map<String, Object> spannerRecord = new HashMap<>();
    Table table = ddl.table(tableName);
    for (String columnName : columns) {
      Column column = table.column(columnName);
      Object columnValue =
          row.isNull(columnName) ? null : getColumnValueFromRow(column, row.getValue(columnName));
      spannerRecord.put(columnName, columnValue);
    }
    return spannerRecord;
  }

  // Refer https://cloud.google.com/spanner/docs/reference/standard-sql/data-types
  // and https://cloud.google.com/spanner/docs/reference/postgresql/data-types
  // for allowed primary key types
  private com.google.cloud.spanner.Key generateKey(String tableName, JsonNode keysJson)
      throws Exception {
    try {
      Table table = ddl.table(tableName);
      ImmutableList<IndexColumn> keyColumns = table.primaryKeys();
      com.google.cloud.spanner.Key.Builder pk = com.google.cloud.spanner.Key.newBuilder();

      for (IndexColumn keyColumn : keyColumns) {
        Column key = table.column(keyColumn.name());
        Type keyColType = key.type();
        String keyColName = key.name();
        switch (keyColType.getCode()) {
          case BOOL:
          case PG_BOOL:
            pk.append(
                DataChangeRecordTypeConvertor.toBoolean(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          case INT64:
          case PG_INT8:
            pk.append(
                DataChangeRecordTypeConvertor.toLong(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          case FLOAT64:
          case PG_FLOAT8:
            pk.append(
                DataChangeRecordTypeConvertor.toDouble(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          case STRING:
          case PG_VARCHAR:
          case PG_TEXT:
            pk.append(
                DataChangeRecordTypeConvertor.toString(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          case NUMERIC:
          case PG_NUMERIC:
            pk.append(
                DataChangeRecordTypeConvertor.toNumericBigDecimal(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          case BYTES:
          case PG_BYTEA:
            pk.append(
                DataChangeRecordTypeConvertor.toByteArray(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          case TIMESTAMP:
          case PG_TIMESTAMPTZ:
            pk.append(
                DataChangeRecordTypeConvertor.toTimestamp(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          case DATE:
          case PG_DATE:
            pk.append(
                DataChangeRecordTypeConvertor.toDate(
                    keysJson, keyColName, /* requiredField= */ true));
            break;
          default:
            throw new IllegalArgumentException(
                "Column name(" + keyColName + ") has unsupported column type(" + keyColType + ")");
        }
      }
      return pk.build();
    } catch (Exception e) {
      throw new Exception("Error generating key: " + e.getMessage());
    }
  }

  private Object getColumnValueFromJson(Column column, JsonNode valuesJson) throws Exception {
    try {
      Type colType = column.type();
      String colName = column.name();
      switch (colType.getCode()) {
        case BOOL:
        case PG_BOOL:
          return DataChangeRecordTypeConvertor.toBoolean(valuesJson, colName, false);
        case INT64:
        case PG_INT8:
          return DataChangeRecordTypeConvertor.toLong(valuesJson, colName, false);
        case FLOAT64:
        case PG_FLOAT8:
          return DataChangeRecordTypeConvertor.toDouble(valuesJson, colName, false);
        case STRING:
        case PG_VARCHAR:
        case PG_TEXT:
          return DataChangeRecordTypeConvertor.toString(valuesJson, colName, false);
        case NUMERIC:
        case PG_NUMERIC:
          return DataChangeRecordTypeConvertor.toNumericBigDecimal(valuesJson, colName, false);
        case JSON:
        case PG_JSONB:
          return DataChangeRecordTypeConvertor.toString(valuesJson, colName, false);
        case BYTES:
        case PG_BYTEA:
          return DataChangeRecordTypeConvertor.toByteArray(valuesJson, colName, false);
        case TIMESTAMP:
        case PG_TIMESTAMPTZ:
          return DataChangeRecordTypeConvertor.toTimestamp(valuesJson, colName, false);
        case DATE:
        case PG_DATE:
          return DataChangeRecordTypeConvertor.toDate(valuesJson, colName, false);
        default:
          throw new IllegalArgumentException(
              "Column name(" + colName + ") has unsupported column type(" + colType + ")");
      }
    } catch (Exception e) {
      throw new Exception("Error getting column value from json: " + e.getMessage());
    }
  }

  private Object getColumnValueFromRow(Column column, Value value) throws Exception {
    try {
      Type colType = column.type();
      String colName = column.name();
      switch (colType.getCode()) {
        case BOOL:
        case PG_BOOL:
          return value.getBool();
        case INT64:
        case PG_INT8:
          return value.getInt64();
        case FLOAT64:
        case PG_FLOAT8:
          return value.getFloat64();
        case STRING:
        case PG_VARCHAR:
        case PG_TEXT:
          return value.getString();
        case NUMERIC:
        case PG_NUMERIC:
          return value.getNumeric();
        case JSON:
        case PG_JSONB:
          return value.getString();
        case BYTES:
          return value.getBytes();
        case PG_BYTEA:
          return value.getBytesArray();
        case TIMESTAMP:
        case PG_TIMESTAMPTZ:
          return value.getTimestamp();
        case DATE:
        case PG_DATE:
          return value.getDate();
        default:
          throw new IllegalArgumentException(
              "Column name(" + colName + ") has unsupported column type(" + colType + ")");
      }
    } catch (Exception e) {
      throw new Exception("Error getting column value from row: " + e.getMessage());
    }
  }

  private boolean doesTableExistAtSource(String tableName) {
    try {
      String sourceTableName = schema.getToSource().get(tableName).getName();
      if (sourceTableName == null || sourceTableName.isEmpty()) {
        return false;
      }
      for (com.google.cloud.teleport.v2.spanner.sourceddl.SourceTable table :
          sourceSchema.tables().values()) {
        if (table.name().equalsIgnoreCase(sourceTableName)) {
          return true;
        }
      }
      return false;
    } catch (NoSuchElementException e) {
      return false;
    }
  }

  private Map<String, Object> getSpannerRecordFromChangeStreamData(
      String tableName, JsonNode keysJson, JsonNode newValueJson) throws Exception {
    Map<String, Object> spannerRecord = new HashMap<>();
    Table table = ddl.table(tableName);

    // Add all fields from keysJson and valuesJson to spannerRecord
    for (Iterator<String> it = keysJson.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      Column column = table.column(key);
      spannerRecord.put(key, getColumnValueFromJson(column, keysJson));
    }
    for (Iterator<String> it = newValueJson.fieldNames(); it.hasNext(); ) {
      String key = it.next();
      Column column = table.column(key);
      spannerRecord.put(key, getColumnValueFromJson(column, newValueJson));
    }
    return spannerRecord;
  }

  private ShardIdResponse getShardIdResponse(ShardIdRequest shardIdRequest) throws Exception {
    ShardIdResponse shardIdResponse = new ShardIdResponse();
    if (!customJarPath.isEmpty() && !shardingCustomClassName.isEmpty()) {
      Distribution getShardIdResponseTimeMetric =
          Metrics.distribution(AssignShardIdFn.class, "custom_shard_id_impl_latency_ms");
      Instant startTimestamp = Instant.now();
      shardIdResponse = shardIdFetcher.getShardId(shardIdRequest);
      Instant endTimestamp = Instant.now();
      getShardIdResponseTimeMetric.update(new Duration(startTimestamp, endTimestamp).getMillis());
    } else {
      shardIdResponse = shardIdFetcher.getShardId(shardIdRequest);
    }
    return shardIdResponse;
  }
}
