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
package org.apache.gravitino.iceberg.service.spring;

import java.lang.reflect.Method;
import org.apache.gravitino.iceberg.service.authorization.annotation.IcebergAuthorizationOperation;
import org.apache.gravitino.iceberg.service.authorization.interceptor.IcebergMetadataAuthorizationMethodInterceptor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * Spring AOP aspect that intercepts methods annotated with {@link IcebergAuthorizationOperation}
 * and delegates to {@link IcebergMetadataAuthorizationMethodInterceptor#authorize} for the
 * authorization check. If the check fails, a {@code ForbiddenException} is thrown and handled by
 * {@link IcebergGlobalExceptionHandler}.
 */
@Aspect
@Component
public class IcebergAuthorizationAspect {

  private final IcebergMetadataAuthorizationMethodInterceptor interceptor =
      new IcebergMetadataAuthorizationMethodInterceptor();

  @Around("@annotation(op)")
  public Object check(ProceedingJoinPoint pjp, IcebergAuthorizationOperation op) throws Throwable {
    MethodSignature ms = (MethodSignature) pjp.getSignature();
    Method method = ms.getMethod();
    interceptor.authorize(method, pjp.getArgs());
    return pjp.proceed();
  }
}
