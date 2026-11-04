package com.example.ems.tools;

import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

/** Package-local trust anchor, full chain/hostname verification; no global trust changes. */
public final class TlsHealth {
 private TlsHealth(){}
 public static boolean check(String address,int port,Path certificate)throws Exception{
  return check(address,port,certificate,"https://"+address+":"+port);
 }
 public static boolean check(String address,int port,Path certificate,String expectedOrigin)throws Exception{
  if(!address.matches("[0-9.]{7,15}")||port<1024||port>65535||!Files.isRegularFile(certificate,LinkOption.NOFOLLOW_LINKS)||Files.size(certificate)>65536)throw new IllegalArgumentException("Invalid TLS health inputs");
  var store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);
  try(var input=Files.newInputStream(certificate)){int n=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(input))store.setCertificateEntry("package-ca-"+(n++),cert);if(n==0)throw new IllegalArgumentException("Empty trust certificate");}
  var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(store);var ssl=SSLContext.getInstance("TLS");ssl.init(null,managers.getTrustManagers(),null);
  URI expected=URI.create(expectedOrigin);if(!"https".equals(expected.getScheme())||expected.getHost()==null||expected.getUserInfo()!=null||expected.getQuery()!=null||expected.getFragment()!=null||!java.util.Set.of("","/").contains(expected.getPath()))throw new IllegalArgumentException("Invalid expected origin");
  String[] parts=address.split("\\.");if(parts.length!=4)throw new IllegalArgumentException("IPv4 required");byte[] bytes=new byte[4];for(int i=0;i<4;i++){int value=Integer.parseInt(parts[i]);if(value<0||value>255)throw new IllegalArgumentException("Invalid IPv4");bytes[i]=(byte)value;}
  // Connect only to the recorded numeric local endpoint. Authenticate the configured
  // public certificate hostname without resolving that hostname or using a proxy.
  try(var raw=new Socket()){
   raw.connect(new InetSocketAddress(InetAddress.getByAddress(bytes),port),3000);raw.setSoTimeout(4000);
   try(var socket=(SSLSocket)ssl.getSocketFactory().createSocket(raw,expected.getHost(),port,true)){
    var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);socket.setSoTimeout(4000);socket.startHandshake();
    String request="GET /health HTTP/1.1\r\nHost: "+expected.getRawAuthority()+"\r\nConnection: close\r\n\r\n";socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().flush();
    byte[] response=socket.getInputStream().readNBytes(16385);if(response.length>16384)throw new IllegalArgumentException("Health response too large");String wire=new String(response,java.nio.charset.StandardCharsets.UTF_8);int boundary=wire.indexOf("\r\n\r\n");if(boundary<0||!wire.startsWith("HTTP/1.1 200 "))return false;
    String headers=wire.substring(0,boundary).toLowerCase(java.util.Locale.ROOT),body=wire.substring(boundary+4);
    if(headers.contains("transfer-encoding: chunked")){var decoded=new StringBuilder();int at=0;while(true){int end=body.indexOf("\r\n",at);if(end<0)throw new IllegalArgumentException("Invalid health framing");String size=body.substring(at,end).split(";",2)[0];int count=Integer.parseInt(size,16);at=end+2;if(count==0)break;if(count<0||count>8192||at+count+2>body.length()||decoded.length()+count>8192)throw new IllegalArgumentException("Invalid health framing");decoded.append(body,at,at+count);at+=count;if(!body.startsWith("\r\n",at))throw new IllegalArgumentException("Invalid health framing");at+=2;}body=decoded.toString();}
    return "UP".equals(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("status").asText());
   }
  }
 }
 public static void main(String[] args){try{if((args.length!=3&&args.length!=4)||!check(args[0],Integer.parseInt(args[1]),Path.of(args[2]),args.length==4?args[3]:"https://"+args[0]+":"+args[1]))throw new IllegalStateException();System.out.println("UP");}catch(Exception failed){System.err.println("TLS health verification failed");System.exit(1);}}
}
