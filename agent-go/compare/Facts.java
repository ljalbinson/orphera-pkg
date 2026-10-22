// SPDX-License-Identifier: Apache-2.0
//
// Prints the network interfaces and disks as the Scala agent's FactGatherer sees
// them through the JVM, in the form `orphera-agent-go --print-facts` uses.
// Run with: java compare/Facts.java   (Java 11+ runs a single source file)

import java.net.*; import java.nio.file.*; import java.util.*;
public class Facts { public static void main(String[] a) throws Exception {
  for (NetworkInterface i : Collections.list(NetworkInterface.getNetworkInterfaces())) {
    if (i.isLoopback() || !i.isUp()) continue;
    List<String> ad = new ArrayList<>(); for (InetAddress x : Collections.list(i.getInetAddresses())) ad.add(x.getHostAddress());
    byte[] hw = i.getHardwareAddress(); StringBuilder m = new StringBuilder();
    if (hw != null) for (int k=0;k<hw.length;k++){ if(k>0)m.append(':'); m.append(String.format("%02x", hw[k])); }
    System.out.println("IF " + i.getName() + " " + ad + " " + m);
  }
  for (FileStore s : FileSystems.getDefault().getFileStores()) {
    try { System.out.println("DISK " + s.name() + " " + s.type() + " " + s.getTotalSpace() + " " + s.getUsableSpace()); } catch (Throwable t) {}
  }
}}
