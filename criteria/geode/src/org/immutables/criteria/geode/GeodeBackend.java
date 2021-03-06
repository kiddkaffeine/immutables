/*
 * Copyright 2019 Immutables Authors and Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.immutables.criteria.geode;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.QueryService;
import org.immutables.criteria.backend.Backend;
import org.immutables.criteria.backend.DefaultResult;
import org.immutables.criteria.backend.IdExtractor;
import org.immutables.criteria.backend.IdResolver;
import org.immutables.criteria.backend.PathNaming;
import org.immutables.criteria.backend.StandardOperations;
import org.immutables.criteria.backend.WatchEvent;
import org.reactivestreams.Publisher;

import java.lang.reflect.Member;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Backend for <a href="https://geode.apache.org/">Apache Geode</a>
 */
public class GeodeBackend implements Backend {

  static final Logger logger = Logger.getLogger(GeodeBackend.class.getName());

  private final GeodeSetup setup;
  private final PathNaming pathNaming;


  public GeodeBackend(GeodeSetup setup) {
    this.setup = Objects.requireNonNull(setup, "setup");
    this.pathNaming = ReservedWordNaming.of(PathNaming.defaultNaming());
  }

  @Override
  public Backend.Session open(Class<?> entityType) {
    Objects.requireNonNull(entityType, "context");
    return new Session(entityType, this);
  }

  static class Session implements Backend.Session {

    final Class<?> entityType;
    final Region<Object, Object> region;
    final IdExtractor idExtractor;
    final IdResolver idResolver;
    final Member idProperty;
    final QueryService queryService;
    final PathNaming pathNaming;

    private Session(Class<?> entityType, GeodeBackend backend) {
      this.entityType = Objects.requireNonNull(entityType, "entityType");
      GeodeSetup setup = backend.setup;
      @SuppressWarnings("unchecked")
      Region<Object, Object> region = (Region<Object, Object>) setup.regionResolver().resolve(entityType);
      this.region = region;
      this.idResolver = setup.idResolver();
      this.idProperty = setup.idResolver().resolve(entityType);
      this.idExtractor = IdExtractor.fromResolver(idResolver);
      this.queryService = setup.queryServiceResolver().resolve(region);
      this.pathNaming = backend.pathNaming;
    }

    @Override
    public Class<?> entityType() {
      return entityType;
    }

    @Override
    public Result execute(Operation operation) {
      return DefaultResult.of(Flowable.defer(() -> executeInternal(operation)));
    }

    private Publisher<?> executeInternal(Operation operation) {
      if (operation instanceof StandardOperations.Select) {
        return Flowable.fromCallable(new SyncSelect(this, (StandardOperations.Select) operation)).flatMapIterable(x -> x);
      } else if (operation instanceof StandardOperations.Update) {
        return Flowable.fromCallable(new SyncUpdate(this, (StandardOperations.Update) operation));
      } else if (operation instanceof StandardOperations.Insert) {
        return Flowable.fromCallable(new SyncInsert(this, (StandardOperations.Insert) operation));
      } else if (operation instanceof StandardOperations.Delete) {
        return Flowable.fromCallable(new SyncDelete(this, (StandardOperations.Delete) operation));
      } else if (operation instanceof StandardOperations.Watch) {
        return watch((StandardOperations.Watch) operation);
      }

      return Flowable.error(new UnsupportedOperationException(String.format("Operation %s not supported by %s",
              operation, GeodeBackend.class.getSimpleName())));
    }

    private <T> Publisher<WatchEvent<T>> watch(StandardOperations.Watch operation) {
      return Flowable.create(e -> {
        final FlowableEmitter<WatchEvent<T>> emitter = e.serialize();
        final String oql = oqlGenerator().withoutBindVariables().generate(operation.query()).oql();
        final CqAttributesFactory factory = new CqAttributesFactory();
        factory.addCqListener(new GeodeEventListener<>(oql, emitter));
        final CqQuery cqQuery = queryService.newCq(oql, factory.create());
        emitter.setDisposable(new CqDisposable(cqQuery));
        cqQuery.execute();
      }, BackpressureStrategy.ERROR);
    }

    OqlGenerator oqlGenerator() {
      return OqlGenerator.of(region.getFullPath(), pathNaming);
    }

  }
}
