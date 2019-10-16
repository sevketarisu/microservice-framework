package uk.gov.justice.framework.command.client.jmx;

import static uk.gov.justice.services.jmx.api.domain.CommandState.COMMAND_COMPLETE;
import static uk.gov.justice.services.jmx.api.domain.CommandState.COMMAND_FAILED;

import uk.gov.justice.framework.command.client.io.ToConsolePrinter;
import uk.gov.justice.services.jmx.api.SystemCommandFailedException;
import uk.gov.justice.services.jmx.api.UnsupportedSystemCommandException;
import uk.gov.justice.services.jmx.api.command.SystemCommand;
import uk.gov.justice.services.jmx.api.domain.CommandState;
import uk.gov.justice.services.jmx.api.domain.SystemCommandStatus;
import uk.gov.justice.services.jmx.api.mbean.SystemCommanderMBean;
import uk.gov.justice.services.jmx.system.command.client.SystemCommanderClient;
import uk.gov.justice.services.jmx.system.command.client.SystemCommanderClientFactory;
import uk.gov.justice.services.jmx.system.command.client.connection.JmxParameters;

import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class SystemCommandInvoker {

    @Inject
    private SystemCommanderClientFactory systemCommanderClientFactory;

    @Inject
    private CommandPoller commandPoller;

    @Inject
    private ToConsolePrinter toConsolePrinter;

    public void runSystemCommand(final SystemCommand systemCommand, final JmxParameters jmxParameters) {

        final String commandName = systemCommand.getName();
        final String contextName = jmxParameters.getContextName();

        toConsolePrinter.printf("Running system command '%s'", commandName);
        toConsolePrinter.printf("Connecting to %s context at '%s' on port %d", contextName, jmxParameters.getHost(), jmxParameters.getPort());

        jmxParameters.getCredentials().ifPresent(credentials -> toConsolePrinter.printf("Connecting with credentials for user '%s'", credentials.getUsername()));

        try (final SystemCommanderClient systemCommanderClient = systemCommanderClientFactory.create(jmxParameters)) {

            toConsolePrinter.printf("Connected to %s context", contextName);

            final SystemCommanderMBean systemCommanderMBean = systemCommanderClient.getRemote(contextName);
            final UUID commandId = systemCommanderMBean.call(systemCommand);
            toConsolePrinter.printf("System command '%s' with id '%s' successfully sent to %s", commandName, commandId, contextName);
            commandPoller.runUntilComplete(systemCommanderMBean, commandId, systemCommand);

        } catch (final UnsupportedSystemCommandException e) {
            toConsolePrinter.printf("The command '%s' is not supported on this %s context", commandName, contextName);
            throw e;
        } catch (final SystemCommandFailedException e) {
            toConsolePrinter.printf("The command '%s' failed: %s", e.getMessage(), commandName);
            toConsolePrinter.println(e.getServerStackTrace());
            throw e;
        }
    }
}