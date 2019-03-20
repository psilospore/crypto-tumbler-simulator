package com.gemini.jobcoin

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext.Implicits._
import scala.io.StdIn
import scala.util.Try
import scala.util.matching.Regex

object JobcoinMixer {
  object CompletedException extends Exception {}
  private implicit lazy val actorSystem  = ActorSystem()
  private implicit lazy val materializer = ActorMaterializer()
  private implicit lazy val config       = ConfigFactory.load()
  lazy val client                        = new JobcoinWebServiceImpl
  lazy val mixingActor                   = actorSystem.actorOf(MixingActor.props(client), name = "mixingactor")

  private val MINIMUM_RECOMMENDED_ADDRESSES = 4

  val tumbleRegex: Regex    = "tumble\\s?(.*)".r
  val generateRandom: Regex = "generate-random (\\d*) (\\d*)".r

  def main(args: Array[String]): Unit = {

    try {
      while (true) {
        println(prompt)
        val line = StdIn.readLine()

        line match {
          case "quit" => throw CompletedException
          case "help" => println(helpText)
          case tumbleRegex(addresses) => tumble(addresses)
          case "force-payout" => () //TODO
          case generateRandom(numTransactions, safeAddressNum) =>
            Convenience.generateAndDeposit(
              Try { numTransactions.toInt }.toOption,
              Try { safeAddressNum.toInt }.toOption
            )
          case _ => "Invalid input. Type help for available commands"
        }
      }
    } catch {
      case CompletedException => println("Quitting...")
    } finally {
      actorSystem.terminate()
    }
  }

  private def tumble(addresses: String): Unit = {
    val safeAddresses = addresses.split(",").map(_.trim).filter(_.nonEmpty)
    if (safeAddresses.nonEmpty) {
      if (safeAddresses.length < MINIMUM_RECOMMENDED_ADDRESSES) {
        println(s"Warning it is recommend to use at least $MINIMUM_RECOMMENDED_ADDRESSES addresses")
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
    } else {
      println(s"Invalid addresses $addresses")
    }
  }

  val prompt: String =
    s"""
      |Please enter tumble followed by a comma-separated list of new, unused Jobcoin addresses where your mixed Jobcoins will be sent.
      |Type help for more info.
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
      |    tumble return_addresses...
      |    We recommend a minimum of $MINIMUM_RECOMMENDED_ADDRESSES addresses. The less addresses given the easier it is to track.
      |
      |    The following commands may be unsafe
      |    generate-random [number of transactions] [number of safe addresses]
      |        Since transactions are queued by priority and sent in batches it
      |         may be tedious to run this multiple times.
      |        You could use this if you want to tumble your funds earlier.
    """.stripMargin

}
