package com.gemini.jobcoin
import java.util.UUID

import akka.actor.{Actor, ActorRef, Props, Timers}
import akka.event.Logging

import scala.concurrent.duration._
import TumblingTransactionActor._
import com.gemini.jobcoin.JobcoinClient

/**
  * Represents a request to tumble funds. Owned and managed by the MixingActor.
  * Handles detecting when a deposit is made, and interacting the the Jobcoin api to transfer funds.
  *
  * There are 4 states the transaction can be in:
  * 1) Awaiting the deposit: def waitingForDeposit
  * 2) Transfer to house address: def attemptTransferToHouse
  * 3) Transfer to user provided safe address: def attemptTransferToSafeAddress
  * 4) Finished transaction: PoisonPilled
  *
  * TODO params documentation
  * TODO probably no state? State should change and in redesign it doesn't.
  */
class TumblingTransactionActor(val state: State, jobcoinClient: JobcoinClient) extends Actor with Timers {
  import TumblingTransactionActor._

  val log = Logging(context.system, this)
  val failures = 0

  def waitingForDeposit: Receive = {
    case CheckBalance =>
      for {
        balance <- jobcoinClient.checkBalance(state.depositAddress)
      } yield {
        val deposit = balance.balance
        if (deposit > 0) {
          self ! TransferToHouse
        } else {
          timers.startSingleTimer(CheckBalance, CheckBalance, CHECK_BALANCE_DELAY)
        }
      }
  }

  def attemptTransferToHouse: Receive = {
    case AttemptTransferToHouse => jobcoinClient //TODO
  }

  def attemptTransferToSafeAddress: Receive = {
    case AttemptTransferToSafeAddress => jobcoinClient
  }
    override def receive: Receive = {
    case WaitForBalance        => context become waitingForDeposit
    case TransferToHouse       => {
      context become attemptTransferToHouse
      timers.startPeriodicTimer(AttemptTransferToHouse, AttemptTransferToHouse, 2 minute)
    }
    case TransferToSafeAddress(address) => {
      context become attemptTransferToSafeAddress
      timers.startPeriodicTimer(AttemptTransferToSafeAddress, AttemptTransferToSafeAddress(address), 2 minute)
    }
  }

}

case object TumblingTransactionActor {
  private val FAILURE_THRESHOLD = Option(100)

  def props(state: State, jobcoinClient: JobcoinClient, mixingActor: ActorRef): Props =
    Props(new TumblingTransactionActor(state, jobcoinClient))

  case class State(
    name: Option[String] = None,
    addresses: List[String],
    depositAddress: String
  )

  case class WithdrawFromHouse(destination: String, amount: Double)

  private case object CheckBalance

  val CHECK_BALANCE_DELAY: FiniteDuration = 1 minute

  case object WaitForBalance
  case object TransferToHouse
  case class TransferToSafeAddress(address: String)

  case object AttemptTransferToHouse
  case class AttemptTransferToSafeAddress(address: String)

}
