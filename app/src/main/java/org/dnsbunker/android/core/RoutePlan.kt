package org.dnsbunker.android.core
import java.math.BigInteger
import java.net.InetAddress

object RoutePlan {
 data class Route(val address:InetAddress,val prefix:Int)
 private data class Net(val value:BigInteger,val prefix:Int,val bits:Int) {
  fun contains(other:Net)=bits==other.bits&&prefix<=other.prefix&&other.value.shiftRight(bits-prefix)==value.shiftRight(bits-prefix)
  fun route():Route {
   val source=value.toByteArray();val bytes=ByteArray(bits/8)
   val n=minOf(bytes.size,source.size);source.copyInto(bytes,bytes.size-n,source.size-n)
   return Route(InetAddress.getByAddress(bytes),prefix)
  }
 }
 private fun net(text:String):Net {val (ip,prefix)=text.split('/');val b=InetAddress.getByName(ip).address;return Net(BigInteger(1,b),prefix.toInt(),b.size*8)}
 private fun subtract(parent:Net,cuts:List<Net>):List<Net> {
  if(cuts.any{it.contains(parent)})return emptyList()
  if(cuts.none{parent.contains(it)})return listOf(parent)
  val length=parent.prefix+1
  return subtract(Net(parent.value,length,parent.bits),cuts)+subtract(Net(parent.value.setBit(parent.bits-length),length,parent.bits),cuts)
 }
 val publicRoutes:List<Route> by lazy {
  val cuts=listOf("0.0.0.0/8","10.0.0.0/8","127.0.0.0/8","169.254.0.0/16","172.16.0.0/12","192.168.0.0/16","224.0.0.0/4","240.0.0.0/4","fc00::/7","fe80::/10","ff00::/8").map(::net)
  (subtract(net("0.0.0.0/0"),cuts)+subtract(net("::/0"),cuts)).map {it.route()}
 }
}
