package com.awslabs.aws.greengrass.provisioner.implementations.helpers;

import com.awslabs.aws.greengrass.provisioner.data.arguments.TestArguments;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.GlobalDefaultHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.IoHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.TestArgumentHelper;
import com.beust.jcommander.JCommander;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.List;

@Slf4j
public class BasicTestArgumentHelper implements TestArgumentHelper {
    @Inject
    GlobalDefaultHelper globalDefaultHelper;
    @Inject
    IoHelper ioHelper;

    @Override
    public void displayUsage() {
        TestArguments testArguments = new TestArguments();

        JCommander.newBuilder()
                .addObject(testArguments)
                .build()
                .usage();
    }

    @Override
    public TestArguments parseArguments(String[] args) {
        TestArguments testArguments = new TestArguments();

        JCommander.newBuilder()
                .addObject(testArguments)
                .build()
                .parse(args);

        if (!testArguments.isRequiredOptionSet()) {
            throw new RuntimeException("This is not a test request");
        }

        if (testArguments.groupName == null) {
            throw new RuntimeException("Group name is required for all operations");
        }

        if (testArguments.architectureString == null) {
            throw new RuntimeException("Architecture is required for all operations");
        }

        if (testArguments.user == null) {
            throw new RuntimeException("Username is required for all operations");
        }

        if (testArguments.outputDirectory == null) {
            throw new RuntimeException("Output directory is required for all operations");
        }

        if (testArguments.privateKeyPath == null) {
            List<String> privateKeyFiles = Try.of(() -> ioHelper.getPrivateKeyFilesForSsh()).get();

            if (privateKeyFiles.size() == 0) {
                throw new RuntimeException("No private key path specified and could not find any in the default location");
            }

            if (privateKeyFiles.size() != 1) {
                throw new RuntimeException(
                        String.format("Multiple private key files found [%s], you must manually select one",
                                String.join(", ", privateKeyFiles)));
            }

            testArguments.privateKeyPath = privateKeyFiles.get(0);
        }

        testArguments.architecture = getArchitecture(testArguments.architectureString);

        if (testArguments.clean && testArguments.generateConfig) {
            throw new RuntimeException("Can not clean /var/lib/GGQ and generate the config on the same run");
        }

        return testArguments;
    }
}
