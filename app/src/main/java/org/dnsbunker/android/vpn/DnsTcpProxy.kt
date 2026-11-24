package org.dnsbunker.android.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dnsbunker.android.core.DnsWire
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class DnsTcpProxy(
    private val scope: CoroutineScope,
    private val resolve: suspend (ByteArray) -> ByteArray,
    private val transmit: suspend (ByteArray) -> Unit,
    private val onIoFailure: (Exception) -> Unit = {},
) {
    private data class Key(val version: Int, val source: List<Byte>, val destination: List<Byte>, val port: Int)

    private class Session(
        val first: DnsTcpSegment,
        var expectedClient: Long,
        var nextServer: Long,
        var touchedAt: Long,
    ) {
        var established = false
        var dispatched = false
        var clientFinished = false
        var serverFinished = false
        var serverFinAcked = false
        var peerWindow = first.window
        var completed = false
        var flight: ByteArray? = null
        var outgoing: ByteArray? = null
        var outgoingOffset = 0
        val incoming = ByteArrayOutputStream()
    }

    private val random = SecureRandom()
    private val lock = Mutex()
    private val sessions = LinkedHashMap<Key, Session>()

    companion object {
        private const val MAX_SESSIONS = 64
        private const val SESSION_NANOS = 60_000_000_000L
        private const val MAX_REQUEST = 65537
        private const val MSS = 536
        private const val MASK = 0xffff_ffffL
        private fun inc(value: Long, delta: Int) = (value + delta) and MASK
    }

    private fun key(packet: DnsTcpSegment) = Key(
        packet.ipVersion, packet.source.toList(), packet.destination.toList(), packet.sourcePort,
    )

    suspend fun handle(packet: DnsTcpSegment) {
        if (packet.destinationPort != 53) return
        val key = key(packet)
        lock.withLock {
            val now = System.nanoTime()
            sessions.entries.removeAll { now - it.value.touchedAt > SESSION_NANOS }

            if ((packet.flags and DnsTcpPacket.RST) != 0) {
                sessions.remove(key)
                return@withLock
            }
            if ((packet.flags and DnsTcpPacket.SYN) != 0) {
                if(sessions[key]?.completed==true)sessions.remove(key)
                if(sessions.size>=MAX_SESSIONS)sessions.entries.firstOrNull {it.value.completed}?.key?.let {sessions.remove(it)}
                val existing = sessions[key]
                if (existing != null && !existing.established &&
                    packet.sequence == inc(existing.expectedClient, -1)
                ) {
                    send(existing, inc(existing.nextServer, -1), existing.expectedClient,
                        DnsTcpPacket.SYN or DnsTcpPacket.ACK)
                } else if (existing == null && sessions.size < MAX_SESSIONS) {
                    val isn = random.nextInt().toLong() and MASK
                    val created = Session(packet, inc(packet.sequence, 1), inc(isn, 1), now)
                    sessions[key] = created
                    send(created, isn, created.expectedClient, DnsTcpPacket.SYN or DnsTcpPacket.ACK)
                }
                return@withLock
            }

            val session = sessions[key] ?: return@withLock
            session.touchedAt = now
            session.peerWindow = packet.window
            if ((packet.flags and DnsTcpPacket.ACK) == 0) return@withLock
            if (!session.established) {
                if (packet.acknowledgement != session.nextServer ||
                    packet.sequence != session.expectedClient
                ) return@withLock
                session.established = true
            }

            val flight = session.flight
            if (flight != null && packet.acknowledgement == inc(session.nextServer, flight.size)) {
                session.nextServer = inc(session.nextServer, flight.size)
                session.outgoingOffset += flight.size
                session.flight = null
                transmitNext(session)
            } else if (session.serverFinished && packet.acknowledgement == session.nextServer) {
                session.serverFinAcked = true
                if (session.clientFinished && (packet.flags and DnsTcpPacket.FIN) == 0) {
                    complete(key,session)
                    return@withLock
                }
            }

            if (session.flight == null && session.outgoing != null && !session.serverFinished) transmitNext(session)

            if (packet.payload.isNotEmpty()) {
                if (packet.sequence == session.expectedClient && !session.dispatched &&
                    session.incoming.size() + packet.payload.size <= MAX_REQUEST
                ) {
                    session.incoming.write(packet.payload)
                    session.expectedClient = inc(session.expectedClient, packet.payload.size)
                    send(session, session.nextServer, session.expectedClient, DnsTcpPacket.ACK)
                    val input = session.incoming.toByteArray()
                    if (input.size >= 2) {
                        val length = ((input[0].toInt() and 255) shl 8) or (input[1].toInt() and 255)
                        if (length !in 12..65535 || input.size > length + 2) {
                            sessions.remove(key)
                            send(session, session.nextServer, session.expectedClient,
                                DnsTcpPacket.RST or DnsTcpPacket.ACK)
                            return@withLock
                        }
                        if (input.size == length + 2) {
                            session.dispatched = true
                            val dns = input.copyOfRange(2, input.size)
                            launchSafely(session) { resolveAndSend(key, session, dns) }
                        }
                    }
                } else {
                    send(session, session.nextServer, session.expectedClient, DnsTcpPacket.ACK)
                }
            }

            if ((packet.flags and DnsTcpPacket.FIN) != 0) {
                val end = inc(packet.sequence, packet.payload.size)
                if (!session.clientFinished && end == session.expectedClient) {
                    session.clientFinished = true
                    session.expectedClient = inc(session.expectedClient, 1)
                    send(session, session.nextServer, session.expectedClient, DnsTcpPacket.ACK)
                    if (!session.dispatched) {
                        sessions.remove(key)
                        send(session, session.nextServer, session.expectedClient, DnsTcpPacket.RST or DnsTcpPacket.ACK)
                    }
                } else if (session.clientFinished && inc(end, 1) == session.expectedClient) {
                    send(session, session.nextServer, session.expectedClient, DnsTcpPacket.ACK)
                }
                if(session.clientFinished&&session.serverFinAcked)complete(key,session)
            }
        }
    }

    private fun complete(key:Key,session:Session) {
        if(session.completed)return
        session.completed=true
        launchSafely(session){delay(2500);lock.withLock {if(sessions[key]===session)sessions.remove(key)}}
    }

    private fun launchSafely(session: Session, block: suspend () -> Unit) = scope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            lock.withLock { sessions.entries.removeAll { it.value === session } }
            if (error is java.io.IOException) onIoFailure(error)
        }
    }

    private suspend fun resolveAndSend(key: Key, session: Session, query: ByteArray) {
        val result = try {
            resolve(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            runCatching { DnsWire.servfail(query) }.getOrNull() ?: return
        }
        if (result.size !in 12..65535) return
        val framed = ByteArray(result.size + 2)
        framed[0] = (result.size ushr 8).toByte()
        framed[1] = result.size.toByte()
        result.copyInto(framed, 2)
        lock.withLock {
            if (sessions[key] !== session || !session.established) return@withLock
            session.outgoing = framed
            transmitNext(session)
        }
    }

    private suspend fun transmitNext(session: Session) {
        if (session.flight != null || session.serverFinished || session.peerWindow <= 0) return
        val bytes = session.outgoing ?: return
        if (session.outgoingOffset < bytes.size) {
            val next = bytes.copyOfRange(session.outgoingOffset, minOf(bytes.size, session.outgoingOffset + minOf(MSS,session.peerWindow)))
            session.flight = next
            send(session, session.nextServer, session.expectedClient,
                DnsTcpPacket.PSH or DnsTcpPacket.ACK, next)
            val currentSequence = session.nextServer
            launchSafely(session) {
                for (attempt in 1..3) {
                    delay(750L * attempt)
                    lock.withLock {
                        if (session.flight !== next || !sessions.containsValue(session)) return@launchSafely
                        send(session, currentSequence, session.expectedClient,
                            DnsTcpPacket.PSH or DnsTcpPacket.ACK, next)
                    }
                }
            }
        } else {
            session.serverFinished = true
            val finSequence=session.nextServer
            launchSafely(session) {
                for(attempt in 1..3) {
                    delay(750L*attempt)
                    lock.withLock {
                        if(session.serverFinAcked || !sessions.containsValue(session))return@launchSafely
                        send(session,finSequence,session.expectedClient,DnsTcpPacket.FIN or DnsTcpPacket.ACK)
                    }
                }
            }
            send(session, session.nextServer, session.expectedClient, DnsTcpPacket.FIN or DnsTcpPacket.ACK)
            session.nextServer = inc(session.nextServer, 1)
        }
    }

    private suspend fun send(
        session: Session, sequence: Long, acknowledgement: Long, flags: Int,
        payload: ByteArray = byteArrayOf(),
    ) = transmit(DnsTcpPacket.reply(session.first, sequence, acknowledgement, flags, payload))
}
