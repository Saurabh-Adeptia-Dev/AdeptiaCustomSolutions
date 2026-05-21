package com.adeptia.custom.customExceptionHandling;

import com.adeptia.api.rest.spring.utils.BeanFactory;
import com.adeptia.indigo.logging.Logger;
import com.adeptia.indigo.plugin.ConnectPlugin;
import com.adeptia.indigo.plugin.PluginConstants;
import com.adeptia.indigo.processflow.action.impl.CustomTemplateLoggingAction;
import com.adeptia.indigo.system.Context;
import com.adeptia.indigo.transaction.Transaction;



/**
 * Drop-in replacement for the "customTemplateLoggingAction" Spring bean.
 *
 * Registered by {@link ExceptionMessageBeanConfig} via BeanDefinitionRegistryPostProcessor.
 *
 * Follows the same pattern as {@link CustomPFLoggingInConnectDBAction}: overrides onEnd
 * to inject the custom message before the final DB write.
 *
 * The parent CustomTemplateLoggingAction.onEnd() is guarded by (!transactionFailed) and
 * does nothing on error. We call persistExecutionErrorInfo() explicitly after overriding
 * "exceptionMessage" in context so the custom message is persisted. super.onEnd() is still
 * called last — it is a no-op for the failed case, so there is no double-write risk.
 *
 * By this point customTemplateExceptionAction has already run the BSH exception-handler
 * script, so "customExceptionMessage" is already in context when onEnd fires.
 */
public class CustomTemplateLoggingActionOverride extends CustomTemplateLoggingAction {

    @Override
    public void onEnd(Transaction transaction, Context context, Logger log,
                      boolean transactionFailed) throws Exception {
        // super runs first — for the failed case super is a no-op (parent guard: !transactionFailed),
        // but keeping it first mirrors the ProcessFlow pattern and is safe if parent ever changes.
        super.onEnd(transaction, context, log, transactionFailed);

        if (transactionFailed) {
            try {
                ExceptionMessageProvider provider =
                        (ExceptionMessageProvider) BeanFactory.getBean("exceptionMessageProvider");
                Exception currentException = (Exception) context.get("exception");
                String customMessage = provider.getCustomMessage(context, currentException, null);
                if (customMessage != null && !customMessage.trim().isEmpty()) {
                    log.info("CustomTemplateLoggingActionOverride: overriding exceptionMessage with: "
                            + customMessage);
                    context.put("exceptionMessage", customMessage);
                    ConnectPlugin connectPlugin =
                            (ConnectPlugin) BeanFactory.getBean(PluginConstants.CONNECT_PLUGIN);
                    connectPlugin.persistExecutionErrorInfo(context, log);
                }
            } catch (Exception providerEx) {
                log.warn("ExceptionMessageProvider/persistExecutionErrorInfo failed, using original message: "
                        + providerEx.getMessage());
            }
        }
    }
}
