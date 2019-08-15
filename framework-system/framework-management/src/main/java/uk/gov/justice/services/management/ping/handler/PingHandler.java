package uk.gov.justice.services.management.ping.handler;

import static uk.gov.justice.services.jmx.api.command.PingSystemCommand.PING;

import uk.gov.justice.services.common.util.UtcClock;
import uk.gov.justice.services.jmx.api.command.PingSystemCommand;
import uk.gov.justice.services.jmx.command.HandlesSystemCommand;

import javax.inject.Inject;

import org.slf4j.Logger;

public class PingHandler {

    @Inject
    private Logger logger;

    @Inject
    private UtcClock clock;

    @HandlesSystemCommand(PING)
    public void ping(@SuppressWarnings("unused") final PingSystemCommand pingSystemCommand) {
        logger.info("********** Received system command '" + PING + "' at " + clock.now() + " **********");
    }
}