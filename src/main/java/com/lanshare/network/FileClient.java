package com.lanshare.network;
import java.io.*;import java.net.*;import java.nio.file.*;import java.util.function.LongConsumer;
/** Sends one file per TCP connection; progress reports bytes sent. */
public final class FileClient {
 private FileClient(){}
 public static Connection connect(String host,int port)throws IOException {Socket socket=new Socket();socket.connect(new InetSocketAddress(host,port),5000);socket.setSoTimeout(30000);return new Connection(socket);}
 public static void send(String host,int port,Path file,LongConsumer progress)throws IOException {try(Connection connection=connect(host,port)){connection.send(file,progress);}}

 /** A live TCP connection. The receiver closes it after one file, matching the simple protocol. */
 public static final class Connection implements AutoCloseable {
  private final Socket socket;private final DataOutputStream out;private final DataInputStream in;
  private Connection(Socket socket)throws IOException {this.socket=socket;out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));}
  public synchronized void send(Path file,LongConsumer progress)throws IOException {
   long size=Files.size(file);String name=file.getFileName().toString();
   try(InputStream src=new BufferedInputStream(Files.newInputStream(file))){out.writeUTF(name);out.writeLong(size);out.flush();String ready=in.readUTF();if(!ready.equals("READY"))throw new IOException(ready);byte[] b=new byte[65536];long sent=0;int n;while((n=src.read(b))!=-1){out.write(b,0,n);sent+=n;progress.accept(sent);}out.flush();String response=in.readUTF();if(!response.equals("OK"))throw new IOException(response);}
  }
  public boolean isOpen(){return socket.isConnected()&&!socket.isClosed();}
  public void close()throws IOException {socket.close();}
 }
}
