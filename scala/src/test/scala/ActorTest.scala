import java.util.concurrent.Future

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit
import akka.testkit.{ImplicitSender, TestActor, TestActors, TestKit, TestProbe}
import com.gemini.jobcoin.JobcoinWebService.UserBalance
import com.gemini.jobcoin.TransactionActor.Initialize
import com.gemini.jobcoin.{JobcoinWebService, MixingActor, TransactionActor}
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
import com.gemini.jobcoin.MixingActor.DepositInProcess

class ActorTest
    extends TestKit(ActorSystem("JobcoinActorTest"))
    with ImplicitSender
    with MockFactory
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  val testSafeAddress1  = "testSafeAddress1"
  val testSafeAddress2  = "testSafeAddress2"
  val testSafeAddress3  = "testSafeAddress3"
  val testSafeAddresses = List(testSafeAddress1, testSafeAddress2, testSafeAddress3)

  val testDepositAddress = "testDepositAddress"

  "A TransactionActorActor should tumble funds from a address, to the house address, to the safe addresses" must {

    val managingTestProbe: TestProbe             = TestProbe()
    val mockJobcoinWebService: JobcoinWebService = stub[JobcoinWebService]
    val transactionActorActor: ActorRef = managingTestProbe.childActorOf(
      TransactionActor.props(testSafeAddresses, testDepositAddress, mockJobcoinWebService)
    )

    logProbe(managingTestProbe)

    val testAmount = 10.34

    //Expected API calls
    val jobcoinCallToCheckBalance = (mockJobcoinWebService.checkBalance _)
      .when(testDepositAddress)

    val jobcoinCallToHouseAddress = (mockJobcoinWebService.transfer _)
      .when(testDepositAddress, MixingActor.HOUSE_ADDRESS, testAmount)

    //Mock responses to API calls
    jobcoinCallToCheckBalance.returns(scala.concurrent.Future(UserBalance(testAmount, List()).validNel[String]))
    jobcoinCallToHouseAddress.returns(scala.concurrent.Future(().validNel[String]))

    "should move balance from deposit address to house address" in {
      system.scheduler.scheduleOnce(3 seconds, transactionActorActor, TransactionActor.Initialize)
      managingTestProbe.expectMsg(2 minutes, MixingActor.DepositReceived(testAmount, testSafeAddresses))
    }

    "should have checked the balance through the Jobcoin API" in {
      jobcoinCallToCheckBalance.atLeastOnce()
    }

    "should have transferred to the house address from the user address only once" in {
      jobcoinCallToHouseAddress.once()
    }

    "should wait to transfer deposit to safe address until receiving message to" in {
      (mockJobcoinWebService.transfer _)
        .when(where { (source, _, _) =>
          source == MixingActor.HOUSE_ADDRESS
        })
        .never()

      val portionOfTestAmount = testAmount * 0.4
      val depositInProcess    = DepositInProcess(transactionActorActor, testAmount, testSafeAddresses.tail)

      val joncoinTransferToSafe = (mockJobcoinWebService.transfer _)
        .when(MixingActor.HOUSE_ADDRESS, testSafeAddress1, portionOfTestAmount)

      joncoinTransferToSafe
        .returns(scala.concurrent.Future(().validNel[String]))

      system.scheduler.scheduleOnce(3 seconds, transactionActorActor, TransactionActor.AttemptTransferToSafeAddress(
        depositInProcess,
        testSafeAddress1,
        portionOfTestAmount
      ))

      managingTestProbe.expectMsg(
        2 minutes,
        MixingActor.ProcessedDepositSuccess(depositInProcess, portionOfTestAmount, testSafeAddress1)
      )

      joncoinTransferToSafe
        .once()

    }

  }

  "A MixingActor should" must {
    //TODO STUB
  }

  /**
    * Logs messages received to test probe. Helpful for debugging.
    */
  private def logProbe(testProbe: TestProbe): Unit = {
    testProbe.setAutoPilot(new testkit.TestActor.AutoPilot {
      override def run(sender: ActorRef, msg: Any): TestActor.AutoPilot = {
        system.log.info(s"TestProbe received $msg")
        this
      }
    })
  }

}
