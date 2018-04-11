/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
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
package com.dremio.service.reflection.store;

import static com.dremio.datastore.SearchQueryUtils.and;
import static com.dremio.datastore.SearchQueryUtils.newRangeInt;
import static com.dremio.datastore.SearchQueryUtils.newRangeLong;
import static com.dremio.datastore.SearchQueryUtils.newTermQuery;
import static com.dremio.datastore.SearchQueryUtils.or;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.MATERIALIZATION_EXPIRATION;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.MATERIALIZATION_ID;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.MATERIALIZATION_INIT_REFRESH_SUBMIT;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.MATERIALIZATION_MODIFIED_AT;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.MATERIALIZATION_REFLECTION_ID;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.MATERIALIZATION_SERIES_ID;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.MATERIALIZATION_STATE;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.REFRESH_REFLECTION_ID;
import static com.dremio.service.reflection.store.ReflectionIndexKeys.REFRESH_SERIES_ID;
import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.notNull;

import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;
import javax.inject.Provider;

import com.dremio.datastore.IndexedStore;
import com.dremio.datastore.IndexedStore.FindByCondition;
import com.dremio.datastore.KVStoreProvider;
import com.dremio.datastore.SearchTypes;
import com.dremio.datastore.SearchTypes.SearchFieldSorting;
import com.dremio.datastore.SearchTypes.SearchFieldSorting.FieldType;
import com.dremio.datastore.SearchTypes.SortOrder;
import com.dremio.datastore.StoreBuildingFactory;
import com.dremio.datastore.StoreCreationFunction;
import com.dremio.datastore.VersionExtractor;
import com.dremio.datastore.indexed.IndexKey;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationId;
import com.dremio.service.reflection.proto.MaterializationMetrics;
import com.dremio.service.reflection.proto.MaterializationState;
import com.dremio.service.reflection.proto.ReflectionId;
import com.dremio.service.reflection.proto.Refresh;
import com.dremio.service.reflection.proto.RefreshId;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * store reflection materialization entries
 */
public class MaterializationStore {
//  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MaterializationStore.class);

  private static final String MATERIALIZATION_TABLE_NAME = "materialization_store";
  private static final String REFRESH_TABLE_NAME = "refresh_store";

  private static final SearchFieldSorting LAST_REFRESH = SearchTypes.SearchFieldSorting.newBuilder()
      .setType(FieldType.LONG)
      .setField(ReflectionIndexKeys.REFRESH_CREATE.getIndexFieldName())
      .setOrder(SortOrder.DESCENDING)
      .build();

  private static final SearchFieldSorting LAST_REFRESH_SUBMIT = SearchTypes.SearchFieldSorting.newBuilder()
    .setType(FieldType.LONG)
    .setField(ReflectionIndexKeys.MATERIALIZATION_INIT_REFRESH_SUBMIT.getIndexFieldName())
    .setOrder(SortOrder.DESCENDING)
    .build();

  private static final SearchFieldSorting MATERIALIZATION_MODIFIED_AT_SORT = SearchTypes.SearchFieldSorting.newBuilder()
    .setType(FieldType.LONG)
    .setField(ReflectionIndexKeys.MATERIALIZATION_MODIFIED_AT.getIndexFieldName())
    .setOrder(SortOrder.ASCENDING)
    .build();

  private final Supplier<IndexedStore<MaterializationId, Materialization>> materializationStore;
  private final Supplier<IndexedStore<RefreshId, Refresh>> refreshStore;

  private static final Function<Map.Entry<MaterializationId, Materialization>, Materialization> GET_MATERIALIZATION = new Function<Map.Entry<MaterializationId, Materialization>, Materialization>() {
    @Nullable
    @Override
    public Materialization apply(@Nullable Map.Entry<MaterializationId, Materialization> entry) {
      return entry == null ? null : entry.getValue();
    }
  };

  public MaterializationStore(final Provider<KVStoreProvider> provider) {
    Preconditions.checkNotNull(provider, "kvStore provider required");
    this.materializationStore = Suppliers.memoize(new Supplier<IndexedStore<MaterializationId, Materialization>>() {
      @Override
      public IndexedStore<MaterializationId, Materialization> get() {
        return provider.get().getStore(MaterializationStoreCreator.class);
      }
    });

    this.refreshStore = Suppliers.memoize(new Supplier<IndexedStore<RefreshId, Refresh>>() {
      @Override
      public IndexedStore<RefreshId, Refresh> get() {
        return provider.get().getStore(RefreshStoreCreator.class);
      }
    });
  }

  private Iterable<Materialization> findByIndex(IndexKey key, String value) {
    final SearchTypes.SearchQuery query = newTermQuery(key, value);
    final FindByCondition condition = new FindByCondition()
      .setCondition(query);
    return Iterables.transform(materializationStore.get().find(condition), GET_MATERIALIZATION);
  }

