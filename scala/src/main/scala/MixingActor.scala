package com.gemini.jobcoin
import java.time.Instant
import java.util.UUID

import akka.actor.{Actor, ActorRef, PoisonPill, Props, Timers}
import akka.event.Logging

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

/**
  * Sends only once  users have mixed in.
  * Most of the business and mixing logic is here.
  *
  *
  * TODO I would potentially have something else transfer the fee I've collected.
  * @param state
  */
class MixingActor(
  jobcoinWebService: JobcoinWebService
) extends Actor
    with Timers {

  import MixingActor._
  val log = Logging(context.system, this)

  val transactionActors: mutable.Set[ActorRef]                   = mutable.Set()
  private val rnd                                                = Random
  var depositsInProcess: mutable.PriorityQueue[DepositInProcess] = mutable.PriorityQueue[DepositInProcess]()

  timers.startPeriodicTimer(ProcessDeposit, ProcessDeposit, PROCESS_DELAY)

  override def receive: Receive = {

    case CreateTransaction(safeAddresses, depositAddress) =>
      val newTransactionActor = context.actorOf(
        TransactionActor.props(safeAddresses, depositAddress, jobcoinWebService)
      )
      transactionActors.add(newTransactionActor)
      newTransactionActor ! TransactionActor.Initialize

    case DepositReceived(deposit, addresses) =>
      val randomizedFee   = deposit * (rnd.nextInt(31) / 100D)
      val amountToDeposit = deposit - randomizedFee
      depositsInProcess += DepositInProcess(sender(), amountToDeposit, addresses)
      log.info(s"Charged randomized fee of $randomizedFee from total deposit of $amountToDeposit")
      log.info(s"Queued ${sender()} in $depositsInProcess")

    case ProcessDeposit if depositsInProcess.size > MINIMUM_PAID_ACTORS_FOR_PAYOUT =>
      //Fetch up until the last 10
      val cutoff                                      = depositsInProcess.size - 1 - MINIMUM_IN_QUEUE
      val (toProcessIndexed, newPriorityQueueIndexed) = depositsInProcess.zipWithIndex.partition(_._2 <= cutoff)

      depositsInProcess = newPriorityQueueIndexed.map(_._1)
      toProcessIndexed
        .map(_._1)
        .foreach(deposit => {
          val amount =
            if (deposit.unusedAddresses.size == 1)
              deposit.remainder
            else {
              //Random percentage of remainder between 20% and 79%
              (rnd.nextInt(60) + 20) / 100.0 * deposit.remainder
            }
          deposit.transactionActorActor ! TransactionActor
            .WithdrawFromHouse(deposit.unusedAddresses.head, amount)
          log.info("Attempting deposit to safe address")
        })

    case ProcessedDepositFailure(depositInProcess) =>
      //Fatal lost transaction
      //TODO persist in case we want to recover later
      log.error(s"Unable to process $depositInProcess")

    case ProcessedDepositSuccess(depositInProcess, amount, address) =>
      val newDepositInProcess = depositInProcess.copy(
        remainder = depositInProcess.remainder - amount,
        unusedAddresses = depositInProcess.unusedAddresses.filterNot(_ == address)
      )
      if (newDepositInProcess.unusedAddresses.nonEmpty) {
        depositsInProcess.enqueue(depositInProcess)
      } else {
        depositInProcess.transactionActorActor ! PoisonPill //TODO this is how you kill again right?
      }

    //In case of shutdown payout remaining. Could also be used for testing.
    case ForceFinishPayout => () //TODO maybe I could do this if I have to shut down or maybe for testing
  }
}

object MixingActor {

  def props(jobcoinWebService: JobcoinWebService) = Props(new MixingActor(jobcoinWebService))

  //These values could slide depending on activity
  private val MINIMUM_PAID_ACTORS_FOR_PAYOUT = 10
  private val MINIMUM_IN_QUEUE               = MINIMUM_PAID_ACTORS_FOR_PAYOUT / 2

  private val PROCESS_DELAY: FiniteDuration = 30 seconds
  val HOUSE_ADDRESS: String                 = UUID.randomUUID.toString //TODO get from config

  implicit val depositInProcessOrd: Ordering[DepositInProcess] = Ordering.by[DepositInProcess, Instant](_.addedToQueue)

  //TODO rename this is something else now. MixingTransactionState. MixingTransactionInProcessState
  case class DepositInProcess(
    transactionActorActor: ActorRef,
    remainder: Double,
    unusedAddresses: List[String],
    addedToQueue: Instant = Instant.now
  )

  // Messages
  case class CreateTransaction(safeAddresses: List[String], depositAddress: String)

  //Deposit received in house address
  case class DepositReceived(deposit: Double, addresses: List[String])

  case object ProcessDeposit
  case class ForceFinishPayout(withRandomizedDelay: Boolean = true)

  case class ProcessedDepositSuccess(depositInProcess: DepositInProcess, amount: Double, address: String)
  case class ProcessedDepositFailure(depositInProcess: DepositInProcess)
}
