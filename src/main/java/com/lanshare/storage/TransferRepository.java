package com.lanshare.storage;
import com.lanshare.model.Transfer; import java.io.IOException; import java.util.List;
public interface TransferRepository { List<Transfer> load() throws IOException; void add(Transfer transfer) throws IOException; }
