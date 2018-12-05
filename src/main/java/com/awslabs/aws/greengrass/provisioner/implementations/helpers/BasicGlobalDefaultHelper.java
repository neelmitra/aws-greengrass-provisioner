package com.awslabs.aws.greengrass.provisioner.implementations.helpers;

import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.GlobalDefaultHelper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import javax.inject.Inject;
import java.io.File;
import java.util.Optional;

public class BasicGlobalDefaultHelper implements GlobalDefaultHelper {
    public static final String USER_HOME = "user.home";

    @Inject
    public BasicGlobalDefaultHelper() {
    }

    @Override
    public Optional<Config> getGlobalDefaults(String filename) {
        Optional<String> homeDirectory = getHomeDirectory();

        if (!homeDirectory.isPresent()) {
            return Optional.empty();
        }

        File file = new File(String.join("/", homeDirectory.get(), ".ggprovisioner", filename));

        if (!file.exists()) {
            return Optional.empty();
        }

        Config defaults = ConfigFactory.parseFile(file);

        return Optional.of(defaults);
    }

    @Override
    public Optional<String> getHomeDirectory() {
        return Optional.ofNullable(System.getProperty(USER_HOME));
    }
}
