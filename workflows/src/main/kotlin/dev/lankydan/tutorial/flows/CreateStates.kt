package dev.lankydan.tutorial.flows

import co.paralleluniverse.fibers.Suspendable
import dev.lankydan.tutorial.contracts.MessageContract
import dev.lankydan.tutorial.contracts.MessageContract.Commands.Send
import dev.lankydan.tutorial.states.MessageState
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/*
To run this CreateStates (creates transaction with 17 outputs):

flow start dev.lankydan.tutorial.flows.CreateStates contents: "this is my message", recipient: "O=PartyB, L=London, C=GB", numberOfStatesToCreate: 17

This will output a transaction id, use it in the following flow to consume from it

To run ConsumeState (consumes the state from a specified transaction and specified index):

flow start dev.lankydan.tutorial.flows.ConsumeState txId: "7772FC85667319A405854837091CC921729BBB04D378F5ED7127E82006D76F1C", indexToConsume: 10
 */

// USE THESE FLOWS!!
// Returns tx id as a string
@InitiatingFlow
@StartableByRPC
class CreateStates(private val contents: String, private val recipient: String, private val numberOfStatesToCreate: Int) :
  FlowLogic<String>() {

  @Suspendable
  override fun call(): String {
    logger.info("Started CreateStates $contents")
    val recipientParty =
      requireNotNull(serviceHub.networkMapCache.getPeerByLegalName(CordaX500Name.parse(recipient))) { "The recipient party does not exist" }
    val tx = verifyAndSign(transaction(recipientParty))
    val sessions = listOf(initiateFlow(recipientParty))
    val stx = collectSignature(tx, sessions)
    return subFlow(FinalityFlow(stx, sessions)).also {
      logger.info("Finished CreateStates, transaction ${it.id} created with ${it.tx.outputStates.size} output states")
    }.id.toString()
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

  private fun transaction(recipientParty: Party): TransactionBuilder {
    val me = serviceHub.myInfo.legalIdentities.first()
    return TransactionBuilder(notary()).apply {
      for (i in 0 until numberOfStatesToCreate) {
        logger.info("Creating state with index - $i")
        addOutputState(
          MessageState(
            sender = serviceHub.myInfo.legalIdentities.first(),
            recipient = recipientParty,
            contents = "$contents - $i",
            linearId = UniqueIdentifier()
          ),
          MessageContract.CONTRACT_ID
        )
      }
      addCommand(Command(Send(), listOf(me, recipientParty).map(Party::owningKey)))
    }
  }

  private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()
}

@InitiatedBy(CreateStates::class)
class CreateStatesResponder(private val session: FlowSession) : FlowLogic<SignedTransaction>() {

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

// Run this on the same flow that sent the initial message
@InitiatingFlow
@StartableByRPC
class ConsumeState(private val txId: String, private val indexToConsume: Int) :
  FlowLogic<String>() {

  @Suspendable
  override fun call(): String {
    logger.info("Started ConsumeState from $txId index $indexToConsume")
    val ref = StateRef(SecureHash.create(txId), indexToConsume)
    val state = serviceHub.loadState(ref)
    val tx = verifyAndSign(transaction(ref, state))
    val sessions = listOf(initiateFlow((state.data as MessageState).recipient))
    val stx = collectSignature(tx, sessions)
    return subFlow(FinalityFlow(stx, sessions)).also {
      logger.info("Finished ConsumeState from $txId index $indexToConsume")
      logger.info(
        "Currently consumed transactions include: ${
          serviceHub.vaultService.queryBy<MessageState>(
            QueryCriteria.LinearStateQueryCriteria(
              status = Vault.StateStatus.CONSUMED
            )
          ).states.map { it.ref }
        }"
      )
    }.id.toString()
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

  private fun transaction(ref: StateRef, state: TransactionState<*>): TransactionBuilder {
    return TransactionBuilder(notary()).apply {
      addInputState(StateAndRef(state, ref))
      addCommand(Command(MessageContract.Commands.Reply(), (state.data as MessageState).participants.map(Party::owningKey)))
    }
  }

  private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()
}

@InitiatedBy(ConsumeState::class)
class ConsumeStateResponder(private val session: FlowSession) : FlowLogic<SignedTransaction>() {

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