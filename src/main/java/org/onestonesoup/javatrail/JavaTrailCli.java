package org.onestonesoup.javatrail;

import java.io.PrintStream;
import java.util.Arrays;

/** Command-line entry point; the debugger engine is also usable as a library. */
public final class JavaTrailCli {
    private JavaTrailCli() {
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            usage(out);
            return 0;
        }
        try {
            switch (args[0]) {
                case "watch":
                    if (args.length != 5) {
                        throw new IllegalArgumentException("watch requires <class> <field> <host> <port>");
                    }
                    int port;
                    try {
                        port = Integer.parseInt(args[4]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Debug port must be a number from 1 to 65535");
                    }
                    if (port < 1 || port > 65535) {
                        throw new IllegalArgumentException("Debug port must be a number from 1 to 65535");
                    }
                    JavaTrail.main(Arrays.copyOfRange(args, 1, args.length));
                    return 0;
                case "trace":
                    if (args.length != 2) {
                        throw new IllegalArgumentException("trace requires <config.json>");
                    }
                    JavaTrailCam.main(new String[]{args[1]});
                    return 0;
                default:
                    throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Java Trail interrupted");
            return 130;
        } catch (IllegalArgumentException e) {
            err.println("Java Trail: " + e.getMessage());
            usage(err);
            return 2;
        } catch (Exception e) {
            err.println("Java Trail: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return 1;
        }
    }

    private static void usage(PrintStream out) {
        out.println("Usage: java -jar java-trail-1.5.0-cli.jar <command>");
        out.println("  watch <class> <field> <host> <port>  Watch field changes until Ctrl+C");
        out.println("  trace <config.json>                 Trace methods to JSON");
        out.println("  --help                              Show this help");
    }
}
