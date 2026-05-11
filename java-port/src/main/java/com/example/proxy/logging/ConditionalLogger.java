package com.example.proxy.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

public class ConditionalLogger {
    private static final int CRITICAL = 50;
    private static final int ERROR = 40;
    private static final int WARNING = 30;
    private static final int INFO = 20;
    private static final int DEBUG = 10;

    @SuppressWarnings("unused")
    private static final int NOTSET = 0;

    private final AsyncLog logger;
    private final int verbosity;

    public ConditionalLogger(Logger logger, int verbosity) {
        this.logger = new AsyncLog(logger);
        this.verbosity = verbosity;
    }

    @SuppressWarnings("unused")
    @Deprecated
    private void log(int verb, String format, Object... args) {
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
        if (CRITICAL >= verbosity) {
            logger.log(Level.SEVERE, format(s, args));
        }
    }

    public void Error(String s, Object... args) {
        if (ERROR >= verbosity) {
            logger.log(Level.SEVERE, format(s, args));
        }
    }

    public void Warning(String s, Object... args) {
        if (WARNING >= verbosity) {
            logger.log(Level.WARNING, format(s, args));
        }
    }

    public void Info(String s, Object... args) {
        if (INFO >= verbosity) {
            logger.log(Level.INFO, format(s, args));
        }
    }

    public void Debug(String s, Object... args) {
        if (DEBUG >= verbosity) {
            logger.log(Level.FINE, format(s, args));
        }
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