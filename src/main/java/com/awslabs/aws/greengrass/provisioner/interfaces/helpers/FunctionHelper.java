package com.awslabs.aws.greengrass.provisioner.interfaces.helpers;

import com.awslabs.aws.greengrass.provisioner.data.conf.DeploymentConf;
import com.awslabs.aws.greengrass.provisioner.data.conf.FunctionConf;
import com.awslabs.aws.greengrass.provisioner.data.functions.BuildableFunction;
import software.amazon.awssdk.services.greengrass.model.Function;
import software.amazon.awssdk.services.iam.model.Role;

import java.util.List;
import java.util.Map;

public interface FunctionHelper {
    String FUNCTIONS = "functions";
    String URI = "uri";
    String ARN = "arn";
    String PATH = "path";
    String READ_WRITE = "readWrite";
    String TRAINING_JOB = "training-job";
    String LOCAL_LAMBDA = "LOCAL_LAMBDA_";
    String HTTPS = "https://";
    String FUNCTION_CONF = "function.conf";

    List<FunctionConf> getFunctionConfObjects(Map<String, String> defaultEnvironment, DeploymentConf deploymentConf);

    List<BuildableFunction> getBuildableFunctions(List<FunctionConf> functionConfs, Role lambdaRole);

    Map<Function, FunctionConf> buildFunctionsAndGenerateMap(List<BuildableFunction> buildableFunctions);

    void installJavaDependencies();
}
