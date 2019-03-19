package com.gemini.jobcoin

import java.util.UUID

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Timers}
import akka.event.Logging

import scala.concurrent.duration._
import TumblingTransactionActor._
import cats.syntax.validated
import cats.instances.future
import cats.syntax.try_
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.validated._
import cats.data.Validated._
import cats.syntax.nonEmptyTraverse._
import cats.instances.list._
import cats.instances.either._
import cats.instances.option._
import com.gemini.jobcoin.JobcoinWebService.UserBalance

import scala.concurrent.Future

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
  * @param safeAddresses a list of user provided addresses to eventually deposit to
  * @param depositAddress the address to deposit the initial amount. Will be transfered to deposit account.
  * @param jobcoinWebService
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
  var failures = 0

  private def handleResponse[R](
    responseFuture: Future[ValidatedNel[JobcoinWebService.Error, R]],
    success: R => Any,
    failure: NonEmptyList[JobcoinWebService.Error] => Any = _ => ()
  ): Unit =
    for {
      responseValidated <- responseFuture
    } {
      responseValidated match {
        case Valid(response) => success(response)
        case Invalid(errs) => {
          log.info(s"Unable to fetch due to errors: ${errs.toList.mkString("\n")}")
          failures += 1
          failure(errs)
        }
      }
    }

  def waitingForDeposit: Receive = {
    case CheckBalance =>
      handleResponse[UserBalance](
        responseFuture = jobcoinWebService.checkBalance(depositAddress),
        success = balance => {
          val deposit = balance.balance
          if (deposit > 0) {
            log.info(s"Received balance of $deposit to $depositAddress")
            context become attemptTransferToHouse
            val attemptTransferMsg = AttemptTransferToHouse(deposit)
            self ! attemptTransferMsg
            timers.cancelAll()
            timers.startPeriodicTimer(AttemptTransferToHouse, attemptTransferMsg, DELAY)
          }
          failures = 0
        },
        failure = errs => {
          log.info(s"Unable to fetch due to errors: ${errs.toList.mkString("\n")}")
        }
      )
  }

  def attemptTransferToHouse: Receive = {
    case AttemptTransferToHouse(amount) =>
      handleResponse[Unit](
        responseFuture = jobcoinWebService.transfer(depositAddress, MixingActor.HOUSE_ADDRESS, amount),
        success = _ => {
          log.info(s"$amount transferred successfully to house address")
          context.parent ! MixingActor.DepositReceived(amount, safeAddresses)
          timers.cancelAll()
          context become attemptTransferToSafeAddress
          failures = 0
        }
      )
  }

  def attemptTransferToSafeAddress: Receive = {
    case AttemptTransferToSafeAddress(safeAddress, amount) =>
      handleResponse[Unit](
        responseFuture = jobcoinWebService.transfer(MixingActor.HOUSE_ADDRESS, safeAddress, amount),
        success = _ => {
          timers.cancelAll()
          failures = 0
//          context.parent ! MixingActor.ProcessedDepositSuccess() //TODO
        },
        failure = _ => {
          timers.startPeriodicTimer(AttemptTransferToSafeAddress, AttemptTransferToSafeAddress(safeAddress, amount), DELAY)
        }
      )
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
  //TODO after several unexpected failures. Persist this transaction to revive at a later time.
  private val FAILURE_THRESHOLD = Option(20)

  def props(safeAddresses: List[String], depositAddress: String, jobcoinClient: JobcoinWebService): Props =
    Props(new TumblingTransactionActor(safeAddresses, depositAddress, jobcoinClient))

  case class WithdrawFromHouse(destination: String, amount: Double)

  private case object CheckBalance

  val DELAY: FiniteDuration = 30 seconds

  case object Initialize

  case class AttemptTransferToHouse(amount: Double)

  case class AttemptTransferToSafeAddress(address: String, amount: Double)

}
