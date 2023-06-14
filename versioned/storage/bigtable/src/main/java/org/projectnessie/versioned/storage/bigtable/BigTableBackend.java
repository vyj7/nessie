/*
 * Copyright (C) 2023 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.versioned.storage.bigtable;

import static com.google.cloud.bigtable.data.v2.models.Filters.FILTERS;
import static com.google.common.base.Preconditions.checkState;
import static com.google.protobuf.ByteString.copyFromUtf8;
import static java.util.Objects.requireNonNull;
import static org.projectnessie.versioned.storage.bigtable.BigTableConstants.FAMILY_OBJS;
import static org.projectnessie.versioned.storage.bigtable.BigTableConstants.FAMILY_REFS;
import static org.projectnessie.versioned.storage.bigtable.BigTableConstants.MAX_BULK_MUTATIONS;
import static org.projectnessie.versioned.storage.bigtable.BigTableConstants.TABLE_OBJS;
import static org.projectnessie.versioned.storage.bigtable.BigTableConstants.TABLE_REFS;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.BulkMutation;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.models.Query;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.protobuf.ByteString;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.projectnessie.versioned.storage.common.persist.Backend;
import org.projectnessie.versioned.storage.common.persist.PersistFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BigTableBackend implements Backend {
  private static final Logger LOGGER = LoggerFactory.getLogger(BigTableBackend.class);

  private final BigtableDataClient dataClient;
  private final BigtableTableAdminClient tableAdminClient;
  private final boolean closeClient;

  BigTableBackend(
      @Nonnull @jakarta.annotation.Nonnull BigtableDataClient dataClient,
      @Nullable @jakarta.annotation.Nullable BigtableTableAdminClient tableAdminClient,
      boolean closeClient) {
    this.dataClient = dataClient;
    this.tableAdminClient = tableAdminClient;
    this.closeClient = closeClient;
  }

  @Nonnull
  @jakarta.annotation.Nonnull
  BigtableDataClient client() {
    return dataClient;
  }

  @Override
  @Nonnull
  @jakarta.annotation.Nonnull
  public PersistFactory createFactory() {
    return new BigTablePersistFactory(this);
  }

  @Override
  public void close() {
    if (closeClient) {
      RuntimeException ex = null;
      try {
        dataClient.close();
      } catch (Exception e) {
        ex = new RuntimeException(e);
      }
      try {
        if (tableAdminClient != null) {
          tableAdminClient.close();
        }
      } catch (Exception e) {
        if (ex == null) {
          ex = new RuntimeException(e);
        } else {
          ex.addSuppressed(e);
        }
      }
      if (ex != null) {
        throw ex;
      }
    }
  }

  @Override
  public void setupSchema() {
    if (tableAdminClient == null) {
      // If BigTable admin client is not available, check at least that the required tables exist.
      boolean refs = checkTableNoAdmin(TABLE_REFS);
      boolean objs = checkTableNoAdmin(TABLE_OBJS);
      checkState(
          refs && objs,
          "Not all required tables (%s and %s) are available in BigTable, cannot start.",
          TABLE_REFS,
          TABLE_OBJS);
      LOGGER.info("No Bigtable admin client available, skipping schema setup");
      return;
    }

    checkTable(TABLE_REFS, FAMILY_REFS);
    checkTable(TABLE_OBJS, FAMILY_OBJS);
  }

  private boolean checkTableNoAdmin(String table) {
    try {
      dataClient.readRow(table, "dummy");
      return true;
    } catch (NotFoundException nf) {
      LOGGER.error("Nessie table '{}' does not exist in Google Bigtable", table);
    }
    return false;
  }

  private void checkTable(String table, String family) {
    BigtableTableAdminClient client = requireNonNull(tableAdminClient);
    try {
      client.getTable(table);
    } catch (NotFoundException nf) {
      LOGGER.info("Creating Nessie table '{}' in Google Bigtable...", table);
      client.createTable(CreateTableRequest.of(table).addFamily(family));
    }
  }

  @Override
  public void eraseRepositories(Set<String> repositoryIds) {
    if (!eraseRepositoriesAdminClient(repositoryIds)) {
      eraseRepositoriesNoAdminClient(repositoryIds);
    }
  }

  private boolean eraseRepositoriesAdminClient(Set<String> repositoryIds) {
    if (tableAdminClient == null) {
      return false;
    }

    for (String repoId : repositoryIds) {
      ByteString prefix = copyFromUtf8(repoId + ':');
      tableAdminClient.dropRowRange(TABLE_REFS, prefix);
      tableAdminClient.dropRowRange(TABLE_OBJS, prefix);
    }

    return true;
  }

  private void eraseRepositoriesNoAdminClient(Set<String> repositoryIds) {
    List<ByteString> prefixes =
        repositoryIds.stream()
            .map(repoId -> copyFromUtf8(repoId + ':'))
            .collect(Collectors.toList());
    eraseRepositoriesTable(TABLE_REFS, prefixes);
    eraseRepositoriesTable(TABLE_OBJS, prefixes);
  }

  private void eraseRepositoriesTable(String tableId, List<ByteString> prefixes) {
    BulkMutation bulkDelete = BulkMutation.create(tableId);

    Query.QueryPaginator paginator = Query.create(tableId).createPaginator(100);
    Iterator<Row> rows = dataClient.readRows(nextQuery(paginator)).iterator();
    while (true) {
      ByteString lastKey = null;
      while (rows.hasNext()) {
        Row row = rows.next();
        lastKey = row.getKey();
        if (prefixes.stream().anyMatch(prefix -> row.getKey().startsWith(prefix))) {
          bulkDelete.add(row.getKey(), Mutation.create().deleteRow());
        }

        if (bulkDelete.getEntryCount() == MAX_BULK_MUTATIONS) {
          dataClient.bulkMutateRows(bulkDelete);
          bulkDelete = BulkMutation.create(tableId);
        }
      }
      if (lastKey == null) {
        break;
      }
      paginator.advance(lastKey);
      rows = dataClient.readRows(nextQuery(paginator)).iterator();
    }

    if (bulkDelete.getEntryCount() > 0) {
      dataClient.bulkMutateRows(bulkDelete);
    }
  }

  private static Query nextQuery(Query.QueryPaginator paginator) {
    return paginator.getNextQuery().filter(FILTERS.limit().cellsPerRow(1));
  }

  @Override
  public String configInfo() {
    return this.tableAdminClient != null ? "" : " (no admin client)";
  }
}
