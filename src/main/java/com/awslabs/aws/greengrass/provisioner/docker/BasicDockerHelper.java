package com.awslabs.aws.greengrass.provisioner.docker;

import com.awslabs.aws.greengrass.provisioner.data.Architecture;
import com.awslabs.aws.greengrass.provisioner.docker.interfaces.DockerClientProvider;
import com.awslabs.aws.greengrass.provisioner.docker.interfaces.DockerHelper;
import com.github.dockerjava.api.exception.DockerException;
import lombok.extern.slf4j.Slf4j;
import org.newsclub.net.unix.AFUNIXSocketException;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.CreateRepositoryRequest;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesRequest;
import software.amazon.awssdk.services.ecr.model.DescribeRepositoriesResponse;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;

import javax.inject.Inject;
import javax.ws.rs.ProcessingException;
import java.io.File;
import java.util.Optional;

@Slf4j
public class BasicDockerHelper implements DockerHelper {
    public static String dockerFileDirectory = String.join("/",
            "foundation",
            "docker");
    @Inject
    DockerClientProvider dockerClientProvider;
    @Inject
    EcrClient ecrClient;
    private Optional<String> ecrRepositoryName;
    private Optional<String> ecrImageName;

    @Inject
    public BasicDockerHelper() {
    }

    @Override
    public File getDockerfileForArchitecture(Architecture architecture) {
        String dockerfileName = String.join(".",
                "Dockerfile",
                architecture.toString());

        return new File(String.join("/",
                dockerFileDirectory,
                dockerfileName));
    }

    @Override
    public boolean isDockerAvailable() {
        try {
            dockerClientProvider.get().listImagesCmd().exec();
        } catch (DockerException e) {
            log.error("Failed to connect to Docker.  Is it running on this host?  Is it listening on the standard Unix socket?");
            log.error("Docker exception message [" + e.getMessage() + "]");

            return false;
        } catch (ProcessingException e) {
            if (e.getCause() instanceof AFUNIXSocketException) {
                log.error("Failed to connect to Docker on the Unix socket.  It is running on this host?");
                log.error("UNIX socket exception [" + e.getMessage() + "]");
                return false;
            }

            // Unknown exception
            throw new UnsupportedOperationException(e);
        }

        return true;
    }

    @Override
    public void setEcrRepositoryName(Optional<String> ecrRepositoryName) {
        this.ecrRepositoryName = ecrRepositoryName;
    }

    @Override
    public void createEcrRepositoryIfNecessary() {
        try {
            DescribeRepositoriesResponse describeRepositoriesResponse = ecrClient.describeRepositories(
                    DescribeRepositoriesRequest.builder()
                            .repositoryNames(ecrRepositoryName.get())
                            .build());

            if (describeRepositoriesResponse.repositories().size() == 1) {
                log.info("ECR repository [" + ecrRepositoryName.get() + "] already exists");
                return;
            }

            log.info("More than one repository found that matched [" + ecrRepositoryName.get() + "], cannot continue");
            throw new UnsupportedOperationException("More than one matching ECR repository");
        } catch (RepositoryNotFoundException e) {
            log.info("Creating ECR repository [" + ecrRepositoryName.get() + "]");
            ecrClient.createRepository(CreateRepositoryRequest.builder()
                    .repositoryName(ecrRepositoryName.get())
                    .build());
        }
    }

    @Override
    public void setEcrImageName(Optional<String> ecrImageName) {
        this.ecrImageName = ecrImageName;
    }

    @Override
    public String getImageName() {
        return String.join(":", ecrRepositoryName.get(), ecrImageName.get());
    }

    @Override
    public String getEcrProxyEndpoint() {
        return dockerClientProvider.getAuthorizationData().proxyEndpoint();
    }
}
