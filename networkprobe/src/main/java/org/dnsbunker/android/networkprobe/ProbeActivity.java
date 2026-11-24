package org.dnsbunker.android.networkprobe;
import android.app.Activity;
import android.os.Bundle;
import android.net.*;
import android.widget.TextView;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.HttpsURLConnection;

public class ProbeActivity extends Activity {
 private final ExecutorService executor=Executors.newFixedThreadPool(3);
 private TextView result;
 @Override public void onCreate(Bundle state){super.onCreate(state);result=new TextView(this);result.setId(android.view.View.generateViewId());result.setTextSize(18);result.setPadding(32,64,32,32);setContentView(result);result.setText("PROBE_RUNNING");
  executor.execute(()->{
   String text;
   try {
    ConnectivityManager cm=getSystemService(ConnectivityManager.class);Network network=cm.getActiveNetwork();
    NetworkCapabilities caps=network==null?null:cm.getNetworkCapabilities(network);
    boolean vpn=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    boolean validated=caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    int a=query(DnsResolver.TYPE_A),aaaa=query(DnsResolver.TYPE_AAAA);
    HttpsURLConnection http=(HttpsURLConnection)new URL("https://example.com/").openConnection();
    http.setConnectTimeout(6000);http.setReadTimeout(6000);http.setInstanceFollowRedirects(false);
    int status;try{status=http.getResponseCode();}finally{http.disconnect();}
    text="PROBE_RESULT\nVPN="+vpn+"\nANDROID_VALIDATED="+validated+"\nDNS_A="+a+"\nDNS_AAAA="+aaaa+"\nHTTPS="+status;
   }catch(Exception error){text="PROBE_RESULT\nERROR="+error.getClass().getSimpleName();}
   final String done=text;runOnUiThread(()->result.setText(done));
  });
 }
 private int query(int type)throws Exception {
  CompletableFuture<Integer> done=new CompletableFuture<>();
  DnsResolver.getInstance().query(null,"example.com",type,DnsResolver.FLAG_NO_CACHE_LOOKUP,
   executor,null,new DnsResolver.Callback<List<InetAddress>>(){
    @Override public void onAnswer(List<InetAddress> addresses,int rcode){if(rcode==0&&!addresses.isEmpty())done.complete(addresses.size());else done.completeExceptionally(new Exception("DNS failure"));}
    @Override public void onError(DnsResolver.DnsException error){done.completeExceptionally(error);}
   });return done.get(12,TimeUnit.SECONDS);
 }
 @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
