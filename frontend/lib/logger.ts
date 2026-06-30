/**
 * Professional Logger
 * Replaces console.log with structured logging
 */

enum LogLevel {
    DEBUG = 0,
    INFO = 1,
    WARN = 2,
    ERROR = 3,
    NONE = 4,
}

interface LogEntry {
    level: LogLevel;
    message: string;
    data?: any;
    timestamp: Date;
    context?: Record<string, any>;
}

class Logger {
    private level: LogLevel;
    private isDevelopment: boolean;

    constructor() {
        this.isDevelopment = process.env.NODE_ENV === 'development';
        this.level = this.isDevelopment ? LogLevel.DEBUG : LogLevel.INFO;
    }

    /**
     * Set minimum log level
     */
    setLevel(level: LogLevel) {
        this.level = level;
    }

    /**
     * Format log entry
     */
    private format(entry: LogEntry): string {
        const timestamp = entry.timestamp.toISOString();
        const levelName = LogLevel[entry.level];
        return `[${timestamp}] ${levelName}: ${entry.message}`;
    }

    /**
     * Log to console (development) or send to logging service (production)
     */
    private log(entry: LogEntry) {
        if (entry.level < this.level) {
            return; // Skip if below minimum level
        }

        const formatted = this.format(entry);

        if (this.isDevelopment) {
            // Development: use console with colors
            switch (entry.level) {
                case LogLevel.DEBUG:
                    console.debug(formatted, entry.data || '');
                    break;
                case LogLevel.INFO:
                    console.info(formatted, entry.data || '');
                    break;
                case LogLevel.WARN:
                    console.warn(formatted, entry.data || '');
                    break;
                case LogLevel.ERROR:
                    console.error(formatted, entry.data || '', entry.context || '');
                    break;
            }
        } else {
            // Production: send to logging service
            // TODO: Integrate with your logging service (e.g., Sentry, LogRocket, etc.)
            this.sendToLoggingService(entry);
        }
    }

    /**
     * Send to external logging service (production)
     */
    private sendToLoggingService(entry: LogEntry) {
        // TODO: Implement integration with logging service
        // Example: Sentry, LogRocket, Datadog, etc.

        // For now, just use console.error for errors in production
        if (entry.level === LogLevel.ERROR) {
            console.error(this.format(entry), entry.data, entry.context);
        }
    }

    /**
     * Debug log (verbose, development only)
     */
    debug(message: string, data?: any) {
        this.log({
            level: LogLevel.DEBUG,
            message,
            data,
            timestamp: new Date(),
        });
    }

    /**
     * Info log (general information)
     */
    info(message: string, data?: any) {
        this.log({
            level: LogLevel.INFO,
            message,
            data,
            timestamp: new Date(),
        });
    }

    /**
     * Warning log (potential issues)
     */
    warn(message: string, data?: any) {
        this.log({
            level: LogLevel.WARN,
            message,
            data,
            timestamp: new Date(),
        });
    }

    /**
     * Error log (errors that need attention)
     */
    error(message: string, data?: any, context?: Record<string, any>) {
        this.log({
            level: LogLevel.ERROR,
            message,
            data,
            context,
            timestamp: new Date(),
        });
    }

    /**
     * Create a scoped logger with context
     */
    scope(scopeName: string) {
        return {
            debug: (message: string, data?: any) =>
                this.debug(`[${scopeName}] ${message}`, data),
            info: (message: string, data?: any) =>
                this.info(`[${scopeName}] ${message}`, data),
            warn: (message: string, data?: any) =>
                this.warn(`[${scopeName}] ${message}`, data),
            error: (message: string, data?: any, context?: Record<string, any>) =>
                this.error(`[${scopeName}] ${message}`, data, context),
        };
    }
}

// Export singleton instance
export const logger = new Logger();

// Export scoped loggers for common modules
export const conversationLogger = logger.scope('Conversation');
