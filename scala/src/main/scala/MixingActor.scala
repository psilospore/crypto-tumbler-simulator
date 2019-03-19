package com.gemini.jobcoin
import java.time.Instant
import java.util.UUID

import MixingActor.State
import akka.actor.{Actor, ActorRef, PoisonPill, Timers}
import akka.event.Logging

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

/**
  * Sends only once 20 users have mixed in.
  * Most of the business and mixing logic is here.
  *
  * There's solutions to avoid timing attacks.
  * Example if I just send as they come in and out. Someone could narrow in on transaction that happened at that period.
  * If there's a delay and it's constant that's just as easy to track.
  *
  * If I avoid
  * @param state
  */
class MixingActor(var state: State) extends Actor with Timers {
  import MixingActor._
  val log = Logging(context.system, this)

  private val rnd = Random

  timers.startPeriodicTimer(ProcessDeposit, ProcessDeposit, PROCESS_DELAY)

  override def receive: Receive = {
    case DepositReceived(deposit, addresses) => {
      val fee             = deposit * FEE
      val amountToDeposit = deposit - fee
      //TODO store the fee we've collected so far.
      state.depositsInProcess += DepositInProcess(sender(), amountToDeposit, addresses, Instant.now)
      log.info(s"Queued ${sender()} in ${state.depositsInProcess}")
    }
    case ProcessDeposit if state.depositsInProcess.size > MINIMUM_PAID_ACTORS_FOR_PAYOUT => {
      //TODO this is bad I can easily overload the tumbler.
      // One strategy is to keep this same logic but do it for a high percentage every increment.

      //TODO here's one good one. If there's at least 20 users. Leave 10 users in the queue, and get the rest out.
      //Send a message to their actors, but schedule it for a delay. The delays are randomized for each actor.
      //Now at this point
      //This solution also prevents users never receiving their deposits if there's at least 20 people before their transaction.
      //There's always a batch of 10 transactions to randomize.
      val prioritizedRandomIndex   = rnd.nextInt(MINIMUM_PAID_ACTORS_FOR_PAYOUT)
      val indexedDepositsInProcess = state.depositsInProcess.zipWithIndex
      val prioritizedRandomDeposit = indexedDepositsInProcess
        .find(_._2 == prioritizedRandomIndex)
        .map(_._1)

      prioritizedRandomDeposit.fold({
        log.error("Unexpected")
      })(deposit => {
        val amount =
          if (deposit.unusedAddresses.size == 1)
            deposit.remainder
          else {
            //Random percentage of remainder between 20% and 79%
            (rnd.nextInt(60) + 20) / 100.0 * deposit.remainder
          }

        deposit.tumblingTransactionActor ! TumblingTransactionActor
          .WithdrawFromHouse(deposit.unusedAddresses.head, amount)
        log.info("Attempting deposit to safe address") //TODO
      })
      state = state.copy(
        depositsInProcess = indexedDepositsInProcess
          .filterNot(_._2 == prioritizedRandomIndex)
          .map(_._1)
      )
      //TODO send prioritizedRandomDeposit.
      // Service sends success or failure back.
      // On success if there is no more addresses to process then don't add it back to the queue.
      // Otherwise pop back in queue
    }
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
        state.depositsInProcess.enqueue(depositInProcess)
      } else {
        depositInProcess.tumblingTransactionActor ! PoisonPill //TODO this is how you kill again right?
      }
    //In case of shutdown payout remaining. Could also be used for testing.
    case ForceFinishPayout => () //TODO maybe I could do this if I have to shut down or maybe for testing
  }
}

object MixingActor {
  private val MINIMUM_PAID_ACTORS_FOR_PAYOUT = 20
  private val PROCESS_DELAY: FiniteDuration  = 1 minute
  private val FEE: Double                    = 0.03
  val HOUSE_ADDRESS: String                  = UUID.randomUUID.toString //TODO

  implicit val depositInProcessOrd: Ordering[DepositInProcess] = Ordering.by[DepositInProcess, Instant](_.addedToQueue)
  case class State(
    depositsInProcess: mutable.PriorityQueue[DepositInProcess] = mutable.PriorityQueue[DepositInProcess]()
  )

  //TODO rename this is something else now. MixingTransactionState. MixingTransactionInProcessState
  case class DepositInProcess(
    tumblingTransactionActor: ActorRef,
    remainder: Double,
    unusedAddresses: List[String],
    addedToQueue: Instant
  )

  case class CreateTumblingTransaction(addresses: List[String], depositAddress: String)

  //Deposit received in house address
  case class DepositReceived(deposit: Double, addresses: List[String])
  case object ProcessDeposit
  case object ForceFinishPayout
  case class ProcessedDepositSuccess(depositInProcess: DepositInProcess, amount: Double, address: String)
  case class ProcessedDepositFailure(depositInProcess: DepositInProcess)
}
