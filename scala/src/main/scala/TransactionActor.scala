package com.gemini.jobcoin

import akka.actor.{Actor, Props, Timers}
import akka.event.Logging
import cats.data.Validated._
import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.either._
import cats.syntax.validated._
import com.gemini.jobcoin.JobcoinWebService.{Error, UserBalance}
import com.gemini.jobcoin.MixingActor.TransactionsInHouse

import scala.concurrent.Future
import scala.concurrent.duration._

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
class TransactionActor(
  safeAddresses: List[String],
  depositAddress: String,
  jobcoinWebService: JobcoinWebService
) extends Actor
    with Timers {
  import TransactionActor._
  import context.dispatcher

  val log      = Logging(context.system, this)
  var failures = 0

  override def receive: Receive = {
    case Initialize =>
      context become waitingForDeposit
      self ! CheckBalance
      timers.startPeriodicTimer(CheckBalance, CheckBalance, DELAY)
  }

  def waitingForDeposit: Receive = {
    case CheckBalance =>
      handleResponse[UserBalance](
        responseFuture = jobcoinWebService.checkBalance(depositAddress),
        success = balance => {
          val deposit = balance.balance
          failures = 0
          if (deposit > 0) {
            log.info(s"Received balance of $deposit to $depositAddress")
            context become attemptTransferToHouse
            val attemptTransferMsg = AttemptTransferToHouse(deposit)
            self ! attemptTransferMsg
            timers.cancelAll()
            timers.startPeriodicTimer(AttemptTransferToHouse, attemptTransferMsg, DELAY)
          }
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
    case a@AttemptTransferToSafeAddress(transactionInHouse, destinationAddress, amount) =>
      handleResponse[Unit](
        responseFuture = jobcoinWebService.transfer(MixingActor.HOUSE_ADDRESS, destinationAddress, amount),
        success = _ => {
          timers.cancelAll()
          failures = 0
          context.parent ! MixingActor.ProcessedDepositSuccess(transactionInHouse, amount, destinationAddress)
        },
        failure = _ => {
          timers
            .startPeriodicTimer(AttemptTransferToSafeAddress, a, DELAY)
        }
      )
  }

  /**
    * Handles response from  JobcoinWebservice
    */
  private def handleResponse[R](
    responseFuture: Future[ValidatedNel[Error, R]],
    success: R => Unit,
    failure: NonEmptyList[Error] => Any = _ => ()
  ): Unit =
    responseFuture.onComplete(futureTry => {
      val futureValidated: ValidatedNel[String, ValidatedNel[String, R]] = futureTry.toEither.leftMap(_.getMessage).toValidatedNel
      futureValidated.andThen {
        case Valid(response) => success(response).validNel
        case Invalid(errs) =>
          log.info(s"Unable to fetch due to errors: ${errs.toList.mkString("\n")}")
          failures += 1
          failure(errs).invalidNel
      }
    })
}

case object TransactionActor {
  //TODO after several unexpected failures. Persist this transaction to revive at a later time.
  private val FAILURE_THRESHOLD = Option(20)

  def props(safeAddresses: List[String], depositAddress: String, jobcoinClient: JobcoinWebService): Props =
    Props(new TransactionActor(safeAddresses, depositAddress, jobcoinClient))

  val DELAY: FiniteDuration = 30 seconds

  // Messages
  case object CheckBalance

  case object Initialize

  case class AttemptTransferToHouse(amount: Double)

  case class AttemptTransferToSafeAddress(transactionInHouse: TransactionsInHouse, destinationAddress: String, amount: Double)

}
