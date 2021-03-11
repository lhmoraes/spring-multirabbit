package org.springframework.boot.autoconfigure.amqp;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.MultiRabbitBootstrapConfiguration;
import org.springframework.amqp.rabbit.config.AbstractRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.connection.SimpleRoutingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration.RabbitConnectionFactoryCreator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

/**
 * Class responsible for auto-configuring the necessary beans to enable multiple RabbitMQ servers.
 */
@Configuration
@ConditionalOnClass({RabbitTemplate.class, Channel.class})
@EnableConfigurationProperties({RabbitProperties.class, MultiRabbitPropertiesMap.class})
@Import({MultiRabbitBootstrapConfiguration.class, RabbitAutoConfiguration.class})
public class MultiRabbitAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiRabbitAutoConfiguration.class);

    /**
     * Returns a {@link RabbitAutoConfiguration.RabbitConnectionFactoryCreator}.
     *
     * @return a {@link RabbitAutoConfiguration.RabbitConnectionFactoryCreator}.
     */
    @Primary
    @Bean(MultiRabbitConstants.CONNECTION_FACTORY_CREATOR_BEAN_NAME)
    public RabbitConnectionFactoryCreator rabbitConnectionFactoryCreator() {
        return new RabbitConnectionFactoryCreator();
    }

    /**
     * Class to set up multirabbit beans.
     */
    @Configuration
    @DependsOn(MultiRabbitConstants.CONNECTION_FACTORY_CREATOR_BEAN_NAME)
    protected static class MultiRabbitConnectionFactoryCreator implements BeanFactoryAware, ApplicationContextAware {

        private static final String SUSPICIOUS_CONFIGURATION = "Potential issue with MultiRabbitMQ configuration: At "
                + "least two ConnectionFactories were set as default: multirabbit connection '{}' and external "
                + "configuration '{}'. Defining external configuration as the default.";
        private ConfigurableListableBeanFactory beanFactory;
        private ApplicationContext applicationContext;
        private final RabbitConnectionFactoryCreator springFactoryCreator;
        private final ObjectProvider<ConnectionNameStrategy> connectionNameStrategy;

        MultiRabbitConnectionFactoryCreator(final RabbitConnectionFactoryCreator springFactoryCreator,
                                            final ObjectProvider<ConnectionNameStrategy> connectionNameStrategy) {
            this.springFactoryCreator = springFactoryCreator;
            this.connectionNameStrategy = connectionNameStrategy;
        }

        @Bean
        public ConnectionFactoryContextWrapper contextWrapper(final ConnectionFactory connectionFactory) {
            return new ConnectionFactoryContextWrapper(connectionFactory);
        }

        @Bean
        @ConditionalOnMissingBean
        public MultiRabbitConnectionFactoryWrapper externalEmptyWrapper() {
            return new MultiRabbitConnectionFactoryWrapper();
        }

        @Primary
        @Bean(MultiRabbitConstants.CONNECTION_FACTORY_BEAN_NAME)
        public ConnectionFactory routingConnectionFactory(final RabbitProperties springRabbitProperties,
                                                          final MultiRabbitPropertiesMap multiRabbitPropertiesMap,
                                                          final MultiRabbitConnectionFactoryWrapper externalWrapper) {
            final MultiRabbitConnectionFactoryWrapper aggregatedWrapper
                    = multiRabbitConnectionWrapper(springRabbitProperties, multiRabbitPropertiesMap);
            externalWrapper.getConnectionFactories().keySet().forEach(key -> aggregatedWrapper.addConnectionFactory(
                    String.valueOf(key),
                    externalWrapper.getConnectionFactories().get(key),
                    externalWrapper.getContainerFactories().get(key),
                    externalWrapper.getRabbitAdmins().get(key)));
            if (externalWrapper.getDefaultConnectionFactory() != null) {
                asStream(multiRabbitPropertiesMap)
                        .filter(prop -> prop.getValue().isDefaultConnection())
                        .findFirst()
                        .ifPresent(prop -> LOGGER.warn(SUSPICIOUS_CONFIGURATION, prop.getKey(),
                                externalWrapper.getDefaultConnectionFactory()));
                aggregatedWrapper.setDefaultConnectionFactory(externalWrapper.getDefaultConnectionFactory());
            }
            aggregatedWrapper.getContainerFactories().forEach(this::registerContainerFactoryBean);
            aggregatedWrapper.getRabbitAdmins().forEach(this::registerRabbitAdminBean);

            final SimpleRoutingConnectionFactory connectionFactory = new SimpleRoutingConnectionFactory();
            connectionFactory.setTargetConnectionFactories(aggregatedWrapper.getConnectionFactories());
            connectionFactory.setDefaultTargetConnectionFactory(aggregatedWrapper.getDefaultConnectionFactory());
            return connectionFactory;
        }

        /**
         * Returns internal wrapper with default connection factories.
         */
        private MultiRabbitConnectionFactoryWrapper multiRabbitConnectionWrapper(
                final RabbitProperties springRabbitProperties,
                final MultiRabbitPropertiesMap multiRabbitPropertiesMap) {
            final Map<String, ConnectionFactory> connectionFactoryMap = asStream(multiRabbitPropertiesMap)
                    .collect(toMap(Map.Entry::getKey, entry -> instantiateConnectionFactory(entry.getValue())));

            final MultiRabbitConnectionFactoryWrapper wrapper = new MultiRabbitConnectionFactoryWrapper();
            connectionFactoryMap.forEach((key, value) -> wrapper
                    .addConnectionFactory(key, value, newContainerFactory(value), newRabbitAdmin(value)));
            wrapper.setDefaultConnectionFactory(asStream(multiRabbitPropertiesMap)
                    .filter(prop -> prop.getValue().isDefaultConnection())
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .map(connectionFactoryMap::get)
                    .orElse(instantiateConnectionFactory(springRabbitProperties)));
            return wrapper;
        }

        /**
         * Registers the ContainerFactory bean.
         */
        private SimpleRabbitListenerContainerFactory newContainerFactory(final ConnectionFactory connectionFactory) {
            final SimpleRabbitListenerContainerFactory containerFactory = new SimpleRabbitListenerContainerFactory();
            containerFactory.setConnectionFactory(connectionFactory);
            return containerFactory;
        }

        /**
         * Register the RabbitAdmin bean (to enable context changing with Rabbit annotations).
         */
        private RabbitAdmin newRabbitAdmin(final ConnectionFactory connectionFactory) {
            return new RabbitAdmin(connectionFactory);
        }

        /**
         * Method to call default Spring factory creator and suppress a possible Exception into a RuntimeException.
         * The suppression of the Exception will not affect the flow, since Spring will still stop its initialization
         * in the event of any RuntimeException.
         */
        private CachingConnectionFactory instantiateConnectionFactory(final RabbitProperties rabbitProperties) {
            try {
                return springFactoryCreator.rabbitConnectionFactory(rabbitProperties, connectionNameStrategy);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        /**
         * Null-safe method to return a {@link Stream} of the EntrySet of a {@link Map}.
         *
         * @param <T> The generic type of the expected map.
         * @return A {@link Stream} with all elements from the {@link Map} or empty.
         */
        private static <T, U> Stream<Map.Entry<T, U>> asStream(final Map<T, U> map) {
            return Optional.ofNullable(map)
                    .map(Map::entrySet)
                    .map(Collection::stream)
                    .orElse(Stream.empty());
        }

        /**
         * Registers the ContainerFactory bean.
         */
        private void registerContainerFactoryBean(final String name,
                                                  final AbstractRabbitListenerContainerFactory<?> containerFactory) {
            beanFactory.registerSingleton(name, containerFactory);
        }

        /**
         * Register the RabbitAdmin bean (to enable context changing with Rabbit annotations).
         */
        private void registerRabbitAdminBean(final String name, final RabbitAdmin rabbitAdmin) {
            final String beanName = name + MultiRabbitConstants.RABBIT_ADMIN_SUFFIX;
            rabbitAdmin.setApplicationContext(applicationContext);
            rabbitAdmin.afterPropertiesSet();
            rabbitAdmin.setBeanName(beanName);
            beanFactory.registerSingleton(beanName, rabbitAdmin);
        }

        @Override
        public void setBeanFactory(final BeanFactory beanFactory) {
            this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
        }

        @Override
        public void setApplicationContext(final ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }
    }
}