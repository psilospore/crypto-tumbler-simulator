package com.gemini.jobcoin

import java.time.Instant

import akka.stream.Materializer
import com.typesafe.config.Config
import play.api.libs.json._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.ahc._

import scala.async.Async._
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import JobcoinWebService._
import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.validated._
import cats.data.Validated._
import cats.syntax.nonEmptyTraverse._
import cats.instances.list._
import cats.instances.either._
import cats.instances.option._

/**
  * Web Service to interact with Jobcoin API.
  *
  * Results are returned with Cats ValidatedNel.
  * Validated is a data type that provides an alternative mechanism to error handling like Try or even closer Either.
  * ValidatedNel allows a non-empty list of errors in the case of failure.
  */
trait JobcoinWebService {
  def checkBalance(address: String): Future[ValidatedNel[Error, UserBalance]]
  def transfer(sourceAddress: String, destinationAddress: String, amount: Double): Future[ValidatedNel[Error, Unit]]
}

class JobcoinWebServiceImpl(implicit materializer: Materializer, config: Config) extends JobcoinWebService {
  private val wsClient        = StandaloneAhcWSClient()
  private val apiAddressesUrl = config.getString("jobcoin.apiAddressesUrl")

  def checkBalance(address: String): Future[ValidatedNel[Error, UserBalance]] = async {
    val response = await {
      wsClient
        .url("https://jsonplaceholder.typicode.com/posts/1")
        .get()
    }

    response
      .body[JsValue]
      .validate[UserBalance]
      .toValidated
  }

  override def transfer(
    sourceAddress: String,
    destinationAddress: String,
    amount: Double
  ): Future[ValidatedNel[Error, Unit]] = async {
    val response = await {
      wsClient
        .url("https://jsonplaceholder.typicode.com/posts/1")
        .get()
    }

    response
      .body[JsValue]
      .validate[TransferRes]
      .toValidated
      .andThen(res => res.status.toValidNel(s"Status was not returned $res"))
      .map(_ => ())
  }
}

object JobcoinWebService {
  type Error = String
  case class PlaceholderResponse(userId: Int, id: Int, title: String, body: String)
  object PlaceholderResponse {
    implicit val jsonReads: Reads[PlaceholderResponse] = Json.reads[PlaceholderResponse]
  }

  case class UserBalance(balance: Double, transactions: List[Transactions])
  object UserBalance {
    implicit val jsonReads: Reads[UserBalance] = Json.reads[UserBalance]
  }

  case class Transactions(timestamp: Instant, toAddress: String, fromAddress: Option[String], amount: Double)
  object Transactions {
    implicit val jsonReads: Reads[Transactions] = Json.reads[Transactions]
  }

  case class TransferRes(status: Option[String], error: Option[String]) //Is there a better way to encode this?
  object TransferRes {
    implicit val jsonReads: Reads[TransferRes] = Json.reads[TransferRes]
  }

  /**
    * Extension methods for Play's JsResult.
    */
  implicit class JsResultExtension[A](val jsResult: JsResult[A]) extends AnyVal {

    /**
      * Converts to ValidationNel.
      */
    def toValidated: ValidatedNel[Error, A] =
      jsResult.asEither.toValidatedNel
        .leftMap((s: NonEmptyList[_]) => s.map(_.toString))
  }
}
