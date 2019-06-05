package dev.lankydan.tutorial.contracts

import dev.lankydan.tutorial.states.MessageState
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.transactions.LedgerTransaction

class MessageContract : Contract {
  companion object {
    val CONTRACT_ID = MessageContract::class.qualifiedName!!
  }

  interface Commands : CommandData {
    class Send : TypeOnlyCommandData(), Commands
    class Reply : TypeOnlyCommandData(), Commands
  }

  override fun verify(tx: LedgerTransaction) {
    val commandWithParties: CommandWithParties<Commands> = tx.commands.requireSingleCommand()
    val command = commandWithParties.value
    when (command) {
      is Commands.Send -> requireThat {
        "No inputs should be consumed when sending a message." using (tx.inputs.isEmpty())
        "Only one output state should be created when sending a message." using (tx.outputs.size == 1)
      }
      is Commands.Reply -> requireThat {
        "One input should be consumed when replying to a message." using (tx.inputs.size == 1)
        "Only one output state should be created when replying to a message." using (tx.outputs.size == 1)

        // generalised requirements (based on `ContractsDSL.verifyMoveCommand`)
        // `ContractsDSL.verifyMoveCommand` is used by cash, therefore developers won't have to implement contract rules for it
        val inputPublicKeys = tx.inputs.flatMap { it.state.data.participants.map(AbstractParty::owningKey) }.toSet()
        "The input participant keys must be a subset of the signing keys" using commandWithParties.signers.containsAll(inputPublicKeys)
        val outputPublicKeys = tx.outputStates.flatMap { it.participants.map(AbstractParty::owningKey) }.toSet()
        "The output participant keys must be a subset of the signing keys" using commandWithParties.signers.containsAll(outputPublicKeys)

        // precise requirements for the message state
        val output = tx.outputsOfType<MessageState>().single()
        val input = tx.inputsOfType<MessageState>().single()
        "Only the original message's recipient can reply to the message" using (output.sender == input.recipient)
        "The reply must be sent to the original sender" using (output.recipient == input.sender)
        "The original sender must be included in the required signers" using commandWithParties.signers.contains(input.sender.owningKey)
        "The original recipient must be included in the required signers" using commandWithParties.signers.contains(input.recipient.owningKey)
      }
    }
  }
}