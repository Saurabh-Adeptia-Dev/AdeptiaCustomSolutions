package com.adeptia.custom.customExceptionHandling;

import com.adeptia.indigo.services.Service;
import com.adeptia.indigo.system.Context;

/**
 * SPI for overriding the exception message persisted during process-flow error handling.
 *
 * Implement this interface and register the implementation as the Spring bean named
 * "exceptionMessageProvider" to replace the default behavior.
 *
 * The default implementation ({@link DefaultExceptionMessageProvider}) reads the context
 * variable "customExceptionMessage".  Set that variable anywhere in the process flow
 * (e.g. an exception-handler BSH script) before the error is finalized:
 *
 *   context.put("customExceptionMessage", "My user-facing error text");
 */
public interface ExceptionMessageProvider {

    /**
     * Return a custom message to replace the one derived from {@code original},
     * or {@code null} / empty string to keep the original exception message.
     */
    String getCustomMessage(Context context, Exception original, Service service);
}