  public MaterializationMetrics getMetrics(final Materialization materialization) {
    MaterializationMetrics metrics = new MaterializationMetrics();
    long footprint = 0;
    double originalCost = 0;
    for(Refresh r : getRefreshes(materialization)) {
      footprint += r.getMetrics().getFootprint();
      originalCost += r.getMetrics().getOriginalCost();
    }
    metrics.setOriginalCost(originalCost);
    metrics.setFootprint(footprint);
    return metrics;
  }

  public Refresh getMostRecentRefresh(ReflectionId id) {
    final Materialization lastDone = getLastMaterializationDone(id);
    if (lastDone == null) {
      return null;
    }

    return getMostRecentRefresh(id, lastDone.getSeriesId());
  }

  public Iterable<Refresh> getRefreshesForSeries(ReflectionId id, Long seriesId) {
    if (seriesId == null) {
      return Collections.emptyList();
    }

    final FindByCondition condition = new FindByCondition()
      .setCondition(and(
        newTermQuery(REFRESH_REFLECTION_ID, id.getId()),
        newTermQuery(REFRESH_SERIES_ID, seriesId)
      ));

    return FluentIterable.from(refreshStore.get().find(condition)).transform(new Function<Entry<RefreshId, Refresh>, Refresh>() {
      @Override
      public Refresh apply(Entry<RefreshId, Refresh> refreshIdRefreshEntry) {
        return refreshIdRefreshEntry.getValue();
      }
    });
  }

  public Refresh getMostRecentRefresh(ReflectionId reflectionId, Long seriesId) {
    if(seriesId == null) {
      return null;
    }
    final FindByCondition condition = new FindByCondition()
        .addSorting(LAST_REFRESH)
        .setLimit(1)
        .setCondition(and(
          newTermQuery(ReflectionIndexKeys.REFRESH_REFLECTION_ID, reflectionId.getId()),
          newTermQuery(ReflectionIndexKeys.REFRESH_SERIES_ID, seriesId)
        ));

    Entry<RefreshId, Refresh> entry = Iterables.getFirst(refreshStore.get().find(condition), null);
    if(entry == null) {
      return null;
    }

    return entry.getValue();
  }

  public FluentIterable<Refresh> getRefreshesByReflectionId(ReflectionId reflectionId) {
    final FindByCondition condition = new FindByCondition()
      .setCondition(
        newTermQuery(ReflectionIndexKeys.REFRESH_REFLECTION_ID, reflectionId.getId()));

    return FluentIterable.from(refreshStore.get().find(condition)).transform(new Function<Entry<RefreshId, Refresh>, Refresh>() {
      @Override
      public Refresh apply(Entry<RefreshId, Refresh> refreshIdRefreshEntry) {
        return refreshIdRefreshEntry.getValue();
      }
    });
  }

  public Materialization getMostRecentMaterialization(ReflectionId id, Long seriesId) {
    if(seriesId == null) {
      return null;
    }
    final FindByCondition condition = new FindByCondition()
      .addSorting(LAST_REFRESH_SUBMIT)
      .setLimit(1)
      .setCondition(and(
        newTermQuery(ReflectionIndexKeys.MATERIALIZATION_REFLECTION_ID, id.getId()),
        newTermQuery(ReflectionIndexKeys.MATERIALIZATION_SERIES_ID, seriesId)
      ));

    Entry<MaterializationId, Materialization> entry = Iterables.getFirst(materializationStore.get().find(condition), null);
    if(entry == null) {
      return null;
    }

    return entry.getValue();
  }

  public FluentIterable<Refresh> getRefreshes(final Materialization materialization) {
    Long seriesId = materialization.getSeriesId();
    Integer seriesOrdinal = materialization.getSeriesOrdinal();

    if(seriesId == null || seriesOrdinal == null) {
      return FluentIterable.from(ImmutableList.<Refresh>of());
    }

    final FindByCondition condition = new FindByCondition()
        .setCondition(and(
          newTermQuery(ReflectionIndexKeys.REFRESH_REFLECTION_ID, materialization.getReflectionId().getId()),
          newTermQuery(ReflectionIndexKeys.REFRESH_SERIES_ID, seriesId),
          newRangeInt(ReflectionIndexKeys.REFRESH_SERIES_ORDINAL.getIndexFieldName(), 0, seriesOrdinal, true, true)
        ));
      return FluentIterable.from(refreshStore.get().find(condition)).transform(new Function<Entry<RefreshId, Refresh>, Refresh>(){

        @Override
        public Refresh apply(Entry<RefreshId, Refresh> input) {
          return input.getValue();
        }});
  }

