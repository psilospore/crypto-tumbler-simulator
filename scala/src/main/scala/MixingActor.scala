import java.time.Instant

import MixingActor.State
import akka.actor.{Actor, ActorRef, PoisonPill, Timers}
import akka.event.Logging

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

//Owns all user actors
//Pool all transactions to make and randomize them.

/**
  * Sends only once 20 users have mixed in.
  * @param state
  */
class MixingActor(var state: State) extends Actor with Timers {
  import MixingActor._
  val log = Logging(context.system, this)

  private val rnd = Random

  timers.startPeriodicTimer(ProcessDeposit, ProcessDeposit, PROCESS_DELAY)

  override def receive: Receive = {
    case ProcessDeposit if state.depositsInProcess.size > MINIMUM_PAID_ACTORS_FOR_PAYOUT => {
      val prioritizedRandomIndex   = rnd.nextInt(MINIMUM_PAID_ACTORS_FOR_PAYOUT)
      val indexedDepositsInProcess = state.depositsInProcess.zipWithIndex
      val prioritizedRandomDeposit = indexedDepositsInProcess
        .find(_._2 == prioritizedRandomIndex)
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
    case ProcessedDepositFailure(depositInProcess) => {
      log.warning(s"Unable to process") //TODO warn
      state.depositsInProcess.enqueue(depositInProcess)
    }
    case ProcessedDepositSuccess(depositInProcess) =>
      if (depositInProcess.unusedAddresses.nonEmpty) {
        state.depositsInProcess.enqueue(depositInProcess)
      } else {
        depositInProcess.userActor ! PoisonPill //TODO this is how you kill again right?
      }
    case ForceFinishPayout => () //TODO maybe I could do this if I have to shut down or maybe for testing
  }
}

object MixingActor {
  private val MINIMUM_PAID_ACTORS_FOR_PAYOUT = 20
  private val PROCESS_DELAY                  = 1 minute

  implicit val depositInProcessOrd: Ordering[DepositInProcess] = Ordering.by[DepositInProcess, Instant](_.addedToQueue)
  case class State(
    depositsInProcess: mutable.PriorityQueue[DepositInProcess] = mutable.PriorityQueue[DepositInProcess]()
  )

  case class DepositInProcess(
    userActor: ActorRef,
    remainder: Double,
    unusedAddresses: List[String],
    addedToQueue: Instant
  )

  case object ProcessDeposit
  case object ForceFinishPayout
  case class ProcessedDepositSuccess(depositInProcess: DepositInProcess)
  case class ProcessedDepositFailure(depositInProcess: DepositInProcess)
}
