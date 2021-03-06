package org.pcj.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.pcj.PCJ;
import org.pcj.RegisterStorage;
import org.pcj.StartPoint;
import org.pcj.Storage;

// https://stackoverflow.com/questions/26206544/parallel-radix-sort-how-would-this-implementation-actually-work-are-there-some
@RegisterStorage(PcjRadixSort.Shared.class)
public class PcjRadixSort implements StartPoint {

    public static void main(String[] args) throws IOException {
        String[] nodes;
        if (args.length == 0) {
            nodes = IntStream.range(0, 4).mapToObj(x -> "localhost").toArray(String[]::new);
        } else {
            nodes = Files.readAllLines(Paths.get(args[0])).stream().toArray(String[]::new);
        }

        PCJ.executionBuilder(PcjRadixSort.class)
                .addNodes(nodes)
                .deploy();
    }

    @Storage(PcjRadixSort.class)
    enum Shared {
        zeros, ones, newTab, bitsAtomicInteger
    }

    private int[] zeros;
    private int[] ones;
    private int[] newTab;
    private AtomicInteger bitsAtomicInteger = new AtomicInteger(Integer.MIN_VALUE);

    private int[] tab;

    @Override
    public void main() throws Throwable {
        int n = PCJ.threadCount() * 10;
        int minValue = 0;
        int maxValue = 100;
        generateTab(n / PCJ.threadCount(), minValue, maxValue);
        printTab();

        int bits = reduceTabBits();
        parallelRadixSort(bits);

        printTab();
    }

    private void generateTab(int size, int minValue, int maxValue) {
        tab = new Random().ints(size, minValue, maxValue).toArray();
    }

    private void printTab() {
        for (int i = 0; i < PCJ.threadCount(); i++) {
            if (PCJ.myId() == i) {
                System.out.print(Arrays.toString(tab));
            }
            PCJ.barrier();
        }
        if (PCJ.myId() == 0) System.out.println();
    }

    private int reduceTabBits() {
        int localBits = (int) Math.ceil(Math.log(Arrays.stream(tab).max().getAsInt()) / Math.log(2));

        PCJ.at(0, () -> {
            AtomicInteger bits = PCJ.getLocal(Shared.bitsAtomicInteger);
            int oldValue;
            do {
                oldValue = bits.get();
                if (localBits < oldValue) break;
            } while (!bits.compareAndSet(oldValue, localBits));
        });

        PCJ.barrier();

        if (PCJ.myId() == 0) {
            PCJ.broadcast(bitsAtomicInteger, Shared.bitsAtomicInteger);
        }
        PCJ.waitFor(Shared.bitsAtomicInteger);

        return bitsAtomicInteger.get();
    }


    private void parallelRadixSort(int bits) {
        zeros = new int[PCJ.threadCount()];
        ones = new int[PCJ.threadCount()];
        PCJ.barrier();

        for (int bit = 0; bit < bits; ++bit) {
            newTab = new int[tab.length];
            int shift = (1 << bit);

            int zeros = 0;
            for (int t : tab) {
                if ((t & shift) == 0) ++zeros;
            }

            // REDUCE `zeros` - calculate offsets
            PCJ.broadcast(zeros, Shared.zeros, PCJ.myId());
            PCJ.broadcast(tab.length - zeros, Shared.ones, PCJ.myId());

            int[] offsets = new int[2];

            PCJ.waitFor(Shared.zeros, PCJ.threadCount());
            zeros = Arrays.stream(this.zeros).sum();
            offsets[0] = Arrays.stream(this.zeros).limit(PCJ.myId()).sum();

            PCJ.waitFor(Shared.ones, PCJ.threadCount());
            offsets[1] = Arrays.stream(this.ones).limit(PCJ.myId()).sum();

            int[] newIndexTab = new int[tab.length];
            for (int i = 0; i < tab.length; ++i) {
                int value = ((tab[i] & shift) == 0) ? 0 : 1;

                newIndexTab[i] = (value == 0 ? 0 : zeros) + offsets[value]++;
            }

            // EXCHANGE
            for (int i = 0; i < tab.length; ++i) {
                int threadId = newIndexTab[i] / tab.length;
                int tabIndex = newIndexTab[i] % tab.length;

//                System.err.println(PCJ.myId() + "[" + i + "]" + ": " + tab[i] + " -> " + threadId + "[" + tabIndex + "]");

                if (PCJ.myId() == threadId) {
                    newTab[tabIndex] = tab[i];
                } else {
                    PCJ.put(tab[i], threadId, Shared.newTab, tabIndex);
                }
            }
            // wait for modification end by all threads ("I can finish, but other can update my newTab")
            PCJ.barrier();
            tab = newTab;
        }
    }
}
