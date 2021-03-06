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

package com.spotify.styx.util;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import java.util.Optional;

public class GcpUtil {

  public static boolean isPermissionDenied(Throwable t) {
    return t instanceof GoogleJsonResponseException
        && isPermissionDenied((GoogleJsonResponseException) t);
  }

  public static boolean isPermissionDenied(GoogleJsonResponseException e) {
    return e.getStatusCode() == 403 && Optional.ofNullable(e.getDetails())
        .map(GcpUtil::isPermissionDenied)
        .orElse(false);
  }

  public static boolean isPermissionDenied(GoogleJsonError error) {
    return "PERMISSION_DENIED".equals(error.get("status"));
  }

  public static boolean isResourceExhausted(Throwable t) {
    return t instanceof GoogleJsonResponseException
           && isResourceExhausted((GoogleJsonResponseException) t);
  }

  public static boolean isResourceExhausted(GoogleJsonResponseException e) {
    return e.getStatusCode() == 429 && Optional.ofNullable(e.getDetails())
        .map(GcpUtil::isResourceExhausted)
        .orElse(false);
  }

  public static boolean isResourceExhausted(GoogleJsonError error) {
    return "RESOURCE_EXHAUSTED".equals(error.get("status"));
  }
}
