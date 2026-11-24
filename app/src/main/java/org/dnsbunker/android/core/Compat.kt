package org.dnsbunker.android.core

import android.net.Network
import android.os.Build
import java.io.FileDescriptor
import java.net.DatagramSocket
import java.net.Socket

object Compat {
    fun id(network: Network): Long =
        if (Build.VERSION.SDK_INT >= 23) network.networkHandle else network.toString().toLongOrNull() ?: network.hashCode().toLong()

    fun bind(network: Network, socket: Socket) { if (Build.VERSION.SDK_INT >= 22) network.bindSocket(socket) }
    fun bind(network: Network, socket: DatagramSocket) { if (Build.VERSION.SDK_INT >= 22) network.bindSocket(socket) }
    fun bind(network: Network, descriptor: FileDescriptor) { if (Build.VERSION.SDK_INT >= 23) network.bindSocket(descriptor) }
}
