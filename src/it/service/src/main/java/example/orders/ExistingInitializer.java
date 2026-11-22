package example.orders;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

public class ExistingInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.getBeanFactory().registerSingleton("existingInitializerRan", Boolean.TRUE);
    }
}
