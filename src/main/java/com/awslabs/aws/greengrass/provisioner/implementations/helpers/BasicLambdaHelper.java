package com.awslabs.aws.greengrass.provisioner.implementations.helpers;

import com.awslabs.aws.greengrass.provisioner.data.LambdaFunctionArnInfo;
import com.awslabs.aws.greengrass.provisioner.data.conf.FunctionConf;
import com.awslabs.aws.greengrass.provisioner.interfaces.builders.GradleBuilder;
import com.awslabs.aws.greengrass.provisioner.interfaces.builders.MavenBuilder;
import com.awslabs.aws.greengrass.provisioner.interfaces.builders.NodeBuilder;
import com.awslabs.aws.greengrass.provisioner.interfaces.builders.PythonBuilder;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.IoHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.LambdaHelper;
import com.awslabs.aws.greengrass.provisioner.interfaces.helpers.LoggingHelper;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.*;

import javax.inject.Inject;
import java.nio.ByteBuffer;
import java.util.Optional;

@Slf4j
public class BasicLambdaHelper implements LambdaHelper {
    @Inject
    LambdaClient lambdaClient;
    @Inject
    IoHelper ioHelper;
    @Inject
    MavenBuilder mavenBuilder;
    @Inject
    GradleBuilder gradleBuilder;
    @Inject
    PythonBuilder pythonBuilder;
    @Inject
    NodeBuilder nodeBuilder;
    @Inject
    LoggingHelper loggingHelper;

    @Inject
    public BasicLambdaHelper() {
    }

    private boolean functionExists(String functionName) {
        GetFunctionRequest getFunctionRequest = GetFunctionRequest.builder()
                .functionName(functionName)
                .build();

        return Try.of(() -> lambdaClient.getFunction(getFunctionRequest) != null)
                .recover(ResourceNotFoundException.class, throwable -> false)
                .get();
    }

    @Override
    public LambdaFunctionArnInfo buildAndCreateJavaFunctionIfNecessary(FunctionConf functionConf, Role role) {
        String zipFilePath;

        if (mavenBuilder.isMavenFunction(functionConf)) {
            /*
            mavenBuilder.buildJavaFunctionIfNecessary(functionConf);

            zipFilePath = mavenBuilder.getArchivePath(functionConf);
            */
            throw new RuntimeException("This function [" + functionConf.getFunctionName() + "] is a Maven project but Maven support is currently disabled.  If you need this feature please file a Github issue.");
        } else if (gradleBuilder.isGradleFunction(functionConf)) {
            gradleBuilder.buildJavaFunctionIfNecessary(functionConf);

            zipFilePath = gradleBuilder.getArchivePath(functionConf);
        } else {
            throw new RuntimeException("This function [" + functionConf.getFunctionName() + "] is neither a Maven project nor a Gradle project.  It cannot be built automatically.");
        }

        return createFunctionIfNecessary(functionConf, Runtime.JAVA8, role, zipFilePath);
    }

    @Override
    public LambdaFunctionArnInfo buildAndCreatePythonFunctionIfNecessary(FunctionConf functionConf, Role role) {
        Optional<String> error = pythonBuilder.verifyHandlerExists(functionConf);

        if (error.isPresent()) {
            return LambdaFunctionArnInfo.builder()
                    .error(error).build();
        }

        pythonBuilder.buildFunctionIfNecessary(functionConf);

        String zipFilePath = pythonBuilder.getArchivePath(functionConf);
        LambdaFunctionArnInfo result = createFunctionIfNecessary(functionConf, Runtime.PYTHON2_7, role, zipFilePath);
        return result;
    }

    @Override
    public LambdaFunctionArnInfo buildAndCreateNodeFunctionIfNecessary(FunctionConf functionConf, Role role) {
        Optional<String> error = nodeBuilder.verifyHandlerExists(functionConf);

        if (error.isPresent()) {
            return LambdaFunctionArnInfo.builder()
                    .error(error).build();
        }

        nodeBuilder.buildFunctionIfNecessary(functionConf);

        String zipFilePath = nodeBuilder.getArchivePath(functionConf);
        LambdaFunctionArnInfo result = createFunctionIfNecessary(functionConf, Runtime.NODEJS6_10, role, zipFilePath);
        return result;
    }

