package com.adeptia.custom.customExceptionHandling;

import com.adeptia.api.rest.spring.utils.BeanFactory;
import com.adeptia.indigo.logging.Logger;
import com.adeptia.indigo.plugin.ConnectPlugin;
import com.adeptia.indigo.plugin.PluginConstants;
import com.adeptia.indigo.processflow.action.impl.PFLoggingInConnectDBAction;
import com.adeptia.indigo.services.Service;
import com.adeptia.indigo.system.Context;
import com.adeptia.indigo.transaction.Transaction;

/**
 * Drop-in replacement for the "pfLoggingInConnectDBAction" Spring bean.
 *
 * Registered by {@link ExceptionMessageBeanConfig} via BeanDefinitionRegistryPostProcessor
 * so no existing XML or Java source file is modified.
 *
 * WHY onEnd and not onException:
 *   finalizeRuntimeFlowForError (called inside super.onException) uses
 *   ExceptionUtils.getRootCause() to traverse the full exception chain, so
 *   wrapping the exception with a new message is ignored — the root-cause
 *   message is always used. The DB write also happens inside that call, making
 *   any post-super.onException context edits too late.
 *
 *   onEnd(transactionFailed=true) runs after onException and calls
 *   connectPlugin.persistExecutionErrorInfo(), which reads the "exceptionMessage"
 *   key from context (RuntimeConstants.CUSTOM_EXCEPTION_MESSAGE = "exceptionMessage").
 *   finalizeRuntimeFlow (called just before persistExecutionErrorInfo in onEnd)
 *   does NOT touch "exceptionMessage", so setting it here is safe and is picked
 *   up by the subsequent DB write.
 */
public class CustomPFLoggingInConnectDBAction extends PFLoggingInConnectDBAction {

    @Override
    public void onException(Transaction transaction, Context context, Logger log,
                            Exception e, Service service) throws Exception {
        super.onException(transaction, context, log, e, service);
    }

    @Override
    public void onEnd(Transaction transaction, Context context, Logger log,
                      boolean transactionFailed) throws Exception {
        // super runs first — guarantees transaction status is set to ABORTED before
        // we do anything, so a failure in our override can never leave it stuck in RUNNING.
        super.onEnd(transaction, context, log, transactionFailed);

        if (transactionFailed) {
            try {
                ExceptionMessageProvider provider =
                        (ExceptionMessageProvider) BeanFactory.getBean("exceptionMessageProvider");
                Exception currentException = (Exception) context.get("exception");
                String customMessage = provider.getCustomMessage(context, currentException, null);
                if (customMessage != null && !customMessage.trim().isEmpty()) {
                    log.info("CustomPFLoggingInConnectDBAction: overriding exceptionMessage with: "
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
