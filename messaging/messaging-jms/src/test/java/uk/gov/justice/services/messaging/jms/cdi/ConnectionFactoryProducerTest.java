package uk.gov.justice.services.messaging.jms.cdi;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import uk.gov.justice.services.cdi.QualifierAnnotationExtractor;
import uk.gov.justice.services.messaging.jms.annotation.ConnectionFactoryName;

import javax.enterprise.inject.spi.InjectionPoint;
import javax.jms.ConnectionFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ConnectionFactoryProducerTest {

    @Mock
    private QualifierAnnotationExtractor qualifierAnnotationExtractor;

    @Mock
    private JmsConnectionFactoryJndiNameProvider jmsConnectionFactoryJndiNameProvider;

    @Mock
    private JmsConnectionFactoryCache jmsConnectionFactoryCache;

    @InjectMocks
    private ConnectionFactoryProducer connectionFactoryProducer;

    @Test
    public void shouldGetTheDefaultJmsConnectionFactory() throws Exception {
        final String defaultConnectionFactoryName = "defaultConnectionFactoryName";

        final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        when(jmsConnectionFactoryJndiNameProvider
                .defaultConnectionFactoryJndiName()).thenReturn(defaultConnectionFactoryName);
        when(jmsConnectionFactoryCache.getConnectionFactory(defaultConnectionFactoryName))
                .thenReturn(connectionFactory);

        assertThat(connectionFactoryProducer.connectionFactory(), is(connectionFactory));
    }

    @Test
    public void shouldGetJmsConnectionFactoryUsingAnAnnotation() throws Exception {

        final String connectionFactoryJndiName = "connectionFactoryJndiName";
        final InjectionPoint injectionPoint = mock(InjectionPoint.class);
        final ConnectionFactoryName connectionFactoryName = mock(ConnectionFactoryName.class);
        final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        when(qualifierAnnotationExtractor.getFrom(injectionPoint, ConnectionFactoryName.class))
                .thenReturn(connectionFactoryName);
        when(jmsConnectionFactoryJndiNameProvider.determineConnectionFactoryName(connectionFactoryName))
                .thenReturn(connectionFactoryJndiName);
        when(jmsConnectionFactoryCache.getConnectionFactory(connectionFactoryJndiName))
                .thenReturn(connectionFactory);

        assertThat(connectionFactoryProducer.connectionFactory(injectionPoint), is(connectionFactory));
    }
}
