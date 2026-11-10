package org.onestonesoup.javatrail.sample;

/** Short-stack target for field watch integration tests. */
public class FieldWatchApplication {
    public static volatile int counter;

    public static void main(String[] args) throws Exception {
        System.out.println("ready");
        while (true) {
            counter++;
            Thread.sleep(100);
        }
    }
}
