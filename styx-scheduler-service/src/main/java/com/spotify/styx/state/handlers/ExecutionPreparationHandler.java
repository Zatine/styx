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

package com.spotify.styx.state.handlers;

import static com.spotify.styx.state.handlers.TerminationHandler.MISSING_DEPS_RETRY_DELAY_MINUTES;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.spotify.styx.WorkflowExecutionGate;
import com.spotify.styx.model.Event;
import com.spotify.styx.model.ExecutionDescription;
import com.spotify.styx.model.Workflow;
import com.spotify.styx.model.WorkflowId;
import com.spotify.styx.model.WorkflowInstance;
import com.spotify.styx.model.WorkflowState;
import com.spotify.styx.state.Message;
import com.spotify.styx.state.OutputHandler;
import com.spotify.styx.state.RunState;
import com.spotify.styx.state.StateManager;
import com.spotify.styx.storage.Storage;
import com.spotify.styx.util.DockerImageValidator;
import com.spotify.styx.util.IsClosedException;
import com.spotify.styx.util.MissingRequiredPropertyException;
import com.spotify.styx.util.ResourceNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExecutionPreparationHandler implements OutputHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ExecutionPreparationHandler.class);

  private static final String STYX_RUN = "styx-run";

  private final Storage storage;
  private final StateManager stateManager;
  private final DockerImageValidator dockerImageValidator;
  private final WorkflowExecutionGate gate;

  public ExecutionPreparationHandler(
      Storage storage,
      StateManager stateManager,
      DockerImageValidator dockerImageValidator,
      WorkflowExecutionGate gate) {
    this.storage = requireNonNull(storage);
    this.stateManager = requireNonNull(stateManager);
    this.dockerImageValidator = requireNonNull(dockerImageValidator);
    this.gate = requireNonNull(gate, "gate");
  }

  @Override
  public void transitionInto(RunState state) {
    final WorkflowInstance workflowInstance = state.workflowInstance();

    switch (state.state()) {
      case PREPARE:
        final ExecutionDescription execDescription;
        try {
          execDescription = getExecDescription(workflowInstance);
        } catch (ResourceNotFoundException e) {
          LOG.info("Workflow {} does not exist, halting {}", workflowInstance.workflowId(),
                   workflowInstance);
          stateManager.receiveIgnoreClosed(Event.halt(workflowInstance));
          return;
        } catch (MissingRequiredPropertyException e) {
          LOG.warn("Failed to prepare execution description for "
                   + state.workflowInstance().toKey(), e);
          stateManager.receiveIgnoreClosed(Event.halt(workflowInstance));
          return;
        } catch (IOException e) {
          try {
            LOG.error("Failed to retrieve execution description for " + state.workflowInstance().toKey(), e);
            stateManager.receive(Event.runError(state.workflowInstance(), e.getMessage()));
          } catch (IsClosedException isClosedException) {
            LOG.warn("Failed to send 'runError' event", isClosedException);
          }
          return;
        }

        // Check for missing dependencies before submitting
        // TODO (dano): Should this be done by the scheduler before dequeuing instead of in the state machine?
        final Collection<String> missingDependencies = gate.missingDependencies(
            workflowInstance, state, execDescription);
        if (!missingDependencies.isEmpty()) {
          stateManager.receiveIgnoreClosed(Event.info(workflowInstance, Message.info(
              "Missing dependencies: " + missingDependencies.stream().collect(Collectors.joining(", ")))));
          stateManager.receiveIgnoreClosed(Event.retryAfter(
              workflowInstance, TimeUnit.MINUTES.toMillis(MISSING_DEPS_RETRY_DELAY_MINUTES)));
          return;
        }

        // We have all dependencies that we know about, submit!
        final Event submitEvent = Event.submit(
            state.workflowInstance(), execDescription, createExecutionId());
        try {
          stateManager.receive(submitEvent);
        } catch (IsClosedException isClosed) {
          LOG.warn("Could not send 'submit' event", isClosed);
        }
        break;

      default:
        // do nothing
    }
  }

  private ExecutionDescription getExecDescription(WorkflowInstance workflowInstance)
      throws IOException, MissingRequiredPropertyException {
    final WorkflowId workflowId = workflowInstance.workflowId();

    final Workflow workflow = storage.workflow(workflowId).orElseThrow(
        () -> new ResourceNotFoundException(format("Missing %s, halting %s",
                                                   workflowId, workflowInstance)));

    final List<String> dockerArgs = workflow.configuration().dockerArgs().orElse(
        Collections.emptyList());

    final WorkflowState workflowState = storage.workflowState(workflow.id());

    final Optional<String> dockerImageOpt = workflowState.dockerImage().isPresent()
                                            ? workflowState.dockerImage()
                                            : workflow.configuration().dockerImage(); // backwards compatibility

    if (!dockerImageOpt.isPresent()) {
      throw new MissingRequiredPropertyException(format("%s has no docker image, halting %s",
                                                        workflowId, workflowInstance));
    }

    final Collection<String> errors = dockerImageValidator.validateImageReference(dockerImageOpt.get());
    if (!errors.isEmpty()) {
      throw new MissingRequiredPropertyException(format(
          "%s has an invalid docker image reference, halting %s. Errors: %s",
          workflowId, workflowInstance, errors));
    }

    return ExecutionDescription.builder()
        .dockerImage(dockerImageOpt.get())
        .dockerArgs(dockerArgs)
        .dockerTerminationLogging(workflow.configuration().dockerTerminationLogging())
        .secret(workflow.configuration().secret())
        .serviceAccount(workflow.configuration().serviceAccount())
        .commitSha(workflowState.commitSha())
        .build();
  }

  static String createExecutionId() {
    return STYX_RUN + "-" + UUID.randomUUID().toString();
  }
}