  public Iterable<Refresh> getRefreshesExclusivelyOwnedBy(final Materialization m) {
    FluentIterable<Refresh> refreshes = getRefreshes(m);
    if (refreshes.isEmpty()) {
      return refreshes;
    }

    final Materialization mostRecent = getMostRecentMaterialization(m.getReflectionId(), m.getSeriesId());
    if (mostRecent != null && !mostRecent.getId().equals(m.getId())) {
      return Collections.emptyList();
    }

    return refreshes;
  }

  public Iterable<Refresh> getAllRefreshes() {
    return FluentIterable.from(refreshStore.get().find()).transform(new Function<Entry<RefreshId, Refresh>, Refresh>() {
      @Override
      public Refresh apply(Entry<RefreshId, Refresh> refreshIdRefreshEntry) {
        return refreshIdRefreshEntry.getValue();
      }
    });
  }

  public Materialization getLastMaterializationDone(final ReflectionId id) {
    return findLastMaterializationByState(id, MaterializationState.DONE);
  }

  public Materialization getLastMaterialization(final ReflectionId id) {
    final FindByCondition condition = new FindByCondition()
      .addSorting(LAST_REFRESH_SUBMIT)
      .setLimit(1)
      .setCondition(and(
        newTermQuery(ReflectionIndexKeys.MATERIALIZATION_REFLECTION_ID, id.getId())
      ));

    Entry<MaterializationId, Materialization> entry = Iterables.getFirst(materializationStore.get().find(condition), null);
    if(entry == null) {
      return null;
    }

    return entry.getValue();
  }

  public Materialization getRunningMaterialization(final ReflectionId id) {
    return findLastMaterializationByState(id, MaterializationState.RUNNING);
  }

  public Iterable<Materialization> getAllMaterializations() {
    return FluentIterable.from(materializationStore.get().find()).transform(GET_MATERIALIZATION);
  }

  public Iterable<Materialization> getByReflectionId(final ReflectionId reflectionId) {
    return find(reflectionId);
  }

  private Materialization findLastMaterializationByState(final ReflectionId id, final MaterializationState state) {
    final FindByCondition condition = new FindByCondition()
      .addSorting(LAST_REFRESH_SUBMIT)
      .setLimit(1)
      .setCondition(and(
        newTermQuery(ReflectionIndexKeys.MATERIALIZATION_REFLECTION_ID, id.getId()),
        newTermQuery(ReflectionIndexKeys.MATERIALIZATION_STATE, state.name())
      ));

    Entry<MaterializationId, Materialization> entry = Iterables.getFirst(materializationStore.get().find(condition), null);
    if(entry == null) {
      return null;
    }

    return entry.getValue();
  }

  /**
   * @return all DONE materializations that expire after the passed timestamp
   */
  public Iterable<Materialization> getAllDoneWhen(long expiresAfter) {
    final FindByCondition condition = new FindByCondition()
      .setCondition(and(
        newTermQuery(MATERIALIZATION_STATE, MaterializationState.DONE.name()),
        newRangeLong(MATERIALIZATION_EXPIRATION.getIndexFieldName(), expiresAfter, Long.MAX_VALUE, false, true)
      ));
    return Iterables.transform(materializationStore.get().find(condition), GET_MATERIALIZATION);
  }

  /**
   * @return all DONE materializations that expire before the passed timestamp
   */
  public Iterable<Materialization> getAllExpiredWhen(long expiresBefore) {
    final FindByCondition condition = new FindByCondition()
      .setCondition(and(
        newTermQuery(MATERIALIZATION_STATE, MaterializationState.DONE.name()),
        newRangeLong(MATERIALIZATION_EXPIRATION.getIndexFieldName(), 0L, expiresBefore, true, true)
      ));
    return Iterables.transform(materializationStore.get().find(condition), GET_MATERIALIZATION);
  }

  public Iterable<Materialization> getAllDone(ReflectionId id) {
    final FindByCondition condition = new FindByCondition()
      .setCondition(and(
        newTermQuery(MATERIALIZATION_STATE, MaterializationState.DONE.name()),
        newTermQuery(MATERIALIZATION_REFLECTION_ID, id.getId())
      ));
    return Iterables.transform(materializationStore.get().find(condition), GET_MATERIALIZATION);
  }

  /**
   * @return all materializations deprecated before the passed timestamp
   */
  public Iterable<Materialization> getDeletableEntriesModifiedBefore(long timestamp, int numEntries) {
    final FindByCondition condition = new FindByCondition()
      .addSorting(MATERIALIZATION_MODIFIED_AT_SORT)
      .setLimit(numEntries)
      .setCondition(and(
        or(
          newTermQuery(MATERIALIZATION_STATE, MaterializationState.DEPRECATED.name()),
          newTermQuery(MATERIALIZATION_STATE, MaterializationState.CANCELED.name()),
          newTermQuery(MATERIALIZATION_STATE, MaterializationState.FAILED.name())
        ),
        newRangeLong(MATERIALIZATION_MODIFIED_AT.getIndexFieldName(), 0L, timestamp, false, true)
      ));
    return Iterables.transform(materializationStore.get().find(condition), GET_MATERIALIZATION);
  }

