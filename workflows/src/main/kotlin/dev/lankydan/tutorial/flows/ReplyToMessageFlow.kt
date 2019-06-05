package dev.lankydan.tutorial.flows

import co.paralleluniverse.fibers.Suspendable
import dev.lankydan.tutorial.contracts.MessageContract
import dev.lankydan.tutorial.contracts.MessageContract.Commands.Reply
import dev.lankydan.tutorial.states.MessageState
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
@StartableByRPC
class ReplyToMessageFlow(
  private val messageStateAndRef: StateAndRef<MessageState>,
  private val sender: Party,
  private val recipient: Party
) :
  FlowLogic<SignedTransaction>() {

  val message = messageStateAndRef.state.data
  lateinit var reply: MessageState

  @Suspendable
  override fun call(): SignedTransaction {
    reply = reply()
    logger.info("Started sending message ${message.contents}")
    val tx = verifyAndSign(transaction())
    val sessions = (listOf(message.sender, message.recipient, reply.sender, reply.recipient) - ourIdentity).map { initiateFlow(it) }
    val stx = collectSignature(tx, sessions)
    return subFlow(FinalityFlow(stx, sessions)).also {
      logger.info("Finished sending message ${message.contents}")
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
      addInputState(messageStateAndRef)
      addOutputState(reply, MessageContract.CONTRACT_ID)
      addCommand(Command(Reply(), (message.participants.map(Party::owningKey)) + reply.participants.map(Party::owningKey)))
    }

//  private fun reply() = MessageState(
//    contents = "thanks for the reply: ${message.contents}",
//    recipient = message.sender,
////    sender = ourIdentity,
//    sender = message.recipient,
//    linearId = UniqueIdentifier()
//  )

  private fun reply() = MessageState(
    contents = "thanks for the reply: ${message.contents}",
    recipient = recipient,
    sender = sender,
    linearId = UniqueIdentifier()
  )

  private fun notary() = serviceHub.networkMapCache.notaryIdentities.first()
}

@InitiatedBy(ReplyToMessageFlow::class)
class ReplyToMessageResponder(private val session: FlowSession) : FlowLogic<SignedTransaction>() {

  @Suspendable
  override fun call(): SignedTransaction {
    val stx = subFlow(object : SignTransactionFlow(session) {
      override fun checkTransaction(stx: SignedTransaction) {
        println("Im in check transaction : $ourIdentity")
        // can call single safely since the contract's validation runs before this and already
        // checks that there is only a single output state
        val message = stx.coreTransaction.outputsOfType<MessageState>().single()
        require(message.sender != ourIdentity) {
          "The sender of the new message cannot have my identity when I am not the creator of the transaction"
        }
        require(message.sender == session.counterparty) {
          "The sender of the reply must must be the party creating this transaction"
        }
      }
    })
    return subFlow(
      ReceiveFinalityFlow(
        otherSideSession = session,
        expectedTxId = stx.id
      )
    )
  }
}