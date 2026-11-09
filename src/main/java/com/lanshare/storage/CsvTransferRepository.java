package com.lanshare.storage;
import com.lanshare.model.Transfer; import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.time.Instant; import java.util.*;
/** Small escaped CSV repository; synchronized because server handlers may save concurrently. */
public class CsvTransferRepository implements TransferRepository {
 private final Path file; public CsvTransferRepository(Path file){this.file=file;}
 private static String q(String s){return "\""+s.replace("\"","\"\"")+"\"";}
 public synchronized List<Transfer> load() throws IOException { if(!Files.exists(file))return new ArrayList<>(); List<Transfer> out=new ArrayList<>(); for(String line:Files.readAllLines(file,StandardCharsets.UTF_8)){if(line.isBlank()||line.startsWith("id,"))continue; String[] a=parse(line); if(a.length==6)try{out.add(new Transfer(a[0],a[1],a[2],Long.parseLong(a[3]),Instant.parse(a[4]),a[5]));}catch(RuntimeException ignored){/* Skip damaged row; retain other history. */}} return out; }
 private String[] parse(String s){List<String> a=new ArrayList<>();StringBuilder b=new StringBuilder();boolean quote=false; for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'){if(quote&&i+1<s.length()&&s.charAt(i+1)=='"'){b.append('"');i++;}else quote=!quote;}else if(c==','&&!quote){a.add(b.toString());b.setLength(0);}else b.append(c);}a.add(b.toString());return a.toArray(String[]::new);}
 public synchronized void add(Transfer t)throws IOException {Files.createDirectories(file.toAbsolutePath().getParent()); boolean fresh=!Files.exists(file);try(BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND)){if(fresh)w.write("id,filename,sender,size,dateTime,status\n");w.write(q(t.id())+","+q(t.filename())+","+q(t.sender())+","+t.size()+","+q(t.dateTime().toString())+","+q(t.status())+"\n");}}
}
