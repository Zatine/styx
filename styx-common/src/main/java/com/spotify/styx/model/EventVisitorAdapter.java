/*
 * -\-\-
 * Spotify Styx Scheduler Service
 * --
 * Copyright (C) 2017 Spotify AB
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

package com.spotify.styx.model;

import com.spotify.styx.state.Message;
import com.spotify.styx.state.Trigger;
import java.util.Optional;
import javax.annotation.Nullable;

public class EventVisitorAdapter<T> implements EventVisitor<T> {

  @Override
  public T triggerExecution(WorkflowInstance workflowInstance, Trigger trigger) {
    return null;
  }

  @Override
  public T info(WorkflowInstance workflowInstance, Message message) {
    return null;
  }

  @Override
  public T dequeue(WorkflowInstance workflowInstance) {
    return null;
  }

  @Override
  public T submit(WorkflowInstance workflowInstance, ExecutionDescription executionDescription,
      @Nullable String executionId) {
    return null;
  }

  @Override
  public T submitted(WorkflowInstance workflowInstance, @Nullable String executionId) {
    return null;
  }

  @Override
  public T started(WorkflowInstance workflowInstance) {
    return null;
  }

  @Override
  public T terminate(WorkflowInstance workflowInstance, Optional<Integer> exitCode) {
    return null;
  }

  @Override
  public T runError(WorkflowInstance workflowInstance, String message) {
    return null;
  }

  @Override
  public T success(WorkflowInstance workflowInstance) {
    return null;
  }

  @Override
  public T retryAfter(WorkflowInstance workflowInstance, long delayMillis) {
    return null;
  }

  @Override
  public T stop(WorkflowInstance workflowInstance) {
    return null;
  }

  @Override
  public T timeout(WorkflowInstance workflowInstance) {
    return null;
  }

  @Override
  public T halt(WorkflowInstance workflowInstance) {
    return null;
  }

  @Override
  public T timeTrigger(WorkflowInstance workflowInstance) {
    return null;
  }

  @Override
  public T created(WorkflowInstance workflowInstance, String executionId, String dockerImage) {
    return null;
  }

  @Override
  public T retry(WorkflowInstance workflowInstance) {
    return null;
  }
}
