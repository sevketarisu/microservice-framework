package uk.gov.justice.api;

import static javax.json.Json.createObjectBuilder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static uk.gov.justice.services.core.annotation.Component.COMMAND_API;
import static uk.gov.justice.services.messaging.JsonObjectMetadata.ID;
import static uk.gov.justice.services.messaging.JsonObjectMetadata.metadataFrom;

import uk.gov.justice.services.core.annotation.ServiceComponent;
import uk.gov.justice.services.core.dispatcher.DispatcherProducer;
import uk.gov.justice.services.core.jms.JmsDestinations;
import uk.gov.justice.services.core.jms.JmsSenderFactory;
import uk.gov.justice.services.core.sender.ComponentDestination;
import uk.gov.justice.services.core.sender.Sender;
import uk.gov.justice.services.core.sender.SenderProducer;
import uk.gov.justice.services.messaging.DefaultJsonEnvelope;
import uk.gov.justice.services.messaging.JsonEnvelope;
import uk.gov.justice.test.util.RecordingJmsEnvelopeSender;

import java.util.List;
import java.util.Properties;
import java.util.UUID;

import javax.inject.Inject;

import org.apache.openejb.OpenEjbContainer;
import org.apache.openejb.jee.WebApp;
import org.apache.openejb.junit.ApplicationComposer;
import org.apache.openejb.testing.Classes;
import org.apache.openejb.testing.Configuration;
import org.apache.openejb.testing.Module;
import org.apache.openejb.testng.PropertiesBuilder;
import org.apache.openejb.util.NetworkUtil;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(ApplicationComposer.class)
@ServiceComponent(COMMAND_API)
public class RemoteCommandControllerIT {
    private static int port = -1;

    @BeforeClass
    public static void beforeClass() {
        port = NetworkUtil.getNextAvailablePort();
    }

    @Configuration
    public Properties properties() {
        return new PropertiesBuilder()
                .property("httpejbd.port", Integer.toString(port))
                .property(OpenEjbContainer.OPENEJB_EMBEDDED_REMOTABLE, "true")
                .build();
    }
    
    @Inject
    Sender sender;

    @Inject
    RecordingJmsEnvelopeSender envelopeSender;

    @Module
    @Classes(cdi = true, value = {
            RecordingJmsEnvelopeSender.class,
            SenderProducer.class,
            ComponentDestination.class,
            JmsSenderFactory.class,
            DispatcherProducer.class,
            JmsDestinations.class,
            RemoteContextaCommandController.class
    })
    public WebApp war() {
        return new WebApp()
                .contextRoot("jms-endpoint-test");
    }

    @Before
    public void setUp() throws Exception {
        envelopeSender.init();
    }

    @Test
    public void shouldPassEnvelopeToEnvelopeSender() throws Exception {
        final String name = "contexta.commanda";
        final UUID id = UUID.randomUUID();
        sender.send(DefaultJsonEnvelope.envelopeFrom(metadataFrom(
                createObjectBuilder()
                        .add(ID, id.toString())
                        .add("name", name)
                        .build()),null));

        final List<JsonEnvelope> sentEnvelopes = envelopeSender.envelopesSentTo("contexta.controller.command");
        assertThat(sentEnvelopes, hasSize(1));
        assertThat(sentEnvelopes.get(0).metadata().name(), is(name));
        assertThat(sentEnvelopes.get(0).metadata().id(), is(id));
    }
}