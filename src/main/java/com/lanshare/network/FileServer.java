package com.lanshare.network;
import com.lanshare.model.Transfer;
import com.lanshare.storage.TransferRepository;
import com.lanshare.util.FileValidator;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
/** TCP receiver: UTF filename, long size, exactly size bytes, then UTF status reply. */
public class FileServer implements AutoCloseable {
 private final int port;
 private final Path directory;
 private final TransferRepository history;
 private final Consumer<String> events;
 private final ExecutorService workers=Executors.newFixedThreadPool(8);
 private final Set<Socket> clients=ConcurrentHashMap.newKeySet();
 private final AtomicInteger count=new AtomicInteger();
 private volatile boolean running;
 private ServerSocket server;
 public FileServer(int port,Path directory,TransferRepository history,
                   Consumer<String> events){
  this.port=port;this.directory=directory;this.history=history;this.events=events;}
 public synchronized void start()throws IOException {
  if(running)return;
  Files.createDirectories(directory);
  server=new ServerSocket(port);
  running=true;
  Thread t=new Thread(this::acceptLoop,"lan-share-accept");
  t.setDaemon(true);
  t.start();
  events.accept("Server started on port "+server.getLocalPort());
 }
 private void acceptLoop(){while(running){try{Socket s=server.accept();
  clients.add(s);
  count.incrementAndGet();
  events.accept("Client connected: "+s.getInetAddress().getHostAddress());
  workers.submit(()->receive(s));
 }
 catch(SocketException e){if(running)events.accept("Server socket error: "+e.getMessage());
 }
 catch(IOException e){events.accept("Accept failed: "+e.getMessage());}
 }
 }
 private void receive(Socket s){Path temp=null;
  try(s;DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));
      DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))){String name=in.readUTF();
   long size=in.readLong();
   if(!FileValidator.validFilename(name)||size<0){out.writeUTF("REJECTED: invalid filename or size");
    out.flush();
    return;
   }
   long free=Files.getFileStore(directory).getUsableSpace();
   if(size>free){out.writeUTF("REJECTED: not enough free disk space");
    out.flush();
    return;}out.writeUTF("READY");out.flush();
    Path dest=unique(directory,name);temp=Files.createTempFile(directory,".incoming-",".part");
    try(OutputStream f=new BufferedOutputStream(Files.newOutputStream(temp)))
    {byte[] buf=new byte[65536];long remaining=size;
     while(remaining>0){int n=in.read(buf,0,(int)Math.min(buf.length,remaining));
      if(n<0)throw new EOFException("Connection ended before file completed");
      f.write(buf,0,n);
      remaining-=n;}}Files.move(temp,dest);
    temp=null;
    history.add(Transfer.received(dest.getFileName().toString(),s.getInetAddress().getHostAddress(),size));
    out.writeUTF("OK");out.flush();
    events.accept("Received "+dest.getFileName()+" ("+size+" bytes)");
  }
  catch(Exception e){events.accept("Transfer failed: "+e.getMessage());
   try{if(!s.isClosed()){DataOutputStream out=new DataOutputStream(s.getOutputStream());
    out.writeUTF("FAILED: "+e.getMessage());
    out.flush();}}catch(IOException ignored){/* Peer may already be gone. */}}finally{if(temp!=null)
     try{Files.deleteIfExists(temp);}
     catch(IOException e){events.accept("Could not remove partial file: "+e.getMessage());
     }if(clients.remove(s))count.updateAndGet(n->Math.max(0,n-1));}
 }
 private Path unique(Path dir,String name){Path p=dir.resolve(name);
  if(!Files.exists(p))return p;String stem=name,ext="";
  int dot=name.lastIndexOf('.');
  if(dot>0){stem=name.substring(0,dot);
   ext=name.substring(dot);}for(int i=1;;i++){p=dir.resolve(stem+" ("+i+")"+ext);if(!Files.exists(p))return p;}
 }
 public int connectedClients(){return count.get();}
 public boolean isRunning(){return running;}
 public synchronized void stop(){running=false;
  if(server!=null)try{server.close();}
  catch(IOException e){events.accept("Close failed: "+e.getMessage());}
  for(Socket s:clients)try{s.close();}
  catch(IOException ignored){}clients.clear();
  count.set(0);
  workers.shutdownNow();
  events.accept("Server stopped");}
 public void close(){stop();}
}
