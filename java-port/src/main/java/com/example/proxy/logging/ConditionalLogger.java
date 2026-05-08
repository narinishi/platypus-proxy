package com.example.proxy.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

public class ConditionalLogger {
    public static final int CRITICAL = 50;
    public static final int ERROR = 40;
    public static final int WARNING = 30;
    public static final int INFO = 20;
    public static final int DEBUG = 10;
    public static final int NOTSET = 0;

    private final Logger logger;
    private final int verbosity;

    public ConditionalLogger(Logger logger, int verbosity) {
        this.logger = logger;
        this.verbosity = verbosity;
    }

    public void log(int verb, String format, Object... args) {
        if (verb >= verbosity) {
            String msg = format(format, args);
            Level level;
            switch (verb) {
                case CRITICAL:
                case ERROR:
                    level = Level.SEVERE;
                    break;
                case WARNING:
                    level = Level.WARNING;
                    break;
                case INFO:
                    level = Level.INFO;
                    break;
                case DEBUG:
                default:
                    level = Level.FINE;
                    break;
            }
            logger.log(level, msg);
        }
    }

    public void Critical(String s, Object... args) {
        log(CRITICAL, s, args);
    }

    public void Error(String s, Object... args) {
        log(ERROR, s, args);
    }

    public void Warning(String s, Object... args) {
        log(WARNING, s, args);
    }

    public void Info(String s, Object... args) {
        log(INFO, s, args);
    }

    public void Debug(String s, Object... args) {
        log(DEBUG, s, args);
    }

    private String format(String fmt, Object[] args) {
        if (args == null || args.length == 0) {
            return fmt;
        }
        try {
            return String.format(fmt, args);
        } catch (Exception e) {
            return fmt;
        }
    }
}