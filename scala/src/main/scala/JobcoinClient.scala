package com.gemini.jobcoin

import java.time.Instant

import akka.stream.Materializer
import com.gemini.jobcoin.JobcoinClient.PlaceholderResponse
import com.typesafe.config.Config
import play.api.libs.json._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.ahc._

import scala.async.Async._
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

class JobcoinClient(config: Config)(implicit materializer: Materializer) {
  import JobcoinClient._
  private val wsClient        = StandaloneAhcWSClient()
  private val apiAddressesUrl = config.getString("jobcoin.apiAddressesUrl")

  // Docs:
  // https://github.com/playframework/play-ws
  // https://www.playframework.com/documentation/2.6.x/ScalaJsonCombinators
  def testGet(): Future[PlaceholderResponse] = async {
    val response = await {
      wsClient
        .url("https://jsonplaceholder.typicode.com/posts/1")
        .get()
    }

    response
      .body[JsValue]
      .validate[PlaceholderResponse]
      .get
  }

  //TODO what if the network is down. Handle 404?
  def checkBalance(address: String): Future[UserBalance] = async {
    val response = await {
      wsClient
        .url("https://jsonplaceholder.typicode.com/posts/1")
        .get()
    }

    response
      .body[JsValue]
      .validate[UserBalance]
      .get
  }

}

object JobcoinClient {
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

}
