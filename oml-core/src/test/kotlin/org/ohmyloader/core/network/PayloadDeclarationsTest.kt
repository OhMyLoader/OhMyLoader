package org.ohmyloader.core.network

import org.ohmyloader.api.network.OMLNetworkContext
import org.ohmyloader.api.network.OMLPacketBuffer
import org.ohmyloader.api.network.OMLPayloadCodec
import org.ohmyloader.api.network.OMLPayloadType
import kotlin.test.*

/**
 * The declaration contract of a custom-payload channel: namespacing, the two rejection rules that keep a
 * bad declaration out of the game's payload registry, and the direction gate a send call is checked
 * against. Channel names are unique per test because the collection is a process-wide singleton.
 */
class PayloadDeclarationsTest {

    private object StringCodec : OMLPayloadCodec<String> {
        override fun write(buffer: OMLPacketBuffer, value: String) {
            buffer.writeString(value)
        }

        override fun read(buffer: OMLPacketBuffer): String = buffer.readString()
    }

    private fun type(id: String) = OMLPayloadType(id, StringCodec)

    private fun context() = OMLNetworkContext("probe", null) { "platform" }

    @Test
    fun `an id without a namespace is published under the declaring mod id`() {
        assertEquals("mymod:ping", PayloadDeclarations.wireIdOf("mymod", "ping"))
        assertEquals("other:ping", PayloadDeclarations.wireIdOf("mymod", "other:ping"))
    }

    @Test
    fun `a declaration is collected with its published name and handler`() {
        val ping = type("ns1:ping")
        var received: String? = null
        PayloadDeclarations.add("ns1", ping, PayloadDeclarations.Direction.CLIENT_TO_SERVER) { value, _ ->
            received = value
        }
        val entry = PayloadDeclarations.handlerFor("ns1:ping", PayloadDeclarations.Direction.CLIENT_TO_SERVER)
        assertNotNull(entry)
        assertEquals("ns1", entry.modId)
        entry.handler("hello", context())
        assertEquals("hello", received)
    }

    @Test
    fun `an illegal channel name is rejected where it is declared`() {
        // Left unchecked, the game's identifier parser throws inside the payload registry's own
        // class initialization: a crash that names neither the mod nor the channel.
        val bad = assertFailsWith<IllegalArgumentException> {
            PayloadDeclarations.add(
                "ns2",
                type("NS2:Bad Name"),
                PayloadDeclarations.Direction.CLIENT_TO_SERVER,
            ) { _, _ ->
            }
        }
        assertTrue(bad.message!!.contains("ns2"), "the message must name the mod: ${bad.message}")
    }

    @Test
    fun `two claims of one channel in one direction are rejected`() {
        PayloadDeclarations.add("ns3", type("ns3:shared"), PayloadDeclarations.Direction.SERVER_TO_CLIENT) { _, _ -> }
        val clash = assertFailsWith<IllegalStateException> {
            PayloadDeclarations.add(
                "other",
                type("ns3:shared"),
                PayloadDeclarations.Direction.SERVER_TO_CLIENT,
            ) { _, _ ->
            }
        }
        assertTrue(clash.message!!.contains("[ns3]"), "the message must name the first claimant: ${clash.message}")
    }

    @Test
    fun `one channel may be declared in both directions`() {
        PayloadDeclarations.add("ns4", type("ns4:echo"), PayloadDeclarations.Direction.CLIENT_TO_SERVER) { _, _ -> }
        PayloadDeclarations.add("ns4", type("ns4:echo"), PayloadDeclarations.Direction.SERVER_TO_CLIENT) { _, _ -> }
        assertNotNull(PayloadDeclarations.handlerFor("ns4:echo", PayloadDeclarations.Direction.CLIENT_TO_SERVER))
        assertNotNull(PayloadDeclarations.handlerFor("ns4:echo", PayloadDeclarations.Direction.SERVER_TO_CLIENT))
    }

    @Test
    fun `a send is gated to the direction the type was declared for`() {
        val outbound = type("ns5:out")
        PayloadDeclarations.add("ns5", outbound, PayloadDeclarations.Direction.CLIENT_TO_SERVER) { _, _ -> }
        assertNotNull(PayloadDeclarations.entryFor(outbound, PayloadDeclarations.Direction.CLIENT_TO_SERVER))
        assertNull(PayloadDeclarations.entryFor(outbound, PayloadDeclarations.Direction.SERVER_TO_CLIENT))
        assertNull(PayloadDeclarations.entryFor(type("ns5:never"), PayloadDeclarations.Direction.CLIENT_TO_SERVER))
    }
}
