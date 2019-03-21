package com.gemini.jobcoin

import java.time.Instant

import akka.stream.Materializer
import com.typesafe.config.Config
import play.api.libs.json._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.JsonBodyWritables._
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
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._


import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.JsonBodyWritables._

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
  private val wsClient          = StandaloneAhcWSClient()
  private val apiAddressesUrl   = config.getString("jobcoin.apiAddressesUrl")
  private val apiTransactionUrl = config.getString("jobcoin.apiTransactionsUrl")

  def checkBalance(address: String): Future[ValidatedNel[Error, UserBalance]] = async {
    val response = await {
      wsClient
        .url(s"$apiAddressesUrl/$address")
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
        .url(s"$apiTransactionUrl")
        .post(
          Json.obj(
            "fromAddress" -> sourceAddress,
            "toAddress"   -> destinationAddress,
            "amount"      -> amount
          )
        )
    }

    response
      .body[JsValue]
      .validate[TransferRes]
      .toValidated
      .andThen(res =>
        if(res.status.contains("OK")){
          println(s"Successful transaction from $sourceAddress to $destinationAddress for $amount") //TODO delete
          ().validNel[String]
        } else
          s"Status is not ok $res".invalidNel[Unit]
      )
  }
}

object JobcoinWebService {
  type Error = String
  case class TransactionBody(fromAddress: String, toAddress: String, amount: Double)
  object TransactionBody {
    implicit val jsonWrites: Writes[TransactionBody] = Json.writes[TransactionBody]
  }

  case class UserBalance(balance: Double, transactions: List[Transactions])
  object UserBalance {
    implicit val jsonReads: Reads[UserBalance] = (
      (JsPath \ "balance").read[String].map(_.toDouble) and
        (JsPath \ "transactions").readNullable[List[Transactions]].map(_.getOrElse(List.empty))
      )(UserBalance.apply _)
  }

  case class Transactions(timestamp: Instant, toAddress: String, fromAddress: Option[String], amount: Double)
  object Transactions {
    implicit val jsonReads: Reads[Transactions] = (
      (JsPath \ "timestamp").read[Instant] and
        (JsPath \ "toAddress").read[String] and
        (JsPath \ "fromAddress").readNullableWithDefault(Option.empty[String]) and
        (JsPath \ "amount").read[String].map(_.toDouble)
    )(Transactions.apply _)
  }

  case class TransferRes(status: Option[String], error: Option[String])
  object TransferRes {
    implicit val jsonReads: Reads[TransferRes] = (
      (JsPath \ "status").readNullable[String] and
        (JsPath \ "error").readNullable[String]
      )(TransferRes.apply _)
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
