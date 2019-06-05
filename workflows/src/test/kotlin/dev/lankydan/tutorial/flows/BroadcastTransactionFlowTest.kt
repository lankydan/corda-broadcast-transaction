package dev.lankydan.tutorial.flows

import dev.lankydan.tutorial.states.MessageState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.queryBy
import net.corda.core.toFuture
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkNotarySpec
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.util.concurrent.ExecutionException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BroadcastTransactionFlowTest {

  private lateinit var mockNetwork: MockNetwork
  private lateinit var partyA: StartedMockNode
  private lateinit var partyB: StartedMockNode
  private lateinit var partyC: StartedMockNode
  private lateinit var partyD: StartedMockNode
  private lateinit var notaryNode: MockNetworkNotarySpec

  @Before
  fun setup() {
    notaryNode = MockNetworkNotarySpec(CordaX500Name("Notary", "London", "GB"))
    mockNetwork = MockNetwork(
      listOf(
        "dev.lankydan"
      ),
      notarySpecs = listOf(notaryNode)
    )
    partyA =
      mockNetwork.createNode(
        MockNodeParameters(
          legalName = CordaX500Name(
            "PartyA",
            "Berlin",
            "DE"
          )
        )
      )

    partyB =
      mockNetwork.createNode(
        MockNodeParameters(
          legalName = CordaX500Name(
            "PartyB",
            "Berlin",
            "DE"
          )
        )
      )

    partyC =
      mockNetwork.createNode(
        MockNodeParameters(
          legalName = CordaX500Name(
            "PartyC",
            "Berlin",
            "DE"
          )
        )
      )

    partyD =
      mockNetwork.createNode(
        MockNodeParameters(
          legalName = CordaX500Name(
            "PartyD",
            "Berlin",
            "DE"
          )
        )
      )
    mockNetwork.runNetwork()
  }

  @After
  fun tearDown() {
    mockNetwork.stopNodes()
  }

  @Test
  fun `Transaction is broadcast to parties not involved in original transaction`() {
    val future = partyA.startFlow(
      SendMessageFlow(
        MessageState(
          contents = "hi",
          recipient = partyB.info.singleIdentity(),
          sender = partyA.info.singleIdentity(),
          linearId = UniqueIdentifier()
        )
      )
    )
    mockNetwork.runNetwork()
    val stx = future.get()

    val message = stx.coreTransaction.outputsOfType<MessageState>().single()

    val broadcastFuture = partyA.startFlow(
      BroadcastTransactionFlow(
        stx, listOf(partyC.info.singleIdentity(), partyD.info.singleIdentity())
      )
    )
    mockNetwork.runNetwork()
    broadcastFuture.get()

    assertStateExists(partyA, message)
    assertStateExists(partyB, message)
    assertStateExists(partyC, message)
    assertStateExists(partyD, message)
  }

  // this test is not throwing an exception using just corda code
  // i probably need to write something into the contract to prevent other parties from spending the state
  // for replying, the sender must be the recipient of the input message and must include their signature
  // the participants of the new state must sign the transaction
  // therefore they can decline the transaction inside of their responder flow
  // would prefer if I could 100% enforce in the contract
  @Test
  fun `Broadcasted transaction states cannot be spent in a transaction created by non participant parties`() {
    val future = partyA.startFlow(
      SendMessageFlow(
        MessageState(
          contents = "hi",
          recipient = partyB.info.singleIdentity(),
          sender = partyA.info.singleIdentity(),
          linearId = UniqueIdentifier()
        )
      )
    )
    mockNetwork.runNetwork()
    val stx = future.get()

    val broadcastFuture = partyA.startFlow(
      BroadcastTransactionFlow(
        stx, listOf(partyC.info.singleIdentity(), partyD.info.singleIdentity())
      )
    )
    mockNetwork.runNetwork()
    broadcastFuture.get()

    val messageStateAndRef = stx.coreTransaction.outRefsOfType<MessageState>().single()
    assertThatExceptionOfType(FlowException::class.java).isThrownBy {
      val replyFuture = partyD.startFlow(ReplyToMessageFlow(messageStateAndRef, partyB.info.singleIdentity(), partyA.info.singleIdentity()))
      mockNetwork.runNetwork()
      replyFuture.getOrThrow()
    }.withMessageContaining("The sender of the new message cannot have my identity")
  }

  @Test
  fun `Broadcasted transaction states cannot be sent to parties not included in the original transaction`() {
    val future = partyA.startFlow(
      SendMessageFlow(
        MessageState(
          contents = "hi",
          recipient = partyB.info.singleIdentity(),
          sender = partyA.info.singleIdentity(),
          linearId = UniqueIdentifier()
        )
      )
    )
    mockNetwork.runNetwork()
    val stx = future.get()

    val broadcastFuture = partyA.startFlow(
      BroadcastTransactionFlow(
        stx, listOf(partyC.info.singleIdentity(), partyD.info.singleIdentity())
      )
    )
    mockNetwork.runNetwork()
    broadcastFuture.get()

    val messageStateAndRef = stx.coreTransaction.outRefsOfType<MessageState>().single()
    assertThatExceptionOfType(FlowException::class.java).isThrownBy {
      val replyFuture = partyD.startFlow(ReplyToMessageFlow(messageStateAndRef, partyC.info.singleIdentity(), partyD.info.singleIdentity()))
      mockNetwork.runNetwork()
      replyFuture.getOrThrow()
    }.withMessageContaining("Only the original message's recipient can reply to the message")
  }

  @Test
  fun `Broadcasted transaction - sender cannot be mimicked`() {
    val future = partyA.startFlow(
      SendMessageFlow(
        MessageState(
          contents = "hi",
          recipient = partyB.info.singleIdentity(),
          sender = partyA.info.singleIdentity(),
          linearId = UniqueIdentifier()
        )
      )
    )
    mockNetwork.runNetwork()
    val stx = future.get()

    val broadcastFuture = partyA.startFlow(
      BroadcastTransactionFlow(
        stx, listOf(partyC.info.singleIdentity(), partyD.info.singleIdentity())
      )
    )
    mockNetwork.runNetwork()
    broadcastFuture.get()

    val messageStateAndRef = stx.coreTransaction.outRefsOfType<MessageState>().single()
    assertThatExceptionOfType(FlowException::class.java).isThrownBy {
      val replyFuture = partyD.startFlow(ReplyToMessageFlow(messageStateAndRef, partyB.info.singleIdentity(), partyA.info.singleIdentity()))
      mockNetwork.runNetwork()
      replyFuture.getOrThrow()
    }.withMessageContaining("The sender of the reply must must be the party creating this transaction")
  }

  @Test
  fun `BroadcastTransactionFlow can be called from inside another flow`() {
    val future = partyA.startFlow(
      SendMessageAndBroadcastFlow(
        MessageState(
          contents = "hi",
          recipient = partyB.info.singleIdentity(),
          sender = partyA.info.singleIdentity(),
          linearId = UniqueIdentifier()
        )
      )
    )
    mockNetwork.runNetwork()
    val stx = future.get()
    val message = stx.coreTransaction.outputsOfType<MessageState>().single()
    assertStateExists(partyA, message)
    assertStateExists(partyB, message)
    assertStateExists(partyC, message)
    assertStateExists(partyD, message)
  }

  private fun assertStateExists(node: StartedMockNode, message: MessageState) {
    node.transaction {
      val state = node.services.vaultService.queryBy<MessageState>().states.single().state.data
      assertEquals(message, state)
    }
  }
}