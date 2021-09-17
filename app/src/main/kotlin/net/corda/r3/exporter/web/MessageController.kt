package net.corda.r3.exporter.web

import dev.lankydan.tutorial.flows.SendMessageFlow
import net.corda.r3.exporter.NodeRPCConnection
import net.corda.r3.exporter.dto.Message
import dev.lankydan.tutorial.states.MessageState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.*

@RestController
@RequestMapping("/messages")
class MessageController(rpc: NodeRPCConnection) {

  private val proxy = rpc.proxy

  @PostMapping
  fun post(@RequestBody message: Message): ResponseEntity<String> {
    return UUID.randomUUID().let {
      ResponseEntity.created(URI("/messages/$it")).body(
        (proxy.startFlow(
          ::SendMessageFlow,
          state(message, it)
        ).returnValue.getOrThrow().coreTransaction.outputStates.first() as MessageState).contents
      )
    }
  }

  private fun state(message: Message, id: UUID) =
    MessageState(
      sender = proxy.nodeInfo().legalIdentities.first(),
      recipient = parse(message.recipient),
      contents = message.contents,
      linearId = UniqueIdentifier(id.toString())
    )

  private fun parse(party: String) =
    proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(party))
      ?: throw IllegalArgumentException("Unknown party name.")
}