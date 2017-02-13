package io.kagera.execution

import io.kagera.api.colored.ExceptionStrategy

case class ExceptionState(
  failureCount: Int,
  failureReason: String,
  failureStrategy: ExceptionStrategy)