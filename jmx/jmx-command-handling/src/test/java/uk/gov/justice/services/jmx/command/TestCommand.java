package uk.gov.justice.services.jmx.command;

public class TestCommand extends BaseSystemCommand {

    public static final String TEST_COMMAND = "TEST_COMMAND";

    public TestCommand() {
        super(TEST_COMMAND, "This is a command used for testing");
    }
}