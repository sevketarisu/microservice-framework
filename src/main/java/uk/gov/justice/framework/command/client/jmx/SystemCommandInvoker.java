package uk.gov.justice.framework.command.client.jmx;

import uk.gov.justice.framework.command.client.io.ToConsolePrinter;
import uk.gov.justice.services.jmx.api.command.SystemCommand;
import uk.gov.justice.services.jmx.api.mbean.SystemCommanderMBean;
import uk.gov.justice.services.jmx.system.command.client.SystemCommanderClient;
import uk.gov.justice.services.jmx.system.command.client.SystemCommanderClientFactory;
import uk.gov.justice.services.jmx.system.command.client.connection.JmxAuthenticationException;
import uk.gov.justice.services.jmx.system.command.client.connection.JmxParameters;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class SystemCommandInvoker {

    @Inject
    private SystemCommanderClientFactory systemCommanderClientFactory;

    @Inject
    private ToConsolePrinter toConsolePrinter;

    public void runSystemCommand(final SystemCommand systemCommand, final JmxParameters jmxParameters) {

        final String commandName = systemCommand.getName();
        final String contextName = jmxParameters.getContextName();

        toConsolePrinter.printf("Running system command '%s'", commandName);
        toConsolePrinter.printf("Connecting to %s context at '%s' on port %d", contextName, jmxParameters.getHost(), jmxParameters.getPort());

        jmxParameters.getCredentials().ifPresent(credentials -> toConsolePrinter.printf("Connecting with credentials for user '%s'", credentials.getUsername()));

        try (final SystemCommanderClient systemCommanderClient = systemCommanderClientFactory.create(jmxParameters)) {
            final SystemCommanderMBean systemCommanderMBean = systemCommanderClient.getRemote(contextName);

            toConsolePrinter.printf("Connected to %s context", contextName);

            systemCommanderMBean.call(systemCommand);

            toConsolePrinter.printf("System command '%s' successfully sent to %s", commandName, contextName);

        } catch (final JmxAuthenticationException e) {
            toConsolePrinter.println("Authentication failed. Please ensure your username and password are correct");
        } 
    }
}