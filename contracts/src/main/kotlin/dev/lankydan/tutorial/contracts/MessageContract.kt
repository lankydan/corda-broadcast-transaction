package dev.lankydan.tutorial.contracts

import dev.lankydan.tutorial.states.MessageState
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

class MessageContract : Contract {
  companion object {
    val CONTRACT_ID = MessageContract::class.qualifiedName!!
  }

  interface Commands : CommandData {
    class Send : TypeOnlyCommandData(), Commands
    class Reply : TypeOnlyCommandData(), Commands
//    data class Reply(val senderOwningKey: PublicKey) : Commands
  }

  override fun verify(tx: LedgerTransaction) {
    val commandWithParties = tx.commands.requireSingleCommand<Commands>()
    val command = commandWithParties.value
    when (command) {
      is Commands.Send -> requireThat {
        "No inputs should be consumed when sending a message." using (tx.inputs.isEmpty())
        "Only one output state should be created when sending a message." using (tx.outputs.size == 1)
      }
      is Commands.Reply -> requireThat {
        "One input should be consumed when replying to a message." using (tx.inputs.size == 1)
        "Only one output state should be created when replying to a message." using (tx.outputs.size == 1)
        val output = tx.outputsOfType<MessageState>().single()
        val input = tx.inputsOfType<MessageState>().single()
        "Only the original message's recipient can reply to the message" using (output.sender == input.recipient)
        "The reply must be sent to the original sender" using (output.recipient == input.sender)
        "The original sender must be included in the required signers" using commandWithParties.signers.contains(input.sender.owningKey)
        "The original recipient must be included in the required signers" using commandWithParties.signers.contains(input.recipient.owningKey)
        // even stricter rule, not sure if it works though
//        "The sender of the reply must be the party "(command.senderOwningKey == output.sender.owningKey)
      }
    }
  }
}