package com.gemini.jobcoin

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext.Implicits._
import scala.io.StdIn


object JobcoinMixer {
  object CompletedException extends Exception {}
  private implicit lazy val actorSystem  = ActorSystem()
  private implicit lazy val materializer = ActorMaterializer()
  private implicit lazy val config       = ConfigFactory.load()
  lazy val client                        = new JobcoinWebServiceImpl
  lazy val mixingActor = actorSystem.actorOf(MixingActor.props(client), name = "mixingactor")

  private val MINIMUM_RECOMMENDED_ADDRESSES = 4

  def main(args: Array[String]): Unit = {

    try {
      while (true) {
        println(prompt)
        val line = StdIn.readLine()

        if (line == "quit") throw CompletedException

        //TODO validate addresses
        val safeAddresses = line.split(",").map(_.trim).filterNot(_.nonEmpty)
        if (line == "") {
          println(s"You must specify empty addresses to mix into!\n$helpText")
        } else if (safeAddresses.nonEmpty) {
          if (safeAddresses.length < MINIMUM_RECOMMENDED_ADDRESSES) {
            println(s"Warning we recommend at least $MINIMUM_RECOMMENDED_ADDRESSES")
          }
          val depositAddress = UUID.randomUUID()
          println(
            s"""
               |You may now send Jobcoins to address $depositAddress.
               |They will be mixed and sent to your destination addresses.
               |
               """.stripMargin
          )
          mixingActor ! MixingActor.CreateTransaction(safeAddresses.toList, depositAddress.toString)
        }
      }
    } catch {
      case CompletedException => println("Quitting...")
    } finally {
      actorSystem.terminate()
    }
  }

  val prompt: String =
    s"""
      |Please enter a comma-separated list of new, unused Jobcoin addresses where your mixed Jobcoins will be sent.
      |We recommend a minimum of $MINIMUM_RECOMMENDED_ADDRESSES addresses. The less addresses given the easier it is to track.
      |
      |""".stripMargin
  val helpText: String =
    """
      |Jobcoin Mixer
      |
      |Takes in at least one return address as parameters (where to send coins after mixing). Returns a deposit address to send coins to.
      |
      |Usage:
      |    run return_addresses...
      |
      |    force-payout
      |        Unsafe operation that forces all transactions to be sent immediately.
      |        For obscurity transactions are batched, randomized, and delayed.
      |        This will send everything immediately.
      |        This action happens by default
      |    force-payout-delayed
      |        Similar to force-payout but slightly safer since there is a delay and randomized transactions.
      |
    """.stripMargin

}
