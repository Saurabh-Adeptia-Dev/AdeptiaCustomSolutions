package com.adeptia.custom.customExceptionHandling;

import com.adeptia.indigo.services.Service;
import com.adeptia.indigo.system.Context;

/**
 * Default implementation of {@link ExceptionMessageProvider}.
 *
 * Reads the context variable "customExceptionMessage".
 * Returns null when the variable is absent, which preserves the original exception message.
 *
 * To override the message from a process-flow script:
 *   context.put("customExceptionMessage", "Your custom error text here");
 *
 * To override programmatically, replace this bean by providing a different
 * ExceptionMessageProvider @Bean named "exceptionMessageProvider" in your own
 * @Configuration class (mark it @Primary or rely on bean override order).
 */
public class DefaultExceptionMessageProvider implements ExceptionMessageProvider {

    static final String CTX_KEY = "customExceptionMessage";

    @Override
    public String getCustomMessage(Context context, Exception original, Service service) {
        Object value = context.get(CTX_KEY);
        if (value instanceof String msg && !msg.trim().isEmpty()) {
            return msg;
        }
        return null;
    }
}
