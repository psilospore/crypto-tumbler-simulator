import akka.actor.Actor
//Owns all user actors
//Pool all transactions to make and randomize them.

//One strategy is to only send if there is a pool of 20 users who have paid. 1 comes in 1 comes out.
class MixingActor extends Actor {
  val actors = List[UserActor]
  val paidActors = List[UserActor] //TODO circular queue

}

object MixingActor {
  val MINIMUM_PAID_ACTORS_FOR_PAYOUT = 20
}