    @Override
    public LambdaFunctionArnInfo createFunctionIfNecessary(FunctionConf functionConf, Runtime runtime, Role role, String zipFilePath) {
        String baseFunctionName = functionConf.getFunctionName();
        String groupFunctionName = getFunctionName(functionConf);

        if (functionExists(groupFunctionName)) {
            loggingHelper.logInfoWithName(log, baseFunctionName, "Deleting existing Lambda function");
            DeleteFunctionRequest deleteFunctionRequest = DeleteFunctionRequest.builder()
                    .functionName(groupFunctionName)
                    .build();

            lambdaClient.deleteFunction(deleteFunctionRequest);
        }

        FunctionCode functionCode = FunctionCode.builder()
                .zipFile(SdkBytes.fromByteBuffer(ByteBuffer.wrap(ioHelper.readFile(zipFilePath))))
                .build();

        loggingHelper.logInfoWithName(log, baseFunctionName, "Creating new Lambda function");
        CreateFunctionRequest createFunctionRequest = CreateFunctionRequest.builder()
                .functionName(groupFunctionName)
                .runtime(runtime)
                .role(role.arn())
                .handler(functionConf.getHandlerName())
                .code(functionCode)
                .build();

        boolean created = false;
        int counter = 0;

        // Make sure multiple threads don't do this at the same time
        synchronized (this) {
            while (!created) {
                counter++;

                if (counter > 10) {
                    throw new RuntimeException("Something went wrong with the Lambda IAM role, try again later");
                }

                created = Try.of(() -> lambdaClient.createFunction(createFunctionRequest) != null)
                        .recover(InvalidParameterValueException.class, this::waitForIamRoleToBeAvailableToLambda)
                        .get();
            }
        }

        loggingHelper.logInfoWithName(log, baseFunctionName, "Publishing Lambda function version");
        PublishVersionResponse publishVersionResponse = publishFunctionVersion(groupFunctionName);

        String qualifier = publishVersionResponse.version();
        String qualifiedArn = publishVersionResponse.functionArn();
        String baseArn = qualifiedArn.replaceAll(":" + qualifier + "$", "");

        LambdaFunctionArnInfo lambdaFunctionArnInfo = LambdaFunctionArnInfo.builder()
                .qualifier(qualifier)
                .qualifiedArn(qualifiedArn)
                .baseArn(baseArn)
                .build();

        return lambdaFunctionArnInfo;
    }

    public Boolean waitForIamRoleToBeAvailableToLambda(InvalidParameterValueException throwable) {
        if (!throwable.getMessage().startsWith("The role defined for the function cannot be assumed by Lambda.")) {
            throw throwable;
        }

        log.warn("Waiting for IAM role to be available to AWS Lambda...");

        ioHelper.sleep(5000);

        return false;
    }

    @Override
    public PublishVersionResponse publishFunctionVersion(String groupFunctionName) {
        PublishVersionRequest publishVersionRequest = PublishVersionRequest.builder()
                .functionName(groupFunctionName)
                .build();

        return lambdaClient.publishVersion(publishVersionRequest);
    }

    private boolean aliasExists(FunctionConf functionConf) {
        return aliasExists(getFunctionName(functionConf), functionConf.getAliasName());
    }

    @Override
    public boolean aliasExists(String functionName, String aliasName) {
        GetAliasRequest getAliasRequest = GetAliasRequest.builder()
                .functionName(functionName)
                .name(aliasName)
                .build();

        return Try.of(() -> lambdaClient.getAlias(getAliasRequest) != null)
                .recover(ResourceNotFoundException.class, throwable -> false)
                .get();
    }

    private String getFunctionName(FunctionConf functionConf) {
        return getFunctionName(functionConf.getGroupName(), functionConf.getFunctionName());
    }

    private String getFunctionName(String groupName, String baseFunctionName) {
        return String.join("-", groupName, baseFunctionName);
    }

    @Override
    public String createAlias(Optional<String> groupName, String baseFunctionName, String functionVersion, String aliasName) {
        String groupFunctionName = baseFunctionName;

        if (groupName.isPresent()) {
            groupFunctionName = getFunctionName(groupName.get(), baseFunctionName);
        }

        if (aliasExists(groupFunctionName, aliasName)) {
            loggingHelper.logInfoWithName(log, baseFunctionName, "Deleting existing alias");

            DeleteAliasRequest deleteAliasRequest = DeleteAliasRequest.builder()
                    .functionName(groupFunctionName)
                    .name(aliasName)
                    .build();

            lambdaClient.deleteAlias(deleteAliasRequest);
        }

        loggingHelper.logInfoWithName(log, baseFunctionName, "Creating new alias");

        CreateAliasRequest createAliasRequest = CreateAliasRequest.builder()
                .functionName(groupFunctionName)
                .name(aliasName)
                .functionVersion(functionVersion)
                .build();

        CreateAliasResponse createAliasResponse = lambdaClient.createAlias(createAliasRequest);

        return createAliasResponse.aliasArn();
    }

    @Override
    public String createAlias(FunctionConf functionConf, String functionVersion) {
        return createAlias(Optional.of(functionConf.getGroupName()), functionConf.getFunctionName(), functionVersion, functionConf.getAliasName());
    }

    @Override
    public Optional<GetFunctionResponse> getFunction(String functionName) {
        GetFunctionRequest getFunctionRequest = GetFunctionRequest.builder()
                .functionName(functionName)
                .build();

        return Try.of(() -> Optional.of(lambdaClient.getFunction(getFunctionRequest)))
                .recover(ResourceNotFoundException.class, throwable -> Optional.empty())
                .get();
    }

    @Override
    public void deleteAlias(String functionArn) {
        String temp = functionArn.substring(0, functionArn.lastIndexOf(":"));
        String aliasName = functionArn.substring(functionArn.lastIndexOf(":") + 1);
        String functionName = temp.substring(temp.lastIndexOf(":") + 1);

        DeleteAliasRequest deleteAliasRequest = DeleteAliasRequest.builder()
                .functionName(functionName)
                .name(aliasName)
                .build();

        lambdaClient.deleteAlias(deleteAliasRequest);
    }
}
