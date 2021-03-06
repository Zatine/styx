/*-
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2016 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.styx;

import static com.spotify.styx.util.GuardedRunnable.guard;
import static com.spotify.styx.util.TimeUtil.nextInstant;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Throwables;
import com.spotify.styx.model.Schedule;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.monitoring.Stats;
import com.spotify.styx.state.Trigger;
import com.spotify.styx.storage.Storage;
import com.spotify.styx.util.AlreadyInitializedException;
import com.spotify.styx.util.Time;
import com.spotify.styx.util.TriggerInstantSpec;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Triggers natural executions for {@link Workflow}s.
 */
public class TriggerManager {

  private static final Logger LOG = LoggerFactory.getLogger(TriggerManager.class);

  private final TriggerListener triggerListener;
  private final Time time;
  private final Storage storage;
  private final Stats stats;

  public TriggerManager(TriggerListener triggerListener,
                        Time time,
                        Storage storage,
                        Stats stats) {
    this.triggerListener = requireNonNull(triggerListener);
    this.time = requireNonNull(time);
    this.storage = requireNonNull(storage);
    this.stats = requireNonNull(stats);
  }

  void tick() {
    try {
      if (!storage.globalEnabled()) {
        LOG.info("Triggering has been disabled globally.");
        return;
      }
    } catch (IOException e) {
      LOG.warn("Couldn't fetch global enabled status, skipping this run.");
      return;
    }

    final Map<Workflow, TriggerInstantSpec> canBeTriggeredWorkflows;
    final Set<WorkflowId> enabledWorkflows;
    try {
      canBeTriggeredWorkflows = storage.workflowsWithNextNaturalTrigger();
      enabledWorkflows = storage.enabled();
    } catch (IOException e) {
      LOG.warn("Couldn't fetch workflows to trigger, skipping this run.");
      return;
    }

    final Instant now = time.get();
    canBeTriggeredWorkflows.entrySet().stream()
        .filter(entry -> now.isAfter(entry.getValue().offsetInstant()))
        .forEach(entry -> tryTriggering(entry.getKey(), entry.getValue(), enabledWorkflows));
  }

  private void tryTriggering(Workflow workflow,
                             TriggerInstantSpec instantSpec,
                             Set<WorkflowId> enabledWorkflows) {
    guard(() -> {
      if (enabledWorkflows.contains(workflow.id())) {
        try {
          final CompletionStage<Void> processed = triggerListener.event(
              workflow,
              Trigger.natural(),
              instantSpec.instant());
          // Wait for the event to be processed before proceeding to the next trigger
          processed.toCompletableFuture().get();
        } catch (AlreadyInitializedException e) {
          LOG.warn("{}", e.getMessage());
        } catch (Throwable e) {
          LOG.warn("Triggering {} threw exception", workflow.id(), e);
          return; // so we don't update the trigger time
        }

        stats.recordNaturalTrigger();
      }

      final Schedule schedule = workflow.configuration().schedule();
      final Instant nextTrigger = nextInstant(instantSpec.instant(), schedule);
      final Instant nextWithOffset = workflow.configuration().addOffset(nextTrigger);
      final TriggerInstantSpec nextSpec = TriggerInstantSpec.create(nextTrigger, nextWithOffset);

      try {
        storage.updateNextNaturalTrigger(workflow.id(), nextSpec);
      } catch (IOException e) {
        LOG.error(
            "Sent trigger for workflow {}, but didn't succeed storing next scheduled run {}.",
            workflow.id(), nextTrigger);
        throw Throwables.propagate(e);
      }
    }).run();
  }
}
