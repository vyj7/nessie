/*
 * Copyright (C) 2020 Dremio
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
package org.projectnessie.client;

import java.util.OptionalInt;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import org.projectnessie.api.TreeApi;
import org.projectnessie.api.params.CommitLogParams;
import org.projectnessie.api.params.EntriesParams;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.client.api.PagingBuilder;
import org.projectnessie.error.NessieNotFoundException;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.EntriesResponse;
import org.projectnessie.model.EntriesResponse.Entry;
import org.projectnessie.model.LogResponse;

/** Helper and utility methods around streaming of {@link NessieApiV1} et al. */
public final class StreamingUtil {

  private StreamingUtil() {
    // intentionally blank
  }

  /**
   * Default implementation to return a stream of objects for a ref, functionally equivalent to
   * calling {@link TreeApi#getEntries(String, EntriesParams)} with manual paging.
   *
   * <p>The {@link Stream} returned by {@code getEntriesStream(ref, OptionalInt.empty())}, if not
   * limited, returns all commit-log entries.
   *
   * @param ref a named reference (branch or tag name) or a commit-hash
   * @param entriesParams A wrapper object holding all filtering parameters
   * @return stream of {@link Entry} objects
   */
  @Deprecated
  public static Stream<Entry> getEntriesStream(
      @NotNull TreeApi treeApi, @NotNull String ref, @NotNull EntriesParams entriesParams)
      throws NessieNotFoundException {

    return new ResultStreamPaginator<>(
            EntriesResponse::getEntries,
            (ref1, pageSize, token) ->
                treeApi.getEntries(
                    ref1,
                    EntriesParams.builder()
                        .from(entriesParams)
                        .maxRecords(pageSize)
                        .pageToken(token)
                        .build()))
        .generateStream(
            ref,
            entriesParams.maxRecords() == null
                ? OptionalInt.empty()
                : OptionalInt.of(entriesParams.maxRecords()));
  }

  /**
   * Default implementation to return a stream of commit-log entries, functionally equivalent to
   * calling {@link TreeApi#getCommitLog(String, CommitLogParams)} with manual paging.
   *
   * <p>The {@link Stream} returned by {@code getCommitLogStream(ref, OptionalInt.empty())}, if not
   * limited, returns all commit-log entries.
   *
   * @param treeApi The {@link TreeApi} to use
   * @param ref a named reference (branch or tag name) or a commit-hash
   * @param commitLogParams A wrapper object holding all filtering parameters for the commit log
   * @return stream of {@link CommitMeta} objects
   */
  @Deprecated
  public static Stream<CommitMeta> getCommitLogStream(
      @NotNull TreeApi treeApi, @NotNull String ref, @NotNull CommitLogParams commitLogParams)
      throws NessieNotFoundException {
    return new ResultStreamPaginator<>(
            LogResponse::getOperations,
            (reference, pageSize, token) ->
                treeApi.getCommitLog(
                    reference,
                    CommitLogParams.builder()
                        .from(commitLogParams)
                        .maxRecords(pageSize)
                        .pageToken(token)
                        .build()))
        .generateStream(
            ref,
            commitLogParams.maxRecords() == null
                ? OptionalInt.empty()
                : OptionalInt.of(commitLogParams.maxRecords()));
  }

  /**
   * Default implementation to return a stream of objects for a ref, functionally equivalent to
   * calling {@link TreeApi#getEntries(String, EntriesParams)} with manual paging.
   *
   * <p>The {@link Stream} returned by {@code getEntriesStream(ref, OptionalInt.empty())}, if not
   * limited, returns all commit-log entries.
   *
   * @param ref a named reference (branch or tag name) or a commit-hash
   * @return stream of {@link Entry} objects
   */
  public static Stream<Entry> getEntriesStream(
      @NotNull NessieApiV1 api,
      @NotNull String ref,
      @Nullable String hashOnRef,
      @Nullable String queryExpression,
      OptionalInt maxRecords)
      throws NessieNotFoundException {

    return new ResultStreamPaginator<>(
            EntriesResponse::getEntries,
            (ref1, pageSize, token) ->
                builderWithPagedQuery(
                        api.getEntries(), ref1, hashOnRef, pageSize, token, queryExpression)
                    .submit())
        .generateStream(ref, maxRecords);
  }

  /**
   * Default implementation to return a stream of commit-log entries, functionally equivalent to
   * calling {@link NessieApiV1#getCommitLog()} with manual paging.
   *
   * <p>The {@link Stream} returned by {@code getCommitLogStream(ref, OptionalInt.empty())}, if not
   * limited, returns all commit-log entries.
   *
   * @param api The {@link NessieApiV1} to use
   * @param ref a named reference (branch or tag name) or a commit-hash
   * @return stream of {@link CommitMeta} objects
   */
  public static Stream<CommitMeta> getCommitLogStream(
      @NotNull NessieApiV1 api,
      @NotNull String ref,
      @Nullable String hashOnRef,
      @Nullable String untilHash,
      @Nullable String queryExpression,
      OptionalInt maxRecords)
      throws NessieNotFoundException {
    return new ResultStreamPaginator<>(
            LogResponse::getOperations,
            (reference, pageSize, token) ->
                builderWithPagedQuery(
                        api.getCommitLog(), reference, hashOnRef, pageSize, token, queryExpression)
                    .untilHash(untilHash)
                    .submit())
        .generateStream(ref, maxRecords);
  }

  private static <B extends PagingBuilder<B>> B builderWithPagedQuery(
      B builder,
      String reference,
      String hashOnRef,
      Integer pageSize,
      String token,
      String queryExpression) {
    builder.refName(reference).hashOnRef(hashOnRef).queryExpression(queryExpression);
    if (pageSize != null) {
      builder.maxRecords(pageSize);
    }
    if (token != null) {
      builder.pageToken(token);
    }
    return builder;
  }
}
