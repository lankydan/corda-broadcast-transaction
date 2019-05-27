package dev.lankydan.tutorial.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction

@InitiatingFlow
class BroadcastTransactionFlow(
  private val stx: SignedTransaction,
  private val recipients: List<Party>
) : FlowLogic<Unit>() {

  @Suspendable
  override fun call() {
    for (recipient in recipients) {
      val session = initiateFlow(recipient)
      subFlow(SendTransactionFlow(session, stx))
    }
  }
}

@InitiatedBy(BroadcastTransactionFlow::class)
class BroadcastTransactionResponder(private val session: FlowSession) : FlowLogic<Unit>() {

  @Suspendable
  override fun call() {
    subFlow(ReceiveTransactionFlow(session, statesToRecord = StatesToRecord.ALL_VISIBLE))
  }
}