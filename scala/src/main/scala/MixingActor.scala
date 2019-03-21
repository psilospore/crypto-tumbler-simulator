package com.gemini.jobcoin
import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Timers}
import akka.event.Logging

import scala.concurrent.duration._
import scala.util.Random

/**
  * Most of the mixing logic is here.
  * Manages transaction actors.
  * When a transaction is in the state where their funds are in the house address they are placed in a queue of
  * transactions to payout. They are charged a randomized fee of up to 3%.
  *
  * When there are enough transactions to payout they are batched together and paid in non-sequential order with randomized delays.
  * If there are multiple safe addresses only a randomized portion of the remainder is sent, and it is placed on the queue again.
  * Note: That means finishing payout a transaction with 3 safe addresses would take 3 different batches of payout.
  *
  * TODO I would potentially have something else transfer the fee I've collected.
  * @param state
  */
class MixingActor(
  jobcoinWebService: JobcoinWebService
) extends Actor
    with Timers {
  import MixingActor._
  import context.dispatcher

  val log = Logging(context.system, this)

  private val rnd = Random

  var transactionsInHouse: List[TransactionsInHouse] = List()
  timers.startPeriodicTimer(ProcessPayout, ProcessPayout, PROCESS_DELAY)

  override def receive: Receive = {

    case CreateTransaction(safeAddresses, depositAddress) =>
      val newTransactionActor = context.actorOf(
        TransactionActor.props(safeAddresses, depositAddress, jobcoinWebService)
      )
      newTransactionActor ! TransactionActor.Initialize

    case DepositReceived(deposit, addresses) =>
      val randomizedFee   = deposit * (rnd.nextInt(31) / 1000D)
      val amountToDeposit = deposit - randomizedFee
      log.info(s"Charged randomized fee of $randomizedFee from total deposit of $deposit")
      log.info(s"Queued ${sender()}")

      transactionsInHouse = TransactionsInHouse(sender(), amountToDeposit, addresses) :: transactionsInHouse

    case ProcessPayout if transactionsInHouse.size >= MINIMUM_PAID_ACTORS_FOR_PAYOUT =>
      transactionsInHouse
        .foreach(deposit => {
          val amount =
            if (deposit.unusedAddresses.size == 1)
              deposit.remainder
            else {
              //Random percentage of remainder between 20% and 79%
              (rnd.nextInt(60) + 20) / 100.0 * deposit.remainder
            }

          context.system.scheduler.scheduleOnce(
            randomDuration(),
            deposit.transactionActor,
            TransactionActor.AttemptTransferToSafeAddress(deposit, deposit.unusedAddresses.head, amount)
          )
          log.info(s"Attempting deposit of $amount to safe address ${deposit.unusedAddresses.head}")
        })
      transactionsInHouse = List()

    case ProcessedDepositSuccess(transactionInHouse, amount, address) =>
      val newtransactionInHouse = transactionInHouse.copy(
        remainder = transactionInHouse.remainder - amount,
        unusedAddresses = transactionInHouse.unusedAddresses.filterNot(_ == address)
      )

      log.info(
        s"Successfully deposited $amount with ${transactionInHouse.remainder} remaining to safe address $address"
      )

      if (newtransactionInHouse.unusedAddresses.nonEmpty) {
        transactionsInHouse = newtransactionInHouse :: transactionsInHouse
      } else {
        log.info(s"Done processing transaction ${newtransactionInHouse.transactionActor}")
        transactionInHouse.transactionActor ! PoisonPill
      }

    case ProcessedDepositFailure(transactionInHouse) =>
      //Fatal lost transaction
      //TODO persist in case we want to recover later
      log.error(s"Unable to process $transactionInHouse")

    //TODO in case of shutdown force payout
    case ForceFinishPayout(withRandomizedDelay) => //TODO
  }
}

object MixingActor {

  def props(jobcoinWebService: JobcoinWebService) = Props(new MixingActor(jobcoinWebService))

  //These values could slide depending on activity and a larger value may be better
  private val MINIMUM_PAID_ACTORS_FOR_PAYOUT = 5

  private val PROCESS_DELAY: FiniteDuration = 10 seconds //TODO this could be randomized as well for timing "attacks"
  val HOUSE_ADDRESS: String                 = "HOUSE ADDRESS" //In reality we would get this from a configuration

  def randomDuration(): FiniteDuration = FiniteDuration.apply(Random.nextInt(30), TimeUnit.SECONDS)

  //Transaction is in the house address
  case class TransactionsInHouse(
    transactionActor: ActorRef,
    remainder: Double,
    unusedAddresses: List[String],
    addedToQueue: Instant = Instant.now
  )

  // Messages
  case class CreateTransaction(safeAddresses: List[String], depositAddress: String)

  //Deposit received in house address
  case class DepositReceived(deposit: Double, addresses: List[String])

  case object ProcessPayout
  case class ForceFinishPayout(withRandomizedDelay: Boolean = true)

  case class ProcessedDepositSuccess(transactionInHouse: TransactionsInHouse, amount: Double, address: String)
  case class ProcessedDepositFailure(transactionInHouse: TransactionsInHouse)
}
