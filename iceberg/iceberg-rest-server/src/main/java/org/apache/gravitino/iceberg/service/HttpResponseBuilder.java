/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.service;

import org.apache.iceberg.rest.responses.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Builder helpers for constructing Spring MVC {@link ResponseEntity} objects. */
public final class HttpResponseBuilder {

  private HttpResponseBuilder() {}

  /**
   * Returns a {@code 200 OK} response with a JSON body.
   *
   * @deprecated use {@link #okEntity(Object)} for Spring MVC controllers.
   */
  @Deprecated
  public static <T> ResponseEntity<Object> ok(T entity) {
    return okEntity(entity);
  }

  /** Returns a {@code 200 OK} response with a JSON body. */
  public static <T> ResponseEntity<Object> okEntity(T entity) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(entity);
  }

  /** Returns a {@code 204 No Content} response. */
  public static ResponseEntity<Object> noContentEntity() {
    return ResponseEntity.noContent().build();
  }

  /** Returns a {@code 404 Not Found} response (entity does not exist). */
  public static ResponseEntity<Object> notExistsEntity() {
    return ResponseEntity.notFound().build();
  }

  /** Returns a {@code 204 No Content} response. */
  @Deprecated
  public static ResponseEntity<Object> noContent() {
    return noContentEntity();
  }

  /** Returns a {@code 404 Not Found} response (entity does not exist). */
  @Deprecated
  public static ResponseEntity<Object> notExists() {
    return notExistsEntity();
  }

  /** Returns an error response with the given status and body. */
  public static ResponseEntity<Object> errorEntity(Throwable ex, int httpStatus) {
    ErrorResponse errorResponse =
        ErrorResponse.builder()
            .responseCode(httpStatus)
            .withType(ex.getClass().getSimpleName())
            .withMessage(ex.getMessage())
            .withStackTrace(ex)
            .build();
    return ResponseEntity.status(HttpStatus.valueOf(httpStatus))
        .contentType(MediaType.APPLICATION_JSON)
        .body(errorResponse);
  }

  /** Returns an error response with the given status and body. */
  @Deprecated
  public static ResponseEntity<Object> errorResponse(Throwable ex, int httpStatus) {
    return errorEntity(ex, httpStatus);
  }

  /**
   * Build an Iceberg {@link ErrorResponse} for a given HTTP status code and message, without an
   * exception.
   */
  public static ErrorResponse errorResponse(int httpStatus, String type, String message) {
    return ErrorResponse.builder()
        .responseCode(httpStatus)
        .withType(type)
        .withMessage(message)
        .build();
  }
}
