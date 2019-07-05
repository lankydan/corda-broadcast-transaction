package dev.lankydan.tutorial.flows

import co.paralleluniverse.fibers.Suspendable
import dev.lankydan.tutorial.contracts.MessageContract
import dev.lankydan.tutorial.contracts.MessageContract.Commands.Send
import dev.lankydan.tutorial.states.MessageState
import net.corda.core.contracts.Command
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/** copy of [SendMessageFlow] to demonstrate [BroadcastTransactionFlow] being called from inside another flow */
@InitiatingFlow
@StartableByRPC
class SendMessageAndBroadcastFlow(private val message: MessageState) :
  FlowLogic<SignedTransaction>() {

  @Suspendable
  override fun call(): SignedTransaction {
    logger.info("Started sending message ${message.contents}")
    val tx = verifyAndSign(transaction())
    val sessions = listOf(initiateFlow(message.recipient))
    val stx = collectSignature(tx, sessions)
    return subFlow(FinalityFlow(stx, sessions)).also {
      logger.info("Finished sending message ${message.contents}")
      val broadcastToParties =
        serviceHub.networkMapCache.allNodes.map { node -> node.legalIdentities.first() }
          .minus(serviceHub.networkMapCache.notaryIdentities)
          .minus(message.recipient)
          .minus(message.sender)
      subFlow(
        BroadcastTransactionFlow(
          it, broadcastToParties
        )
      )
    }
  }

  @Suspendable
  private fun collectSignature(
    transaction: SignedTransaction,
    sessions: List<FlowSession>
  ): SignedTransaction = subFlow(CollectSignaturesFlow(transaction, sessions))

  private fun verifyAndSign(transaction: TransactionBuilder): SignedTransaction {
    transaction.verify(serviceHub)
    return serviceHub.signInitialTransaction(transaction)
  }

  private fun transaction() =
    TransactionBuilder(notary()).apply {
      addOutputState(message, MessageContract.CONTRACT_ID)
      addCommand(Command(Send(), message.participants.map(Party::owningKey)))
    }

  private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()
}

@InitiatedBy(SendMessageAndBroadcastFlow::class)
class SendMessageAndBroadcastResponder(private val session: FlowSession) : FlowLogic<SignedTransaction>() {

  @Suspendable
  override fun call(): SignedTransaction {
    val stx = subFlow(object : SignTransactionFlow(session) {
      override fun checkTransaction(stx: SignedTransaction) {
      }
    })
    val tx = subFlow(
      ReceiveFinalityFlow(
        otherSideSession = session,
        expectedTxId = stx.id
      )
    )
    logger.info("Received transaction from finality")
    return tx
  }
}