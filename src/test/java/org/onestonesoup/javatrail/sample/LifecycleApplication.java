package org.onestonesoup.javatrail.sample;

import java.io.*;

/** Command-driven target keeps failure and class-loading tests deterministic. */
public class LifecycleApplication {
    public static volatile long total;
    public static void main(String[] args) throws Exception {
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in));
        System.out.println("ready");
        String command;
        while((command=input.readLine())!=null) {
            if(command.equals("late")) Class.forName("org.onestonesoup.javatrail.sample.LateLoadedCounter").getMethod("increment").invoke(null);
            else if(command.equals("flow")) flow();
            else if(command.equals("exit")) exitFlow();
            System.out.println("done");
        }
    }
    public static void flow() { for(int i=0;i<200;i++) child(); }
    public static void child() { total++; }
    public static void exitFlow() { System.exit(0); }
}
