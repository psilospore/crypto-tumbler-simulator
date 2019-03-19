package com.gemini.jobcoin

import java.util.UUID

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Timers}
import akka.event.Logging

import scala.concurrent.duration._
import TumblingTransactionActor._
import cats.syntax.validated
import cats.instances.future
import cats.syntax.try_
import cats.data.{Validated, ValidatedNel}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.validated._
import cats.data.Validated._
import cats.syntax.nonEmptyTraverse._
import cats.instances.list._
import cats.instances.either._
import cats.instances.option._

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
  */
class TumblingTransactionActor(
  safeAddresses: List[String],
  depositAddress: String,
  jobcoinWebService: JobcoinWebService
) extends Actor
    with Timers {
  import TumblingTransactionActor._
  import context.dispatcher

  val log      = Logging(context.system, this)
  val failures = 0

  def waitingForDeposit: Receive = {
    case CheckBalance =>
      log.info(s"Checking balance")
      for {
        balanceValidation <- jobcoinWebService.checkBalance(depositAddress)
      } yield {
        balanceValidation match {
          case Valid(balance) =>
            val deposit = balance.balance
            if (deposit > 0) {
              log.info(s"Received balance of $deposit to $depositAddress")
              context become attemptTransferToHouse
              val attemptTransferMsg = AttemptTransferToHouse(deposit)
              self ! attemptTransferMsg
              timers.cancelAll()
              timers.startPeriodicTimer(AttemptTransferToHouse, attemptTransferMsg, DELAY)
            }
          case Invalid(err) => {
            log.info(s"Unable to fetch due to errors: ${err.toList.mkString("\n")}")
          }
        }

      }
  }

  def attemptTransferToHouse: Receive = {
    case AttemptTransferToHouse(amount) => {
      jobcoinWebService
        .transfer(depositAddress, MixingActor.HOUSE_ADDRESS, amount)
        .onComplete(t => {
          //TODO generalize
          t.toEither.leftMap(_.toString).toValidatedNel.andThen(identity) match {
            case Validated.Valid(_) =>
              log.info(s"$amount transferred successfully to house address")
              log.info(s"sending ${MixingActor.DepositReceived(amount, safeAddresses)} to ${context.parent}")
              context.parent ! MixingActor.DepositReceived(amount, safeAddresses)
              timers.cancelAll()
            case Invalid(err) => log.info(s"Unable to fetch due to errors: ${err.toList.mkString("\n")}")
          }
        })
    }
    case TransferToSafeAddressBehavior(address, amount) =>
      context become attemptTransferToSafeAddress
      self ! AttemptTransferToSafeAddress(address, amount)
      timers.startPeriodicTimer(AttemptTransferToSafeAddress, AttemptTransferToSafeAddress(address, amount), DELAY)
  }

  def attemptTransferToSafeAddress: Receive = {
    case AttemptTransferToSafeAddress(safeAddress, amount) =>
      //TODO handle
      jobcoinWebService.transfer(MixingActor.HOUSE_ADDRESS, safeAddress, amount)
  }

  override def receive: Receive = {
    case Initialize => {
      println("hi")
      context become waitingForDeposit
      self ! CheckBalance
      timers.startPeriodicTimer(CheckBalance, CheckBalance, DELAY)
    }

  }

}

case object TumblingTransactionActor {
  private val FAILURE_THRESHOLD = Option(100)

  def props(safeAddresses: List[String], depositAddress: String, jobcoinClient: JobcoinWebService): Props =
    Props(new TumblingTransactionActor(safeAddresses, depositAddress, jobcoinClient))

  case class WithdrawFromHouse(destination: String, amount: Double)

  private case object CheckBalance

  val DELAY: FiniteDuration = 30 seconds

  case object Initialize

  case class TransferToHouseBehavior(amount: Double)

  case class TransferToSafeAddressBehavior(address: String, amount: Double)

  case class AttemptTransferToHouse(amount: Double)

  case class AttemptTransferToSafeAddress(address: String, amount: Double)

}
