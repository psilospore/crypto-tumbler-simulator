import java.util.UUID

import akka.actor.{Actor, ActorRef, Props, Timers}
import akka.event.Logging

import scala.concurrent.duration._
import UserActor._
import com.gemini.jobcoin.JobcoinClient

class UserActor(val state: UserState, jobcoinClient: JobcoinClient, mixingActor: ActorRef) extends Actor with Timers {
  import UserActor._

  val log = Logging(context.system, this)

  override def receive: Receive = {
    case CheckBalance if state.checkingForBalance => {
      for {
        balance <- jobcoinClient.checkBalance(state.depositAddress)
      } yield {
        if (balance.balance > 0) {
          //Start tell some mixing actor to add to queue
          //assign an amount to each address
          //TODO send to

          state.addresses.size
        } else {
          log.info(s"${state.id} does not have any funds")
          timers.startSingleTimer(CheckBalance, CheckBalance, CHECK_BALANCE_DELAY)
        }
      }
    }
    case SendNextTransaction => {

    }
  }

}

case object UserActor {

  def props(state: UserState, jobcoinClient: JobcoinClient, mixingActor: ActorRef): Props = Props(new UserActor(state, jobcoinClient, mixingActor))

  //TODO 2 states. One where I'm waiting for funds to get in. The other I'm waiting when to send my money

  case class UserState(
    id: Int,
    name: Option[String] = None,
    addresses: List[String],
    depositAddress: String,
    checkingForBalance: Boolean,
    mixerBalance: Double
  )

  case object CheckBalance

  val CHECK_BALANCE_DELAY: FiniteDuration = 1 minute
}
