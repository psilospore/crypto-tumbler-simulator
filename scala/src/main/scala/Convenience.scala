package com.gemini.jobcoin

import java.util.UUID

import cats.data.ValidatedNel
import com.gemini.jobcoin.MixingActor.CreateTransaction

import scala.concurrent.{ExecutionContext, Future}

/**
  * Just some convenient scripts I'm using.
  * Feel free to ignore.
  */
object Convenience {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global

  /**
    * Stimulate tumbler usage by generating 
    */
  def generateTransactions(numTransactions: Int, safeAddressNum: Int): List[MixingActor.CreateTransaction] = {
    val mixingActor = JobcoinMixer.mixingActor

    val createTransactions = List
      .fill(numTransactions)(
        (List.fill(safeAddressNum)(UUID.randomUUID().toString), UUID.randomUUID().toString)
      )
      .zipWithIndex
      .map({
        case (addresses, index) =>
          val safeAddresses = addresses._1.map(s"Generated Safe Address $index - "+ _)
          val depositAddress = s"Generated Safe Address $index -  ${addresses._2}"
          MixingActor.CreateTransaction(safeAddresses, depositAddress)
      })

    createTransactions.foreach(mixingActor ! _)
    createTransactions
  }

  def transferToDeposit(
    createTransactions: List[CreateTransaction],
    sourceAddress: String = "mymasterwallet"
  ): Future[List[ValidatedNel[JobcoinWebService.Error, Unit]]] = {
    Future.sequence(
    createTransactions.map(
      createTransaction => JobcoinMixer.client.transfer(sourceAddress, createTransaction.depositAddress, 0.1)
    ))
  }

  def generateAndDeposit(numTransactions: Int, safeAddressNum: Int) = {
    val transactions = generateTransactions(numTransactions, safeAddressNum)
    transferToDeposit(transactions)
  }

}
