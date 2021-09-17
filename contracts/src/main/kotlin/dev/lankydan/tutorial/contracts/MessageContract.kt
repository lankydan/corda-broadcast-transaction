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
  }
}