  public void save(Materialization m) {
    final long currentTime = System.currentTimeMillis();
    if (m.getCreatedAt() == null) {
      m.setCreatedAt(currentTime);
    }
    m.setModifiedAt(currentTime);

    materializationStore.get().put(m.getId(), m);
  }

  public void save(Refresh refresh) {
    final long currentTime = System.currentTimeMillis();
    if (refresh.getCreatedAt() == null) {
      refresh.setCreatedAt(currentTime);
    }
    refresh.setModifiedAt(currentTime);
    refreshStore.get().put(refresh.getId(), refresh);
  }

  public Materialization get(MaterializationId materializationId) {
    return materializationStore.get().get(materializationId);
  }

  public Iterable<Materialization> find(final ReflectionId id) {
    return Iterables.filter(findByIndex(MATERIALIZATION_REFLECTION_ID, id.getId()),
      and(notNull(), new Predicate<Materialization>() {
        @Override
        public boolean apply(Materialization m) {
          return id.equals(m.getReflectionId());
        }
      }));
  }

  public void delete(MaterializationId id) {
    materializationStore.get().delete(id);
  }

  public void delete(RefreshId id) {
    refreshStore.get().delete(id);
  }

  private static final class MaterializationVersionExtractor implements VersionExtractor<Materialization> {
    @Override
    public Long getVersion(Materialization value) {
      return value.getVersion();
    }

    @Override
    public Long incrementVersion(Materialization value) {
      final Long current = value.getVersion();
      value.setVersion(Optional.fromNullable(current).or(-1L) + 1);
      return current;
    }

    @Override
    public void setVersion(Materialization value, Long version) {
      value.setVersion(version == null ? 0 : version);
    }
  }

  private static final class MaterializationConverter implements KVStoreProvider.DocumentConverter<MaterializationId, Materialization> {
    @Override
    public void convert(KVStoreProvider.DocumentWriter writer, MaterializationId key, Materialization record) {
      writer.write(MATERIALIZATION_ID, key.getId());
      writer.write(MATERIALIZATION_STATE, record.getState().name());
      writer.write(MATERIALIZATION_REFLECTION_ID, record.getReflectionId().getId());
      writer.write(MATERIALIZATION_INIT_REFRESH_SUBMIT, record.getInitRefreshSubmit());
      writer.write(MATERIALIZATION_SERIES_ID, record.getSeriesId());
      writer.write(ReflectionIndexKeys.MATERIALIZATION_EXPIRATION, record.getExpiration());
      writer.write(ReflectionIndexKeys.MATERIALIZATION_MODIFIED_AT, record.getModifiedAt());
    }
  }

  private static final class RefreshConverter implements KVStoreProvider.DocumentConverter<RefreshId, Refresh> {
    @Override
    public void convert(KVStoreProvider.DocumentWriter writer, RefreshId key, Refresh record) {
      writer.write(ReflectionIndexKeys.REFRESH_REFLECTION_ID, record.getReflectionId().getId());
      writer.write(ReflectionIndexKeys.REFRESH_SERIES_ID, record.getSeriesId());
      writer.write(ReflectionIndexKeys.REFRESH_CREATE, record.getCreatedAt());
      writer.write(ReflectionIndexKeys.REFRESH_SERIES_ORDINAL, record.getSeriesOrdinal());
    }
  }

  /**
   * {@link MaterializationStore} creator
   */
  public static final class MaterializationStoreCreator implements StoreCreationFunction<IndexedStore<MaterializationId, Materialization>> {
    @Override
    public IndexedStore<MaterializationId, Materialization> build(StoreBuildingFactory factory) {
      return factory.<MaterializationId, Materialization>newStore()
        .name(MATERIALIZATION_TABLE_NAME)
        .keySerializer(Serializers.MaterializationIdSerializer.class)
        .valueSerializer(Serializers.MaterializationSerializer.class)
        .versionExtractor(MaterializationVersionExtractor.class)
        .buildIndexed(MaterializationConverter.class);
    }
  }

  /**
   * {@link Refresh} store creator
   */
  public static final class RefreshStoreCreator implements StoreCreationFunction<IndexedStore<RefreshId, Refresh>> {
    @Override
    public IndexedStore<RefreshId, Refresh> build(StoreBuildingFactory factory) {
      return factory.<RefreshId, Refresh>newStore()
        .name(REFRESH_TABLE_NAME)
        .keySerializer(Serializers.RefreshIdSerializer.class)
        .valueSerializer(Serializers.RefreshSerializer.class)
        .buildIndexed(RefreshConverter.class);
    }
  }
}
