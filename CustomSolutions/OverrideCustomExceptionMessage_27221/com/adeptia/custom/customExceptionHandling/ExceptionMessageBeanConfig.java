package com.adeptia.custom.customExceptionHandling;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the custom exception-message override infrastructure without touching any
 * existing source file.
 *
 * Two things happen here:
 *
 * 1. "exceptionMessageProvider" bean is registered.
 *    Replace DefaultExceptionMessageProvider with your own ExceptionMessageProvider
 *    implementation to apply any custom logic (e.g. message lookup table, tenant-aware
 *    messages, masking sensitive stack traces, etc.).
 *
 * 2. The XML-defined "pfLoggingInConnectDBAction" bean is replaced at startup with
 *    CustomPFLoggingInConnectDBAction via a BeanDefinitionRegistryPostProcessor.
 *    Because the replacement extends the original class and only adds an override for
 *    onException, all other behaviour (onStart / onEnd) is identical.
 */
@Configuration
public class ExceptionMessageBeanConfig {

    @Bean("exceptionMessageProvider")
    public ExceptionMessageProvider exceptionMessageProvider() {
        return new DefaultExceptionMessageProvider();
    }

    /**
     * static @Bean is required so Spring instantiates the BeanDefinitionRegistryPostProcessor
     * before the enclosing @Configuration class is fully initialized.
     * This guarantees the bean-definition override runs after the XML import but
     * before any bean is instantiated.
     */
    @Bean
    public static BeanDefinitionRegistryPostProcessor pfLoggingActionOverrider() {
        return new BeanDefinitionRegistryPostProcessor() {

            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
                    throws BeansException {
                // Remove the XML-defined beans first so we don't hit Spring Boot's
                // allowBeanDefinitionOverriding=false guard (default since 2.1).
                replaceBean(registry, "pfLoggingInConnectDBAction",
                        CustomPFLoggingInConnectDBAction.class);
                replaceBean(registry, "customTemplateLoggingAction",
                        CustomTemplateLoggingActionOverride.class);
            }

            private void replaceBean(BeanDefinitionRegistry registry, String beanName,
                                     Class<?> replacement) {
                if (registry.containsBeanDefinition(beanName)) {
                    registry.removeBeanDefinition(beanName);
                }
                GenericBeanDefinition def = new GenericBeanDefinition();
                def.setBeanClass(replacement);
                def.setLazyInit(true);
                registry.registerBeanDefinition(beanName, def);
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
                    throws BeansException {
                // nothing needed here
            }
        };
    }
}
