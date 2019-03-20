package com.gemini.jobcoin

import java.util.UUID

import cats.data.ValidatedNel
import com.gemini.jobcoin.MixingActor.CreateTransaction

import scala.concurrent.{ExecutionContext, Future}

/**
  * Just some convenient scripts I'm using.
  * I originally made it for personal use but thought it might be useful in the CLI.
  */
object Convenience {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  val STORAGE_WALLET                              = "StorageWallet"

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
          val safeAddresses  = addresses._1.map(s"Generated Safe Address $index - " + _)
          val depositAddress = s"Generated Deposit Address $index -  ${addresses._2}"
          MixingActor.CreateTransaction(safeAddresses, depositAddress)
      })

    createTransactions.foreach(transaction => {
      println(
        s"Generated transaction with a deposit address of ${transaction.depositAddress} that should be tumbled to ${transaction.safeAddresses}"
      )
      mixingActor ! transaction
    })
    createTransactions
  }

  def transferToDeposit(
    createTransactions: List[CreateTransaction],
    sourceAddress: String = STORAGE_WALLET
  ): Future[List[ValidatedNel[JobcoinWebService.Error, Unit]]] = {
    Future.sequence(
      createTransactions.map(
        createTransaction => JobcoinMixer.client.transfer(sourceAddress, createTransaction.depositAddress, 0.1)
      )
    )
  }

  def generateAndDeposit(numTransactions: Option[Int], safeAddressNum: Option[Int]) = {
    val transactions = generateTransactions(numTransactions.getOrElse(1), safeAddressNum.getOrElse(1))
    transferToDeposit(transactions)
  }

}
