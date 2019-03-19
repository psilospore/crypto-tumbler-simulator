import java.util.concurrent.Future

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit
import akka.testkit.{ImplicitSender, TestActor, TestActors, TestKit, TestProbe}
import com.gemini.jobcoin.JobcoinWebService.UserBalance
import com.gemini.jobcoin.TumblingTransactionActor.Initialize
import com.gemini.jobcoin.{JobcoinWebService, MixingActor, TumblingTransactionActor}
import org.scalamock.scalatest.MockFactory
import org.scalatest._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import cats.data.{NonEmptyList, ValidatedNel}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.validated._
import cats.data.Validated._
import cats.syntax.nonEmptyTraverse._
import cats.instances.list._
import cats.instances.either._
import cats.instances.option._

class ActorTest
    extends TestKit(ActorSystem("MySpec"))
    with ImplicitSender
    with MockFactory
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  implicit val executionContext = ExecutionContext.Implicits.global

  val testSafeAddress1  = "testSafeAddress1"
  val testSafeAddress2  = "testSafeAddress2"
  val testSafeAddress3  = "testSafeAddress3"
  val testSafeAddresses = List(testSafeAddress1, testSafeAddress2, testSafeAddress3)

  val testDepositAddress = "testDepositAddress"

  var tumblingTransactionActor: ActorRef       = _
  var mockJobcoinWebService: JobcoinWebService = stub[JobcoinWebService
  var mixingActorProbe: TestProbe              = TestProbe()
  logProbe(mixingActorProbe)

  override def beforeAll: Unit = {
    mockJobcoinWebService = stub[JobcoinWebService]
    tumblingTransactionActor = mixingActorProbe.childActorOf(
      TumblingTransactionActor.props(testSafeAddresses, testDepositAddress, mockJobcoinWebService)
    )
  }

  "A TumblingTransactionActor" must {

    "Should move balance from deposit address to house address" in {
      val testAmount = 10.34

      (mockJobcoinWebService.checkBalance _)
        .when(testDepositAddress)
        .returns(scala.concurrent.Future(UserBalance(testAmount, List()).validNel[String]))

      (mockJobcoinWebService.transfer _)
        .when(testDepositAddress, MixingActor.HOUSE_ADDRESS, testAmount)
        .returns(scala.concurrent.Future(().validNel[String]))

      //Start
      system.scheduler.scheduleOnce(2 seconds, tumblingTransactionActor, TumblingTransactionActor.Initialize)
      mixingActorProbe.expectMsg(2 minutes, MixingActor.DepositReceived(testAmount, testSafeAddresses))
    }

  }

  private def logProbe(testProbe: TestProbe) = {
    mixingActorProbe.setAutoPilot(new testkit.TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        system.log.info(s"TestProbe received $msg")
        this
      }
    })
  }

}
