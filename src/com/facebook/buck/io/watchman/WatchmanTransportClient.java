/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.io.watchman;

import static com.facebook.buck.util.concurrent.MostExecutors.newSingleThreadExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.bser.BserDeserializer;
import com.facebook.buck.util.bser.BserSerializer;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

class WatchmanTransportClient implements WatchmanClient, AutoCloseable {

  private static final Logger LOG = Logger.get(WatchmanTransportClient.class);

  private final ListeningExecutorService listeningExecutorService;
  private final Clock clock;
  private final Transport transport;
  private final Console console;
  private final BserSerializer bserSerializer;
  private final BserDeserializer bserDeserializer;

  public WatchmanTransportClient(Console console, Clock clock, Transport transport) {
    this.listeningExecutorService = listeningDecorator(newSingleThreadExecutor("Watchman"));
    this.console = console;
    this.clock = clock;
    this.transport = transport;
    this.bserSerializer = new BserSerializer();
    this.bserDeserializer = new BserDeserializer(BserDeserializer.KeyOrdering.UNSORTED);
  }

  private final AtomicBoolean running = new AtomicBoolean();

  @Override
  public Either<Map<String, Object>, Timeout> queryWithTimeout(
      long timeoutNanos, long warnTimeNanos, WatchmanQuery query)
      throws IOException, InterruptedException {
    if (!running.compareAndSet(false, true)) {
      throw new IllegalStateException("WatchmanTransportClient is single-threaded");
    }
    try {
      return queryListWithTimeoutAndWarning(timeoutNanos, warnTimeNanos, query);
    } finally {
      running.set(false);
    }
  }

  private Either<Map<String, Object>, Timeout> queryListWithTimeoutAndWarning(
      long timeoutNanos, long warnTimeoutNanos, WatchmanQuery query)
      throws IOException, InterruptedException {
    ListenableFuture<Map<String, Object>> future =
        listeningExecutorService.submit(() -> sendWatchmanQuery(query.toProtocolArgs()));
    try {
      long startTimeNanos = clock.nanoTime();
      Either<Map<String, Object>, Timeout> result =
          waitForQueryNotifyingUserIfSlow(future, timeoutNanos, warnTimeoutNanos, query);
      long elapsedNanos = clock.nanoTime() - startTimeNanos;
      LOG.debug("Query %s returned in %d ms", query, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
      return result;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void close() throws IOException {
    LOG.debug("Closing Watchman transport.");
    transport.close();
    listeningExecutorService.shutdown();
  }

  private synchronized void showTimeoutWarning(long timeoutNanos, String whichQuery) {
    LOG.warn(
        "Watchman did not respond to '%s' within %dms.",
        whichQuery, TimeUnit.NANOSECONDS.toMillis(timeoutNanos));

    if (console.getVerbosity().isSilent()) {
      return;
    }
    if (timeoutNanos < 0) {
      timeoutNanos = 0;
    }
    console
        .getStdErr()
        .getRawStream()
        .format(
            "Timed out after %ds waiting for watchman query '%s'.\n",
            TimeUnit.NANOSECONDS.toSeconds(timeoutNanos), whichQuery);
  }

  private Either<Map<String, Object>, Timeout> waitForQueryNotifyingUserIfSlow(
      ListenableFuture<Map<String, Object>> future,
      long timeoutNanos,
      long warnTimeNanos,
      WatchmanQuery query)
      throws InterruptedException, ExecutionException {
    long queryStartNanos = clock.nanoTime();
    try {
      return Either.ofLeft(future.get(Math.min(timeoutNanos, warnTimeNanos), TimeUnit.NANOSECONDS));
    } catch (TimeoutException e) {
      long remainingNanos = timeoutNanos - (clock.nanoTime() - queryStartNanos);
      if (remainingNanos > 0) {
        LOG.debug("Waiting for Watchman query [%s]...", query);
        if (!console.getVerbosity().isSilent()) {
          console
              .getStdErr()
              .getRawStream()
              .format(
                  "Waiting for watchman query '%s' for %ds...\n",
                  query.queryDesc(), TimeUnit.NANOSECONDS.toSeconds(timeoutNanos));
        }
        try {
          Map<String, Object> result = future.get(remainingNanos, TimeUnit.NANOSECONDS);
          if (!console.getVerbosity().isSilent()) {
            long queryDurationNanos = clock.nanoTime() - queryStartNanos;
            LOG.debug(
                "Watchman query [%s] finished in %dms",
                query, TimeUnit.NANOSECONDS.toMillis(queryDurationNanos));
            console
                .getStdErr()
                .getRawStream()
                .format(
                    "Watchman query '%s' finished in %ds...\n",
                    query.queryDesc(), TimeUnit.NANOSECONDS.toSeconds(queryDurationNanos));
          }
          return Either.ofLeft(result);
        } catch (TimeoutException te) {
          LOG.debug("Timed out");
        }
      }
      showTimeoutWarning(timeoutNanos, query.queryDesc());
      return Either.ofRight(Timeout.INSTANCE);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> sendWatchmanQuery(List<Object> query) throws IOException {
    LOG.verbose("Sending query: %s", query);
    bserSerializer.serializeToStream(query, transport.getOutputStream());
    Object response = bserDeserializer.deserializeBserValue(transport.getInputStream());
    LOG.verbose("Got response: %s", response);
    Map<String, Object> responseMap = (Map<String, Object>) response;
    Preconditions.checkNotNull(responseMap, "response must not be null");
    return responseMap;
  }
}
