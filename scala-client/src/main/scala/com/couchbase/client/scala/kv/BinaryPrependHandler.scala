package com.couchbase.client.scala.kv

import com.couchbase.client.core.error.EncodingFailedException
import com.couchbase.client.core.msg.ResponseStatus
import com.couchbase.client.core.msg.kv.{AppendRequest, AppendResponse, PrependRequest, PrependResponse}
import com.couchbase.client.core.retry.RetryStrategy
import com.couchbase.client.scala.HandlerParams
import com.couchbase.client.scala.api.MutationResult
import com.couchbase.client.scala.codec.Conversions
import com.couchbase.client.scala.durability.Durability
import com.couchbase.client.scala.util.Validate
import io.opentracing.Span

import scala.compat.java8.OptionConverters._
import scala.util.{Failure, Success, Try}

class BinaryPrependHandler(hp: HandlerParams) extends RequestHandler[PrependResponse, MutationResult] {

  def request[T](id: String,
                 content: T,
                 cas: Long = 0,
                 durability: Durability,
                 parentSpan: Option[Span],
                 timeout: java.time.Duration,
                 retryStrategy: RetryStrategy)
                (implicit ev: Conversions.Encodable[T])
  : Try[PrependRequest] = {

    val validations: Try[PrependRequest] = for {
      _ <- Validate.notNullOrEmpty(id, "id")
      _ <- Validate.notNull(content, "content")
      _ <- Validate.notNull(cas, "cas")
      _ <- Validate.notNull(durability, "durability")
      _ <- Validate.notNull(parentSpan, "parentSpan")
      _ <- Validate.notNull(timeout, "timeout")
      _ <- Validate.notNull(retryStrategy, "retryStrategy")
    } yield null

    if (validations.isFailure) {
      validations
    }
    else {
      ev.encode(content) match {
        case Success(encoded) =>
          Success(new PrependRequest(
            timeout,
            hp.core.context(),
            hp.bucketName,
            retryStrategy,
            id,
            hp.collectionIdEncoded,
            encoded._1,
            cas,
            durability.toDurabilityLevel
          ))
        case Failure(err) =>
          Failure(new EncodingFailedException(err))
      }
    }
  }

  def response(id: String, response: PrependResponse): MutationResult = {
    response.status() match {
      case ResponseStatus.SUCCESS =>
        MutationResult(response.cas(), response.mutationToken().asScala)

      case _ => throw DefaultErrors.throwOnBadResult(response.status())
    }
  }
}
