package com.bondplatform.dataprocessing.persistence;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

/**
 * Removes the deployed NSDL handler from a test application, for tests that supply their own
 * handler for the NSDL dataset. A job's dataset has exactly one handler, so two would not start.
 */
public final class WithoutNsdlHandler implements BeanDefinitionRegistryPostProcessor {

  private static final String NSDL_HANDLER = "nsdlHandler";

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    registry.removeBeanDefinition(NSDL_HANDLER);
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    // Nothing to change once the definitions are registered.
  }
}
